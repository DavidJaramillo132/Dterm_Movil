package com.example.dterm.net

/**
 * Turns raw PTY bytes into text a TextView can show.
 *
 * This is NOT a terminal emulator. A real one keeps a grid of cells and moves a
 * cursor around it, so that `vim` or `htop` can repaint any part of the screen.
 * This only strips the control sequences and appends what is left, which is
 * enough for a shell prompt and command output and nothing more.
 *
 * It is stateful on purpose: OUTPUT frames arrive at arbitrary boundaries, so a
 * single escape sequence — or a single accented character — can be split across
 * two frames. Both cases are carried over to the next chunk.
 */
class TerminalBuffer(private val limit: Int = 200_000) {

    private enum class State { TEXT, ESCAPE, CSI, OSC, CHARSET }

    private val text = StringBuilder()
    private var pending = ByteArray(0)
    private var state = State.TEXT

    fun append(chunk: ByteArray) {
        val data = if (pending.isEmpty()) chunk else pending + chunk
        val complete = completeBytes(data)

        pending = data.copyOfRange(complete, data.size)
        consume(String(data, 0, complete, Charsets.UTF_8))

        if (text.length > limit) {
            text.delete(0, text.length - limit)
        }
    }

    fun snapshot(): String = text.toString()

    fun clear() {
        text.setLength(0)
        pending = ByteArray(0)
        state = State.TEXT
    }

    private fun consume(input: String) {
        for (character in input) {
            when (state) {
                State.TEXT -> onText(character)

                State.ESCAPE -> state = when (character) {
                    '[' -> State.CSI
                    ']' -> State.OSC
                    '(', ')' -> State.CHARSET
                    else -> State.TEXT   // a two-byte sequence; drop both bytes
                }

                // Parameters and intermediates run until a final byte in 0x40..0x7E.
                State.CSI -> if (character.code in 0x40..0x7E) state = State.TEXT

                // Window titles and the like end at BEL, or at "ESC \". Going
                // back to ESCAPE rather than TEXT is what makes the trailing
                // backslash get consumed instead of printed.
                State.OSC -> when (character) {
                    BELL -> state = State.TEXT
                    ESCAPE -> state = State.ESCAPE
                    else -> Unit
                }

                State.CHARSET -> state = State.TEXT
            }
        }
    }

    private fun onText(character: Char) {
        when (character) {
            ESCAPE -> state = State.ESCAPE

            // The shell erases by moving back and overwriting with a space.
            '\b' -> if (text.isNotEmpty() && text.last() != '\n') text.setLength(text.length - 1)

            // A PTY ends every line with CR LF. Without a cursor there is
            // nothing for a bare CR to rewind to, so it is dropped.
            '\r' -> Unit

            else -> text.append(character)
        }
    }

    /**
     * How many bytes can be decoded right now.
     *
     * A UTF-8 character is 1 to 4 bytes. If the chunk ends in the middle of one,
     * decoding it now would produce a replacement character that never repairs
     * itself, so the tail is held back until the rest arrives.
     */
    private fun completeBytes(data: ByteArray): Int {
        var index = data.size - 1
        var continuations = 0

        while (index >= 0 && continuations < 3 && (data[index].toInt() and 0xC0) == 0x80) {
            index--
            continuations++
        }

        if (index < 0) return data.size

        val lead = data[index].toInt() and 0xFF
        val needed = when {
            lead >= 0xF0 -> 4
            lead >= 0xE0 -> 3
            lead >= 0xC0 -> 2
            else -> 1   // ASCII, or a stray continuation byte we cannot fix anyway
        }

        return if (continuations + 1 < needed) index else data.size
    }

    private companion object {
        const val ESCAPE = '\u001B'
        const val BELL = '\u0007'
    }
}
