package com.example.dterm.net

import java.io.Closeable
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
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

    @Test
    fun `the kotlin client completes the handshake and runs a command`() {
        LiveServer.start("a-secret-for-the-test").use { server ->
            val screen = Emulator(40, 80)
            val sawMarker = CountDownLatch(1)

            val link = DtermConnection("127.0.0.1", server.port, server.secret, "livetest")
            link.open(rows = 40, cols = 80)

            thread(isDaemon = true) {
                runCatching {
                    link.readLoop { chunk ->
                        screen.feed(chunk)
                        if (screen.plainText().contains("KOTLIN-OK")) sawMarker.countDown()
                    }
                }
            }

            Thread.sleep(500)
            link.sendInput("echo KOTLIN-OK\n")

            assertTrue(
                "the shell never answered; the screen held:\n${screen.plainText()}",
                sawMarker.await(10, TimeUnit.SECONDS),
            )

            link.close()
        }
    }

    @Test
    fun `the wrong secret is rejected`() {
        LiveServer.start("the-real-secret").use { server ->
            val failure = runCatching {
                DtermConnection("127.0.0.1", server.port, "the-wrong-secret", "nope").open(40, 80)
            }.exceptionOrNull()

            assertTrue("expected a rejection, got $failure", failure != null)
        }
    }

    @Test
    fun `tearing a server down leaves none of its processes behind`() {
        val server = LiveServer.start("a-secret-for-the-test")

        server.use {
            // Attaching creates a session, and a session outlives the
            // connection that created it. That is what teardown has to reach.
            DtermConnection("127.0.0.1", it.port, it.secret, "teardown").apply {
                open(rows = 40, cols = 80)
                close()
            }
            Thread.sleep(1000)

            // Listener, session and shell. Without them the check below would
            // pass whether or not teardown works.
            assertTrue(
                "a session should be running before teardown",
                LiveServer.processesUnder(it.home).size >= 3,
            )
        }

        assertEquals(emptyList<Long>(), LiveServer.processesUnder(server.home))
    }
}

/**
 * One server on a private port with a private HOME, torn down completely.
 *
 * Killing the listener is not enough, and neither is killing its descendants:
 * the session a test creates calls setsid, so it is re-parented to init and
 * belongs to nobody. That is the whole design of a persistent session, and it
 * is exactly why a test that only stopped the listener left a shell running
 * after every run, forever, under a HOME it had already deleted. HOME is the
 * one thing still tying those processes to this test, so close() goes by it.
 */
private class LiveServer private constructor(
    val port: Int,
    val secret: String,
    val home: File,
    private val process: Process,
) : Closeable {

    override fun close() {
        process.destroyForcibly()
        process.waitFor(5, TimeUnit.SECONDS)

        // Kill and re-check until nothing is left, so a caller that looks
        // afterwards sees the real outcome rather than a process on its way out.
        for (attempt in 1..20) {
            val leftovers = processesUnder(home)
            if (leftovers.isEmpty()) break

            ProcessBuilder(listOf("kill", "-KILL") + leftovers.map(Long::toString))
                .start()
                .waitFor(5, TimeUnit.SECONDS)
            Thread.sleep(100)
        }

        home.deleteRecursively()
    }

    companion object {
        private val binary = File(
            System.getenv("DTERM_SERVER")
                ?: "${System.getProperty("user.home")}/Projects/dterm/build/dterm"
        )

        fun start(secret: String): LiveServer {
            assumeTrue("the server binary has not been built", binary.canExecute())

            val port = ServerSocket(0).use { it.localPort }
            val home = Files.createTempDirectory("dterm-live").toFile()

            val process = ProcessBuilder(binary.absolutePath, port.toString())
                .apply {
                    // Settings exported where the suite runs must not change
                    // what these tests measure.
                    environment().keys.removeAll { it.startsWith("DTERM_") }
                    environment()["HOME"] = home.absolutePath
                    environment()["DTERM_SECRET"] = secret
                }
                .redirectErrorStream(true)
                .start()

            Thread.sleep(500)
            return LiveServer(port, secret, home, process)
        }

        /**
         * Every live process whose HOME is exactly [home].
         *
         * Other users' processes cannot be read and are skipped. The trailing
         * NUL makes the match exact, so one test's HOME is never mistaken for
         * a prefix of another's.
         */
        fun processesUnder(home: File): List<Long> {
            val marker = "HOME=${home.absolutePath}\u0000"

            return File("/proc").listFiles().orEmpty()
                .filter { it.name.all(Char::isDigit) }
                .filter { dir ->
                    runCatching { String(File(dir, "environ").readBytes(), Charsets.ISO_8859_1) }
                        .getOrNull()
                        ?.contains(marker) == true
                }
                .map { it.name.toLong() }
        }
    }
}

/** The whole screen as plain text, for asserting on what the shell printed. */
private fun Emulator.plainText(): String =
    snapshot().joinToString("\n") { row -> row.joinToString("") { it.text } }
