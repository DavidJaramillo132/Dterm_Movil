package com.example.dterm

import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.dterm.net.Keys
import com.example.dterm.net.Span
import com.example.dterm.ui.theme.Bone
import com.example.dterm.ui.theme.Bone2
import com.example.dterm.ui.theme.Bone3
import com.example.dterm.ui.theme.DtermTheme
import com.example.dterm.ui.theme.Ink
import com.example.dterm.ui.theme.Ink2
import com.example.dterm.ui.theme.Ink3
import com.example.dterm.ui.theme.Mono
import com.example.dterm.ui.theme.Rust
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Cell width is averaged over this many characters to limit rounding error. */
private const val SAMPLE = 100

/** Invisible characters kept in the input field so Backspace is observable. */
private const val PAD = 8

/** Silence beyond this reads as "probably gone" on the lifeline. */
private const val SILENT_MS = 45_000f

private val TERMINAL = TextStyle(fontFamily = Mono, fontSize = 13.sp, lineHeight = 18.sp)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Both bars get the dark style so their icons stay light over Ink.
        // Left to the theme, the icon colour follows whatever sits behind the
        // bar, which on a scrolling terminal is not a stable thing to bet on.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Ink.toArgb()),
            navigationBarStyle = SystemBarStyle.dark(Ink.toArgb()),
        )

        setContent {
            DtermTheme {
                Scaffold(
                    containerColor = Ink,
                    modifier = Modifier.fillMaxSize().imePadding(),
                ) { padding ->
                    TerminalScreen(Modifier.padding(padding))
                }
            }
        }
    }
}

@Composable
fun TerminalScreen(modifier: Modifier = Modifier, model: TerminalViewModel = viewModel()) {
    val state by model.uiState.collectAsState()
    val context = LocalContext.current
    val recall = remember(context) { Recall(context) }

    // Recorded here rather than on the button, because reaching CONNECTED is
    // the only proof the address was the right one.
    LaunchedEffect(state.status, state.host) {
        if (state.status == TerminalViewModel.Status.CONNECTED) recall.remember(state.host)
    }

    when (state.status) {
        TerminalViewModel.Status.DISCONNECTED ->
            ConnectScreen(state, recall, modifier, model::connect)

        TerminalViewModel.Status.CONNECTING ->
            Connecting(state, modifier, model::disconnect)

        TerminalViewModel.Status.CONNECTED ->
            Terminal(state, modifier, model::send, model::resize, model::disconnect)
    }
}

// ---------------------------------------------------------------- connecting

