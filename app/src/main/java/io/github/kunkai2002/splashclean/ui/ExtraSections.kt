package io.github.kunkai2002.splashclean.ui

import android.app.Activity
import android.net.VpnService
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.kunkai2002.splashclean.Pages
import io.github.kunkai2002.splashclean.R
import io.github.kunkai2002.splashclean.adb.ShakeBlocker
import io.github.kunkai2002.splashclean.data.Prefs
import io.github.kunkai2002.splashclean.service.A11yGuard
import io.github.kunkai2002.splashclean.vpn.Blocklist
import io.github.kunkai2002.splashclean.vpn.DnsVpnService
import kotlinx.coroutines.launch

@Composable
fun DnsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by Prefs.flow.collectAsState()
    val stats by DnsVpnService.stats.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var listInfo by remember { mutableStateOf(Blocklist.updatedAt(context)) }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            Prefs.update { it.copy(dnsBlockEnabled = true) }
            DnsVpnService.start(context)
        }
    }
    SectionCard(stringResource(R.string.dns_title)) {
        Text(stringResource(R.string.dns_desc), style = MaterialTheme.typography.bodySmall)
        SwitchRow(
            title = stringResource(R.string.dns_switch),
            subtitle = if (stats.running) stringResource(R.string.dns_stats, stats.blocked, stats.forwarded, stats.listSize)
            else stringResource(R.string.dns_off),
            checked = s.dnsBlockEnabled,
        ) { v ->
            if (v) {
                val intent = VpnService.prepare(context)
                if (intent != null) consent.launch(intent) else {
                    Prefs.update { it.copy(dnsBlockEnabled = true) }
                    DnsVpnService.start(context)
                }
            } else {
                Prefs.update { it.copy(dnsBlockEnabled = false) }
                DnsVpnService.stop(context)
            }
        }
        if (DnsVpnService.privateDnsBypasses(context)) {
            Text(stringResource(R.string.dns_private_dns), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (stats.running && stats.upstream.isNotBlank()) {
            Text(stringResource(R.string.dns_upstream, stats.upstream), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (stats.recentBlocked.isNotEmpty()) {
            Text(
                stringResource(R.string.dns_recent, stats.recentBlocked.take(6).joinToString("\n")),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            if (listInfo > 0) stringResource(R.string.dns_list_updated, formatTime(listInfo, "MM-dd HH:mm")) else stringResource(R.string.dns_list_builtin),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    val r = Blocklist.update(context, s.dnsBlocklistUrl)
                    busy = false
                    listInfo = Blocklist.updatedAt(context)
                    Toast.makeText(
                        context,
                        r.fold({ context.getString(R.string.dns_update_ok, it) }, { context.getString(R.string.dns_update_failed, it.message ?: "") }),
                        Toast.LENGTH_LONG,
                    ).show()
                    if (r.isSuccess && stats.running) {
                        DnsVpnService.stop(context)
                        DnsVpnService.start(context)
                    }
                }
            }) { Text(stringResource(R.string.dns_update)) }
        }
    }
}

@Composable
fun ShakeSection(open: (String) -> Unit) {
    val context = LocalContext.current
    val s by Prefs.flow.collectAsState()
    val authorized = A11yGuard.hasWriteSecureSettings(context)
    SectionCard(stringResource(R.string.shake_title)) {
        Text(stringResource(R.string.shake_desc), style = MaterialTheme.typography.bodySmall)
        Column(Modifier.fillMaxWidth().clickable { open(Pages.SHAKE) }.padding(vertical = 6.dp)) {
            Text(stringResource(R.string.shake_pick), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.shake_picked, s.shakeBlockApps.size),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val status = when {
            !authorized -> stringResource(R.string.shake_need_auth)
            s.shakeLastError != null -> stringResource(R.string.shake_error, s.shakeLastError ?: "")
            s.shakeLastApply > 0 && s.shakeAppliedApps.isNotEmpty() -> stringResource(R.string.shake_active, s.shakeAppliedApps.size, formatTime(s.shakeLastApply, "MM-dd HH:mm"))
            else -> stringResource(R.string.shake_inactive)
        }
        Text(status, style = MaterialTheme.typography.bodySmall, color = if (s.shakeLastError != null || !authorized) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        FilledTonalButton(enabled = authorized, onClick = {
            ShakeBlocker.applyAsync(context)
            Toast.makeText(context, R.string.shake_applying, Toast.LENGTH_SHORT).show()
        }) { Text(stringResource(R.string.shake_apply)) }
    }
}
