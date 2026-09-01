package com.example.dterm.net

/**
 * What a key actually sends down the wire.
 *
 * A terminal has no notion of "the up arrow". The keyboard sends bytes, and a
 * program decides what they mean. The arrows send an escape sequence, which is
 * why pressing one in a program that does not read them prints `^[[A`.
 */
object Keys {
    const val ESC = "\u001B"

    const val UP = "\u001B[A"
    const val DOWN = "\u001B[B"
    const val RIGHT = "\u001B[C"
    const val LEFT = "\u001B[D"
    const val HOME = "\u001B[H"
    const val END = "\u001B[F"
    const val PAGE_UP = "\u001B[5~"
    const val PAGE_DOWN = "\u001B[6~"

    const val TAB = "\t"

    /** A PTY in its normal mode expects carriage return, not line feed. */
    const val ENTER = "\r"

    /** What the Backspace key sends: DEL, not the backspace control code. */
    const val BACKSPACE = "\u007F"

    /**
     * Ctrl combined with a letter.
     *
     * Holding Ctrl clears the top three bits of the character, which is why
     * Ctrl+C is 0x03 and Ctrl+A is 0x01 — and why Ctrl+C and Ctrl+c are the
     * same key to a terminal.
     */
    fun control(character: Char): String = (character.uppercaseChar().code and 0x1F).toChar().toString()
}
