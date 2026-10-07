package io.github.kunkai2002.splashclean.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import io.github.kunkai2002.splashclean.BuildConfig
import io.github.kunkai2002.splashclean.R
import io.github.kunkai2002.splashclean.data.Prefs
import io.github.kunkai2002.splashclean.data.l10n
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class ReleaseInfo(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val apkName: String,
    val size: Long,
    val pageUrl: String,
)

sealed class UpdateState {
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    data object UpToDate : UpdateState()
    data class Available(val info: ReleaseInfo) : UpdateState()
    data class Downloading(val info: ReleaseInfo, val progress: Float) : UpdateState()
    data class NeedInstallPermission(val info: ReleaseInfo) : UpdateState()
    data class Installing(val info: ReleaseInfo) : UpdateState()
    data class Failed(val message: String, val info: ReleaseInfo? = null) : UpdateState()
}

/** In-app update from GitHub Releases. The downloaded APK must carry this app's signature. */
object Updater {
    private const val TAG = "Updater"
    private const val API = "https://api.github.com/repos/kunkai2002/splash-clean/releases/latest"
    const val PAGE = "https://github.com/kunkai2002/splash-clean/releases/latest"

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> get() = _state

    /** "1.2.3" > "1.2" etc.; non-numeric parts are ignored. */
    fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.trimStart('v', 'V').split('.', '-').map { p -> p.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val a = parts(remote)
        val b = parts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** ABI order of preference for picking the release asset. */
    fun pickAsset(names: List<String>, abis: List<String>): String? {
        for (abi in abis) names.firstOrNull { it.endsWith("-$abi.apk") }?.let { return it }
        return names.firstOrNull { it.endsWith("-universal.apk") }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "SplashClean/${BuildConfig.VERSION_NAME}")
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    suspend fun check(context: Context, manual: Boolean) = withContext(Dispatchers.IO) {
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.Installing) return@withContext
        _state.value = UpdateState.Checking
        Prefs.update { it.copy(lastUpdateCheck = System.currentTimeMillis()) }
        _state.value = try {
            val o = Json.parseToJsonElement(get(API)).jsonObject
            val tag = o["tag_name"]!!.jsonPrimitive.content
            val assets = o["assets"]?.jsonArray?.map { it.jsonObject }.orEmpty()
            val name = pickAsset(assets.map { it["name"]!!.jsonPrimitive.content }, Build.SUPPORTED_ABIS.toList())
            val asset = assets.firstOrNull { it["name"]!!.jsonPrimitive.content == name }
            when {
                !isNewer(tag, BuildConfig.VERSION_NAME) -> UpdateState.UpToDate
                asset == null -> UpdateState.Failed(context.l10n().getString(R.string.update_no_apk))
                else -> UpdateState.Available(
                    ReleaseInfo(
                        version = tag.trimStart('v'),
                        notes = o["body"]?.jsonPrimitive?.content.orEmpty(),
                        apkUrl = asset["browser_download_url"]!!.jsonPrimitive.content,
                        apkName = name!!,
                        size = asset["size"]?.jsonPrimitive?.long ?: 0L,
                        pageUrl = o["html_url"]?.jsonPrimitive?.content ?: PAGE,
                    )
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "check failed", t)
            if (manual) UpdateState.Failed(t.message ?: t.javaClass.simpleName) else UpdateState.Idle
        }
    }

    fun openInBrowser(context: Context, url: String = PAGE) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    suspend fun downloadAndInstall(context: Context, info: ReleaseInfo) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        if (!canInstall(app)) {
            _state.value = UpdateState.NeedInstallPermission(info)
            withContext(Dispatchers.Main) { openInstallPermission(app) }
            return@withContext
        }
        try {
            val file = download(app, info)
            verify(app, file)
            _state.value = UpdateState.Installing(info)
            install(app, file)
        } catch (t: Throwable) {
            Log.w(TAG, "update failed", t)
            _state.value = UpdateState.Failed(t.message ?: t.javaClass.simpleName, info)
        }
    }

    private fun download(context: Context, info: ReleaseInfo): File {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, info.apkName)
        var url = info.apkUrl
        repeat(5) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000
            c.readTimeout = 60_000
            c.setRequestProperty("User-Agent", "SplashClean/${BuildConfig.VERSION_NAME}")
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    url = c.getHeaderField("Location") ?: error("redirect without location")
                    return@repeat
                }
                if (code !in 200..299) error("HTTP $code")
                val total = c.contentLengthLong.takeIf { it > 0 } ?: info.size
                var done = 0L
                var lastReport = 0L
                c.inputStream.use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (done - lastReport > 256 * 1024) {
                                lastReport = done
                                _state.value = UpdateState.Downloading(info, if (total > 0) done.toFloat() / total else 0f)
                            }
                        }
                    }
                }
                if (info.size > 0 && out.length() != info.size) error("incomplete download")
                return out
            } finally {
                c.disconnect()
            }
        }
        error("too many redirects")
    }

    @Suppress("DEPRECATION")
    private fun signatures(info: PackageInfo?): Set<String> {
        info ?: return emptySet()
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.let { si -> if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory }
        } else {
            info.signatures
        } ?: return emptySet()
        val md = MessageDigest.getInstance("SHA-256")
        return sigs.map { s -> md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun verify(context: Context, file: File) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: error("not an APK")
        check(archive.packageName == context.packageName) { "package mismatch: ${archive.packageName}" }
        val installed = pm.getPackageInfo(context.packageName, flags)
        val newCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        val oldCode = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        check(newCode > oldCode) { "version $newCode is not newer than $oldCode" }
        val a = signatures(archive)
        val b = signatures(installed)
        check(a.isNotEmpty() && a.any { it in b }) { context.l10n().getString(R.string.update_bad_signature) }
    }

    private fun install(context: Context, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            file.inputStream().use { input ->
                session.openWrite("base.apk", 0, file.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val intent = Intent(context, InstallResultReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
    }

    internal fun onInstallFailed(message: String) {
        val info = (_state.value as? UpdateState.Installing)?.info
        _state.value = UpdateState.Failed(message, info)
    }
}

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { Updater.onInstallFailed(it.message ?: "cannot open installer") }
            }

            PackageInstaller.STATUS_SUCCESS -> Unit // the app is restarted by the system

            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "install failed"
                Updater.onInstallFailed(msg)
                Toast.makeText(context, context.l10n().getString(R.string.update_failed, msg), Toast.LENGTH_LONG).show()
            }
        }
    }
}
