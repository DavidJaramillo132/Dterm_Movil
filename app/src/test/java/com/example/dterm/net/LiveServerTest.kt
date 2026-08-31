package com.example.dterm.net

import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Talks to the real C++ server, so a wire mismatch shows up here rather than on
 * a phone. Skipped when the binary is not around, which is every machine that
 * has not built the server repository.
 *
 * Point DTERM_SERVER at the binary if it lives somewhere else.
 */
class LiveServerTest {

    private val server = File(
        System.getenv("DTERM_SERVER")
            ?: "${System.getProperty("user.home")}/Projects/dterm/build/dterm"
    )

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun `the kotlin client completes the handshake and runs a command`() {
        assumeTrue(server.canExecute())

        val port = freePort()
        val home = createTempDir("dterm-live")
        val secret = "a-secret-for-the-test"

        val process = ProcessBuilder(server.absolutePath, port.toString())
            .apply {
                environment()["HOME"] = home.absolutePath
                environment()["DTERM_SECRET"] = secret
            }
            .redirectErrorStream(true)
            .start()

        try {
            Thread.sleep(500)

            val screen = TerminalBuffer()
            val sawMarker = CountDownLatch(1)

            val link = DtermConnection("127.0.0.1", port, secret, "livetest")
            link.open(rows = 40, cols = 80)

            thread(isDaemon = true) {
                runCatching {
                    link.readLoop { chunk ->
                        screen.append(chunk)
                        if (screen.snapshot().contains("KOTLIN-OK")) sawMarker.countDown()
                    }
                }
            }

            Thread.sleep(500)
            link.sendInput("echo KOTLIN-OK\n")

            assertTrue(
                "the shell never answered; buffer was:\n${screen.snapshot()}",
                sawMarker.await(10, TimeUnit.SECONDS),
            )

            link.close()
        } finally {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
            home.deleteRecursively()
        }
    }

    @Test
    fun `the wrong secret is rejected`() {
        assumeTrue(server.canExecute())

        val port = freePort()
        val home = createTempDir("dterm-live")

        val process = ProcessBuilder(server.absolutePath, port.toString())
            .apply {
                environment()["HOME"] = home.absolutePath
                environment()["DTERM_SECRET"] = "the-real-secret"
            }
            .redirectErrorStream(true)
            .start()

        try {
            Thread.sleep(500)

            val failure = runCatching {
                DtermConnection("127.0.0.1", port, "the-wrong-secret", "nope").open(40, 80)
            }.exceptionOrNull()

            assertTrue("expected a rejection, got $failure", failure != null)
        } finally {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
            home.deleteRecursively()
        }
    }
}
