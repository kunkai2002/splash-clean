package io.github.kunkai2002.splashclean.adb

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import io.github.kunkai2002.splashclean.R
import io.github.kunkai2002.splashclean.data.Prefs
import io.github.kunkai2002.splashclean.service.A11yGuard
import io.github.kunkai2002.splashclean.service.Notifications
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/** ADB client identity of this app (key + self-signed certificate), kept in private storage. */
class SelfAdbManager private constructor(context: Context) : AbsAdbConnectionManager() {
    private val key: PrivateKey
    private val cert: Certificate

    init {
        api = Build.VERSION.SDK_INT
        val dir = File(context.filesDir, "adb").apply { mkdirs() }
        val keyFile = File(dir, "key.pk8")
        val certFile = File(dir, "cert.der")
        if (keyFile.exists() && certFile.exists()) {
            key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            cert = CertificateFactory.getInstance("X.509").generateCertificate(certFile.inputStream())
        } else {
            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val name = X500Name("CN=SplashClean")
            val now = System.currentTimeMillis()
            val holder = JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(now), Date(now - 86_400_000L), Date(now + 20L * 365 * 86_400_000L), name, kp.public,
            ).build(JcaContentSignerBuilder("SHA256withRSA").build(kp.private))
            key = kp.private
            cert = JcaX509CertificateConverter().getCertificate(holder)
            keyFile.writeBytes(key.encoded)
            certFile.writeBytes(cert.encoded)
        }
    }

    override fun getPrivateKey(): PrivateKey = key
    override fun getCertificate(): Certificate = cert
    override fun getDeviceName(): String = "SplashClean"

    companion object {
        @Volatile
        private var instance: SelfAdbManager? = null
        fun get(context: Context): SelfAdbManager = instance ?: synchronized(this) {
            instance ?: SelfAdbManager(context.applicationContext).also { instance = it }
        }
    }
}

sealed class AuthState {
    data object Idle : AuthState()
    data object WaitingForPairingDialog : AuthState()
    data class WaitingForCode(val host: String, val port: Int) : AuthState()
    data object Working : AuthState()
    data class Done(val log: String) : AuthState()
    data class Failed(val message: String) : AuthState()
}

/**
 * One-time self-authorization through Wireless debugging (Android 11+):
 * pair once with the 6-digit code, then run a few shell commands as the ADB shell user.
 * After that the app holds WRITE_SECURE_SETTINGS permanently and needs no ADB anymore.
 */
object AdbAuthorizer {
    private const val TAG = "AdbAuthorizer"
    const val KEY_CODE = "pairing_code"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<AuthState>(AuthState.Idle)
    val state: StateFlow<AuthState> get() = _state
    private var mdns: AdbMdns? = null

