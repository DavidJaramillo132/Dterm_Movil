package com.example.dterm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.dterm.net.DtermConnection
import com.example.dterm.net.Emulator
import com.example.dterm.net.ProtocolException
import com.example.dterm.net.Span
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * Holds the connection across configuration changes.
 *
 * Rotating the phone destroys and rebuilds the Activity. If the socket lived
 * there, every rotation would drop the session and start a new shell.
 */
class TerminalViewModel : ViewModel() {

    enum class Status { DISCONNECTED, CONNECTING, CONNECTED }

    /**
     * Why a connection is not up.
     *
     * A category, not a sentence: the same cause has to read differently in a
     * banner, a dialog and a retry prompt, and only the UI knows which of those
     * it is drawing, or what language the phone is set to.
     */
    enum class Problem {
        /** Nothing is listening on that port. */
        REFUSED,

        /** The host itself could not be reached or resolved. */
        UNREACHABLE,

        /** The host answered too slowly, or stopped answering. */
        TIMEOUT,

        /** The server turned down the shared secret. */
        WRONG_SECRET,

        /** The server said something this client does not understand. */
        PROTOCOL,

        /** The link dropped after it was up. The session itself survives. */
        LOST,

        /** The user asked to detach. Not a failure. */
        CLOSED_BY_USER,
    }

    data class UiState(
        val status: Status = Status.DISCONNECTED,
        val message: String = "",
        val screen: List<List<Span>> = emptyList(),
        val fullScreen: Boolean = false,
        val host: String = "",
        val session: String = "",
        val rows: Int = DEFAULT_ROWS,
        val cols: Int = DEFAULT_COLS,
        val cursorLine: Int = 0,
        val cursorColumn: Int = 0,
        val cursorVisible: Boolean = true,
        /** System.currentTimeMillis() of the last frame from the server, 0 if none. */
        val lastHeard: Long = 0L,
        val problem: Problem? = null,
    )

    private val terminal = Emulator()
    private val state = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = state.asStateFlow()

    // The reader thread writes into the emulator while the painter reads it.
    private val lock = Any()
    private val dirty = AtomicBoolean(false)

    // The reader thread stamps this on every frame; the painter publishes it.
    // Writing to the state flow from the reader instead would put an update on
    // every ping and on every burst of output, which is what the painter exists
    // to avoid.
    private val heard = AtomicLong(0)

    // Raised before the socket is closed on purpose, so that the failure this
    // provokes in the reader can be told apart from the link dying on its own.
    private val closing = AtomicBoolean(false)

    private var connection: DtermConnection? = null
    private var worker: Job? = null
    private var painter: Job? = null

    private var rows = DEFAULT_ROWS
    private var cols = DEFAULT_COLS

