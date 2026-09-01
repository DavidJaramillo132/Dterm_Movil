package com.example.dterm.net

//
// A terminal emulator: a grid of cells and a cursor.
//
// The difference from a plain text buffer is the whole point. A shell prints
// forwards and never looks back, so appending text is enough for it. A
// full-screen program does something else entirely: it says "put the cursor at
// row 3 column 1, erase to the end of the line, write this", and repeats that
// many times a second. Those instructions only mean something against a grid
// that can be overwritten in place.
//
// What is implemented here is the subset that real programs actually use:
// cursor movement, erasing, insert and delete, a scrolling region, the
// alternate screen, and colour. It is not a complete VT500.
//

/** One run of characters that share a colour and weight. */
data class Span(
    val text: String,
    val fg: Int,
    val bg: Int,
    val bold: Boolean,
    val inverse: Boolean,
)

class Emulator(rows: Int = 24, cols: Int = 80) {

    var rows = rows.coerceAtLeast(1)
        private set
    var cols = cols.coerceAtLeast(1)
        private set

    /** A line of the grid. Parallel arrays rather than objects per cell. */
    private class Line(size: Int) {
        var chars = CharArray(size) { ' ' }
        var fg = IntArray(size)
        var bg = IntArray(size)
        var bold = BooleanArray(size)
        var inverse = BooleanArray(size)

        fun resize(size: Int) {
            chars = chars.copyOf(size).also { for (i in chars.size until size) it[i] = ' ' }
            fg = fg.copyOf(size)
            bg = bg.copyOf(size)
            bold = bold.copyOf(size)
            inverse = inverse.copyOf(size)
        }

        fun clear(from: Int, to: Int, fg: Int, bg: Int) {
            for (i in from until to) {
                chars[i] = ' '
                this.fg[i] = fg
                this.bg[i] = bg
                bold[i] = false
                inverse[i] = false
            }
        }
    }

    private var screen = MutableList(this.rows) { Line(this.cols) }
    private var saved: MutableList<Line>? = null      // the main screen, while on the alternate one
    private val scrollback = ArrayDeque<Line>()

    private var row = 0
    private var col = 0
    private var savedRow = 0
    private var savedCol = 0

    // The region lines scroll within. Full screen unless a program narrows it,
    // which is how a status bar stays put while the rest scrolls.
    private var top = 0
    private var bottom = this.rows - 1

    private var fg = 0
    private var bg = 0
    private var bold = false
    private var inverse = false

    private var autoWrap = true

    // Writing in the last column does not move the cursor off the line. The
    // wrap happens when the NEXT character arrives, which is what keeps a line
    // that ends exactly at the margin from leaving a blank line behind.
    private var wrapPending = false

    val onAlternateScreen: Boolean get() = saved != null

    // ---------------------------------------------------------------- input

    private enum class State { GROUND, ESCAPE, CSI, OSC, CHARSET }

    private var state = State.GROUND
    private var pending = ByteArray(0)
    private val params = StringBuilder()

    fun feed(chunk: ByteArray) {
        val data = if (pending.isEmpty()) chunk else pending + chunk
        val complete = completeBytes(data)
        pending = data.copyOfRange(complete, data.size)

        for (character in String(data, 0, complete, Charsets.UTF_8)) {
            when (state) {
                State.GROUND -> ground(character)

                State.ESCAPE -> {
                    params.setLength(0)
                    when (character) {
                        '[' -> state = State.CSI
                        ']' -> state = State.OSC
                        '(', ')', '*', '+' -> state = State.CHARSET
                        'M' -> { reverseIndex(); state = State.GROUND }
                        'D' -> { index(); state = State.GROUND }
                        'E' -> { index(); col = 0; state = State.GROUND }
                        '7' -> { savedRow = row; savedCol = col; state = State.GROUND }
                        '8' -> { row = savedRow; col = savedCol; state = State.GROUND }
                        'c' -> { reset(); state = State.GROUND }
                        else -> state = State.GROUND
                    }
                }

                State.CSI ->
                    if (character.code in 0x40..0x7E) {
                        csi(character)
                        state = State.GROUND
                    } else {
                        params.append(character)
                    }

                // Window titles. Nothing to draw, but they must be swallowed
                // whole: they carry arbitrary text that would otherwise print.
                State.OSC -> when (character) {
                    BELL -> state = State.GROUND
                    ESCAPE -> state = State.ESCAPE
                    else -> Unit
                }

                State.CHARSET -> state = State.GROUND
            }
        }
    }

