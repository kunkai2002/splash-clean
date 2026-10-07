package io.github.kunkai2002.splashclean

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Rule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.kunkai2002.splashclean.ui.AppListScreen
import io.github.kunkai2002.splashclean.ui.AppPickerScreen
import io.github.kunkai2002.splashclean.ui.AppRulesScreen
import io.github.kunkai2002.splashclean.ui.AuthorizeScreen
import io.github.kunkai2002.splashclean.ui.GuideScreen
import io.github.kunkai2002.splashclean.ui.HomeScreen
import io.github.kunkai2002.splashclean.ui.LogScreen
import io.github.kunkai2002.splashclean.ui.RulesScreen
import io.github.kunkai2002.splashclean.ui.SettingsScreen
import io.github.kunkai2002.splashclean.ui.SplashCleanTheme

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        // Android 13+ applies the per-app language itself; older versions use the in-app setting.
        super.attachBaseContext(
            if (android.os.Build.VERSION.SDK_INT < 33) io.github.kunkai2002.splashclean.data.AppLocale.wrap(newBase) else newBase
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SplashCleanTheme { MainScaffold() } }
    }
}

object Pages {
    const val AUTHORIZE = "authorize"
    const val APPS = "apps"
    const val GUIDE = "guide"
    const val SHAKE = "shake"
    private const val APP_PREFIX = "app:"
    fun app(appId: String) = APP_PREFIX + appId
    fun appIdOf(page: String?): String? = page?.takeIf { it.startsWith(APP_PREFIX) }?.removePrefix(APP_PREFIX)
}

@Composable
fun MainScaffold() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // Sub-page stack (app list → one app), so Back returns to where the user came from.
    var stack by rememberSaveable { mutableStateOf(listOf<String>()) }
    val page = stack.lastOrNull()
    val context = androidx.compose.ui.platform.LocalContext.current
    val back: () -> Unit = {
        if (page == Pages.SHAKE) io.github.kunkai2002.splashclean.adb.ShakeBlocker.applyAsync(context)
        stack = stack.dropLast(1)
    }
    BackHandler(enabled = page != null) { back() }
    val open: (String) -> Unit = { stack = stack + it }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val s = io.github.kunkai2002.splashclean.data.Prefs.value
        if (s.autoCheckUpdate && System.currentTimeMillis() - s.lastUpdateCheck > 20 * 3600_000L) {
            io.github.kunkai2002.splashclean.update.Updater.check(context, manual = false)
        }
    }
    Scaffold(
        bottomBar = {
            if (page == null) {
                NavigationBar {
                    val items = listOf(
                        Icons.Outlined.Home to R.string.tab_home,
                        Icons.Outlined.Rule to R.string.tab_rules,
                        Icons.Outlined.History to R.string.tab_log,
                        Icons.Outlined.Settings to R.string.tab_settings,
                    )
                    items.forEachIndexed { i, (icon, label) ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Icon(icon, contentDescription = null) },
                            label = { Text(stringResource(label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        val m = Modifier.padding(padding)
        val appPage = Pages.appIdOf(page)
        when {
            appPage != null -> AppRulesScreen(m, appPage, back)
            page == Pages.AUTHORIZE -> AuthorizeScreen(m, back)
            page == Pages.APPS -> AppListScreen(m, openApp = { open(Pages.app(it)) }, onBack = back)
            page == Pages.SHAKE -> AppPickerScreen(m, shake = true, onBack = back)
            page == Pages.GUIDE -> GuideScreen(m, back)
            else -> when (tab) {
                0 -> HomeScreen(m, open) { tab = 1 }
                1 -> RulesScreen(m, open)
                2 -> LogScreen(m) { open(Pages.app(it)) }
                else -> SettingsScreen(m, open)
            }
        }
    }
}