    @RequiresApi(Build.VERSION_CODES.R)
    fun startPairing(context: Context) {
        val app = context.applicationContext
        stopDiscovery()
        _state.value = AuthState.WaitingForPairingDialog
        showPairingNotification(app, null)
        mdns = AdbMdns(app, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { host, port ->
            if (port > 0 && host != null) {
                _state.value = AuthState.WaitingForCode(host.hostAddress ?: "127.0.0.1", port)
                showPairingNotification(app, port)
            }
        }.also { it.start() }
        scope.launch {
            delay(5 * 60_000L)
            if (_state.value is AuthState.WaitingForPairingDialog || _state.value is AuthState.WaitingForCode) {
                stopDiscovery()
                _state.value = AuthState.Failed(app.getString(R.string.pair_timeout))
            }
        }
    }

    fun stopDiscovery() {
        runCatching { mdns?.stop() }
        mdns = null
    }

    fun cancel(context: Context) {
        stopDiscovery()
        _state.value = AuthState.Idle
        context.getSystemService(NotificationManager::class.java).cancel(Notifications.ID_PAIRING)
    }

    /** Called with the code typed in the notification or in the app. */
    fun submitCode(context: Context, code: String, manualPort: Int? = null) {
        val app = context.applicationContext
        val s = _state.value
        val (host, port) = when {
            manualPort != null -> "127.0.0.1" to manualPort
            s is AuthState.WaitingForCode -> s.host to s.port
            else -> {
                _state.value = AuthState.Failed(app.getString(R.string.pair_no_port))
                return
            }
        }
        stopDiscovery()
        _state.value = AuthState.Working
        scope.launch {
            val result = runCatching {
                val m = SelfAdbManager.get(app)
                check(m.pair(host, port, code.trim())) { app.getString(R.string.pair_failed_code) }
                grant(app, m)
            }
            _state.value = result.fold({ AuthState.Done(it) }, { AuthState.Failed(it.message ?: it.javaClass.simpleName) })
            showResultNotification(app, result.isSuccess)
        }
    }

    /** Re-connect with the stored pairing (wireless debugging must be on) and grant again. */
    fun reconnectAndGrant(context: Context) {
        val app = context.applicationContext
        _state.value = AuthState.Working
        scope.launch {
            val result = runCatching {
                val m = SelfAdbManager.get(app)
                grant(app, m)
            }
            _state.value = result.fold({ AuthState.Done(it) }, { AuthState.Failed(it.message ?: it.javaClass.simpleName) })
        }
    }

    private suspend fun grant(context: Context, m: SelfAdbManager): String = withContext(Dispatchers.IO) {
        if (!m.isConnected) {
            check(m.autoConnect(context, 15_000)) { context.getString(R.string.pair_failed_connect) }
        }
        val pkg = context.packageName
        val commands = buildList {
            add("pm grant $pkg android.permission.WRITE_SECURE_SETTINGS")
            if (Build.VERSION.SDK_INT >= 33) add("appops set $pkg ACCESS_RESTRICTED_SETTINGS allow")
            add("dumpsys deviceidle whitelist +$pkg")
            add("cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow")
            add("cmd appops set $pkg RUN_IN_BACKGROUND allow")
            if (Build.VERSION.SDK_INT >= 33) add("pm grant $pkg android.permission.POST_NOTIFICATIONS")
            Prefs.value.shakeBlockApps.forEach { add("cmd sensorservice set-uid-state $it idle") }
        }
        val log = StringBuilder()
        for (cmd in commands) {
            val out = runCatching { shell(m, cmd) }.getOrElse { "error: ${it.message}" }
            log.append("$ ").append(cmd).append('\n').append(out.trim()).append('\n')
        }
        runCatching { m.disconnect() }
        check(A11yGuard.hasWriteSecureSettings(context)) { log.toString() + "\n" + context.getString(R.string.pair_grant_failed) }
        A11yGuard.ensureEnabled(context, "adb grant")
        log.toString()
    }

    private fun shell(m: SelfAdbManager, cmd: String): String {
        m.openStream("shell:$cmd").use { stream ->
            return stream.openInputStream().bufferedReader().readText()
        }
    }

    // ---- notifications (so the user can stay on the system pairing dialog) ----------------------

    private fun showPairingNotification(context: Context, port: Int?) {
        Notifications.ensureChannels(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        val b = NotificationCompat.Builder(context, Notifications.CHANNEL_EVENTS)
            .setSmallIcon(io.github.kunkai2002.splashclean.R.drawable.ic_stat)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(Notifications.openApp(context))
        if (port == null) {
            b.setContentTitle(context.getString(R.string.pair_notify_searching))
                .setContentText(context.getString(R.string.pair_notify_searching_text))
        } else {
            val input = RemoteInput.Builder(KEY_CODE).setLabel(context.getString(R.string.pair_code_hint)).build()
            val pi = PendingIntent.getBroadcast(
                context, 7, Intent(context, PairingCodeReceiver::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val action = NotificationCompat.Action.Builder(0, context.getString(R.string.pair_enter_code), pi)
                .addRemoteInput(input).build()
            b.setContentTitle(context.getString(R.string.pair_notify_found))
                .setContentText(context.getString(R.string.pair_notify_found_text))
                .addAction(action)
        }
        runCatching { nm.notify(Notifications.ID_PAIRING, b.build()) }
    }

    private fun showResultNotification(context: Context, ok: Boolean) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val n = NotificationCompat.Builder(context, Notifications.CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(context.getString(if (ok) R.string.pair_done else R.string.pair_failed))
            .setContentText(context.getString(if (ok) R.string.pair_done_text else R.string.pair_failed_text))
            .setAutoCancel(true)
            .setContentIntent(Notifications.openApp(context))
            .build()
        runCatching { nm.notify(Notifications.ID_PAIRING, n) }
    }

    fun isWirelessDebuggingOn(context: Context): Boolean =
        Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1

    fun log(t: Throwable) = Log.w(TAG, t)
}

class PairingCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(AdbAuthorizer.KEY_CODE)?.toString()
        if (code.isNullOrBlank()) return
        AdbAuthorizer.submitCode(context, code)
    }
}
