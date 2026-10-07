package io.github.kunkai2002.splashclean.ui

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.kunkai2002.splashclean.R
import io.github.kunkai2002.splashclean.data.ActionEntry
import io.github.kunkai2002.splashclean.data.ActionLog
import io.github.kunkai2002.splashclean.data.InstalledApps
import io.github.kunkai2002.splashclean.data.Prefs
import io.github.kunkai2002.splashclean.rule.RuleRepository

@Composable
private fun subsLabel(subsId: Long, name: String): String = when (subsId) {
    RuleRepository.BUILTIN_ID -> stringResource(R.string.subs_builtin)
    RuleRepository.USER_ID -> stringResource(R.string.rules_mine_title)
    else -> name
}

/** All launchable apps: master switch per app, tap for that app's rules. */
@Composable
fun AppListScreen(modifier: Modifier, openApp: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val s by Prefs.flow.collectAsState()
    val summary by RuleRepository.summary.collectAsState()
    val counters by ActionLog.counters.collectAsState()
    var query by remember { mutableStateOf("") }
    val apps = remember {
        val pm = context.packageManager
        InstalledApps.apps.values
            .filter { pm.getLaunchIntentForPackage(it.id) != null && it.id != context.packageName }
            .map { it.id to InstalledApps.label(context, it.id) }
    }
    val sorted = remember(apps, counters) {
        apps.sortedWith(compareByDescending<Pair<String, String>> { counters.perApp[it.first] ?: 0 }.thenBy { it.second })
    }
    Column(modifier.fillMaxSize()) {
        SubPageBar(stringResource(R.string.apps_title), onBack)
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text(stringResource(R.string.search)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Text(stringResource(R.string.apps_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
        LazyColumn {
            items(
                sorted.filter { query.isBlank() || it.second.contains(query, true) || it.first.contains(query, true) },
                key = { it.first },
            ) { (id, label) ->
                val builtin = id in InstalledApps.builtinBlocked
                val ruleCount = summary.appIdToRules[id]?.map { it.groupKey to it.subsId }?.distinct()?.size ?: 0
                val skips = counters.perApp[id] ?: 0
                Row(
                    Modifier.fillMaxWidth().clickable { openApp(id) }.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (builtin) stringResource(R.string.exclude_builtin)
                            else stringResource(R.string.apps_row_meta, ruleCount, skips),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = !builtin && id !in s.disabledApps,
                        enabled = !builtin,
                        onCheckedChange = { on ->
                            Prefs.update { st -> st.copy(disabledApps = if (on) st.disabledApps - id else st.disabledApps + id) }
                        },
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

/** One app: master switch, its own rule groups, the global groups, OCR, and what happened there recently. */
@Composable
fun AppRulesScreen(modifier: Modifier, appId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val s by Prefs.flow.collectAsState()
    val loaded by RuleRepository.loaded.collectAsState()
    val entries by ActionLog.entries.collectAsState()
    val label = remember(appId) { InstalledApps.label(context, appId) }
    val groups = remember(s, loaded, appId) { RuleRepository.groupsForApp(appId) }
    val builtinBlocked = appId in InstalledApps.builtinBlocked
    val appOn = !builtinBlocked && appId !in s.disabledApps
    var mistake by remember { mutableStateOf<ActionEntry?>(null) }

    Column(modifier.fillMaxSize()) {
        SubPageBar(label, onBack)
        LazyColumn {
            item {
                SectionCard {
                    SwitchRow(
                        stringResource(R.string.app_master),
                        if (builtinBlocked) stringResource(R.string.exclude_builtin) else appId,
                        checked = appOn, enabled = !builtinBlocked,
                    ) { on -> Prefs.update { st -> st.copy(disabledApps = if (on) st.disabledApps - appId else st.disabledApps + appId) } }
                }
            }
            val own = groups.filter { !it.global }
            item {
                SectionCard(stringResource(R.string.app_own_rules)) {
                    if (own.isEmpty()) Text(stringResource(R.string.app_own_rules_empty), style = MaterialTheme.typography.bodySmall)
                    own.forEach { g ->
                        SwitchRow(
                            g.name,
                            listOfNotNull(subsLabel(g.subsId, g.subsName), g.desc).joinToString(" · "),
                            checked = g.enabled, enabled = appOn,
                        ) { on -> RuleRepository.setGroupEnabledForApp(g.subsId, g.groupKey, false, appId, on) }
                    }
                }
            }
            val global = groups.filter { it.global }
            item {
                SectionCard(stringResource(R.string.app_global_rules)) {
                    Text(stringResource(R.string.app_global_rules_desc), style = MaterialTheme.typography.bodySmall)
                    global.forEach { g ->
                        val note = when (g.lock) {
                            RuleRepository.Lock.AUTHOR_EXCLUDED -> stringResource(R.string.app_lock_author)
                            RuleRepository.Lock.REPLACED_BY_APP_RULE -> stringResource(R.string.app_lock_replaced)
                            null -> null
                        }
                        SwitchRow(
                            g.name,
                            listOfNotNull(subsLabel(g.subsId, g.subsName), note ?: g.desc).joinToString(" · "),
                            checked = g.enabled, enabled = appOn && g.lock == null,
                        ) { on -> RuleRepository.setGroupEnabledForApp(g.subsId, g.groupKey, true, appId, on) }
                    }
                    val ocrBlocked = appId in RuleRepository.RISKY_APPS
                    SwitchRow(
                        stringResource(R.string.set_ocr),
                        when {
                            Build.VERSION.SDK_INT < 30 -> stringResource(R.string.set_ocr_unsupported)
                            ocrBlocked -> stringResource(R.string.app_ocr_risky)
                            else -> stringResource(R.string.app_ocr_desc)
                        },
                        checked = appOn && s.ocrEnabled && !ocrBlocked && appId !in s.ocrDisabledApps && Build.VERSION.SDK_INT >= 30,
                        enabled = appOn && s.ocrEnabled && !ocrBlocked && Build.VERSION.SDK_INT >= 30,
                    ) { on ->
                        Prefs.update { st -> st.copy(ocrDisabledApps = if (on) st.ocrDisabledApps - appId else st.ocrDisabledApps + appId) }
                    }
                }
            }
            val recent = entries.filter { it.appId == appId }.take(20)
            item {
                SectionCard(stringResource(R.string.app_recent)) {
                    if (recent.isEmpty()) Text(stringResource(R.string.log_empty_app), style = MaterialTheme.typography.bodySmall)
                    recent.forEachIndexed { i, e ->
                        if (i > 0) HorizontalDivider()
                        LogRow(e) { mistake = e }
                    }
                }
            }
        }
    }
    mistake?.let { e -> MistakeDialog(e, onDismiss = { mistake = null }, openApp = null) }
}

/** "This one was a mistake": switch off the rule (or OCR) that acted, for that app only. */
@Composable
fun MistakeDialog(e: ActionEntry, onDismiss: () -> Unit, openApp: ((String) -> Unit)?) {
    val context = LocalContext.current
    val appLabel = remember(e.appId) { InstalledApps.label(context, e.appId) }
    val canDisable = e.source == "ocr" || e.subsId != null
    val what = if (e.source == "ocr") stringResource(R.string.set_ocr) else e.groupName
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mistake_dialog_title)) },
        text = {
            Text(
                if (canDisable) stringResource(R.string.mistake_dialog_body, what, appLabel)
                else stringResource(R.string.mistake_dialog_old, appLabel)
            )
        },
        confirmButton = {
            if (canDisable) {
                TextButton(onClick = {
                    if (e.source == "ocr") {
                        Prefs.update { st -> st.copy(ocrDisabledApps = st.ocrDisabledApps + e.appId) }
                    } else {
                        RuleRepository.setGroupEnabledForApp(e.subsId!!, e.groupKey ?: 0, e.global, e.appId, false)
                    }
                    Toast.makeText(context, context.getString(R.string.mistake_disabled, what, appLabel), Toast.LENGTH_LONG).show()
                    onDismiss()
                }) { Text(stringResource(R.string.mistake_disable)) }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (openApp != null) {
                    TextButton(onClick = {
                        onDismiss()
                        openApp(e.appId)
                    }) { Text(stringResource(R.string.mistake_open_app)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}
