package nz.org.aotearoa.guandanscore.recognition.webview

import nz.org.aotearoa.guandanscore.recognition.site.AnswerSnapshot

/**
 * AI-174：提交是否已开始的纯判定。
 *
 * 抽成纯函数的原因：判定规则（新节点 / 文本变化 / 生成中 / 进入会话页 / 死文档）
 * 必须能用单测穷举，而 `PageDriver` 依赖 WebView，无法在 JVM 里实例化。
 *
 * 判定顺序不重要——任何一条正面证据成立即为已开始；只有「6s 内 URL 与 DOM
 * 完全没动」才判死文档，避免把慢页面误杀。
 */
object SubmitProgress {

    /** 判定死文档的观察窗口：6s 内 URL 与 DOM 均无变化。 */
    const val STALE_AFTER_MILLIS = 6_000L

    /**
     * 是否已有提交开始的正面证据。
     *
     * @param sessionUrlReached 当前 URL 已进入会话页（由适配器判定，如 `/a/chat/s/`）
     */
    fun started(
        snapshot: AnswerSnapshot,
        baseline: AnswerSnapshot,
        sessionUrlReached: Boolean
    ): Boolean {
        if (sessionUrlReached) return true
        if (snapshot.generating) return true
        if (snapshot.responseCount > baseline.responseCount) return true
        return snapshot.lastAnswerText.isNotBlank() &&
            snapshot.lastAnswerText != baseline.lastAnswerText
    }

    /**
     * 是否应判为死文档。
     *
     * @param urlChanged 观察窗口内 URL 变过
     * @param domChanged 观察窗口内回答节点数或文本变过
     */
    fun stale(elapsedMillis: Long, urlChanged: Boolean, domChanged: Boolean): Boolean =
        elapsedMillis >= STALE_AFTER_MILLIS && !urlChanged && !domChanged
}