    /**
     * Tells the shell how big the window actually is.
     *
     * This is not cosmetic. Programs that draw a full screen ask the kernel for
     * the window size and lay themselves out to it, so a wrong size garbles
     * them no matter how good the renderer is.
     */
    fun resize(rows: Int, cols: Int) {
        if (rows == this.rows && cols == this.cols) return

        this.rows = rows
        this.cols = cols
        synchronized(lock) { terminal.resize(rows, cols) }
        dirty.set(true)
        state.update { it.copy(rows = rows, cols = cols) }

        val link = connection ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { link.resize(rows, cols) }
        }
    }

    fun connect(host: String, port: Int, secret: String, session: String) {
        if (state.value.status != Status.DISCONNECTED) return

        synchronized(lock) {
            terminal.clear()
            terminal.resize(rows, cols)
        }
        closing.set(false)
        heard.set(0)
        state.value = UiState(
            status = Status.CONNECTING,
            host = host,
            session = session,
            rows = rows,
            cols = cols,
        )

        worker = viewModelScope.launch {
            // Sockets on the main thread throw NetworkOnMainThreadException, so
            // every byte of this runs off it.
            withContext(Dispatchers.IO) {
                val link = DtermConnection(host, port, secret, session)
                connection = link

                // Whether the handshake got through changes what a later
                // failure means: the same dead socket is a refused login before
                // it and a lost session after it.
                var attached = false

                try {
                    link.open(rows, cols)
                    attached = true
                    heard.set(System.currentTimeMillis())
                    state.update {
                        it.copy(status = Status.CONNECTED, problem = null, lastHeard = heard.get())
                    }
                    painter = startPainting()

                    link.readLoop(onFrame = { heard.set(System.currentTimeMillis()) }) { chunk ->
                        synchronized(lock) { terminal.feed(chunk) }
                        dirty.set(true)
                    }
                } catch (cancelled: CancellationException) {
                    // disconnect() and onCleared() cancel this job. Nothing
                    // broke; the screen is simply going away.
                    finish(Problem.CLOSED_BY_USER)
                    throw cancelled
                } catch (failure: Exception) {
                    finish(classify(failure, attached))
                } finally {
                    painter?.cancel()
                    link.close()
                    connection = null

                    // readLoop only ever leaves by throwing, so this is a guard
                    // against a path that stopped short of reporting anything.
                    if (state.value.status != Status.DISCONNECTED) {
                        finish(if (closing.get()) Problem.CLOSED_BY_USER else Problem.LOST)
                    }
                }
            }
        }
    }

    /**
     * Puts a category on whatever ended the connection.
     *
     * A deliberate close wins over everything else: closing the socket from
     * another thread is what makes the reader throw, so the exception seen here
     * describes the mechanism rather than the reason.
     */
    private fun classify(failure: Throwable, attached: Boolean): Problem = when {
        closing.get() -> Problem.CLOSED_BY_USER

        failure is ProtocolException ->
            if (failure.message == DtermConnection.REJECTED_SECRET) Problem.WRONG_SECRET
            else Problem.PROTOCOL

        failure is ConnectException -> Problem.REFUSED
        failure is UnknownHostException || failure is NoRouteToHostException -> Problem.UNREACHABLE
        failure is SocketTimeoutException -> Problem.TIMEOUT

        // What is left is an I/O failure with no name of its own: a reset
        // socket, an unexpected end of stream, a read that timed out and was
        // wrapped. Which side of the handshake it fell on is all that separates
        // a server that never let us in from a session we have simply lost.
        attached -> Problem.LOST
        else -> Problem.PROTOCOL
    }

    private fun finish(problem: Problem) {
        state.update { it.copy(status = Status.DISCONNECTED, message = "", problem = problem) }
    }

    /**
     * Repaints at a fixed rate rather than on every frame that arrives.
     *
     * A full-screen program can emit dozens of repaints a second, and each one
     * would otherwise rebuild the whole screen on the UI thread. Coalescing
     * them costs nothing visually: no display shows more than it can draw.
     */
    private fun startPainting(): Job = viewModelScope.launch(Dispatchers.Default) {
        while (isActive) {
            val at = heard.get()

            if (dirty.getAndSet(false)) {
                val frame = synchronized(lock) {
                    Frame(
                        terminal.snapshot(),
                        terminal.onAlternateScreen,
                        terminal.cursorLine,
                        terminal.cursorColumn,
                        terminal.cursorVisible,
                    )
                }
                state.update {
                    it.copy(
                        screen = frame.screen,
                        fullScreen = frame.fullScreen,
                        cursorLine = frame.cursorLine,
                        cursorColumn = frame.cursorColumn,
                        cursorVisible = frame.cursorVisible,
                        lastHeard = at,
                    )
                }
            } else if (at != state.value.lastHeard) {
                // An idle session answers pings and draws nothing at all. That
                // silence is exactly what a liveness indicator has to survive.
                state.update { it.copy(lastHeard = at) }
            }

            delay(FRAME_MS)
        }
    }

    /** One consistent reading of the emulator, taken under the lock. */
    private class Frame(
        val screen: List<List<Span>>,
        val fullScreen: Boolean,
        val cursorLine: Int,
        val cursorColumn: Int,
        val cursorVisible: Boolean,
    )

    /** Sends text exactly as typed. The shell decides what a key means. */
    fun send(text: String) {
        val link = connection ?: return

        viewModelScope.launch(Dispatchers.IO) {
            runCatching { link.sendInput(text) }
        }
    }

    fun disconnect() {
        // The flag has to be up before the socket goes down, or the reader
        // reports the close it is about to see as a failure of its own.
        closing.set(true)

        // Closing the socket is what breaks readLoop out of its blocking read.
        painter?.cancel()
        connection?.close()
        worker?.cancel()

        // A connection that never got as far as starting its worker leaves
        // nobody behind to report the outcome.
        if (worker == null && state.value.status != Status.DISCONNECTED) {
            finish(Problem.CLOSED_BY_USER)
        }
    }

    override fun onCleared() {
        disconnect()
        super.onCleared()
    }

    private companion object {
        const val FRAME_MS = 33L   // about 30 repaints a second
        const val DEFAULT_ROWS = 24
        const val DEFAULT_COLS = 80
    }
}
