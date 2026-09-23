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
import io.github.kunkai2002.splashclean.ui.AppPickerScreen
import io.github.kunkai2002.splashclean.ui.AuthorizeScreen
import io.github.kunkai2002.splashclean.ui.GuideScreen
import io.github.kunkai2002.splashclean.ui.HomeScreen
import io.github.kunkai2002.splashclean.ui.LogScreen
import io.github.kunkai2002.splashclean.ui.RulesScreen
import io.github.kunkai2002.splashclean.ui.SettingsScreen
import io.github.kunkai2002.splashclean.ui.SplashCleanTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SplashCleanTheme { MainScaffold() } }
    }
}

object Pages {
    const val AUTHORIZE = "authorize"
    const val EXCLUDE = "exclude"
    const val GUIDE = "guide"
}

@Composable
fun MainScaffold() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = page != null) { page = null }
    val open: (String) -> Unit = { page = it }
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
        when (page) {
            Pages.AUTHORIZE -> AuthorizeScreen(m) { page = null }
            Pages.EXCLUDE -> AppPickerScreen(m) { page = null }
            Pages.GUIDE -> GuideScreen(m) { page = null }
            else -> when (tab) {
                0 -> HomeScreen(m, open) { tab = 1 }
                1 -> RulesScreen(m)
                2 -> LogScreen(m)
                else -> SettingsScreen(m, open)
            }
        }
    }
}