@Composable
private fun ConnectScreen(
    state: TerminalViewModel.UiState,
    recall: Recall,
    modifier: Modifier = Modifier,
    onConnect: (String, Int, String, String) -> Unit,
) {
    var form by remember { mutableStateOf(recall.load()) }
    var reveal by remember { mutableStateOf(false) }
    val recent = remember { recall.recentHosts() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "DTerm",
            style = MaterialTheme.typography.displaySmall,
            color = Bone,
        )
        Text(
            text = "A shell that keeps running after you close this.",
            style = MaterialTheme.typography.bodyMedium,
            color = Bone2,
            modifier = Modifier.padding(bottom = 10.dp),
        )

        Field(
            value = form.host,
            onValueChange = { form = form.copy(host = it) },
            label = "Host",
            placeholder = "192.168.1.10",
        )

        if (recent.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                recent.forEach { address ->
                    HostChip(address, address == form.host) { form = form.copy(host = address) }
                }
            }
        }

        Field(
            value = form.port,
            onValueChange = { form = form.copy(port = it.filter(Char::isDigit)) },
            label = "Port",
            keyboardType = KeyboardType.Number,
            modifier = Modifier.width(140.dp),
        )

        Field(
            value = form.secret,
            onValueChange = { form = form.copy(secret = it) },
            label = "Shared secret",
            visual = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
            trailing = {
                // A 64-character hex string typed on a phone cannot be checked
                // any other way, and the only feedback for getting it wrong
                // arrives after a failed handshake.
                TextButton(onClick = { reveal = !reveal }) {
                    Text(
                        text = if (reveal) "Hide" else "Show",
                        style = MaterialTheme.typography.labelMedium,
                        color = Bone2,
                    )
                }
            },
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = form.rememberSecret,
                onCheckedChange = { form = form.copy(rememberSecret = it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Ink,
                    checkedTrackColor = Bone2,
                    uncheckedThumbColor = Bone3,
                    uncheckedTrackColor = Ink2,
                    uncheckedBorderColor = Ink3,
                ),
            )
            Column(Modifier.padding(start = 12.dp)) {
                Text("Keep the secret on this phone", style = MaterialTheme.typography.bodyMedium, color = Bone)
                Text(
                    "Saved where only this app can read it.",
                    style = MaterialTheme.typography.labelMedium,
                    color = Bone3,
                )
            }
        }

        Field(
            value = form.session,
            onValueChange = { form = form.copy(session = it) },
            label = "Session",
        )

        Button(
            onClick = {
                recall.save(form)
                onConnect(form.host.trim(), form.port.toIntOrNull() ?: 4242, form.secret, form.session.trim())
            },
            enabled = form.host.isNotBlank() && form.secret.isNotBlank(),
            shape = RoundedCornerShape(6.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Bone,
                contentColor = Ink,
                disabledContainerColor = Ink2,
                disabledContentColor = Bone3,
            ),
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp).padding(top = 8.dp),
        ) {
            Text("Attach", style = MaterialTheme.typography.labelLarge)
        }

        state.problem?.let { Verdict(it, state.session) }
    }
}

/**
 * An address that has worked before.
 *
 * The same machine answers at one address on the local network and another
 * through a tunnel, and which one applies changes when you leave the building.
 */
@Composable
private fun HostChip(address: String, current: Boolean, onPick: () -> Unit) {
    Text(
        text = address,
        style = MaterialTheme.typography.bodySmall,
        color = if (current) Ink else Bone2,
        modifier = Modifier
            .defaultMinSize(minHeight = 48.dp)
            .background(if (current) Bone else Ink2, RoundedCornerShape(6.dp))
            .clickable(onClick = onPick)
            .semantics { contentDescription = "Use $address" }
            .padding(horizontal = 14.dp)
            .wrapContentHeight(Alignment.CenterVertically),
    )
}

/** What ended the last connection, said in the interface's voice. */
@Composable
private fun Verdict(problem: TerminalViewModel.Problem, session: String) {
    val failed = problem != TerminalViewModel.Problem.CLOSED_BY_USER &&
        problem != TerminalViewModel.Problem.LOST

    val words = when (problem) {
        TerminalViewModel.Problem.REFUSED ->
            "Nothing is listening on that port. Start dterm on the host, or check the port."
        TerminalViewModel.Problem.UNREACHABLE ->
            "That address cannot be reached. Check the host, and that both devices are on the same network."
        TerminalViewModel.Problem.TIMEOUT ->
            "The host never answered. A firewall may be dropping the port."
        TerminalViewModel.Problem.WRONG_SECRET ->
            "The server turned down the secret. It has to match ~/.dterm/secret on the host exactly."
        TerminalViewModel.Problem.PROTOCOL ->
            "The server speaks a different version of the protocol. Rebuild it from the same commit."
        // Neither of these is a failure, and neither is drawn as one: the
        // session outliving the connection is the point of the whole project.
        TerminalViewModel.Problem.LOST ->
            "The link dropped. Session \"$session\" is still running — attach again to pick it up."
        TerminalViewModel.Problem.CLOSED_BY_USER ->
            "Detached. Session \"$session\" keeps running."
    }

    Text(
        text = words,
        style = MaterialTheme.typography.bodyMedium,
        color = if (failed) Rust else Bone2,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .background(Ink2, RoundedCornerShape(6.dp))
            .padding(14.dp),
    )
}

