package nz.org.aotearoa.guandanscore.recognition.source

import android.content.Context
import android.util.Log
import nz.org.aotearoa.guandanscore.recognition.AiSettings
import nz.org.aotearoa.guandanscore.recognition.AiTransport
import nz.org.aotearoa.guandanscore.recognition.site.ChatGPTAdapter
import nz.org.aotearoa.guandanscore.recognition.site.CustomAdapter
import nz.org.aotearoa.guandanscore.recognition.site.DeepSeekAdapter
import nz.org.aotearoa.guandanscore.recognition.site.GeminiAdapter
import nz.org.aotearoa.guandanscore.recognition.site.SiteAdapter
import nz.org.aotearoa.guandanscore.recognition.webview.WebViewAiSource
import nz.org.aotearoa.guandanscore.recognition.webview.WebViewBridge
import nz.org.aotearoa.guandanscore.recognition.webview.WebViewHost

/**
 * 由配置构造实际可用的 [AiSource]。
 *
 * 这里是**唯一**允许做 sourceKey → 具体实现映射的地方。
 * 新增站点 = 新增 [SiteAdapter] 实现 + 在 [adapterFor] 加一行，驱动层不动。
 */
object AiSourceRegistry {

    private const val TAG = "AiSourceRegistry"

    /** 内置预设，顺序即配置页顺序（DeepSeek 首发）。 */
    fun presets(): List<AiSettings> = AiSourcePresets.presets()

    /** 只有网页源需要的适配器。HTTP API 源没有站点。 */
    fun adapterFor(settings: AiSettings): SiteAdapter? = when (settings.source) {
        AiSourceId.DEEPSEEK_WEBVIEW -> DeepSeekAdapter()
        AiSourceId.GEMINI_WEBVIEW -> GeminiAdapter()
        AiSourceId.CHATGPT_WEBVIEW -> ChatGPTAdapter()
        AiSourceId.CUSTOM_WEBVIEW -> CustomAdapter(settings.homeUrl)
        AiSourceId.HTTP_API -> null
    }

    /**
     * 构造源。
     *
     * @param host 网页源需要；HTTP API 源可为 null（避免无谓创建 WebView）。
     */
    fun create(
        settings: AiSettings,
        host: WebViewHost? = null,
        context: Context? = null,
        bridgeFactory: (WebViewHost) -> WebViewBridge = { WebViewBridge(it) }
    ): AiSource {
        val source = settings.source
        if (settings.sourceKey != source.key) {
            Log.w(TAG, "unknown sourceKey='${settings.sourceKey}', fell back to ${source.key}")
        }
        if (settings.transport == AiTransport.API || source == AiSourceId.HTTP_API) {
            return HttpApiSource(settings)
        }
        val adapter = adapterFor(settings) ?: return HttpApiSource(settings)
        val actualHost = host ?: requireNotNull(context) {
            "网页源需要 WebViewHost；请传入 host 或 context"
        }.let { WebViewHost.get(it) }
        return WebViewAiSource(
            adapter = adapter,
            host = actualHost,
            bridge = bridgeFactory(actualHost)
        )
    }
}
