package nz.org.aotearoa.guandanscore.recognition.source

import nz.org.aotearoa.guandanscore.recognition.AiPresets
import nz.org.aotearoa.guandanscore.recognition.AiSettings
import nz.org.aotearoa.guandanscore.recognition.AiTransport

/**
 * 内置源的单一事实来源。
 *
 * 站点首页 URL 只在这里定义一次；适配器通过 [homeUrlFor] 读取，不要在 `DeepSeekAdapter` /
 * `GeminiAdapter` 里再写一遍字面量（AI-106 的验收要求）。
 *
 * 注意：HTTP API 的 endpoint / model 仍由 `AiPresets`（recognition 包）持有，
 * 这里只做转发，不复制常量。
 */
object AiSourcePresets {

    /** 各网页源首页。`custom_web` 没有默认值，必须由用户填写。 */
    private val WEB_HOME_URLS: Map<AiSourceId, String> = mapOf(
        AiSourceId.DEEPSEEK_WEBVIEW to "https://chat.deepseek.com",
        AiSourceId.GEMINI_WEBVIEW to "https://gemini.google.com/app"
    )

    fun homeUrlFor(id: AiSourceId): String? = WEB_HOME_URLS[id]

    /** 全部预设，顺序与配置页下拉一致（DeepSeek 首发）。 */
    fun presets(): List<AiSettings> = listOf(
        deepseekWebView(),
        geminiWebView(),
        customWebView(),
        httpApiGemini(),
        httpApiOpenAi()
    )

    /** 供 `AiSettingsStore` 首次初始化使用；只含四个“源”，不含第二个 API 变体。 */
    fun defaultProfiles(): List<AiSettings> = listOf(
        deepseekWebView(),
        geminiWebView(),
        httpApiGemini()
    )

    fun deepseekWebView() = AiSettings(
        id = "preset_deepseek_webview",
        sourceKey = AiSourceId.DEEPSEEK_WEBVIEW.key,
        provider = AiSourceId.DEEPSEEK_WEBVIEW.label,
        endpoint = "",
        model = "",
        apiKey = "",
        homeUrl = WEB_HOME_URLS.getValue(AiSourceId.DEEPSEEK_WEBVIEW),
        transport = AiTransport.WEBVIEW
    )

    fun geminiWebView() = AiSettings(
        id = "preset_gemini_webview",
        sourceKey = AiSourceId.GEMINI_WEBVIEW.key,
        provider = AiSourceId.GEMINI_WEBVIEW.label,
        endpoint = "",
        model = "",
        apiKey = "",
        homeUrl = WEB_HOME_URLS.getValue(AiSourceId.GEMINI_WEBVIEW),
        transport = AiTransport.WEBVIEW
    )

    fun customWebView() = AiSettings(
        id = "preset_custom_webview",
        sourceKey = AiSourceId.CUSTOM_WEBVIEW.key,
        provider = AiSourceId.CUSTOM_WEBVIEW.label,
        endpoint = "",
        model = "",
        apiKey = "",
        homeUrl = "",
        transport = AiTransport.WEBVIEW
    )

    fun httpApiGemini(): AiSettings = AiPresets.gemini().copy(
        sourceKey = AiSourceId.HTTP_API.key,
        transport = AiTransport.API
    )

    fun httpApiOpenAi(): AiSettings = AiPresets.openAi().copy(
        sourceKey = AiSourceId.HTTP_API.key,
        transport = AiTransport.API
    )
}