@Composable
private fun Connecting(
    state: TerminalViewModel.UiState,
    modifier: Modifier = Modifier,
    onCancel: () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Attaching", style = MaterialTheme.typography.titleMedium, color = Bone)
        Text(
            text = state.host,
            style = MaterialTheme.typography.bodySmall,
            color = Bone2,
            modifier = Modifier.padding(top = 6.dp),
        )

        // A hung TCP handshake otherwise leaves nothing to do but wait out the
        // timeout, staring at a screen with no controls on it.
        TextButton(onClick = onCancel, modifier = Modifier.padding(top = 20.dp)) {
            Text("Cancel", style = MaterialTheme.typography.labelLarge, color = Bone2)
        }
    }
}

// ------------------------------------------------------------------ terminal

@Composable
private fun Terminal(
    state: TerminalViewModel.UiState,
    modifier: Modifier = Modifier,
    onSend: (String) -> Unit,
    onResize: (Int, Int) -> Unit,
    onDetach: () -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        StatusRail(state, onDetach)
        Lifeline(state)
        Viewport(state, onResize, Modifier.weight(1f))
        KeyRows(onSend)
        InputBar(onSend)
    }
}

/** Which machine, which session, how big. Detach lives here, far from the thumb. */
@Composable
private fun StatusRail(state: TerminalViewModel.UiState, onDetach: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Ink)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "${state.host} · ${state.session}",
                style = MaterialTheme.typography.bodySmall,
                color = Bone,
                maxLines = 1,
            )
            Text(
                text = "${state.rows}×${state.cols}",
                style = MaterialTheme.typography.labelMedium,
                color = Bone3,
            )
        }

        // Detaching costs nothing — the shell and its jobs keep running — so
        // this needs no confirmation, only distance from where the thumb works.
        TextButton(onClick = onDetach) {
            Text("Detach", style = MaterialTheme.typography.labelLarge, color = Bone2)
        }
    }
}

/**
 * The one moving thing in the app, and the only thing that answers the question
 * the app exists to answer: is my session still there?
 *
 * The server pings a quiet client every 30 seconds, so an idle session still
 * produces traffic. The rule breathes while that traffic is recent and fades as
 * the silence grows, which is the honest shape of the evidence.
 */
@Composable
private fun Lifeline(state: TerminalViewModel.UiState) {
    val context = LocalContext.current
    val animated = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }

    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    val breath by rememberInfiniteTransition(label = "lifeline").animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2_400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breath",
    )

    val silence = (now - state.lastHeard).coerceAtLeast(0L)
    val heard = 1f - (silence / SILENT_MS).coerceIn(0f, 0.85f)

    Box(
        Modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(Bone.copy(alpha = if (animated) breath * heard else heard)),
    )
}

