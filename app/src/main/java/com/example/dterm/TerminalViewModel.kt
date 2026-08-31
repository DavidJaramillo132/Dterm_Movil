package com.example.dterm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.dterm.net.DtermConnection
import com.example.dterm.net.TerminalBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
        val output: String = "",
    )

    private val screen = TerminalBuffer()
    private val state = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = state.asStateFlow()

    private var connection: DtermConnection? = null
    private var worker: Job? = null

    fun connect(host: String, port: Int, secret: String, session: String) {
        if (state.value.status != Status.DISCONNECTED) return

        screen.clear()
        state.value = UiState(status = Status.CONNECTING, message = "connecting to $host:$port…")

        worker = viewModelScope.launch {
            // Sockets on the main thread throw NetworkOnMainThreadException, so
            // every byte of this runs on the IO dispatcher.
            withContext(Dispatchers.IO) {
                val link = DtermConnection(host, port, secret, session)
                connection = link

                try {
                    link.open(ROWS, COLS)
                    state.value = state.value.copy(status = Status.CONNECTED, message = "")

                    link.readLoop { chunk ->
                        screen.append(chunk)
                        state.value = state.value.copy(output = screen.snapshot())
                    }
                } catch (failure: Exception) {
                    state.value = state.value.copy(
                        status = Status.DISCONNECTED,
                        message = failure.message ?: failure.javaClass.simpleName,
                    )
                } finally {
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

    /** Sends one line, with the newline the shell needs to act on it. */
    fun submit(line: String) = send(line + "\n")

    fun send(text: String) {
        val link = connection ?: return

        viewModelScope.launch(Dispatchers.IO) {
            runCatching { link.sendInput(text) }
        }
    }

    fun disconnect() {
        // Closing the socket is what breaks readLoop out of its blocking read.
        connection?.close()
        worker?.cancel()
    }

    override fun onCleared() {
        disconnect()
        super.onCleared()
    }

    private companion object {
        // Fixed for now. A real client would measure the text area and send a
        // RESIZE whenever the keyboard opens or the phone rotates.
        const val ROWS = 40
        const val COLS = 80
    }
}
