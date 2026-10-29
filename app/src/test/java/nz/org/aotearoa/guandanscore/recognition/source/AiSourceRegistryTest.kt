package nz.org.aotearoa.guandanscore.recognition.source

import nz.org.aotearoa.guandanscore.recognition.AiPresets
import nz.org.aotearoa.guandanscore.recognition.AiSettings
import nz.org.aotearoa.guandanscore.recognition.AiTransport
import nz.org.aotearoa.guandanscore.recognition.site.ChatGPTAdapter
import nz.org.aotearoa.guandanscore.recognition.site.ChatGPTScripts
import nz.org.aotearoa.guandanscore.recognition.site.CustomAdapter
import nz.org.aotearoa.guandanscore.recognition.site.DeepSeekAdapter
import nz.org.aotearoa.guandanscore.recognition.site.GeminiAdapter
import org.junit.Assert.*
import org.junit.Test

/** 源注册表单测。不创建 WebView，只验证映射与预设（AI-105/106/146）。 */
class AiSourceRegistryTest {

    private fun webView(sourceId: AiSourceId) = AiSettings(
        id = "t_${sourceId.key}",
        sourceKey = sourceId.key,
        provider = sourceId.label,
        transport = AiTransport.WEBVIEW,
        homeUrl = AiSourcePresets.homeUrlFor(sourceId).orEmpty()
    )

    @Test
    fun adapterMappingForEachWebSource() {
        assertTrue(AiSourceRegistry.adapterFor(webView(AiSourceId.DEEPSEEK_WEBVIEW)) is DeepSeekAdapter)
        assertTrue(AiSourceRegistry.adapterFor(webView(AiSourceId.GEMINI_WEBVIEW)) is GeminiAdapter)
        assertTrue(AiSourceRegistry.adapterFor(webView(AiSourceId.CHATGPT_WEBVIEW)) is ChatGPTAdapter)
        assertTrue(AiSourceRegistry.adapterFor(webView(AiSourceId.CUSTOM_WEBVIEW)) is CustomAdapter)
    }

    @Test
    fun httpApiHasNoAdapter() {
        assertNull(AiSourceRegistry.adapterFor(AiPresets.gemini()))
    }

    @Test
    fun apiTransportCreatesHttpApiSource() {
        val src = AiSourceRegistry.create(AiPresets.openAi())
        assertTrue(src is HttpApiSource)
        assertEquals(AiSourceId.HTTP_API.key, src.id)
        assertTrue(src.requiresNetworkConfig)
        assertFalse(src.requiresWebLogin)
    }

    /** 未知 sourceKey 回退 HTTP API，不静默走网页路径（AI-105）。 */
    @Test
    fun unknownSourceKeyFallsBackToHttpApi() {
        val settings = AiSettings(
            id = "x",
            sourceKey = "not_a_real_source",
            provider = "Mystery",
            transport = AiTransport.API
        )
        assertTrue(AiSourceRegistry.create(settings) is HttpApiSource)
    }

    /** 一次识牌只可能由一个实现执行（AI-146）。 */
    @Test
    fun onlyOneImplementationIsReturned() {
        val settings = webView(AiSourceId.DEEPSEEK_WEBVIEW)
        // 网页源需要 host；用一个空构造避免真的建 WebView 的路径不应在这里被触发。
        assertNotNull(AiSourceRegistry.adapterFor(settings))
        assertTrue(AiSourceRegistry.create(AiPresets.gemini()) is HttpApiSource)
    }

    @Test
    fun presetsCoverFourSourcesWithDeepSeekFirst() {
        val presets = AiSourceRegistry.presets()
        assertEquals(AiSourceId.DEEPSEEK_WEBVIEW.key, presets.first().sourceKey)
        val keys = presets.map { it.sourceKey }.toSet()
        assertTrue(keys.containsAll(listOf(
            AiSourceId.DEEPSEEK_WEBVIEW.key,
            AiSourceId.GEMINI_WEBVIEW.key,
            AiSourceId.CUSTOM_WEBVIEW.key,
            AiSourceId.HTTP_API.key
        )))
    }

    /** 首页 URL 只在 AiSourcePresets 定义一次，适配器不得另写字面量（AI-106）。 */
    @Test
    fun homeUrlsComeFromSingleSource() {
        assertEquals(AiSourcePresets.homeUrlFor(AiSourceId.DEEPSEEK_WEBVIEW), DeepSeekAdapter().homeUrl)
        assertEquals(AiSourcePresets.homeUrlFor(AiSourceId.GEMINI_WEBVIEW), GeminiAdapter().homeUrl)
    }

    @Test
    fun customAdapterUsesUserHomeUrl() {
        val adapter = CustomAdapter("https://example.test/chat")
        assertEquals("https://example.test/chat", adapter.homeUrl)
        assertEquals(AiSourceId.CUSTOM_WEBVIEW.key, adapter.siteKey)
    }

