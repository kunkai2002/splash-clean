package io.github.kunkai2002.splashclean

import io.github.kunkai2002.splashclean.capture.UserRule
import io.github.kunkai2002.splashclean.capture.UserRules
import io.github.kunkai2002.splashclean.engine.SplashFallback
import io.github.kunkai2002.splashclean.rule.RawSubscription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class RulesParseTest {
    @Test
    fun builtinRulesAreValid() {
        val subs = RawSubscription.parse(File("src/main/assets/builtin_rules.json5").readText())
        assertEquals(-1L, subs.id)
        assertEquals(3, subs.globalGroups.size)
        subs.globalGroups.forEach { g -> assertEquals("${g.name}: ${g.errorDesc}", null, g.errorDesc) }
    }

    @Test
    fun userRulesRoundTrip() {
        val rules = listOf(
            UserRule(1, "com.example", "Example", "com.example.Splash", "splash", "[vid=\"skip\"][visibleToUser=true]", true),
            UserRule(2, "com.example", "Example", null, "popup", null, false, 0.9f, 0.05f),
        )
        val subs = RawSubscription.parse(UserRules.toSubscription(rules))
        assertEquals(-2L, subs.id)
        val groups = subs.apps.single().groups
        assertEquals(2, groups.size)
        groups.forEach { g -> assertEquals(g.errorDesc, null, g.errorDesc) }
        assertTrue(groups[0].name.startsWith("开屏广告"))
        assertTrue(groups[1].name.startsWith("全屏广告"))
    }

    @Test
    fun skipTextMatcher() {
        listOf("跳过", "跳过 5", "5s | 跳过", "5s丨跳过", "跳過", "Skip", "SKIP 3", "跳过广告", "3 跳过", "跳过>").forEach {
            assertTrue(it, SplashFallback.isSkipText(it))
        }
        listOf("跳过片头", "点击跳转详情页或第三方应用", "跳过此步骤", "已跳过", "skipping ahead now", "").forEach {
            assertFalse(it, SplashFallback.isSkipText(it))
        }
    }

    /** Parses a full community subscription when SC_SUBS_FILE points to one (not committed to the repo). */
    @Test
    fun communitySubscriptionParses() {
        val path = System.getenv("SC_SUBS_FILE")
        assumeTrue(path != null && File(path).exists())
        val start = System.currentTimeMillis()
        val subs = RawSubscription.parse(File(path!!).readText())
        val parseMs = System.currentTimeMillis() - start
        val groups = subs.appGroups + subs.globalGroups
        val t2 = System.currentTimeMillis()
        val invalid = groups.filter { !it.valid }
        val validateMs = System.currentTimeMillis() - t2
        println("subscription ${subs.name} v${subs.version}: ${subs.apps.size} apps, ${groups.size} groups, parse $parseMs ms, validate $validateMs ms, invalid ${invalid.size}")
        invalid.take(10).forEach { println("  invalid: ${it.name}: ${it.errorDesc?.lines()?.firstOrNull()}") }
        assertTrue("too many invalid groups: ${invalid.size}", invalid.size <= groups.size / 100)
    }
}
