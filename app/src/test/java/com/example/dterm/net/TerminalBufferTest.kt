package com.example.dterm.net

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The inputs here are the sequences a real bash session actually emits, so a
 * regression shows up as unreadable output on screen and as a red test here.
 */
class TerminalBufferTest {

    private val esc = "\u001B"

    private fun render(vararg chunks: String): String {
        val buffer = TerminalBuffer()
        chunks.forEach { buffer.append(it.toByteArray()) }
        return buffer.snapshot()
    }

    @Test
    fun `plain text passes through untouched`() {
        assertEquals("hello world\n", render("hello world\n"))
    }

    @Test
    fun `a coloured prompt loses the colour but keeps the prompt`() {
        assertEquals(
            "david@fedora:~$ ",
            render("$esc[01;32mdavid@fedora$esc[00m:$esc[01;34m~$esc[00m$ "),
        )
    }

    @Test
    fun `a window title sequence disappears entirely`() {
        assertEquals("ready\n", render("${esc}]0;david@fedora:~ready\n"))
    }

    @Test
    fun `a title terminated by ESC backslash also disappears`() {
        assertEquals("ready\n", render("${esc}]0;title$esc\\ready\n"))
    }

    @Test
    fun `clear screen and cursor moves are dropped`() {
        assertEquals("done\n", render("$esc[2J$esc[H$esc[3;10Hdone\n"))
    }

    @Test
    fun `the CR of a CR LF pair does not survive`() {
        assertEquals("one\ntwo\n", render("one\r\ntwo\r\n"))
    }

    @Test
    fun `backspace erases the character before it`() {
        assertEquals("ls", render("lsX\b"))
    }

    @Test
    fun `backspace does not eat across a line break`() {
        assertEquals("done\n", render("done\n\b\b"))
    }

    @Test
    fun `an escape sequence split across two frames is still removed`() {
        // A 1 MiB read boundary can fall anywhere, including mid-sequence.
        assertEquals("red", render("$esc[3", "1mred"))
    }

    @Test
    fun `an accented character split across two frames is not corrupted`() {
        val bytes = "ñ".toByteArray()   // 0xC3 0xB1
        val buffer = TerminalBuffer()

        buffer.append(byteArrayOf(bytes[0]))
        assertEquals("", buffer.snapshot())   // held back, not guessed at

        buffer.append(byteArrayOf(bytes[1]))
        assertEquals("ñ", buffer.snapshot())
    }

    @Test
    fun `a four-byte character split across frames survives`() {
        val bytes = "🙂".toByteArray()
        val buffer = TerminalBuffer()

        buffer.append(bytes.copyOfRange(0, 2))
        buffer.append(bytes.copyOfRange(2, 4))

        assertEquals("🙂", buffer.snapshot())
    }

    @Test
    fun `the buffer stops growing once it hits its limit`() {
        val buffer = TerminalBuffer(limit = 100)
        repeat(50) { buffer.append("0123456789\n".toByteArray()) }

        assertEquals(100, buffer.snapshot().length)
        // The tail is what a user wants to see, so the head is what gets dropped.
        assertEquals("9\n", buffer.snapshot().takeLast(2))
    }

    @Test
    fun `real ls output comes out readable`() {
        val output = "$esc[0m$esc[01;34mbuild$esc[0m  README.md  $esc[01;32mgradlew$esc[0m\r\n"

        assertEquals("build  README.md  gradlew\n", render(output))
    }
}
