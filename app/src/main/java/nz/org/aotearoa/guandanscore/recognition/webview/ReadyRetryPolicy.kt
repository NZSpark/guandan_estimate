package nz.org.aotearoa.guandanscore.recognition.webview

/**
 * AI-034 / AI-035：就绪等待的重试策略。
 *
 * 抽成纯函数的原因：`PageDriver.awaitReady()` 依赖具体 WebView 宿主，无法在 JVM 单测里
 * 跑完整循环；而「退避多久」「什么时候放弃」「什么时候允许 reload」恰恰是最容易写错、
 * 也最需要被钉住的部分。策略集中在这里，驱动层只负责调用。
 */
internal object ReadyRetryPolicy {

    /** 首次轮询间隔（毫秒）。页面刚 commit 时密集轮询有效。 */
    const val FIRST_DELAY_MILLIS = 300L

    /** 退避上限（毫秒）。超过后不再继续放大，避免长等待期间几乎不再探测。 */
    const val MAX_DELAY_MILLIS = 1_200L

    /** 就绪等待期间允许的最大 reload 次数。防止「选择器缺失 → reload → 仍缺失」空转。 */
    const val MAX_RELOADS = 1

    /** 下一次轮询间隔：×1.5 退避，封顶 [MAX_DELAY_MILLIS]。 */
    fun nextDelay(currentMillis: Long): Long =
        (currentMillis * 3 / 2).coerceIn(FIRST_DELAY_MILLIS, MAX_DELAY_MILLIS)

    /**
     * 本次是否允许发起 reload。
     *
     * 三条例外都必须拦住：
     * - 业务生成中（reload 会丢弃进行中的请求与回答）
     * - 导航刚发出（SPA 正在初始化，reload 会打断首屏加载）
     * - 已达重载次数上限
     */
    fun canReload(reloadsDone: Int, generating: Boolean, justNavigated: Boolean): Boolean =
        !generating && !justNavigated && reloadsDone < MAX_RELOADS

    /** reload 被跳过的原因，用于日志；允许 reload 时返回 null。 */
    fun reloadSkipReason(reloadsDone: Int, generating: Boolean, justNavigated: Boolean): String? = when {
        generating -> "business-task-running"
        justNavigated -> "just-navigated"
        reloadsDone >= MAX_RELOADS -> "reload-limit-reached"
        else -> null
    }
}
