package nz.org.aotearoa.guandanscore.recognition

import nz.org.aotearoa.guandanscore.recognition.source.AiSourceId
import org.junit.Assert.*
import org.junit.Test

/**
 * AI-094：旧 Gemini Key 清理的判定规则。
 *
 * 这些用例守的是两类真机上难以复现的事故：
 * - 误删 API 源的 Key（用户调用直接失败，且看不出是清理导致的）；
 * - 在还有密文依赖时删掉 Keystore alias（密文永久解不开）。
 */
class LegacyKeyCleanupTest {

    private fun web(key: String = "old-gemini-key", id: String = "web1") = AiSettings(
        id = id,
        sourceKey = AiSourceId.GEMINI_WEBVIEW.key,
        provider = "Gemini",
        apiKey = key,
        transport = AiTransport.WEBVIEW
    )

    private fun api(key: String = "openai-key", id: String = "api1") = AiSettings(
        id = id,
        sourceKey = AiSourceId.HTTP_API.key,
        provider = "OpenAI",
        endpoint = "https://api.example.test/v1",
        model = "m",
        apiKey = key,
        transport = AiTransport.API
    )

    @Test
    fun clearsKeyOnWebSourceOnly() {
        val plan = LegacyKeyCleanup.plan(listOf(web(), api()))
        assertTrue(plan.changed)
        assertEquals(listOf("web1"), plan.clearedIds)
        assertEquals("", plan.profiles.first { it.id == "web1" }.apiKey)
        // API 源的 Key 必须原样保留，否则调用会直接失败。
        assertEquals("openai-key", plan.profiles.first { it.id == "api1" }.apiKey)
        assertEquals(listOf("api1"), plan.retainingIds)
    }

    @Test
    fun keepsApiKeyEvenWhenItLooksLikeAGeminiKey() {
        // 用户可能把 Gemini API Key 填在 API 源里 —— 那不是「旧网页 Key」，必须保留。
        val plan = LegacyKeyCleanup.plan(listOf(api(key = "AIzaLegacyLooking")))
        assertFalse(plan.changed)
        assertEquals("AIzaLegacyLooking", plan.profiles.single().apiKey)
    }

    @Test
    fun isIdempotent() {
        val once = LegacyKeyCleanup.plan(listOf(web(), api()))
        val twice = LegacyKeyCleanup.plan(once.profiles)
        assertFalse("第二次清理不应再有变化", twice.changed)
        assertTrue(twice.clearedIds.isEmpty())
        assertEquals(once.profiles, twice.profiles)
    }

    @Test
    fun doesNotTouchOtherFields() {
        val before = web().copy(homeUrl = "https://gemini.google.com/app")
        val after = LegacyKeyCleanup.plan(listOf(before)).profiles.single()
        assertEquals(before.id, after.id)
        assertEquals(before.sourceKey, after.sourceKey)
        assertEquals(before.provider, after.provider)
        assertEquals(before.homeUrl, after.homeUrl)
        assertEquals(before.transport, after.transport)
        assertEquals("", after.apiKey)
    }

    @Test
    fun emptyKeyProfilesAreUntouched() {
        val plan = LegacyKeyCleanup.plan(listOf(web(key = ""), api(key = "")))
        assertFalse(plan.changed)
        assertTrue(plan.retainingIds.isEmpty())
        assertTrue(plan.clearedIds.isEmpty())
    }

    @Test
    fun aliasCanOnlyBeDroppedWhenNoProfileRetainsKey() {
        assertFalse(LegacyKeyCleanup.canDropKeystoreAlias(listOf(api())))
        // 网页源尚未清理时 Key 仍在内存对象里，此时删 alias 会让密文解不开 —— 也不许删。
        assertFalse(LegacyKeyCleanup.canDropKeystoreAlias(listOf(web())))
        assertFalse(LegacyKeyCleanup.canDropKeystoreAlias(listOf(web(), api(key = ""))))
        assertTrue(LegacyKeyCleanup.canDropKeystoreAlias(listOf(web(key = ""), api(key = ""))))
        assertTrue(LegacyKeyCleanup.canDropKeystoreAlias(emptyList()))
        // 清理后的列表（只剩无 Key 的源）才可以删 alias。
        val cleaned = LegacyKeyCleanup.plan(listOf(web())).profiles
        assertTrue(LegacyKeyCleanup.canDropKeystoreAlias(cleaned))
    }

    @Test
    fun planKeepsAliasWhenApiSourceSurvives() {
        val plan = LegacyKeyCleanup.plan(listOf(web(), api()))
        assertTrue(plan.keepsKeystoreAlias)
    }
}
