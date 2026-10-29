package nz.org.aotearoa.guandanscore.recognition.webview

import nz.org.aotearoa.guandanscore.recognition.site.AnswerSnapshot
import nz.org.aotearoa.guandanscore.recognition.site.DiagnoseResult
import nz.org.aotearoa.guandanscore.recognition.site.SubmitOutcome

/**
 * 驱动层对页面的全部操作。抽成接口的唯一目的是让 [WebViewAiSource] 可用 Fake 单测，
 * 不引入 WebView / Android 依赖（AI-126）。
 *
 * 站点无关：实现方 [PageDriver] 与假实现 [FakePageOps] 都不得含站点字样。
 *
 * 线程契约（AI-022）：本接口所有方法可在后台线程调用，但实现方必须保证
 * 任何 WebView API（`evaluateJavascript` / `loadUrl` / 视图增删 / 触摸派发）
 * 都切换到主线程执行；调用方不得自行在主线程上阻塞等待。
 */
/**
 * 提交是否已经开始（AI-174 三态）。
 *
 * 布尔值无法区分「还没开始」和「页面已经卡死」：前者可以继续等，后者继续等
 * 只会在 120s 超时后报一句笼统的「没有新回答」。
 */
enum class SubmitStart {
    /** 出现正面证据：新回答节点、文本变化、生成中标志或进入会话页。 */
    STARTED,

    /** 等了完整确认窗口仍无正面证据，但页面确实有变化（仍在处理中）。 */
    NOT_STARTED,

    /** 6s 内 URL 与 DOM 均无任何变化：文档已死，继续等无意义。 */
    STALE_PAGE,
}

interface PageOps {

    @Throws(nz.org.aotearoa.guandanscore.recognition.source.AiSourceError::class)
    fun ensureLoaded(force: Boolean = false): Boolean

    /**
     * 上一次就绪失败的具体原因（AI-031）。
     *
     * [ensureLoaded] 返回 false 只是布尔值，上层没法区分「还在加载」「选择器失效」
     * 「被踢回登录页」「视口塌陷」——处置手段互斥。默认 null 保持既有实现零改动。
     */
    fun lastFailureReason(): String? = null

    /**
     * AI-033：只读当前页面状态，不导航、不重载、不点击。
     *
     * [lastFailureReason] 只在 [ensureLoaded] 失败后才有值；健康检查需要在不触发
     * 导航的前提下拿到「登录失效 / 选择器缺失 / 视口异常」的区分，所以单独开这个读数口。
     * 返回 null 表示实现方不支持只读诊断。
     */
    fun pageState(): PageState? = null

    /**
     * AI-035：暂停/恢复自动 reload。业务任务运行期间必须暂停，
     * 否则恢复逻辑会把正在生成的请求直接 reload 掉。默认无操作。
     */
    fun setReloadSuspended(suspended: Boolean) = Unit

    fun applyPageFix()

    fun isLoggedIn(): Boolean

    fun captureBaseline(): AnswerSnapshot

    /**
     * AI-046：建立一个**经 DOM 确认**的新会话。
     *
     * 这是页面操作 + 状态确认的组合：真正导航到站点首页（站点自身的 new chat），
     * 再用快照验证「回答节点为 0、输入区干净」。仅拼接会话 URL 或看到导航完成
     * 都不算成功——SPA 可能把旧会话原样恢复。
     *
     * 返回 false 表示无法确认；调用方不得把它当成“新会话”继续提交。
     * 默认不支持，保持既有实现零改动。
     */
    fun startNewConversation(): Boolean = false

    fun pollSnapshot(): AnswerSnapshot

    fun fillAndSubmit(prompt: String, fileName: String, mimeType: String, base64: String): SubmitOutcome

    /** 粘贴路径是否被派发。真实成功仍以 [verifyAttachment] 为准。 */
    fun pasteImage(fileName: String, mimeType: String, base64: String): Boolean

    /** 文件选择路径：写 cache + 回填 URI + 触发上传入口。 */
    fun prepareFileChooserUpload(fileName: String, mimeType: String, bytes: ByteArray): Boolean

    fun verifyAttachment(): Boolean

    /**
     * AI-037：清理上传用的临时文件。
     *
     * 必须在成功、失败、取消三种结束路径都被调用；默认无操作以保持既有实现零改动。
     */
    fun cleanupUploads() = Unit

    fun awaitSubmitStarted(baseline: AnswerSnapshot): SubmitStart

    /**
     * AI-173：点击前轮询发送按钮是否可用。
     *
     * 返回 false 表示超时或实现方不支持；调用方据此重新填文本，
     * **不得**对 disabled 按钮点击并当作提交成功。
     */
    fun awaitSendEnabled(timeoutMillis: Long = 5_000L): Boolean = false

    fun diagnose(): DiagnoseResult?
}
