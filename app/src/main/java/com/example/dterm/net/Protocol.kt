package com.example.dterm.net

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

//
// DTerm wire protocol: length-prefixed binary frames.
//
//  0        1                                5
//  +--------+--------+--------+--------+--------+---------------+
//  |  type  |          length (uint32 BE)       |    payload    |
//  +--------+--------+--------+--------+--------+---------------+
//
// This must stay byte-for-byte identical to protocol/protocol.hpp in the
// server repository. A change on one side without the other is a wire break.
//

object Wire {
    const val MAX_PAYLOAD = 1 shl 20   // 1 MiB
    const val VERSION: Byte = 2

    /** The HELLO payload the server checks before anything else: "DTRM" + version. */
    val HELLO_PAYLOAD = byteArrayOf('D'.code.toByte(), 'T'.code.toByte(),
                                    'R'.code.toByte(), 'M'.code.toByte(), VERSION)
}

enum class FrameType(val code: Byte) {
    HELLO(0x00),
    INPUT(0x01),
    OUTPUT(0x02),
    RESIZE(0x03),
    PING(0x04),
    PONG(0x05),
    CHALLENGE(0x06),
    AUTH(0x07),
    AUTH_OK(0x08),
    AUTH_FAIL(0x09),
    ATTACH(0x0A);

    companion object {
        fun from(code: Byte): FrameType? = entries.firstOrNull { it.code == code }
    }
}

class ProtocolException(message: String) : IOException(message)

/**
 * One decoded frame.
 *
 * Deliberately not a data class: the generated equals() on a ByteArray compares
 * references, so two frames carrying identical bytes would come out unequal.
 */
class Frame(val type: FrameType, val payload: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is Frame && other.type == type && other.payload.contentEquals(payload)

    override fun hashCode(): Int = 31 * type.hashCode() + payload.contentHashCode()

    override fun toString(): String = "Frame($type, ${payload.size} bytes)"
}

/**
 * Writes whole frames to a stream.
 *
 * DataOutputStream.writeInt is big-endian by specification, which is exactly
 * what htonl() produces on the server side, so no byte swapping is needed.
 *
 * Synchronized because the UI thread sends INPUT and RESIZE while the reader
 * thread answers PING; two writers interleaving mid-frame would corrupt the
 * stream beyond recovery.
 */
class FrameWriter(stream: OutputStream) {
    private val out = DataOutputStream(BufferedOutputStream(stream))

    @Synchronized
    fun send(type: FrameType, payload: ByteArray = ByteArray(0)) {
        if (payload.size > Wire.MAX_PAYLOAD) {
            throw ProtocolException("payload of ${payload.size} bytes exceeds the limit")
        }

        out.writeByte(type.code.toInt())
        out.writeInt(payload.size)
        out.write(payload)
        out.flush()
    }

    fun send(type: FrameType, text: String) = send(type, text.toByteArray(Charsets.UTF_8))
}

/**
 * Reads whole frames from a stream, blocking until each one is complete.
 *
 * The C++ client needs a buffering state machine here because it multiplexes
 * the socket and the terminal in a single poll() loop. This client gives the
 * socket its own thread, so readFully can block and reassemble partial reads
 * for us.
 */
class FrameReader(stream: InputStream) {
    private val input = DataInputStream(BufferedInputStream(stream))

    fun next(): Frame {
        val code = input.readByte()
        val length = input.readInt()

        // readInt is signed: a length above 2^31 arrives as a negative number,
        // and allocating on it would throw somewhere far less obvious.
        if (length < 0 || length > Wire.MAX_PAYLOAD) {
            throw ProtocolException("frame claims a length of $length bytes")
        }

        val type = FrameType.from(code)
            ?: throw ProtocolException("unknown frame type 0x%02x".format(code))

        val payload = ByteArray(length)
        input.readFully(payload)

        return Frame(type, payload)
    }
}

/** rows and cols as two big-endian uint16, matching encode_resize() on the server. */
fun encodeResize(rows: Int, cols: Int): ByteArray = byteArrayOf(
    (rows shr 8).toByte(), rows.toByte(),
    (cols shr 8).toByte(), cols.toByte(),
)
