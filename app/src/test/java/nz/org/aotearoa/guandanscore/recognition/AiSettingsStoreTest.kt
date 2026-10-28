package nz.org.aotearoa.guandanscore.recognition

import nz.org.aotearoa.guandanscore.recognition.AiTransport.API
import nz.org.aotearoa.guandanscore.recognition.AiTransport.GEMINI_WEBVIEW
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiSettingsStoreTest {

    // 与 AiSettingsStore.decodeProfile 等价的解析逻辑，用于验证迁移规则不依赖 Android Context。
    private fun decode(json: String): AiSettings {
        val o = JSONObject(json)
        return AiSettings(
            provider = o.optString("provider", "Google Gemini"),
            endpoint = o.optString("endpoint", ""),
            model = o.optString("model", ""),
            apiKey = "",
            id = o.optString("id").ifBlank { "new" },
            transport = o.optString("transport").takeIf { it.isNotBlank() }
                ?.let { runCatching { AiTransport.valueOf(it) }.getOrNull() } ?: API
        )
    }

    @Test
    fun legacyProfileWithoutTransportDefaultsToApi() {
        val s = decode("""{"provider":"Google Gemini","endpoint":"https://x","model":"m","id":"a"}""")
        assertEquals(API, s.transport)
        assertTrue(s.requiresApiKey)
    }

    @Test
    fun webViewProfileRoundTrips() {
        val s = decode("""{"provider":"Gemini 网页版","transport":"GEMINI_WEBVIEW","id":"w"}""")
        assertEquals(GEMINI_WEBVIEW, s.transport)
        assertFalse(s.requiresApiKey)
    }

    @Test
    fun unknownTransportFallsBackToApi() {
        val s = decode("""{"provider":"X","transport":"SOMETHING_NEW","id":"b"}""")
        assertEquals(API, s.transport)
    }

    @Test
    fun openAiProfileStaysApi() {
        val s = decode("""{"provider":"OpenAI","endpoint":"https://api.openai.com/v1/responses","model":"gpt-5.6-sol","transport":"API","id":"c"}""")
        assertEquals(API, s.transport)
        assertEquals("OpenAI", s.provider)
        assertTrue(s.requiresApiKey)
    }

    @Test
    fun webViewPresetHasNoEndpointOrKey() {
        val p = AiPresets.geminiWebView()
        assertEquals(GEMINI_WEBVIEW, p.transport)
        assertEquals("", p.endpoint)
        assertEquals("", p.model)
        assertEquals("", p.apiKey)
        assertFalse(p.requiresApiKey)
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
    }
}
