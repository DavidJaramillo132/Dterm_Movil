package com.example.dterm.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmulatorTest {

    private val esc = "\u001B"

    private fun Emulator.write(text: String) = feed(text.toByteArray())

    /** Every row as plain text, with the trailing blank rows dropped. */
    private fun Emulator.lines(): List<String> =
        snapshot().map { row -> row.joinToString("") { it.text } }
            .dropLastWhile { it.isEmpty() }

    private fun emulator(rows: Int = 5, cols: Int = 20) = Emulator(rows, cols)

    // ------------------------------------------------------------- the grid

    @Test
    fun `plain text lands on the first row`() {
        val term = emulator().apply { write("hello") }

        assertEquals(listOf("hello"), term.lines())
    }

    @Test
    fun `a line feed moves down and a carriage return moves to column one`() {
        val term = emulator().apply { write("one\r\ntwo") }

        assertEquals(listOf("one", "two"), term.lines())
    }

    @Test
    fun `writing over an existing cell replaces it instead of appending`() {
        // This is the whole reason for a grid. A text buffer would end up
        // with "hello" followed by "HE" on the same line.
        val term = emulator().apply {
            write("hello")
            write("$esc[1;1H")
            write("HE")
        }

        assertEquals(listOf("HEllo"), term.lines())
    }

    @Test
    fun `the cursor can be placed anywhere`() {
        val term = emulator().apply {
            write("$esc[3;5Hx")
        }

        assertEquals(listOf("", "", "    x"), term.lines())
    }

    @Test
    fun `a repaint replaces the previous frame rather than stacking below it`() {
        // What a full-screen program actually does, twice over.
        val term = emulator()
        repeat(2) { frame ->
            term.write("$esc[H$esc[2Jframe $frame")
        }

        assertEquals(listOf("frame 1"), term.lines())
    }

    // --------------------------------------------------------------- cursor

    @Test
    fun `the cursor sits just past the text that was written`() {
        val term = emulator().apply { write("hello") }

        assertEquals(0, term.cursorLine)
        assertEquals(5, term.cursorColumn)
    }

    @Test
    fun `the cursor follows an explicit position sequence`() {
        // CSI counts rows and columns from one, the grid from zero.
        val term = emulator().apply { write("$esc[3;5H") }

        assertEquals(2, term.cursorLine)
        assertEquals(4, term.cursorColumn)
    }

    @Test
    fun `the cursor line counts the history once the screen has scrolled`() {
        // The reported line indexes snapshot(), which puts the two scrolled-off
        // rows above the screen. Reporting the grid row instead would draw the
        // cursor two rows too high.
        val term = emulator(rows = 3, cols = 20).apply {
            write("one\r\ntwo\r\nthree\r\nfour\r\nfive")
        }

        assertEquals(listOf("one", "two", "three", "four", "five"), term.lines())
        assertEquals(4, term.cursorLine)
        assertEquals(4, term.cursorColumn)
        assertEquals("five", term.snapshot()[term.cursorLine].joinToString("") { it.text })
    }

    @Test
    fun `the alternate screen reports the cursor without any history`() {
        val term = emulator(rows = 3, cols = 20).apply {
            write("one\r\ntwo\r\nthree\r\nfour")
            write("$esc[?1049h")
            write("$esc[2;3Hx")
        }

        assertEquals(1, term.cursorLine)
        assertEquals(3, term.cursorColumn)
    }

    @Test
    fun `a program can hide the cursor and bring it back`() {
        val term = emulator()

        assertTrue(term.cursorVisible)

        term.write("$esc[?25l")
        assertFalse(term.cursorVisible)

        term.write("$esc[?25h")
        assertTrue(term.cursorVisible)
    }

    @Test
    fun `a full reset shows the cursor again`() {
        // A program killed between hiding and showing would otherwise leave the
        // cursor invisible for whatever runs next.
        val term = emulator().apply {
            write("$esc[?25l")
            write("${esc}c")
        }

        assertTrue(term.cursorVisible)
    }

    // -------------------------------------------------------------- erasing

    @Test
    fun `erase to end of line keeps what is before the cursor`() {
        val term = emulator().apply {
            write("keep this")
            write("$esc[1;5H$esc[K")
        }

        assertEquals(listOf("keep"), term.lines())
    }

    @Test
    fun `erase to start of line keeps what is after the cursor`() {
        val term = emulator().apply {
            write("0123456789")
            write("$esc[1;5H$esc[1K")
        }

        assertEquals(listOf("     56789"), term.lines())
    }

    @Test
    fun `erase display clears every row`() {
        val term = emulator().apply {
            write("one\r\ntwo\r\nthree")
            write("$esc[2J")
        }

        assertEquals(emptyList<String>(), term.lines())
    }

    @Test
    fun `delete characters pulls the rest of the line left`() {
        val term = emulator().apply {
            write("abcdef")
            write("$esc[1;2H$esc[2P")
        }

        assertEquals(listOf("adef"), term.lines())
    }

    @Test
    fun `insert characters pushes the rest of the line right`() {
        val term = emulator().apply {
            write("abc")
            write("$esc[1;2H$esc[2@")
        }

        assertEquals(listOf("a  bc"), term.lines())
    }

    // ------------------------------------------------------------ scrolling

    @Test
    fun `passing the last row scrolls and keeps the old row as history`() {
        val term = emulator(rows = 3, cols = 20).apply {
            write("one\r\ntwo\r\nthree\r\nfour")
        }

        // Three rows on screen, so "one" is now above it.
        assertEquals(listOf("one", "two", "three", "four"), term.lines())
    }

    @Test
    fun `a scrolling region leaves the rows outside it alone`() {
        val term = emulator(rows = 5, cols = 20).apply {
            write("$esc[1;1Hheader")
            write("$esc[2;5r")       // rows 2..5 scroll, row 1 does not
            write("$esc[5;1Ha\r\nb")
        }

        assertEquals("header", term.lines().first())
    }

    @Test
    fun `insert line pushes the rows below it down`() {
        val term = emulator(rows = 4, cols = 20).apply {
            write("one\r\ntwo\r\nthree")
            write("$esc[1;1H$esc[L")
        }

        assertEquals(listOf("", "one", "two", "three"), term.lines())
    }

    @Test
    fun `delete line pulls the rows below it up`() {
        val term = emulator(rows = 4, cols = 20).apply {
            write("one\r\ntwo\r\nthree")
            write("$esc[1;1H$esc[M")
        }

        assertEquals(listOf("two", "three"), term.lines())
    }

    // ---------------------------------------------------- alternate screen

    @Test
    fun `the alternate screen hides the history while it is in use`() {
        val term = emulator(rows = 3, cols = 20).apply {
            write("shell output\r\n")
            write("$esc[?1049h")
            write("full screen program")
        }

        assertTrue(term.onAlternateScreen)
        assertFalse(term.lines().contains("shell output"))
    }

    @Test
    fun `leaving the alternate screen brings the shell back untouched`() {
        val term = emulator(rows = 3, cols = 20).apply {
            write("shell output")
            write("$esc[?1049h")
            write("$esc[2Jfull screen program")
            write("$esc[?1049l")
        }

        assertFalse(term.onAlternateScreen)
        assertEquals(listOf("shell output"), term.lines())
    }

    // -------------------------------------------------------------- wrapping

    @Test
    fun `a line that fills the width exactly does not leave a blank row`() {
        // The deferred wrap: the cursor stays on the last column until the
        // next character actually arrives.
        val term = emulator(rows = 4, cols = 5).apply { write("abcde") }

        assertEquals(listOf("abcde"), term.lines())
    }

    @Test
    fun `one character past the width continues on the next row`() {
        val term = emulator(rows = 4, cols = 5).apply { write("abcdef") }

        assertEquals(listOf("abcde", "f"), term.lines())
    }

    // --------------------------------------------------------------- colour

    @Test
    fun `a colour applies until it is reset`() {
        val term = emulator().apply { write("${esc}[31mred${esc}[0m plain") }
        val spans = term.snapshot().first()

        assertEquals("red", spans[0].text)
        assertEquals(Emulator.palette(1), spans[0].fg)
        assertEquals(" plain", spans[1].text)
        assertEquals(0, spans[1].fg)
    }

    @Test
    fun `bold is carried on the span, not written into the text`() {
        val term = emulator().apply { write("${esc}[1mbold") }
        val span = term.snapshot().first().single()

        assertEquals("bold", span.text)
        assertTrue(span.bold)
    }

    @Test
    fun `a 256-colour index resolves to the xterm palette`() {
        val term = emulator().apply { write("${esc}[38;5;196mx") }

        assertEquals(Emulator.palette(196), term.snapshot().first().single().fg)
    }

    @Test
    fun `a direct colour keeps its exact rgb value`() {
        val term = emulator().apply { write("${esc}[38;2;18;52;86mx") }

        assertEquals(0xFF123456.toInt(), term.snapshot().first().single().fg)
    }

    @Test
    fun `the palette follows the xterm layout`() {
        assertEquals(0xFF000000.toInt(), Emulator.palette(0))
        assertEquals(0xFFFFFFFF.toInt(), Emulator.palette(231))   // top of the cube
        assertEquals(0xFF080808.toInt(), Emulator.palette(232))   // first grey
    }

    // ----------------------------------------------------------- robustness

    @Test
    fun `an escape sequence split across two chunks still works`() {
        val term = emulator()
        term.feed("${esc}[3".toByteArray())
        term.feed("1mred".toByteArray())

        assertEquals(Emulator.palette(1), term.snapshot().first().single().fg)
    }

    @Test
    fun `an accented character split across two chunks is not corrupted`() {
        val bytes = "ñ".toByteArray()
        val term = emulator()

        term.feed(byteArrayOf(bytes[0]))
        assertEquals(emptyList<String>(), term.lines())

        term.feed(byteArrayOf(bytes[1]))
        assertEquals(listOf("ñ"), term.lines())
    }

    @Test
    fun `a window title is swallowed whole`() {
        val term = emulator().apply { write("$esc]0;a title${esc}\\after") }

        assertEquals(listOf("after"), term.lines())
    }

    @Test
    fun `an unknown sequence is skipped without printing its parameters`() {
        val term = emulator().apply { write("${esc}[>4;2mtext") }

        assertEquals(listOf("text"), term.lines())
    }

    @Test
    fun `backspace moves the cursor rather than deleting`() {
        val term = emulator().apply { write("abc\b\bX") }

        assertEquals(listOf("aXc"), term.lines())
    }

    @Test
    fun `a tab lands on the next multiple of eight`() {
        val term = emulator(rows = 3, cols = 40).apply { write("ab\tc") }

        assertEquals(listOf("ab      c"), term.lines())
    }

    // ---------------------------------------------------------------- resize

    @Test
    fun `resizing keeps the text that fits`() {
        val term = emulator(rows = 5, cols = 20).apply { write("hello") }
        term.resize(10, 40)

        assertEquals(10, term.rows)
        assertEquals(40, term.cols)
        assertEquals(listOf("hello"), term.lines())
    }

    @Test
    fun `shrinking the height pushes the top rows into history`() {
        val term = emulator(rows = 4, cols = 20).apply { write("one\r\ntwo\r\nthree") }
        term.resize(2, 20)

        assertEquals(listOf("one", "two", "three"), term.lines())
    }

    @Test
    fun `clear drops the history as well as the screen`() {
        val term = emulator(rows = 2, cols = 20).apply {
            write("one\r\ntwo\r\nthree")
            clear()
        }

        assertEquals(emptyList<String>(), term.lines())
    }
}
