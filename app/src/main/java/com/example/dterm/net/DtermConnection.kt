package com.example.dterm.net

import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * One connection to a DTerm server: the handshake, then the read loop.
 *
 * Deliberately free of Android types so it can be exercised from a plain JVM
 * test against the real C++ server.
 */
class DtermConnection(
    private val host: String,
    private val port: Int,
    private val secret: String,
    private val session: String,
) : Closeable {

    private val socket = Socket()
    private var reader: FrameReader? = null
    private var writer: FrameWriter? = null

    /**
     * Connects and proves we hold the shared secret.
     *
     * The order is fixed by the server: HELLO, then CHALLENGE, AUTH, AUTH_OK,
     * then ATTACH. Sending anything else in between drops the connection, and
     * the server gives the whole handshake 5 seconds.
     */
    fun open(rows: Int, cols: Int) {
        socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)

        // A terminal sends single keystrokes. Nagle's algorithm would hold each
        // one back waiting for company, which reads as laggy typing.
        socket.tcpNoDelay = true

        // The server pings every 30 s by default. Going quiet for well over
        // three intervals means it is gone, not just idle.
        socket.soTimeout = READ_TIMEOUT_MS

        val out = FrameWriter(socket.getOutputStream())
        val input = FrameReader(socket.getInputStream())
        writer = out
        reader = input

        out.send(FrameType.HELLO, Wire.HELLO_PAYLOAD)

        val challenge = input.next()
        if (challenge.type != FrameType.CHALLENGE || challenge.payload.size != Auth.CHALLENGE_SIZE) {
            throw ProtocolException("the server did not send a valid challenge")
        }

        out.send(FrameType.AUTH, Auth.hmacSha256(secret, challenge.payload))

        when (val verdict = input.next().type) {
            FrameType.AUTH_OK -> Unit
            FrameType.AUTH_FAIL -> throw ProtocolException("the server rejected the shared secret")
            else -> throw ProtocolException("expected AUTH_OK, got $verdict")
        }

        out.send(FrameType.ATTACH, session)
        out.send(FrameType.RESIZE, encodeResize(rows, cols))
    }

    /**
     * Reads until the peer hangs up or the connection is closed from another
     * thread. PING is answered here so that a session sitting idle on screen
     * does not get dropped for being silent.
     */
    fun readLoop(onOutput: (ByteArray) -> Unit) {
        val input = reader ?: throw IllegalStateException("open() was never called")

        while (true) {
            val frame = try {
                input.next()
            } catch (timeout: SocketTimeoutException) {
                throw IOException("the server stopped responding", timeout)
            }

            when (frame.type) {
                FrameType.OUTPUT -> onOutput(frame.payload)
                FrameType.PING -> writer?.send(FrameType.PONG)
                FrameType.PONG -> Unit
                else -> Unit   // nothing else is meaningful after the handshake
            }
        }
    }

    fun sendInput(text: String) {
        writer?.send(FrameType.INPUT, text)
    }

    fun resize(rows: Int, cols: Int) {
        writer?.send(FrameType.RESIZE, encodeResize(rows, cols))
    }

    /** Closing the socket is what unblocks readLoop from another thread. */
    override fun close() {
        runCatching { socket.close() }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 120_000
    }
}