    private fun ground(character: Char) {
        when (character) {
            ESCAPE -> state = State.ESCAPE
            '\n' -> { index(); wrapPending = false }
            '\r' -> { col = 0; wrapPending = false }
            '\b' -> { if (col > 0) col--; wrapPending = false }
            '\t' -> { col = ((col / TAB + 1) * TAB).coerceAtMost(cols - 1); wrapPending = false }
            BELL -> Unit
            else -> if (character.code >= 0x20) put(character)
        }
    }

    private fun put(character: Char) {
        if (wrapPending && autoWrap) {
            col = 0
            index()
            wrapPending = false
        }

        val line = screen[row]
        line.chars[col] = character
        line.fg[col] = fg
        line.bg[col] = bg
        line.bold[col] = bold
        line.inverse[col] = inverse

        if (col == cols - 1) wrapPending = true else col++
    }

    // ------------------------------------------------------------ sequences

    private fun numbers(): List<Int> =
        params.toString().trimStart('?', '>', '!').split(';')
            .map { it.trim().toIntOrNull() ?: 0 }

    private fun argument(index: Int, fallback: Int = 1): Int =
        numbers().getOrNull(index)?.takeIf { it != 0 } ?: fallback

    private fun csi(final: Char) {
        val private = params.startsWith("?")
        val values = numbers()

        when (final) {
            'A' -> row = (row - argument(0)).coerceAtLeast(top)
            'B' -> row = (row + argument(0)).coerceAtMost(bottom)
            'C' -> col = (col + argument(0)).coerceAtMost(cols - 1)
            'D' -> col = (col - argument(0)).coerceAtLeast(0)
            'E' -> { row = (row + argument(0)).coerceAtMost(bottom); col = 0 }
            'F' -> { row = (row - argument(0)).coerceAtLeast(top); col = 0 }
            'G', '`' -> col = (argument(0) - 1).coerceIn(0, cols - 1)
            'd' -> row = (argument(0) - 1).coerceIn(0, rows - 1)

            'H', 'f' -> {
                row = (argument(0) - 1).coerceIn(0, rows - 1)
                col = (argument(1) - 1).coerceIn(0, cols - 1)
            }

            'J' -> eraseDisplay(values.firstOrNull() ?: 0)
            'K' -> eraseLine(values.firstOrNull() ?: 0)
            'L' -> insertLines(argument(0))
            'M' -> deleteLines(argument(0))
            'P' -> deleteChars(argument(0))
            '@' -> insertChars(argument(0))
            'X' -> screen[row].clear(col, (col + argument(0)).coerceAtMost(cols), fg, bg)
            'S' -> scrollUp(argument(0))
            'T' -> scrollDown(argument(0))

            'r' -> {
                top = (argument(0) - 1).coerceIn(0, rows - 1)
                bottom = (argument(1, rows) - 1).coerceIn(top, rows - 1)
                row = top
                col = 0
            }

            'm' -> graphics(values)
            's' -> { savedRow = row; savedCol = col }
            'u' -> { row = savedRow; col = savedCol }
            'h' -> if (private) mode(values, true)
            'l' -> if (private) mode(values, false)
        }

        wrapPending = false
    }

    private fun mode(values: List<Int>, on: Boolean) {
        for (value in values) {
            when (value) {
                7 -> autoWrap = on
                // The alternate screen is why `vim` leaves your scrollback
                // untouched when it exits: it draws on a second grid entirely.
                1047, 1049 -> if (on) enterAlternate() else leaveAlternate()
            }
        }
    }

    private fun graphics(values: List<Int>) {
        if (values.isEmpty()) return resetGraphics()

        var index = 0
        while (index < values.size) {
            when (val code = values[index]) {
                0 -> resetGraphics()
                1 -> bold = true
                7 -> inverse = true
                22 -> bold = false
                27 -> inverse = false
                39 -> fg = 0
                49 -> bg = 0
                in 30..37 -> fg = palette(code - 30)
                in 90..97 -> fg = palette(code - 90 + 8)
                in 40..47 -> bg = palette(code - 40)
                in 100..107 -> bg = palette(code - 100 + 8)

                38, 48 -> {
                    val colour = extended(values, index)
                    if (code == 38) fg = colour.first else bg = colour.first
                    index = colour.second
                }
            }
            index++
        }
    }

