package io.github.kunkai2002.splashclean.capture

import android.content.Context
import io.github.kunkai2002.splashclean.rule.RuleRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.Locale

@Serializable
data class UserRule(
    val id: Long,
    val appId: String,
    val appName: String,
    val activityId: String? = null,
    /** "splash" (only in the first seconds after opening the app) or "popup" (any time). */
    val kind: String,
    /** GKD selector; null means "tap at a position relative to the window". */
    val selector: String? = null,
    val fastQuery: Boolean = false,
    val relX: Float? = null,
    val relY: Float? = null,
    val label: String = "",
)

object UserRules {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private fun file(context: Context) = File(context.filesDir, "user_rules_list.json")

    fun load(context: Context): List<UserRule> = runCatching {
        json.decodeFromString<List<UserRule>>(file(context).readText())
    }.getOrDefault(emptyList())

    fun add(context: Context, rule: UserRule): Result<Unit> = save(context, load(context) + rule)

    fun remove(context: Context, id: Long): Result<Unit> = save(context, load(context).filter { it.id != id })

    private fun save(context: Context, rules: List<UserRule>): Result<Unit> {
        val result = RuleRepository.saveUserRules(toSubscription(rules))
        if (result.isSuccess) file(context).writeText(json.encodeToString(rules))
        return result
    }

    private fun quote(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    /** Selector candidates for a captured node, most specific first. */
    fun selectorsFor(node: SnapNode): List<Pair<String, Boolean>> = buildList {
        node.vid?.takeIf { it.isNotBlank() }?.let { add("[vid=\"${quote(it)}\"][visibleToUser=true]" to true) }
        node.idName?.takeIf { node.vid == null && it.isNotBlank() }?.let { add("[id=\"${quote(it)}\"][visibleToUser=true]" to true) }
        node.text?.takeIf { it.isNotBlank() && it.length <= 20 }?.let { add("[text=\"${quote(it)}\"][visibleToUser=true]" to true) }
        node.desc?.takeIf { it.isNotBlank() && it.length <= 20 }?.let { add("[desc=\"${quote(it)}\"][visibleToUser=true]" to false) }
    }

    fun toSubscription(rules: List<UserRule>): String {
        val obj = buildJsonObject {
            put("id", RuleRepository.USER_ID)
            put("name", "My rules")
            put("version", (System.currentTimeMillis() / 1000).toInt())
            putJsonArray("apps") {
                rules.groupBy { it.appId }.forEach { (appId, list) ->
                    add(buildJsonObject {
                        put("id", appId)
                        put("name", list.first().appName)
                        putJsonArray("groups") {
                            list.forEachIndexed { i, r ->
                                add(buildJsonObject {
                                    put("key", i)
                                    val splash = r.kind == "splash"
                                    put("name", (if (splash) "开屏广告" else "全屏广告") + "-" + r.label.ifBlank { "#${r.id}" })
                                    if (splash) {
                                        put("matchTime", 10000)
                                        put("actionMaximum", 1)
                                        put("resetMatch", "app")
                                        put("forcedTime", 10000)
                                    }
                                    put("fastQuery", r.fastQuery)
                                    r.activityId?.let { a -> putJsonArray("activityIds") { add(JsonPrimitive(a)) } }
                                    putJsonArray("rules") {
                                        add(buildJsonObject {
                                            if (r.selector != null) {
                                                putJsonArray("matches") { add(JsonPrimitive(r.selector)) }
                                            } else {
                                                putJsonArray("matches") { add(JsonPrimitive("[parent=null]")) }
                                                put("action", "clickCenter")
                                                putJsonObject("position") {
                                                    put("left", String.format(Locale.ROOT, "width * %.4f", r.relX ?: 0.5f))
                                                    put("top", String.format(Locale.ROOT, "height * %.4f", r.relY ?: 0.5f))
                                                }
                                            }
                                        })
                                    }
                                })
                            }
                        }
                    })
                }
            }
        }
        return obj.toString()
    }
}
