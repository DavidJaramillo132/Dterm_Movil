package com.example.dterm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.dterm.net.DtermConnection
import com.example.dterm.net.Emulator
import com.example.dterm.net.Span
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Holds the connection across configuration changes.
 *
 * Rotating the phone destroys and rebuilds the Activity. If the socket lived
 * there, every rotation would drop the session and start a new shell.
 */
class TerminalViewModel : ViewModel() {

    enum class Status { DISCONNECTED, CONNECTING, CONNECTED }

    data class UiState(
        val status: Status = Status.DISCONNECTED,
        val message: String = "",
        val screen: List<List<Span>> = emptyList(),
        val fullScreen: Boolean = false,
    )

    private val terminal = Emulator()
    private val state = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = state.asStateFlow()

    // The reader thread writes into the emulator while the painter reads it.
    private val lock = Any()
    private val dirty = AtomicBoolean(false)

    private var connection: DtermConnection? = null
    private var worker: Job? = null
    private var painter: Job? = null

    private var rows = 24
    private var cols = 80

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
        state.value = UiState(status = Status.CONNECTING, message = "connecting to $host:$port…")

        worker = viewModelScope.launch {
            // Sockets on the main thread throw NetworkOnMainThreadException, so
            // every byte of this runs off it.
            withContext(Dispatchers.IO) {
                val link = DtermConnection(host, port, secret, session)
                connection = link

                try {
                    link.open(rows, cols)
                    state.value = state.value.copy(status = Status.CONNECTED, message = "")
                    painter = startPainting()

                    link.readLoop { chunk ->
                        synchronized(lock) { terminal.feed(chunk) }
                        dirty.set(true)
                    }
                } catch (failure: Exception) {
                    state.value = state.value.copy(
                        status = Status.DISCONNECTED,
                        message = failure.message ?: failure.javaClass.simpleName,
                    )
                } finally {
                    painter?.cancel()
                    link.close()
                    connection = null

                    if (state.value.status == Status.CONNECTED) {
                        state.value = state.value.copy(
                            status = Status.DISCONNECTED,
                            message = "the connection closed",
                        )
                    }
                }
            }
        }
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
            if (dirty.getAndSet(false)) {
                val frame = synchronized(lock) { terminal.snapshot() to terminal.onAlternateScreen }
                state.value = state.value.copy(screen = frame.first, fullScreen = frame.second)
            }
            delay(FRAME_MS)
        }
    }

    /** Sends text exactly as typed. The shell decides what a key means. */
    fun send(text: String) {
        val link = connection ?: return

        viewModelScope.launch(Dispatchers.IO) {
            runCatching { link.sendInput(text) }
        }
    }

    fun disconnect() {
        // Closing the socket is what breaks readLoop out of its blocking read.
        painter?.cancel()
        connection?.close()
        worker?.cancel()
    }

    override fun onCleared() {
        disconnect()
        super.onCleared()
    }

    private companion object {
        const val FRAME_MS = 33L   // about 30 repaints a second
    }
}
