package io.github.kunkai2002.splashclean.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.kunkai2002.splashclean.Pages
import io.github.kunkai2002.splashclean.R
import io.github.kunkai2002.splashclean.data.ActionEntry
import io.github.kunkai2002.splashclean.data.ActionLog
import io.github.kunkai2002.splashclean.data.InstalledApps
import io.github.kunkai2002.splashclean.data.Prefs
import io.github.kunkai2002.splashclean.rule.RuleRepository
import io.github.kunkai2002.splashclean.service.A11yGuard
import io.github.kunkai2002.splashclean.service.CleanAccessibilityService
import io.github.kunkai2002.splashclean.service.KeepAliveService
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(modifier: Modifier, open: (String) -> Unit, goRules: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }
    val running by CleanAccessibilityService.running.collectAsState()
    val settings by Prefs.flow.collectAsState()
    val counters by ActionLog.counters.collectAsState()
    val entries by ActionLog.entries.collectAsState()
    val summary by RuleRepository.summary.collectAsState()
    val wss = remember(tick) { A11yGuard.hasWriteSecureSettings(context) }
    val battery = remember(tick) { SystemIntents.isIgnoringBatteryOptimizations(context) }
    val notifyOk = remember(tick) {
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val notifyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        tick++
        KeepAliveService.sync(context)
    }
    val today = if (counters.day == formatTime(System.currentTimeMillis(), "yyyyMMdd")) counters.today else 0

    LazyColumn(modifier.fillMaxSize()) {
        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(
                                when {
                                    !settings.enabled -> R.string.home_paused
                                    running -> R.string.home_running
                                    else -> R.string.home_not_running
                                }
                            ),
                            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            stringResource(R.string.home_rules_summary, summary.appCount, summary.globalRules.size),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = settings.enabled, onCheckedChange = { v ->
                        Prefs.update { it.copy(enabled = v) }
                        KeepAliveService.sync(context)
                    })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Stat(stringResource(R.string.home_today), today.toString())
                    Stat(stringResource(R.string.home_total), counters.total.toString())
                }
            }
        }
        item {
            SectionCard(stringResource(R.string.home_checklist)) {
                CheckRow(
                    ok = running,
                    title = stringResource(R.string.check_a11y),
                    desc = stringResource(if (running) R.string.check_a11y_ok else R.string.check_a11y_desc),
                    action = if (running) null else stringResource(R.string.action_enable),
                ) {
                    if (wss && A11yGuard.ensureEnabled(context, "user")) {
                        Toast.makeText(context, R.string.toast_enabled_by_guard, Toast.LENGTH_SHORT).show()
                    } else {
                        SystemIntents.openAccessibilitySettings(context)
                    }
                }
                CheckRow(
                    ok = wss,
                    title = stringResource(R.string.check_guard),
                    desc = stringResource(if (wss) R.string.check_guard_ok else R.string.check_guard_desc),
                    action = if (wss) null else stringResource(R.string.action_authorize),
                ) { open(Pages.AUTHORIZE) }
                val hasSubs = settings.subscriptions.isNotEmpty()
                CheckRow(
                    ok = hasSubs,
                    title = stringResource(R.string.check_subs),
                    desc = stringResource(if (hasSubs) R.string.check_subs_ok else R.string.check_subs_desc),
                    action = if (hasSubs) null else stringResource(R.string.action_add),
                ) {
                    scope.launch {
                        val r = RuleRepository.addSubscription(RuleRepository.knownSubscriptions.first().urls)
                        Toast.makeText(
                            context,
                            if (r.isSuccess) context.getString(R.string.toast_subs_added, r.getOrNull()?.name ?: "")
                            else context.getString(R.string.toast_subs_failed, r.exceptionOrNull()?.message ?: ""),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
                CheckRow(
                    ok = battery,
                    title = stringResource(R.string.check_battery),
                    desc = stringResource(if (battery) R.string.check_battery_ok else R.string.check_battery_desc),
                    action = if (battery) null else stringResource(R.string.action_allow),
                ) { SystemIntents.requestIgnoreBatteryOptimizations(context) }
                if (!notifyOk) {
                    CheckRow(
                        ok = false,
                        title = stringResource(R.string.check_notify),
                        desc = stringResource(R.string.check_notify_desc),
                        action = stringResource(R.string.action_allow),
                    ) {
                        if (Build.VERSION.SDK_INT >= 33) notifyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                CheckRow(
                    ok = null,
                    title = stringResource(R.string.check_brand),
                    desc = stringResource(R.string.check_brand_desc),
                    action = stringResource(R.string.action_view),
                ) { open(Pages.GUIDE) }
            }
        }
        item {
            SectionCard(stringResource(R.string.home_recent)) {
                if (entries.isEmpty()) {
                    Text(stringResource(R.string.log_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    entries.take(5).forEachIndexed { i, e ->
                        if (i > 0) HorizontalDivider()
                        LogRow(e)
                    }
                    TextButton(onClick = goRules) { Text(stringResource(R.string.home_manage_rules)) }
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CheckRow(ok: Boolean?, title: String, desc: String, action: String?, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        val (icon, tint) = when (ok) {
            true -> Icons.Outlined.CheckCircle to MaterialTheme.colorScheme.primary
            false -> Icons.Outlined.ErrorOutline to MaterialTheme.colorScheme.error
            null -> Icons.Outlined.RadioButtonUnchecked to MaterialTheme.colorScheme.onSurfaceVariant
        }
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (action != null) {
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
fun LogRow(e: ActionEntry) {
    val context = LocalContext.current
    val label = remember(e.appId) { InstalledApps.label(context, e.appId) }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                (if (e.source == "ocr") stringResource(R.string.log_ocr_group, e.groupName) else e.groupName) +
                    if (e.subscription.isNotBlank()) " · ${e.subscription}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(formatTime(e.time), style = MaterialTheme.typography.bodySmall)
            if (e.sinceAppEnter in 0..60_000) {
                Text(
                    stringResource(R.string.log_since_enter, e.sinceAppEnter / 1000f),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (e.source == "ocr") Color(0xFF8A6D1D) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun LogScreen(modifier: Modifier) {
    val entries by ActionLog.entries.collectAsState()
    LazyColumn(modifier.fillMaxSize()) {
        item {
            Text(
                stringResource(R.string.log_title), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(16.dp),
            )
        }
        if (entries.isEmpty()) {
            item { Text(stringResource(R.string.log_empty), modifier = Modifier.padding(16.dp)) }
        }
        items(entries, key = { it.time.toString() + it.appId + it.groupName }) { e ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                LogRow(e)
                HorizontalDivider()
            }
        }
    }
}