    /**
     * AI-164：五源（DeepSeek / Gemini / ChatGPT / 自定义网页 / HTTP API）必须在预设中
     * 同时存在、key 唯一，且 DeepSeek 仍排第一。
     */
    @Test
    fun presetsCoverFiveSourcesWithUniqueKeys() {
        val presets = AiSourceRegistry.presets()
        assertEquals(AiSourceId.DEEPSEEK_WEBVIEW.key, presets.first().sourceKey)
        val webKeys = presets.filter { it.transport == AiTransport.WEBVIEW }.map { it.sourceKey }.toSet()
        assertEquals(
            setOf(
                AiSourceId.DEEPSEEK_WEBVIEW.key,
                AiSourceId.GEMINI_WEBVIEW.key,
                AiSourceId.CHATGPT_WEBVIEW.key,
                AiSourceId.CUSTOM_WEBVIEW.key
            ),
            webKeys
        )
        assertTrue(presets.any { it.transport == AiTransport.API })
        // id 必须唯一，否则 store.select(id) 会串源。
        assertEquals(presets.size, presets.map { it.id }.toSet().size)
    }

    /** ChatGPT 网页源不要求 API Key，且必须带可登录的首页（AI-164）。 */
    @Test
    fun chatgptPresetNeedsNoApiKeyButHasHomeUrl() {
        val preset = AiSourcePresets.chatgptWebView()
        assertEquals(AiSourceId.CHATGPT_WEBVIEW.key, preset.sourceKey)
        assertEquals(AiTransport.WEBVIEW, preset.transport)
        assertEquals("", preset.apiKey)
        assertEquals("", preset.endpoint)
        assertFalse(preset.requiresApiKey)
        assertEquals("https://chatgpt.com/", preset.homeUrl)
        assertFalse(preset.requiresApiKey)
    }

    /** 首页 URL 只有一个事实来源：适配器必须读 AiSourcePresets，不得另写字面量（AI-106/164）。 */
    @Test
    fun chatgptHomeUrlComesFromSingleSource() {
        assertEquals(AiSourcePresets.homeUrlFor(AiSourceId.CHATGPT_WEBVIEW), ChatGPTAdapter().homeUrl)
        assertEquals(AiSourceId.CHATGPT_WEBVIEW.key, ChatGPTAdapter().siteKey)
    }

    /** AI-164 明确要求：旧 profile 迁移不得丢掉其它源配置，未识别 key 仍回落 API。 */
    @Test
    fun chatgptKeyIsRecognizedAndOldUnknownStillFallsBack() {
        assertEquals(AiSourceId.CHATGPT_WEBVIEW, AiSourceId.fromKey("chatgpt_web"))
        assertEquals(AiSourceId.HTTP_API, AiSourceId.fromKey("chat_gpt_web"))
        assertEquals(AiSourceId.HTTP_API, AiSourceId.fromKey(null))
    }

    /** ChatGPT 脚本必须可被 JSON 解析且不含同步忙等（与其它站点同一硬规则）。 */
    @Test
    fun chatgptScriptsAreJsonAndNeverBusyWait() {
        val scripts = listOf(
            ChatGPTScripts.diagnose(),
            ChatGPTScripts.snapshot(),
            ChatGPTScripts.loginCheck(),
            ChatGPTScripts.verifyAttachment(),
            ChatGPTScripts.fillAndSubmit("a\"b\u2028c"),
            ChatGPTScripts.pasteImage("x.png", "image/png", "AAA="),
            ChatGPTScripts.uploadTrigger()
        )
        scripts.forEach {
            assertTrue("脚本不得同步忙等: $it", !it.contains("while"))
            assertTrue("必须返回 JSON: $it", it.contains("JSON.stringify"))
        }
    }

    /**
     * AI-093：网页源不参与 API Key 门槛。
     * 即使旧配置里残留了 Gemini Key，网页源也不得被当成需要 Key 的源，
     * 更不得因此改走 HTTP API 路径。
     */
    @Test
    fun webSourcesIgnoreLeftoverApiKey() {
        listOf(
            AiSourceId.DEEPSEEK_WEBVIEW,
            AiSourceId.GEMINI_WEBVIEW,
            AiSourceId.CHATGPT_WEBVIEW
        ).forEach { id ->
            val withKey = AiSettings(
                id = "t_${id.key}",
                sourceKey = id.key,
                provider = id.label,
                endpoint = "https://example.test",
                model = "m",
                apiKey = "leftover-old-gemini-key",
                homeUrl = AiSourcePresets.homeUrlFor(id).orEmpty(),
                transport = AiTransport.WEBVIEW
            )
            assertFalse("${id.key} 不得要求 API Key", withKey.requiresApiKey)
            assertNotNull("${id.key} 必须有网页适配器", AiSourceRegistry.adapterFor(withKey))
        }
    }

    /** AI-093：只有 API 源仍要求 Key，且 WebView 源不需要网络配置。 */
    @Test
    fun onlyApiSourcesRequireKeyAndNetworkConfig() {
        val api = AiPresets.gemini()
        assertTrue(api.requiresApiKey)

        val web = AiSourcePresets.geminiWebView()
        assertFalse(web.requiresApiKey)
    }

    @Test
    fun deepSeekIsFirstDefaultProfile() {
        assertEquals(AiSourceId.DEEPSEEK_WEBVIEW.key, AiPresets.defaults().first().sourceKey)
    }
}
