package nz.org.aotearoa.guandanscore.recognition

import nz.org.aotearoa.guandanscore.recognition.AiTransport.API
import nz.org.aotearoa.guandanscore.recognition.AiTransport.WEBVIEW
import nz.org.aotearoa.guandanscore.recognition.source.AiSourceId
import nz.org.aotearoa.guandanscore.recognition.source.AiSourcePresets
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiSettingsStoreTest {

    // 与 AiSettingsStore.decodeProfile 等价的解析逻辑，用于验证迁移规则不依赖 Android Context。
    private fun decode(json: String, decrypt: (String) -> String = { "" }): AiSettings =
        AiSettingsStore.decodeProfile(JSONObject(json), decrypt)

    @Test
    fun legacyProfileWithoutTransportDefaultsToApi() {
        val s = decode("""{"provider":"Google Gemini","endpoint":"https://x","model":"m","id":"a"}""")
        assertEquals(API, s.transport)
        assertEquals(AiSourceId.HTTP_API.key, s.sourceKey)
        assertTrue(s.requiresApiKey)
    }

    @Test
    fun legacyGeminiWebViewTransportMigratesToSourceKey() {
        val s = decode("""{"provider":"Gemini 网页版","transport":"GEMINI_WEBVIEW","id":"w"}""")
        assertEquals(WEBVIEW, s.transport)
        assertEquals(AiSourceId.GEMINI_WEBVIEW.key, s.sourceKey)
        assertEquals("https://gemini.google.com/app", s.homeUrl)
        assertFalse(s.requiresApiKey)
    }

    @Test
    fun webViewTransportWithoutSourceKeyFallsBackToCustom() {
        val s = decode("""{"provider":"X","transport":"WEBVIEW","id":"x"}""")
        assertEquals(WEBVIEW, s.transport)
        assertEquals(AiSourceId.CUSTOM_WEBVIEW.key, s.sourceKey)
        assertEquals("", s.homeUrl)
    }

    @Test
    fun unknownTransportFallsBackToApi() {
        val s = decode("""{"provider":"X","transport":"SOMETHING_NEW","id":"b"}""")
        assertEquals(API, s.transport)
        assertEquals(AiSourceId.HTTP_API.key, s.sourceKey)
    }

    @Test
    fun unknownSourceKeyFallsBackToHttpApi() {
        val s = decode("""{"provider":"X","source_key":"nope","transport":"API","id":"c"}""")
        assertEquals(AiSourceId.HTTP_API.key, s.sourceKey)
    }

    @Test
    fun deepseekProfileKeepsSourceKeyAndHomeUrl() {
        val s = decode(
            """{"provider":"DeepSeek 网页版","source_key":"deepseek_web","transport":"WEBVIEW","home_url":"https://chat.deepseek.com","id":"d"}"""
        )
        assertEquals(AiSourceId.DEEPSEEK_WEBVIEW.key, s.sourceKey)
        assertEquals(WEBVIEW, s.transport)
        assertEquals("https://chat.deepseek.com", s.homeUrl)
        assertFalse(s.requiresApiKey)
    }

    @Test
    fun missingHomeUrlIsFilledFromPreset() {
        val s = decode("""{"provider":"DeepSeek 网页版","source_key":"deepseek_web","transport":"WEBVIEW","id":"d2"}""")
        assertEquals("https://chat.deepseek.com", s.homeUrl)
    }

    @Test
    fun customHomeUrlIsPreserved() {
        val s = decode(
            """{"provider":"Mine","source_key":"custom_web","transport":"WEBVIEW","home_url":"https://example.test/chat","id":"e"}"""
        )
        assertEquals("https://example.test/chat", s.homeUrl)
    }

    @Test
    fun openAiProfileStaysApi() {
        val s = decode(
            """{"provider":"OpenAI","endpoint":"https://api.openai.com/v1/responses","model":"gpt-5.6-sol","transport":"API","id":"c2"}"""
        )
        assertEquals(API, s.transport)
        assertEquals("OpenAI", s.provider)
        assertTrue(s.requiresApiKey)
    }

    @Test
    fun apiKeyIsDecryptedThroughInjectedDecryptor() {
        // 模拟 Keystore 改名后仍可解密：解密函数由调用方注入，本类只负责把密文交给它。
        val s = decode("""{"provider":"X","api_key":"CIPHERTEXT","transport":"API","id":"f"}""") {
            if (it == "CIPHERTEXT") "plain-key" else ""
        }
        assertEquals("plain-key", s.apiKey)
    }

    @Test
    fun presetsCoverAllSourcesWithDeepSeekFirst() {
        val defaults = AiPresets.defaults()
        assertEquals(AiSourceId.DEEPSEEK_WEBVIEW.key, defaults.first().sourceKey)
        assertEquals(WEBVIEW, defaults.first().transport)
        assertTrue(defaults.any { it.sourceKey == AiSourceId.GEMINI_WEBVIEW.key })
        assertTrue(defaults.any { it.sourceKey == AiSourceId.HTTP_API.key })
    }

    @Test
    fun webViewPresetsHaveNoEndpointOrKey() {
        listOf(
            AiSourcePresets.geminiWebView(),
            AiSourcePresets.deepseekWebView(),
            AiSourcePresets.chatgptWebView(),
            AiSourcePresets.customWebView()
        ).forEach {
            assertEquals(WEBVIEW, it.transport)
            assertEquals("", it.endpoint)
            assertEquals("", it.model)
            assertEquals("", it.apiKey)
            assertFalse(it.requiresApiKey)
        }
    }

    @Test
    fun webViewPresetHomeUrlsMatchRegistry() {
        assertEquals("https://chat.deepseek.com", AiPresets.deepseekWebView().homeUrl)
        assertEquals("https://gemini.google.com/app", AiPresets.geminiWebView().homeUrl)
        assertEquals("https://chatgpt.com/", AiSourcePresets.chatgptWebView().homeUrl)
        assertEquals("", AiPresets.customWebView().homeUrl)
    }

    @Test
    fun apiPresetsStillRequireKey() {
        assertTrue(AiPresets.gemini().requiresApiKey)
        assertTrue(AiPresets.openAi().requiresApiKey)
    }

    @Test
    fun positionalConstructorStaysCompatible() {
        // 旧的 5 位置参数写法必须继续编译并得到 API 传输
        val s = AiSettings("Custom", "https://x", "m", "key", "id-1")
        assertEquals("Custom", s.provider)
        assertEquals("id-1", s.id)
        assertEquals(API, s.transport)
        assertEquals(AiSourceId.HTTP_API.key, s.sourceKey)
    }

    @Test
    fun sourceResolvesFromSourceKey() {
        val s = AiSettings(sourceKey = AiSourceId.DEEPSEEK_WEBVIEW.key, transport = WEBVIEW)
        assertEquals(AiSourceId.DEEPSEEK_WEBVIEW, s.source)
        val unknown = AiSettings(sourceKey = "nope", transport = API)
        assertEquals(AiSourceId.HTTP_API, unknown.source)
    }
}
