package io.github.kunkai2002.splashclean

import android.app.Application
import io.github.kunkai2002.splashclean.data.ActionLog
import io.github.kunkai2002.splashclean.data.Prefs
import io.github.kunkai2002.splashclean.rule.RuleRepository
import io.github.kunkai2002.splashclean.service.A11yGuard
import io.github.kunkai2002.splashclean.service.GuardJob
import io.github.kunkai2002.splashclean.service.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        ActionLog.init(this)
        Notifications.ensureChannels(this)
        io.github.kunkai2002.splashclean.capture.UserRules.regenerate(this)
        RuleRepository.init(this)
        A11yGuard.ensureEnabled(this, "app start")
        GuardJob.schedule(this)
        if (Prefs.value.dnsBlockEnabled && android.net.VpnService.prepare(this) == null) {
            io.github.kunkai2002.splashclean.vpn.DnsVpnService.start(this)
        }
        scope.launch {
            // Refresh third-party subscriptions at most once every 12 hours.
            val s = Prefs.value
            val stale = s.subscriptions.any { System.currentTimeMillis() - it.updatedAt > 12 * 3600_000L }
            if (s.autoUpdateSubscriptions && stale) runCatching { RuleRepository.updateAll() }
        }
    }
}
