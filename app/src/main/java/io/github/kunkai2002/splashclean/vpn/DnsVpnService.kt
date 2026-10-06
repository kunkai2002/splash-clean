package io.github.kunkai2002.splashclean.vpn

import io.github.kunkai2002.splashclean.data.l10n
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.kunkai2002.splashclean.R
import io.github.kunkai2002.splashclean.data.Prefs
import io.github.kunkai2002.splashclean.service.Notifications
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

data class DnsStats(
    val running: Boolean = false,
    val blocked: Int = 0,
    val forwarded: Int = 0,
    val recentBlocked: List<String> = emptyList(),
    val listSize: Int = 0,
    val upstream: String = "",
)

/**
 * DNS-only VPN: routes just a fake DNS server address into the tunnel, answers ad domains
 * with 0.0.0.0 / :: immediately (so apps do not wait for a timeout) and forwards the rest
 * to the upstream resolver through a protected socket. Other traffic never enters the tunnel.
 */
class DnsVpnService : VpnService() {
    companion object {
        private const val TAG = "DnsVpn"
        const val ACTION_STOP = "stop"
        private const val DNS4 = "10.111.222.1"
        private const val ADDR4 = "10.111.222.2"
        private const val DNS6 = "fd00:6a:6f:6a::1"
        private const val ADDR6 = "fd00:6a:6f:6a::2"
        private val FALLBACK = listOf("223.5.5.5", "119.29.29.29")

        private val _stats = MutableStateFlow(DnsStats())
        val stats: StateFlow<DnsStats> get() = _stats

        fun start(context: Context) {
            val i = Intent(context, DnsVpnService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
            }
        }

        fun stop(context: Context) {
            runCatching { context.startService(Intent(context, DnsVpnService::class.java).setAction(ACTION_STOP)) }
        }

        /** Private DNS set to a specific server bypasses the VPN's resolver. */
        fun privateDnsBypasses(context: Context): Boolean =
            Settings.Global.getString(context.contentResolver, "private_dns_mode") == "hostname"
    }

    @Volatile
    private var tun: ParcelFileDescriptor? = null

