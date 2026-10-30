package nz.org.aotearoa.guandanscore.recognition.source

/**
 * 内置 AI 源的稳定标识。
 *
 * `key` 会写进配置 JSON（`source_key`），**一旦发布不可改名**，否则旧配置会回落。
 * 中文 `label` 只用于 UI，可以随时调整。
 *
 * 顺序即配置页下拉顺序；DeepSeek 为首发源，排第一。
 */
enum class AiSourceId(val key: String, val label: String) {
    DEEPSEEK_WEBVIEW("deepseek_web", "DeepSeek 网页版"),
    GEMINI_WEBVIEW("gemini_web", "Gemini 网页版"),
    CHATGPT_WEBVIEW("chatgpt_web", "ChatGPT 网页版"),
    CUSTOM_WEBVIEW("custom_web", "自定义网页源"),
    HTTP_API("http_api", "HTTP API（API Key）");

    val isWebView: Boolean get() = this != HTTP_API

    companion object {
        /** 未识别时回落到 HTTP API：与迁移前的既有行为一致，不静默改变调用路径。 */
        fun fromKey(key: String?): AiSourceId =
            entries.firstOrNull { it.key == key } ?: HTTP_API
    }
}