@Composable
private fun Viewport(
    state: TerminalViewModel.UiState,
    onResize: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val cell = remember {
        val sample = measurer.measure("M".repeat(SAMPLE), TERMINAL)
        sample.size.width.toFloat() / SAMPLE to sample.size.height.toFloat()
    }

    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    val scope = rememberCoroutineScope()

    val text = remember(state.screen, state.cursorLine, state.cursorColumn, state.cursorVisible) {
        render(state.screen, state.cursorLine, state.cursorColumn, state.cursorVisible)
    }

    // Following the output is only wanted while the reader is already at the
    // end. Someone who scrolled up to read what a running job printed must not
    // be dragged back down thirty times a second.
    val atBottom by remember {
        derivedStateOf { vertical.maxValue - vertical.value < 8 }
    }
    LaunchedEffect(text, state.fullScreen) {
        if (!state.fullScreen && atBottom) vertical.scrollTo(vertical.maxValue)
    }

    Box(
        modifier
            .fillMaxWidth()
            .background(Ink)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .onSizeChanged { size ->
                val cols = (size.width / cell.first).toInt().coerceIn(20, 500)
                val rows = (size.height / cell.second).toInt().coerceIn(4, 200)
                onResize(rows, cols)
            }
    ) {
        // The terminal is a grid whose size is reported to the shell, so the
        // system font scale must not silently change how many columns exist.
        // Scoped to this subtree only: every other screen honours it.
        CompositionLocalProvider(
            LocalDensity provides Density(LocalDensity.current.density, 1f)
        ) {
            SelectionContainer {
                Text(
                    text = text,
                    style = TERMINAL,
                    color = Bone,
                    softWrap = false,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(vertical)
                        .horizontalScroll(horizontal),
                )
            }
        }

        if (!atBottom) {
            Text(
                text = "Jump to latest",
                style = MaterialTheme.typography.labelLarge,
                color = Ink,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp)
                    .background(Bone, RoundedCornerShape(50))
                    .clickable { scope.launch { vertical.animateScrollTo(vertical.maxValue) } }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------- keys

@Composable
private fun KeyRows(onSend: (String) -> Unit) {
    var control by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().background(Ink2)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            KeyCap("ESC", "Escape") { onSend(Keys.ESC) }
            KeyCap("TAB", "Tab") { onSend(Keys.TAB) }
            Divider()
            KeyCap("←", "Left") { onSend(Keys.LEFT) }
            KeyCap("↓", "Down") { onSend(Keys.DOWN) }
            KeyCap("↑", "Up") { onSend(Keys.UP) }
            KeyCap("→", "Right") { onSend(Keys.RIGHT) }
            Divider()
            KeyCap("HOME", "Home") { onSend(Keys.HOME) }
            KeyCap("END", "End") { onSend(Keys.END) }
            KeyCap("PGUP", "Page up") { onSend(Keys.PAGE_UP) }
            KeyCap("PGDN", "Page down") { onSend(Keys.PAGE_DOWN) }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 8.dp, end = 8.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Armed state is a full inversion, not a tint: sending a control
            // code by accident is silent, so the cue has to be unmissable.
            KeyCap(
                label = if (control) "CTRL ON" else "CTRL",
                description = if (control) "Control armed" else "Arm control",
                armed = control,
            ) { control = !control }

            Divider()

            // Interrupting a job is a normal thing to want, so these are not
            // behind a confirmation. They are simply not next to the arrows.
            KeyCap("^C", "Control C, interrupt") { onSend(Keys.control('c')) }
            KeyCap("^D", "Control D, end of input") { onSend(Keys.control('d')) }
            KeyCap("^Z", "Control Z, suspend") { onSend(Keys.control('z')) }
        }

        if (control) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ('a'..'z').forEach { letter ->
                    KeyCap(letter.uppercase(), "Control ${letter.uppercase()}") {
                        onSend(Keys.control(letter))
                        control = false
                    }
                }
            }
        }
    }
}

@Composable
private fun Divider() {
    Box(
        Modifier
            .padding(horizontal = 4.dp)
            .width(1.dp)
            .height(28.dp)
            .background(Ink3),
    )
}

/**
 * One key. Sized to the 48dp target every one of these missed before, which
 * matters most on the row people reach for while walking.
 */
@Composable
private fun KeyCap(
    label: String,
    description: String,
    armed: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .background(if (armed) Bone else Ink, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = if (armed) Ink else Bone2,
            fontWeight = if (armed) FontWeight.W700 else FontWeight.W400,
        )
    }
}

// --------------------------------------------------------------------- input

/**
 * Sends each character as it is typed rather than a line at a time.
 *
 * A shell echoes what you type and handles its own editing, and a full-screen
 * program reacts to every keystroke, so neither works if the app holds the line
 * back until Enter.
 *
 * The field is kept padded with invisible characters because a soft keyboard
 * reports nothing at all when Backspace is pressed on an empty field. Deleting
 * one of those is how a Backspace becomes observable here.
 */