    @Volatile
    private var worker: Thread? = null
    private val pool = Executors.newFixedThreadPool(8)
    private val blocked = AtomicInteger()
    private val forwarded = AtomicInteger()
    private val recent = ArrayDeque<String>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        Notifications.ensureChannels(this)
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        runCatching { ServiceCompat.startForeground(this, Notifications.ID_WARN + 10, notification(), type) }
        if (tun == null) establish()
        return START_STICKY
    }

    private fun notification(): Notification {
        val stop = PendingIntent.getService(
            this, 3, Intent(this, DnsVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, Notifications.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(l10n().getString(R.string.dns_notification))
            .setOngoing(true)
            .setGroup("dns")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(Notifications.openApp(this))
            .addAction(0, l10n().getString(R.string.dns_stop), stop)
            .build()
    }

    /** Current resolvers of the real (non-VPN) network, then public fallbacks. */
    @Volatile
    private var upstream: List<InetAddress> = emptyList()

    @Volatile
    private var preferred: InetAddress? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private fun computeUpstream(networkDns: List<InetAddress>): List<InetAddress> {
        val custom = Prefs.value.dnsUpstream.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }
            .mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
        if (custom.isNotEmpty()) return custom
        val fallback = FALLBACK.map { InetAddress.getByName(it) } // IP literals: no lookup
        return (networkDns.filter { it.hostAddress != DNS4 && it.hostAddress != DNS6 } + fallback).distinct()
    }

    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        upstream = computeUpstream(runCatching { cm.activeNetwork?.let { cm.getLinkProperties(it)?.dnsServers } }.getOrNull().orEmpty())
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: android.net.Network, lp: android.net.LinkProperties) {
                // Ignore our own tunnel (it can be reported here right after it is established).
                val isVpn = cm.getNetworkCapabilities(network)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
                if (isVpn || lp.dnsServers.any { it.hostAddress == DNS4 || it.hostAddress == DNS6 }) return
                upstream = computeUpstream(lp.dnsServers)
                preferred = null
                _stats.value = _stats.value.copy(upstream = upstream.joinToString { it.hostAddress ?: "" })
            }
        }
        // This app is excluded from its own VPN, so its default network is the real one.
        runCatching { cm.registerDefaultNetworkCallback(cb) }.onSuccess { networkCallback = cb }
    }

    private fun establish() {
        watchNetwork()
        val list = runCatching { Blocklist.load(this) }.getOrElse { Blocklist(emptySet(), emptyList()) }
        val allow = Prefs.value.dnsAllowlist
        val builder = Builder()
            .setSession(l10n().getString(R.string.app_name))
            .setMtu(1500)
            .addAddress(ADDR4, 32)
            .addRoute(DNS4, 32)
            .addDnsServer(DNS4)
            .setBlocking(true)
        runCatching { builder.addAddress(ADDR6, 128).addRoute(DNS6, 128).addDnsServer(DNS6) }
        runCatching { builder.addDisallowedApplication(packageName) }
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)
        val pfd = runCatching { builder.establish() }.getOrNull()
        if (pfd == null) {
            Log.w(TAG, "establish failed (no VPN consent?)")
            stopSelf()
            return
        }
        tun = pfd
        _stats.value = DnsStats(running = true, listSize = list.size, upstream = upstream.joinToString { it.hostAddress ?: "" })
        worker = Thread({ loop(pfd, list, allow) }, "sc-dns").also { it.start() }
    }

    private fun loop(pfd: ParcelFileDescriptor, list: Blocklist, allow: Set<String>) {
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        val buf = ByteArray(32767)
        try {
            while (!Thread.currentThread().isInterrupted) {
                val n = input.read(buf)
                if (n <= 0) continue
                val pkt = UdpPacket.parse(buf, n) ?: continue
                if (pkt.dstPort != 53) continue
                val q = Dns.question(pkt.payload)
                if (q != null && list.isBlocked(q.name, allow)) {
                    val reply = pkt.reply(Dns.blockedResponse(pkt.payload, q))
                    synchronized(output) { output.write(reply) }
                    onBlocked(q.name)
                    continue
                }
                pool.execute { forward(pkt, output) }
            }
        } catch (t: Throwable) {
            if (tun != null) Log.w(TAG, "dns loop stopped", t)
        }
    }

    private fun forward(pkt: UdpPacket, output: FileOutputStream) {
        val servers = preferred?.let { p -> listOf(p) + upstream.filter { it != p } } ?: upstream
        for (server in servers.take(3)) {
            try {
                DatagramSocket().use { s ->
                    protect(s)
                    s.soTimeout = 2500
                    s.send(DatagramPacket(pkt.payload, pkt.payload.size, server, 53))
                    val resp = ByteArray(4096)
                    val dp = DatagramPacket(resp, resp.size)
                    s.receive(dp)
                    val reply = pkt.reply(resp.copyOf(dp.length))
                    synchronized(output) { output.write(reply) }
                    preferred = server
                    forwarded.incrementAndGet()
                    publish()
                    return
                }
            } catch (_: Throwable) {
                // try the next upstream server
            }
        }
    }

    private fun onBlocked(name: String) {
        blocked.incrementAndGet()
        synchronized(recent) {
            recent.remove(name)
            recent.addFirst(name)
            while (recent.size > 30) recent.removeLast()
        }
        publish()
    }

    private var lastPublish = 0L

    @Volatile
    private var publishPending = false
    private val publishExecutor = Executors.newSingleThreadScheduledExecutor()

    /** At most two updates per second; a trailing update makes sure the last count is shown. */
    private fun publish() {
        val t = System.currentTimeMillis()
        if (t - lastPublish < 500) {
            if (!publishPending) {
                publishPending = true
                publishExecutor.schedule({ publishPending = false; publish() }, 600, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
            return
        }
        lastPublish = t
        _stats.value = _stats.value.copy(
            blocked = blocked.get(), forwarded = forwarded.get(),
            recentBlocked = synchronized(recent) { recent.toList() },
        )
    }

    private fun shutdown() {
        val p = tun
        tun = null
        worker?.interrupt()
        runCatching { p?.close() }
        networkCallback?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) } }
        networkCallback = null
        _stats.value = _stats.value.copy(running = false)
    }

    override fun onRevoke() {
        // Another VPN app took over, or the user switched us off in system settings.
        shutdown()
        Prefs.update { it.copy(dnsBlockEnabled = false) }
        stopSelf()
    }

    override fun onDestroy() {
        shutdown()
        pool.shutdownNow()
        super.onDestroy()
    }
}
