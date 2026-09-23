package io.github.kunkai2002.splashclean.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.kunkai2002.splashclean.R
import io.github.kunkai2002.splashclean.adb.AdbAuthorizer
import io.github.kunkai2002.splashclean.adb.AuthState

@Composable
fun PhonePairingSection() {
    val context = LocalContext.current
    SectionCard(stringResource(R.string.pair_title)) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Text(stringResource(R.string.pair_unsupported), style = MaterialTheme.typography.bodySmall)
            return@SectionCard
        }
        val state by AdbAuthorizer.state.collectAsState()
        Text(stringResource(R.string.pair_steps), style = MaterialTheme.typography.bodySmall)
        when (val s = state) {
            AuthState.Idle, is AuthState.Failed, is AuthState.Done -> {
                if (s is AuthState.Failed) {
                    Text(stringResource(R.string.pair_error, s.message), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (s is AuthState.Done) {
                    Text(stringResource(R.string.pair_done), color = MaterialTheme.colorScheme.primary)
                    SelectionContainer {
                        Text(s.log, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        AdbAuthorizer.startPairing(context)
                        SystemIntents.openDeveloperOptions(context)
                    }) { Text(stringResource(R.string.pair_start)) }
                    OutlinedButton(onClick = { AdbAuthorizer.reconnectAndGrant(context) }) {
                        Text(stringResource(R.string.pair_reconnect))
                    }
                }
            }

            AuthState.WaitingForPairingDialog, is AuthState.WaitingForCode -> {
                Text(
                    stringResource(
                        if (s is AuthState.WaitingForCode) R.string.pair_notify_found_text else R.string.pair_notify_searching_text
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                var code by remember { mutableStateOf("") }
                var port by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = code, onValueChange = { code = it.filter(Char::isDigit).take(6) }, singleLine = true,
                    label = { Text(stringResource(R.string.pair_code_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (s !is AuthState.WaitingForCode) {
                    OutlinedTextField(
                        value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) }, singleLine = true,
                        label = { Text(stringResource(R.string.pair_port_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = code.length == 6 && (s is AuthState.WaitingForCode || port.isNotEmpty()),
                        onClick = { AdbAuthorizer.submitCode(context, code, port.toIntOrNull().takeIf { s !is AuthState.WaitingForCode }) },
                    ) { Text(stringResource(R.string.pair_submit)) }
                    TextButton(onClick = { AdbAuthorizer.cancel(context) }) { Text(stringResource(R.string.action_cancel)) }
                }
            }

            AuthState.Working -> {
                Text(stringResource(R.string.pair_working))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}
