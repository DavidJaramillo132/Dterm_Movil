package com.example.dterm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.dterm.ui.theme.DtermTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DtermTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
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
            Terminal(state.output, modifier, model::submit, model::disconnect)
    }
}

@Composable
private fun ConnectForm(
    error: String,
    modifier: Modifier = Modifier,
    onConnect: (String, Int, String, String) -> Unit,
) {
    // 127.0.0.1 is right when the phone is wired up with `adb reverse`, which
    // tunnels the port over USB. Over Wi-Fi this is the machine's LAN address.
    var host by rememberSaveable { mutableStateOf("127.0.0.1") }
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
    output: String,
    modifier: Modifier = Modifier,
    onSubmit: (String) -> Unit,
    onDisconnect: () -> Unit,
) {
    var line by remember { mutableStateOf("") }
    val scroll = rememberScrollState()

    // New output should bring the view down with it, the way a terminal does.
    LaunchedEffect(output) { scroll.animateScrollTo(scroll.maxValue) }

    Column(modifier.fillMaxSize()) {
        Text(
            text = output,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = Color(0xFFD0D0D0),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF101010))
                .verticalScroll(scroll)
                .padding(8.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = line,
                onValueChange = { line = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Send,
                ),
                modifier = Modifier.weight(1f),
            )

            Button(onClick = { onSubmit(line); line = "" }) { Text("Send") }
        }

        TextButton(onClick = onDisconnect, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text("Disconnect")
        }
    }
}
