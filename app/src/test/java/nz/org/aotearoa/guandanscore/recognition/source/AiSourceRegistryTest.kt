package nz.org.aotearoa.guandanscore.recognition.source

import nz.org.aotearoa.guandanscore.recognition.AiPresets
import nz.org.aotearoa.guandanscore.recognition.AiSettings
import nz.org.aotearoa.guandanscore.recognition.AiTransport
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

    @Test
    fun deepSeekIsFirstDefaultProfile() {
        assertEquals(AiSourceId.DEEPSEEK_WEBVIEW.key, AiPresets.defaults().first().sourceKey)
    }
}
