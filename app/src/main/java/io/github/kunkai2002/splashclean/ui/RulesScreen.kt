package io.github.kunkai2002.splashclean.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.kunkai2002.splashclean.R
import io.github.kunkai2002.splashclean.capture.UserRules
import io.github.kunkai2002.splashclean.data.Prefs
import io.github.kunkai2002.splashclean.data.SubscriptionSource
import io.github.kunkai2002.splashclean.rule.RawSubscription
import io.github.kunkai2002.splashclean.rule.RuleRepository
import kotlinx.coroutines.launch

@Composable
fun RulesScreen(modifier: Modifier, open: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by Prefs.flow.collectAsState()
    val loaded by RuleRepository.loaded.collectAsState()
    val summary by RuleRepository.summary.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var userRules by remember { mutableStateOf(UserRules.load(context)) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    fun add(urls: List<String>) {
        busy = true
        scope.launch {
            val r = RuleRepository.addSubscription(urls)
            busy = false
            toast(
                if (r.isSuccess) context.getString(R.string.toast_subs_added, r.getOrNull()?.name ?: "")
                else context.getString(R.string.toast_subs_failed, r.exceptionOrNull()?.message ?: "")
            )
        }
    }

    LazyColumn(modifier.fillMaxSize()) {
        item {
            Text(
                stringResource(R.string.rules_title), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding16(),
            )
            Text(
                stringResource(R.string.rules_active, summary.appCount, summary.globalRules.size, summary.groupCount),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding16(bottom = 8),
            )
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        item {
            SectionCard(stringResource(R.string.apps_title)) {
                Text(stringResource(R.string.apps_entry_desc), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { open(io.github.kunkai2002.splashclean.Pages.APPS) }) {
                    Text(stringResource(R.string.apps_open))
                }
            }
        }
        items(settings.subscriptions, key = { it.id }) { src ->
            SubscriptionCard(src, loaded[src.id], settings.categoryOverrides, busy,
                onUpdate = {
                    busy = true
                    scope.launch {
                        val n = RuleRepository.updateAll(force = true)
                        busy = false
                        toast(context.getString(R.string.toast_updated, n))
                    }
                },
                onRemove = { RuleRepository.removeSubscription(src.id) },
            )
        }
        val notAdded = RuleRepository.knownSubscriptions.filter { k ->
            settings.subscriptions.none { s -> k.urls.contains(s.url) || s.name.isNotBlank() && k.name.contains(s.name) }
        }
        item {
            SectionCard(stringResource(R.string.rules_add_title)) {
                Text(stringResource(R.string.rules_add_desc), style = MaterialTheme.typography.bodySmall)
                notAdded.forEach { k ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(k.name, style = MaterialTheme.typography.bodyLarge)
                            Text(k.homepage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(onClick = { add(k.urls) }, enabled = !busy) { Text(stringResource(R.string.action_add)) }
                    }
                }
                OutlinedButton(onClick = { showUrlDialog = true }, enabled = !busy) {
                    Text(stringResource(R.string.rules_add_url))
                }
            }
        }
        item {
            SectionCard(stringResource(R.string.rules_builtin_title)) {
                Text(stringResource(R.string.rules_builtin_desc), style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            SectionCard(stringResource(R.string.rules_mine_title)) {
                if (userRules.isEmpty()) {
                    Text(stringResource(R.string.rules_mine_empty), style = MaterialTheme.typography.bodySmall)
                }
                userRules.forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.appName + " · " + stringResource(if (r.kind == "splash") R.string.kind_splash else R.string.kind_popup))
                            Text(
                                r.selector ?: stringResource(R.string.rule_position, (r.relX ?: 0f) * 100, (r.relY ?: 0f) * 100),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = {
                            UserRules.remove(context, r.id)
                            userRules = UserRules.load(context)
                        }) { Text(stringResource(R.string.action_delete)) }
                    }
                }
            }
        }
    }

    if (showUrlDialog) {
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showUrlDialog = false },
            title = { Text(stringResource(R.string.rules_add_url)) },
            text = {
                OutlinedTextField(value = url, onValueChange = { url = it.trim() }, singleLine = true,
                    placeholder = { Text("https://…/gkd.json5") })
            },
            confirmButton = {
                TextButton(enabled = url.startsWith("http"), onClick = {
                    showUrlDialog = false
                    add(listOf(url))
                }) { Text(stringResource(R.string.action_add)) }
            },
            dismissButton = { TextButton(onClick = { showUrlDialog = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun SubscriptionCard(
    src: SubscriptionSource,
    subs: RawSubscription?,
    overrides: Map<String, Boolean>,
    busy: Boolean,
    onUpdate: () -> Unit,
    onRemove: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(src.name.ifBlank { src.url }, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.subs_meta, src.version, src.appCount, src.groupCount, formatTime(src.updatedAt, "MM-dd HH:mm")),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                src.lastError?.let {
                    Text(stringResource(R.string.subs_error, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            Switch(checked = src.enabled, onCheckedChange = { RuleRepository.setSubscriptionEnabled(src.id, it) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onUpdate, enabled = !busy) { Text(stringResource(R.string.action_update)) }
            OutlinedButton(onClick = { expanded = !expanded }) { Text(stringResource(R.string.subs_categories)) }
            TextButton(onClick = onRemove) { Text(stringResource(R.string.action_delete)) }
        }
        if (expanded && subs != null) {
            HorizontalDivider()
            subs.categories.forEach { c ->
                val key = Prefs.categoryKey(subs.id, c.name)
                val effective = overrides[key] ?: if (c.name in RuleRepository.DEFAULT_ON_CATEGORIES) true else (c.enable ?: true)
                SwitchRow(
                    title = c.name,
                    subtitle = subs.getCategoryCompatDesc(c.key),
                    checked = effective,
                ) { v -> Prefs.update { s -> s.copy(categoryOverrides = s.categoryOverrides + (key to v)) } }
            }
            subs.globalGroups.forEach { g ->
                val key = Prefs.groupKey(subs.id, null, g.key)
                val prefix = g.name.substringBefore('-')
                val effective = Prefs.value.groupOverrides[key]
                    ?: if (prefix in RuleRepository.DEFAULT_ON_CATEGORIES) true else (g.enable ?: true)
                SwitchRow(title = g.name, subtitle = g.desc, checked = effective) { v ->
                    Prefs.update { s -> s.copy(groupOverrides = s.groupOverrides + (key to v)) }
                }
            }
        }
    }
}

private fun Modifier.padding16(bottom: Int = 0) =
    this.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottom.dp)
