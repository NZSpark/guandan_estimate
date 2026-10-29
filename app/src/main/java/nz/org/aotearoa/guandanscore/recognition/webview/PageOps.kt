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

    fun pollSnapshot(): AnswerSnapshot

    fun fillAndSubmit(prompt: String, fileName: String, mimeType: String, base64: String): SubmitOutcome

    /** 粘贴路径是否被派发。真实成功仍以 [verifyAttachment] 为准。 */
    fun pasteImage(fileName: String, mimeType: String, base64: String): Boolean

    /** 文件选择路径：写 cache + 回填 URI + 触发上传入口。 */
    fun prepareFileChooserUpload(fileName: String, mimeType: String, bytes: ByteArray): Boolean

    fun verifyAttachment(): Boolean

    fun awaitSubmitStarted(baseline: AnswerSnapshot): Boolean

    fun diagnose(): DiagnoseResult?
}