@Composable
private fun InputBar(onSend: (String) -> Unit) {
    val pad = remember { "​".repeat(PAD) }
    val start = remember(pad) { TextFieldValue(pad, TextRange(pad.length)) }
    var field by remember { mutableStateOf(start) }

    Row(
        modifier = Modifier.fillMaxWidth().background(Ink2).padding(8.dp),
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
            textStyle = TERMINAL.copy(color = Bone),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Send,
            ),
            // The keyboard's own action key did nothing before, so people
            // pressed it and then reached for the button anyway.
            keyboardActions = KeyboardActions(onSend = { onSend(Keys.ENTER) }),
            colors = fieldColours(),
            modifier = Modifier.weight(1f),
        )

        Button(
            onClick = { onSend(Keys.ENTER) },
            shape = RoundedCornerShape(6.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Bone, contentColor = Ink),
            modifier = Modifier.defaultMinSize(minWidth = 56.dp, minHeight = 48.dp),
        ) {
            Text("↵", style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics {
                contentDescription = "Enter"
            })
        }
    }
}

// -------------------------------------------------------------------- shared

/** Machine data is monospaced; the label beside it is not. */
@Composable
private fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    visual: VisualTransformation = VisualTransformation.None,
    trailing: @Composable (() -> Unit)? = null,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        placeholder = placeholder?.let {
            { Text(it, style = TERMINAL, color = Bone3) }
        },
        singleLine = true,
        textStyle = TERMINAL.copy(color = Bone),
        visualTransformation = visual,
        trailingIcon = trailing,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = keyboardType,
        ),
        colors = fieldColours(),
        shape = RoundedCornerShape(6.dp),
        modifier = modifier,
    )
}

@Composable
private fun fieldColours() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Bone,
    unfocusedTextColor = Bone,
    focusedBorderColor = Bone2,
    unfocusedBorderColor = Ink3,
    focusedLabelColor = Bone2,
    unfocusedLabelColor = Bone3,
    cursorColor = Bone,
    focusedContainerColor = Ink,
    unfocusedContainerColor = Ink,
)

/**
 * Builds the styled text for one screen, with the cursor drawn as an inverted
 * cell.
 *
 * Without it the arrow and Home keys move something invisible, which makes
 * editing a command line on a phone guesswork.
 */
private fun render(
    screen: List<List<Span>>,
    cursorLine: Int,
    cursorColumn: Int,
    cursorVisible: Boolean,
): AnnotatedString = buildAnnotatedString {
    screen.forEachIndexed { index, row ->
        if (index > 0) append('\n')

        val onCursorRow = cursorVisible && index == cursorLine
        var column = 0

        row.forEach { span ->
            val style = styleOf(span)
            val end = column + span.text.length

            if (onCursorRow && cursorColumn in column until end) {
                val at = cursorColumn - column
                if (at > 0) withStyle(style) { append(span.text, 0, at) }
                withStyle(CURSOR) { append(span.text[at]) }
                if (at + 1 < span.text.length) {
                    withStyle(style) { append(span.text, at + 1, span.text.length) }
                }
            } else {
                withStyle(style) { append(span.text) }
            }

            column = end
        }

        // A cursor sitting past the last character of the row — at an empty
        // prompt, or at the end of a line — has no span to live in.
        if (onCursorRow && cursorColumn >= column) {
            append(" ".repeat(cursorColumn - column))
            withStyle(CURSOR) { append(' ') }
        }
    }
}

private val CURSOR = SpanStyle(color = Ink, background = Bone)

private fun styleOf(span: Span): SpanStyle {
    // Inverse video is a swap, not a colour of its own: it is how a selected
    // row or a block cursor gets drawn by the program on the far side.
    val foreground = if (span.inverse) span.bg.orElse(Ink) else span.fg.orElse(Bone)
    val background = if (span.inverse) span.fg.orElse(Bone) else span.bg.orElse(Color.Unspecified)

    return SpanStyle(
        color = foreground,
        background = background,
        fontWeight = if (span.bold) FontWeight.W700 else FontWeight.W400,
    )
}

/** Zero means the emulator never set a colour, so the app's own applies. */
private fun Int.orElse(fallback: Color): Color = if (this == 0) fallback else Color(this)
