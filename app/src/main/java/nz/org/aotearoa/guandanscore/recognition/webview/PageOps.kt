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
