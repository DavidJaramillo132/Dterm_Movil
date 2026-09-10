package com.example.dterm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.dterm.net.Keys
import com.example.dterm.net.Span
import com.example.dterm.ui.theme.DtermTheme

private val BACKGROUND = Color(0xFF101010)
private val FOREGROUND = Color(0xFFD0D0D0)

/** Cell width is averaged over this many characters to limit rounding error. */
private const val SAMPLE = 100

/** Invisible characters kept in the input field so Backspace is observable. */
private const val PAD = 8

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DtermTheme {
                // enableEdgeToEdge turns off the window's own inset handling,
                // which is what makes windowSoftInputMode="adjustResize" stop
                // working. imePadding gives the keyboard its space back, and
                // shrinking the terminal is what triggers the RESIZE the shell
                // needs to lay itself out to what is still visible.
                Scaffold(modifier = Modifier.fillMaxSize().imePadding()) { padding ->
                    TerminalScreen(Modifier.padding(padding))
                }
            }
        }
    }
}

@Composable
fun TerminalScreen(modifier: Modifier = Modifier, model: TerminalViewModel = viewModel()) {
    val state by model.uiState.collectAsState()

    when (state.status) {
        TerminalViewModel.Status.DISCONNECTED ->
            ConnectForm(state.message, modifier, model::connect)

        TerminalViewModel.Status.CONNECTING ->
            Text(state.message, modifier.padding(16.dp))

        TerminalViewModel.Status.CONNECTED ->
            Terminal(state, modifier, model::send, model::resize, model::disconnect)
    }
}

@Composable
private fun ConnectForm(
    error: String,
    modifier: Modifier = Modifier,
    onConnect: (String, Int, String, String) -> Unit,
) {
    // The address of the machine running the server, as the phone sees it.
    // On a shared network that is the machine's LAN address; with
    // `adb reverse tcp:4242 tcp:4242` over USB it is 127.0.0.1.
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("4242") }
    var secret by rememberSaveable { mutableStateOf("") }
    var session by rememberSaveable { mutableStateOf("default") }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("DTerm", style = MaterialTheme.typography.headlineMedium)

        val plain = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
        )

        OutlinedTextField(host, { host = it }, label = { Text("Host") },
            placeholder = { Text("192.168.1.10") },
            singleLine = true, keyboardOptions = plain, modifier = Modifier.fillMaxWidth())

        OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Port") },
            singleLine = true, modifier = Modifier.fillMaxWidth())

        OutlinedTextField(secret, { secret = it }, label = { Text("Shared secret") },
            singleLine = true, keyboardOptions = plain,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth())

        OutlinedTextField(session, { session = it }, label = { Text("Session") },
            singleLine = true, keyboardOptions = plain, modifier = Modifier.fillMaxWidth())

        Button(
            onClick = { onConnect(host.trim(), port.toIntOrNull() ?: 4242, secret, session.trim()) },
            enabled = host.isNotBlank() && secret.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Connect") }

        if (error.isNotEmpty()) {
            Text(error, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun Terminal(
    state: TerminalViewModel.UiState,
    modifier: Modifier = Modifier,
    onSend: (String) -> Unit,
    onResize: (Int, Int) -> Unit,
    onDisconnect: () -> Unit,
) {
    val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    val measurer = rememberTextMeasurer()

    val cell = remember(style) {
        val sample = measurer.measure("M".repeat(SAMPLE), style)
        sample.size.width.toFloat() / SAMPLE to sample.size.height.toFloat()
    }

    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()

    val text = remember(state.screen) { render(state.screen) }

    // Follow new output, but only on the normal screen. A full-screen program
    // owns every row, so there is nothing below to scroll to.
    LaunchedEffect(text, state.fullScreen) {
        if (!state.fullScreen) vertical.animateScrollTo(vertical.maxValue)
    }

    Column(modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(BACKGROUND)
                .padding(8.dp)
                // The viewport, not the text: the content scrolls and is
                // therefore taller than the window the shell needs to know.
                .onSizeChanged { size ->
                    val cols = (size.width / cell.first).toInt().coerceIn(20, 500)
                    val rows = (size.height / cell.second).toInt().coerceIn(4, 200)
                    onResize(rows, cols)
                },
        ) {
            Text(
                text = text,
                style = style,
                color = FOREGROUND,
                softWrap = false,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(vertical)
                    .horizontalScroll(horizontal),
            )
        }

        ExtraKeys(onSend)
        InputField(onSend)

        TextButton(onClick = onDisconnect, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text("Disconnect")
        }
    }
}

/**
 * The keys a phone keyboard does not have but a terminal cannot do without.
 *
 * Ctrl is a latch rather than a held key: tap it, then tap a letter.
 */
@Composable
private fun ExtraKeys(onSend: (String) -> Unit) {
    var control by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChip(
            selected = control,
            onClick = { control = !control },
            label = { Text("CTRL") },
        )

        listOf(
            "ESC" to Keys.ESC,
            "TAB" to Keys.TAB,
            "^C" to Keys.control('c'),
            "^D" to Keys.control('d'),
            "^Z" to Keys.control('z'),
            "←" to Keys.LEFT,
            "↓" to Keys.DOWN,
            "↑" to Keys.UP,
            "→" to Keys.RIGHT,
            "HOME" to Keys.HOME,
            "END" to Keys.END,
            "PgUp" to Keys.PAGE_UP,
            "PgDn" to Keys.PAGE_DOWN,
        ).forEach { (label, sequence) ->
            SuggestionChip(onClick = { onSend(sequence) }, label = { Text(label) })
        }
    }

    if (control) {
        ControlLatch { letter ->
            onSend(Keys.control(letter))
            control = false
        }
    }
}

@Composable
private fun ControlLatch(onLetter: (Char) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ('a'..'z').forEach { letter ->
            SuggestionChip(
                onClick = { onLetter(letter) },
                label = { Text(letter.uppercase()) },
            )
        }
    }
}

