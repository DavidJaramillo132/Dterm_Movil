package com.example.dterm.net

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These run on the plain JVM with `./gradlew test` — no device, no emulator.
 *
 * Their job is to catch a wire mismatch with the C++ server without needing the
 * server running, so the expected bytes below are written out by hand rather
 * than produced by the same code under test.
 */
class ProtocolTest {

    private fun encoded(type: FrameType, payload: ByteArray): ByteArray {
        val sink = ByteArrayOutputStream()
        FrameWriter(sink).send(type, payload)
        return sink.toByteArray()
    }

    @Test
    fun `a frame is a type byte, a big-endian length and the payload`() {
        val bytes = encoded(FrameType.INPUT, "hi".toByteArray())

        assertArrayEquals(
            byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x02, 'h'.code.toByte(), 'i'.code.toByte()),
            bytes,
        )
    }

    @Test
    fun `the length is big-endian, not the platform's order`() {
        val bytes = encoded(FrameType.OUTPUT, ByteArray(258))

        // 258 == 0x0102: the high byte must come first.
        assertArrayEquals(byteArrayOf(0x02, 0x00, 0x00, 0x01, 0x02), bytes.copyOfRange(0, 5))
    }

    @Test
    fun `an empty payload still carries a full header`() {
        assertArrayEquals(byteArrayOf(0x04, 0x00, 0x00, 0x00, 0x00), encoded(FrameType.PING, ByteArray(0)))
    }

    @Test
    fun `the hello payload is the signature the server checks`() {
        assertArrayEquals("DTRM".toByteArray() + byteArrayOf(2), Wire.HELLO_PAYLOAD)
    }

    @Test
    fun `what the writer produces the reader reads back`() {
        val sink = ByteArrayOutputStream()
        val writer = FrameWriter(sink)
        writer.send(FrameType.HELLO, Wire.HELLO_PAYLOAD)
        writer.send(FrameType.ATTACH, "work")
        writer.send(FrameType.INPUT, "ls\n")

        val reader = FrameReader(ByteArrayInputStream(sink.toByteArray()))

        assertEquals(Frame(FrameType.HELLO, Wire.HELLO_PAYLOAD), reader.next())
        assertEquals(Frame(FrameType.ATTACH, "work".toByteArray()), reader.next())
        assertEquals(Frame(FrameType.INPUT, "ls\n".toByteArray()), reader.next())
    }

    @Test
    fun `a frame split across reads is reassembled`() {
        val whole = encoded(FrameType.OUTPUT, "some output".toByteArray())

        // A stream that hands over one byte at a time, like a slow socket.
        val trickle = object : ByteArrayInputStream(whole) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                super.read(buffer, offset, if (length > 0) 1 else 0)
        }

        assertEquals(Frame(FrameType.OUTPUT, "some output".toByteArray()), FrameReader(trickle).next())
    }

    @Test
    fun `an unknown type is rejected instead of being ignored`() {
        val bytes = byteArrayOf(0x7F, 0x00, 0x00, 0x00, 0x00)

        assertThrows(ProtocolException::class.java) { FrameReader(ByteArrayInputStream(bytes)).next() }
    }

    @Test
    fun `a length above the limit is refused before anything is allocated`() {
        // 0x7FFFFFFF: two gigabytes of payload the server would never send.
        val bytes = byteArrayOf(0x02, 0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())

        assertThrows(ProtocolException::class.java) { FrameReader(ByteArrayInputStream(bytes)).next() }
    }

    @Test
    fun `a length that arrives negative is refused too`() {
        // 0xFFFFFFFF reads back as -1 through the signed readInt.
        val bytes = ByteArray(5) { 0xFF.toByte() }.also { it[0] = 0x02 }

        assertThrows(ProtocolException::class.java) { FrameReader(ByteArrayInputStream(bytes)).next() }
    }

    @Test
    fun `a truncated payload fails instead of returning a short frame`() {
        val bytes = byteArrayOf(0x02, 0x00, 0x00, 0x00, 0x08, 1, 2, 3)

        assertThrows(EOFException::class.java) { FrameReader(ByteArrayInputStream(bytes)).next() }
    }

    @Test
    fun `an oversized payload is refused before it reaches the socket`() {
        assertThrows(ProtocolException::class.java) {
            FrameWriter(ByteArrayOutputStream()).send(FrameType.INPUT, ByteArray(Wire.MAX_PAYLOAD + 1))
        }
    }

    @Test
    fun `every type maps back from its code`() {
        FrameType.entries.forEach { assertEquals(it, FrameType.from(it.code)) }
        assertNull(FrameType.from(0x7F))
    }

    @Test
    fun `resize is two big-endian uint16 values`() {
        assertArrayEquals(byteArrayOf(0x00, 0x18, 0x00, 0x50.toByte()), encodeResize(24, 80))
    }

    @Test
    fun `resize carries sizes that do not fit in one byte`() {
        assertArrayEquals(byteArrayOf(0x01, 0x2C, 0x01, 0x90.toByte()), encodeResize(300, 400))
    }
}

class AuthTest {

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun `the mac matches the one openssl computes for the server`() {
        // Produced independently of this code with:
        //   openssl dgst -sha256 -mac HMAC -macopt key:test-secret <challenge>
        // over the 32 bytes 0x00..0x1f.
        val challenge = ByteArray(Auth.CHALLENGE_SIZE) { it.toByte() }

        assertEquals(
            "e78e5449516df08b5495076960af26845347223233f5b0b5068b33cc7aa0934f",
            hex(Auth.hmacSha256("test-secret", challenge)),
        )
    }

    @Test
    fun `the mac is the size the server expects`() {
        assertEquals(Auth.MAC_SIZE, Auth.hmacSha256("secret", ByteArray(32)).size)
    }

    @Test
    fun `a different challenge gives a different mac, so a reply cannot be replayed`() {
        val first = Auth.hmacSha256("secret", ByteArray(32) { 1 })
        val second = Auth.hmacSha256("secret", ByteArray(32) { 2 })

        assertTrue(!first.contentEquals(second))
    }

    @Test
    fun `a trailing newline in the pasted secret is trimmed like the server does`() {
        assertArrayEquals(
            Auth.hmacSha256("test-secret", ByteArray(32)),
            Auth.hmacSha256("  test-secret\n", ByteArray(32)),
        )
    }
}
