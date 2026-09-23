package io.github.kunkai2002.splashclean.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.kunkai2002.splashclean.BuildConfig
import io.github.kunkai2002.splashclean.Pages
import io.github.kunkai2002.splashclean.R
import io.github.kunkai2002.splashclean.TeachActivity
import io.github.kunkai2002.splashclean.data.InstalledApps
import io.github.kunkai2002.splashclean.data.Prefs
import io.github.kunkai2002.splashclean.service.A11yGuard
import io.github.kunkai2002.splashclean.service.KeepAliveService

@Composable
fun SettingsScreen(modifier: Modifier, open: (String) -> Unit) {
    val context = LocalContext.current
    val s by Prefs.flow.collectAsState()
    LazyColumn(modifier.fillMaxSize()) {
        item {
            Text(stringResource(R.string.tab_settings), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
        }
        item {
            SectionCard(stringResource(R.string.set_skip_title)) {
                SwitchRow(
                    stringResource(R.string.set_ocr), stringResource(
                        if (Build.VERSION.SDK_INT >= 30) R.string.set_ocr_desc else R.string.set_ocr_unsupported
                    ),
                    checked = s.ocrEnabled && Build.VERSION.SDK_INT >= 30, enabled = Build.VERSION.SDK_INT >= 30,
                ) { v -> Prefs.update { it.copy(ocrEnabled = v) } }
                SwitchRow(stringResource(R.string.set_toast), stringResource(R.string.set_toast_desc), s.showToast) { v ->
                    Prefs.update { it.copy(showToast = v) }
                }
                SwitchRow(stringResource(R.string.set_autoupdate), stringResource(R.string.set_autoupdate_desc), s.autoUpdateSubscriptions) { v ->
                    Prefs.update { it.copy(autoUpdateSubscriptions = v) }
                }
                SettingLink(stringResource(R.string.set_exclude), stringResource(R.string.set_exclude_desc, s.disabledApps.size)) {
                    open(Pages.EXCLUDE)
                }
            }
        }
        item {
            SectionCard(stringResource(R.string.set_teach_title)) {
                Text(stringResource(R.string.set_teach_desc), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = {
                    context.startActivity(Intent(context, TeachActivity::class.java))
                }) { Text(stringResource(R.string.set_teach_open)) }
            }
        }
        item {
            SectionCard(stringResource(R.string.set_keep_title)) {
                SwitchRow(stringResource(R.string.set_notification), stringResource(R.string.set_notification_desc), s.keepAliveNotification) { v ->
                    Prefs.update { it.copy(keepAliveNotification = v) }
                    KeepAliveService.sync(context)
                }
                SwitchRow(
                    stringResource(R.string.set_guard),
                    stringResource(if (A11yGuard.hasWriteSecureSettings(context)) R.string.set_guard_desc_ok else R.string.set_guard_desc_no),
                    s.guardAccessibility,
                ) { v -> Prefs.update { it.copy(guardAccessibility = v) } }
                SettingLink(stringResource(R.string.check_guard), stringResource(R.string.set_authorize_desc)) { open(Pages.AUTHORIZE) }
                SettingLink(stringResource(R.string.check_brand), stringResource(R.string.check_brand_desc)) { open(Pages.GUIDE) }
            }
        }
        item {
            SectionCard(stringResource(R.string.set_about_title)) {
                Text(stringResource(R.string.about_text, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { SystemIntents.openUrl(context, "https://github.com/kunkai2002/splash-clean") }) {
                    Text(stringResource(R.string.about_source))
                }
                Text(stringResource(R.string.about_licenses), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SettingLink(title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java)
    cm.setPrimaryClip(ClipData.newPlainText("adb", text))
    Toast.makeText(context, R.string.toast_copied, Toast.LENGTH_SHORT).show()
}

@Composable
fun AuthorizeScreen(modifier: Modifier, onBack: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }
    val granted = remember(tick) { A11yGuard.hasWriteSecureSettings(context) }
    val pkg = context.packageName
    val commands = buildString {
        append("adb shell pm grant $pkg android.permission.WRITE_SECURE_SETTINGS")
        if (Build.VERSION.SDK_INT >= 33) append("\nadb shell appops set $pkg ACCESS_RESTRICTED_SETTINGS allow")
    }
    Column(modifier.fillMaxSize()) {
        SubPageBar(stringResource(R.string.check_guard), onBack)
        LazyColumn {
            item {
                SectionCard {
                    Text(
                        stringResource(if (granted) R.string.auth_status_ok else R.string.auth_status_no),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                    Text(stringResource(R.string.auth_what), style = MaterialTheme.typography.bodySmall)
                }
            }
            item { PhonePairingSection() }
            item {
                SectionCard(stringResource(R.string.auth_pc_title)) {
                    Text(stringResource(R.string.auth_pc_steps), style = MaterialTheme.typography.bodySmall)
                    SelectionContainer {
                        Text(commands, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                    FilledTonalButton(onClick = { copy(context, commands) }) { Text(stringResource(R.string.action_copy)) }
                }
            }
            item {
                SectionCard(stringResource(R.string.auth_brand_title)) {
                    Text(stringResource(R.string.auth_brand_notes), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { SystemIntents.openDeveloperOptions(context) }) {
                        Text(stringResource(R.string.auth_open_dev))
                    }
                }
            }
        }
    }
}

@Composable
fun GuideScreen(modifier: Modifier, onBack: () -> Unit) {
    val context = LocalContext.current
    val brand = SystemIntents.brand()
    val brandText = when (brand) {
        "xiaomi" -> R.string.guide_xiaomi
        "huawei", "honor" -> R.string.guide_huawei
        "oppo" -> R.string.guide_oppo
        "vivo" -> R.string.guide_vivo
        "samsung" -> R.string.guide_samsung
        else -> R.string.guide_other
    }
    Column(modifier.fillMaxSize()) {
        SubPageBar(stringResource(R.string.check_brand), onBack)
        LazyColumn {
            item {
                SectionCard(stringResource(R.string.guide_this_phone, Build.MANUFACTURER, Build.VERSION.RELEASE)) {
                    Text(stringResource(brandText), style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { SystemIntents.openAutoStart(context) }) { Text(stringResource(R.string.guide_open_autostart)) }
                        OutlinedButton(onClick = { SystemIntents.requestIgnoreBatteryOptimizations(context) }) { Text(stringResource(R.string.check_battery)) }
                    }
                }
            }
            item {
                SectionCard(stringResource(R.string.guide_general_title)) {
                    Text(stringResource(R.string.guide_general), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (Build.VERSION.SDK_INT >= 33) {
                item {
                    SectionCard(stringResource(R.string.guide_restricted_title)) {
                        Text(stringResource(R.string.guide_restricted), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { SystemIntents.openAppDetails(context) }) { Text(stringResource(R.string.guide_open_app_info)) }
                    }
                }
            }
        }
    }
}

@Composable
fun AppPickerScreen(modifier: Modifier, onBack: () -> Unit) {
    val context = LocalContext.current
    val s by Prefs.flow.collectAsState()
    var query by remember { mutableStateOf("") }
    val apps = remember {
        val pm = context.packageManager
        InstalledApps.apps.values
            .filter { pm.getLaunchIntentForPackage(it.id) != null && it.id != context.packageName }
            .map { it.id to InstalledApps.label(context, it.id) }
            .sortedBy { it.second }
    }
    Column(modifier.fillMaxSize()) {
        SubPageBar(stringResource(R.string.set_exclude), onBack)
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text(stringResource(R.string.search)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Text(stringResource(R.string.exclude_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
        LazyColumn {
            items(apps.filter { query.isBlank() || it.second.contains(query, true) || it.first.contains(query, true) }, key = { it.first }) { (id, label) ->
                val builtin = id in InstalledApps.builtinBlocked
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !builtin) {
                        Prefs.update { st -> st.copy(disabledApps = if (id in st.disabledApps) st.disabledApps - id else st.disabledApps + id) }
                    }.padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(label)
                        Text(
                            if (builtin) stringResource(R.string.exclude_builtin) else id,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Checkbox(checked = builtin || id in s.disabledApps, onCheckedChange = null, enabled = !builtin)
                }
                HorizontalDivider()
            }
        }
    }
}