/**
 * Sends each character as it is typed rather than a line at a time.
 *
 * A shell echoes what you type and handles its own editing, and a full-screen
 * program reacts to every keystroke, so neither works if the app holds the line
 * back until Enter.
 *
 * The field is kept padded with invisible characters because a soft keyboard
 * reports nothing at all when Backspace is pressed on an empty field. Deleting
 * one of those pad characters is how a Backspace becomes observable here.
 */
@Composable
private fun InputField(onSend: (String) -> Unit) {
    val pad = remember { "​".repeat(PAD) }
    val start = remember(pad) { TextFieldValue(pad, TextRange(pad.length)) }
    var field by remember { mutableStateOf(start) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = field,
            onValueChange = { updated ->
                val typed = updated.text
                when {
                    typed.length > pad.length -> onSend(typed.substring(pad.length))
                    typed.length < pad.length -> repeat(pad.length - typed.length) {
                        onSend(Keys.BACKSPACE)
                    }
                }
                field = start
            },
            singleLine = true,
            label = { Text("keys") },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Send,
            ),
            modifier = Modifier.weight(1f),
        )

        Button(onClick = { onSend(Keys.ENTER) }) { Text("↵") }
    }
}

/** Builds the styled text for one screen. */
private fun render(screen: List<List<Span>>): AnnotatedString = buildAnnotatedString {
    screen.forEachIndexed { index, row ->
        if (index > 0) append('\n')

        row.forEach { span ->
            // Inverse video is a swap, not a colour of its own: it is how a
            // selected row or a block cursor gets drawn.
            val foreground =
                if (span.inverse) span.bg.orElse(BACKGROUND) else span.fg.orElse(FOREGROUND)
            val background =
                if (span.inverse) span.fg.orElse(FOREGROUND) else span.bg.orElse(Color.Unspecified)

            withStyle(
                SpanStyle(
                    color = foreground,
                    background = background,
                    fontWeight = if (span.bold) FontWeight.Bold else FontWeight.Normal,
                )
            ) { append(span.text) }
        }
    }
}

/** Zero means the emulator never set a colour, so the default applies. */
private fun Int.orElse(fallback: Color): Color = if (this == 0) fallback else Color(this)
