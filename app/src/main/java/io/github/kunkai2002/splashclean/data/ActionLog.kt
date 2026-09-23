package io.github.kunkai2002.splashclean.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

@Serializable
data class ActionEntry(
    val time: Long,
    val appId: String,
    val activityId: String? = null,
    /** "rule" | "ocr" | "user" */
    val source: String,
    val groupName: String,
    val subscription: String = "",
    val action: String = "",
    /** Milliseconds between entering the app and the action. */
    val sinceAppEnter: Long = -1,
)

@Serializable
data class Counters(
    val total: Long = 0,
    val day: String = "",
    val today: Int = 0,
    val perApp: Map<String, Int> = emptyMap(),
)

object ActionLog {
    private const val MAX = 300
    private val json = Json { ignoreUnknownKeys = true }
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var file: File
    private lateinit var countersFile: File

    private val _entries = MutableStateFlow<List<ActionEntry>>(emptyList())
    val entries: StateFlow<List<ActionEntry>> get() = _entries

    private val _counters = MutableStateFlow(Counters())
    val counters: StateFlow<Counters> get() = _counters

    private fun today(): String = SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date())

    fun init(context: Context) {
        file = File(context.filesDir, "action_log.jsonl")
        countersFile = File(context.filesDir, "counters.json")
        io.execute {
            _entries.value = runCatching {
                file.readLines().mapNotNull { l -> runCatching { json.decodeFromString(ActionEntry.serializer(), l) }.getOrNull() }
            }.getOrDefault(emptyList()).takeLast(MAX).reversed()
            _counters.value = runCatching { json.decodeFromString(Counters.serializer(), countersFile.readText()) }
                .getOrDefault(Counters())
        }
    }

    fun add(entry: ActionEntry) {
        val list = listOf(entry) + _entries.value
        _entries.value = if (list.size > MAX) list.subList(0, MAX) else list
        val d = today()
        val c = _counters.value
        val next = Counters(
            total = c.total + 1,
            day = d,
            today = if (c.day == d) c.today + 1 else 1,
            perApp = c.perApp + (entry.appId to ((c.perApp[entry.appId] ?: 0) + 1)),
        )
        _counters.value = next
        val snapshot = _entries.value
        io.execute {
            runCatching {
                file.writeText(snapshot.reversed().joinToString("\n") { json.encodeToString(ActionEntry.serializer(), it) })
                countersFile.writeText(json.encodeToString(Counters.serializer(), next))
            }
        }
    }

    fun todayCount(): Int = _counters.value.let { if (it.day == today()) it.today else 0 }

    fun clear() {
        _entries.value = emptyList()
        io.execute { runCatching { file.delete() } }
    }
}