    /** Reads `5;n` (256-colour) or `2;r;g;b` (direct colour) after 38 or 48. */
    private fun extended(values: List<Int>, at: Int): Pair<Int, Int> = when (values.getOrNull(at + 1)) {
        5 -> palette(values.getOrNull(at + 2) ?: 0) to at + 2
        2 -> rgb(
            values.getOrNull(at + 2) ?: 0,
            values.getOrNull(at + 3) ?: 0,
            values.getOrNull(at + 4) ?: 0,
        ) to at + 4
        else -> 0 to at
    }

    private fun resetGraphics() {
        fg = 0
        bg = 0
        bold = false
        inverse = false
    }

    // -------------------------------------------------------------- editing

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> {
                screen[row].clear(col, cols, fg, bg)
                for (r in row + 1 until rows) screen[r].clear(0, cols, fg, bg)
            }
            1 -> {
                for (r in 0 until row) screen[r].clear(0, cols, fg, bg)
                screen[row].clear(0, (col + 1).coerceAtMost(cols), fg, bg)
            }
            else -> for (r in 0 until rows) screen[r].clear(0, cols, fg, bg)
        }
    }

    private fun eraseLine(mode: Int) = when (mode) {
        0 -> screen[row].clear(col, cols, fg, bg)
        1 -> screen[row].clear(0, (col + 1).coerceAtMost(cols), fg, bg)
        else -> screen[row].clear(0, cols, fg, bg)
    }

    private fun insertChars(count: Int) {
        val line = screen[row]
        val n = count.coerceAtMost(cols - col)
        for (i in cols - 1 downTo col + n) copyCell(line, i - n, line, i)
        line.clear(col, col + n, fg, bg)
    }

    private fun deleteChars(count: Int) {
        val line = screen[row]
        val n = count.coerceAtMost(cols - col)
        for (i in col until cols - n) copyCell(line, i + n, line, i)
        line.clear(cols - n, cols, fg, bg)
    }

    private fun insertLines(count: Int) {
        if (row < top || row > bottom) return
        repeat(count.coerceAtMost(bottom - row + 1)) {
            screen.removeAt(bottom)
            screen.add(row, Line(cols))
        }
    }

    private fun deleteLines(count: Int) {
        if (row < top || row > bottom) return
        repeat(count.coerceAtMost(bottom - row + 1)) {
            screen.removeAt(row)
            screen.add(bottom, Line(cols))
        }
    }

    /** Moves down one line, scrolling the region when already at its bottom. */
    private fun index() {
        if (row == bottom) scrollUp(1) else if (row < rows - 1) row++
    }

    private fun reverseIndex() {
        if (row == top) scrollDown(1) else if (row > 0) row--
    }

    private fun scrollUp(count: Int) {
        repeat(count) {
            val gone = screen.removeAt(top)

            // Only what scrolls off the real screen is history. The alternate
            // screen is scratch space and must never pollute it.
            if (top == 0 && !onAlternateScreen) {
                scrollback.addLast(gone)
                while (scrollback.size > SCROLLBACK) scrollback.removeFirst()
            }

            screen.add(bottom, Line(cols))
        }
    }

    private fun scrollDown(count: Int) {
        repeat(count) {
            screen.removeAt(bottom)
            screen.add(top, Line(cols))
        }
    }

    private fun enterAlternate() {
        if (onAlternateScreen) return
        saved = screen
        screen = MutableList(rows) { Line(cols) }
        row = 0
        col = 0
    }

    private fun leaveAlternate() {
        val main = saved ?: return
        screen = main
        saved = null
    }

    private fun reset() {
        screen = MutableList(rows) { Line(cols) }
        saved = null
        row = 0
        col = 0
        top = 0
        bottom = rows - 1
        autoWrap = true
        resetGraphics()
    }

    // --------------------------------------------------------------- output

    fun resize(rows: Int, cols: Int) {
        val newRows = rows.coerceAtLeast(1)
        val newCols = cols.coerceAtLeast(1)
        if (newRows == this.rows && newCols == this.cols) return

        if (newCols != this.cols) {
            screen.forEach { it.resize(newCols) }
            saved?.forEach { it.resize(newCols) }
            scrollback.forEach { it.resize(newCols) }
        }

        while (screen.size > newRows) {
            // Lines pushed off the top are history, exactly as when scrolling.
            val gone = screen.removeAt(0)
            if (!onAlternateScreen) scrollback.addLast(gone)
            if (row > 0) row--
        }
        while (screen.size < newRows) screen.add(Line(newCols))

        saved?.let { main ->
            while (main.size > newRows) main.removeAt(0)
            while (main.size < newRows) main.add(Line(newCols))
        }

        this.rows = newRows
        this.cols = newCols
        top = 0
        bottom = newRows - 1
        row = row.coerceIn(0, newRows - 1)
        col = col.coerceIn(0, newCols - 1)
        while (scrollback.size > SCROLLBACK) scrollback.removeFirst()
    }

    fun clear() {
        scrollback.clear()
        reset()
    }

    /**
     * The visible text, as styled runs.
     *
     * History is only included for the normal screen. On the alternate one the
     * program owns every row, so anything above it would be a leftover.
     */
    fun snapshot(): List<List<Span>> {
        val lines = if (onAlternateScreen) screen else scrollback + screen
        return lines.map(::spansOf)
    }

    private fun spansOf(line: Line): List<Span> {
        // Trailing blanks are padding, not content: keeping them would make
        // every row as wide as the screen and break the scroll position.
        var end = line.chars.size
        while (end > 0 && line.chars[end - 1] == ' ' && line.bg[end - 1] == 0) end--
        if (end == 0) return emptyList()

        val spans = mutableListOf<Span>()
        val text = StringBuilder()
        var start = 0

        for (i in 1..end) {
            val split = i == end ||
                line.fg[i] != line.fg[start] || line.bg[i] != line.bg[start] ||
                line.bold[i] != line.bold[start] || line.inverse[i] != line.inverse[start]

            if (split) {
                text.setLength(0)
                text.append(line.chars, start, i - start)
                spans.add(Span(text.toString(), line.fg[start], line.bg[start],
                               line.bold[start], line.inverse[start]))
                start = i
            }
        }

        return spans
    }

    private fun copyCell(from: Line, at: Int, to: Line, into: Int) {
        to.chars[into] = from.chars[at]
        to.fg[into] = from.fg[at]
        to.bg[into] = from.bg[at]
        to.bold[into] = from.bold[at]
        to.inverse[into] = from.inverse[at]
    }

    /** Held-back bytes of a UTF-8 character split across two chunks. */
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
            else -> 1
        }

        return if (continuations + 1 < needed) index else data.size
    }

    companion object {
        const val ESCAPE = '\u001B'
        const val BELL = '\u0007'
        const val TAB = 8
        const val SCROLLBACK = 1000

        private fun rgb(r: Int, g: Int, b: Int): Int =
            0xFF shl 24 or (r and 0xFF shl 16) or (g and 0xFF shl 8) or (b and 0xFF)

        private val BASIC = intArrayOf(
            0x000000, 0xCD0000, 0x00CD00, 0xCDCD00, 0x2222E0, 0xCD00CD, 0x00CDCD, 0xE5E5E5,
            0x7F7F7F, 0xFF0000, 0x00FF00, 0xFFFF00, 0x5C5CFF, 0xFF00FF, 0x00FFFF, 0xFFFFFF,
        )

        /** The xterm 256-colour palette: 16 named, a 6x6x6 cube, then 24 greys. */
        fun palette(index: Int): Int = when {
            index < 16 -> 0xFF shl 24 or BASIC[index.coerceIn(0, 15)]

            index < 232 -> {
                val n = index - 16
                val step = intArrayOf(0, 95, 135, 175, 215, 255)
                rgb(step[n / 36 % 6], step[n / 6 % 6], step[n % 6])
            }

            index < 256 -> (8 + (index - 232) * 10).let { rgb(it, it, it) }

            else -> 0
        }
    }
}
