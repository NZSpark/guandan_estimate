package nz.org.aotearoa.guandanscore.recognition.webview

import nz.org.aotearoa.guandanscore.recognition.site.AnswerSnapshot
import nz.org.aotearoa.guandanscore.recognition.site.DiagnoseResult
import nz.org.aotearoa.guandanscore.recognition.site.SubmitOutcome

/**
 * 可编排的假页面。用于 [WebViewAiSource] 单测，不依赖 Android。
 *
 * 每个行为都有独立开关，测试只改需要的那一个，其余保持“一切正常”。
 */
class FakePageOps : PageOps {

    // 页面状态
    var loaded = true
    var loggedIn = true
    var attachmentVerified = true
    var pasteDispatched = true
    var fileChooserPrepared = true
    var submitStart: SubmitStart = SubmitStart.STARTED
    var sendEnabled = false

    // 连续调用的返回值序列（用于死文档重试、发送按钮重试等场景）。
    var submitStartSequence: List<SubmitStart> = emptyList()
    var submitOutcomeSequence: List<SubmitOutcome> = emptyList()
    private var submitStartIndex = 0
    private var submitOutcomeIndex = 0

    // 回答序列：每次 pollSnapshot 依次返回，最后一个持续返回。
    var answerSequence: List<AnswerSnapshot> = listOf(AnswerSnapshot(1, "", false, false, ""))
    var baselineSnapshot: AnswerSnapshot = AnswerSnapshot(0, "", false, false, "")

    // 基线序列：每次 captureBaseline 依次返回（用于「脏输入区 → 新会话 → 干净」）。
    var baselineSequence: List<AnswerSnapshot> = emptyList()
    private var baselineIndex = 0

    // AI-046：新会话是否经页面确认成功。
    var startNewConversationResult = true
    var startNewConversationCalls = 0
    // AI-158：纯文本草稿是否可被显式清空（站点支持时）。
    var clearComposerResult = false
    var clearComposerCalls = 0

    // 提交结果
    var submitOutcome: SubmitOutcome = SubmitOutcome(ok = true, why = null, inputFilled = true, attached = true)

    // AI-182：拆分流程（填文本 → 上传 → 提交）。
    var fillComposerResult = true
    var submitComposerOutcome: SubmitOutcome =
        SubmitOutcome(ok = true, why = null, inputFilled = true, attached = true)
    var submitComposerOutcomeSequence: List<SubmitOutcome> = emptyList()
    private var submitComposerIndex = 0

    /** AI-183：页面侧上传失败原因。 */
    var uploadFailure: String? = null

    // 记录
    val calls = mutableListOf<String>()
    var lastPrompt: String? = null
    var lastBase64: String? = null
    var ensureLoadedCalls = 0
    var forcedReloadCalls = 0
    var pasteCalls = 0
    var verifyCalls = 0
    var sendEnabledCalls = 0
    var cleanupCalls = 0

    private var pollIndex = 0

    override fun ensureLoaded(force: Boolean): Boolean {
        ensureLoadedCalls++
        if (force) forcedReloadCalls++
        calls += "ensureLoaded"
        return loaded
    }

    override fun applyPageFix() {
        calls += "applyPageFix"
    }

    override fun isLoggedIn(): Boolean {
        calls += "isLoggedIn"
        return loggedIn
    }

    override fun captureBaseline(): AnswerSnapshot {
        calls += "captureBaseline"
        if (baselineSequence.isEmpty()) return baselineSnapshot
        val s = baselineSequence.getOrElse(baselineIndex) { baselineSequence.last() }
        if (baselineIndex < baselineSequence.size - 1) baselineIndex++
        return s
    }

    override fun startNewConversation(): Boolean {
        calls += "startNewConversation"
        startNewConversationCalls++
        return startNewConversationResult
    }

    override fun clearComposer(): Boolean {
        calls += "clearComposer"
        clearComposerCalls++
        return clearComposerResult
    }

    override fun pollSnapshot(): AnswerSnapshot {
        calls += "pollSnapshot"
        val s = answerSequence.getOrElse(pollIndex) { answerSequence.last() }
        if (pollIndex < answerSequence.size - 1) pollIndex++
        return s
    }

    override fun fillAndSubmit(
        prompt: String,
        fileName: String,
        mimeType: String,
        base64: String
    ): SubmitOutcome {
        calls += "fillAndSubmit"
        lastPrompt = prompt
        lastBase64 = base64
        if (submitOutcomeSequence.isEmpty()) return submitOutcome
        val outcome = submitOutcomeSequence.getOrElse(submitOutcomeIndex) { submitOutcomeSequence.last() }
        if (submitOutcomeIndex < submitOutcomeSequence.size - 1) submitOutcomeIndex++
        return outcome
    }

    override fun fillComposer(prompt: String): Boolean {
        calls += "fillComposer"
        lastPrompt = prompt
        return fillComposerResult
    }

    override fun submitComposer(): SubmitOutcome {
        calls += "submitComposer"
        if (submitComposerOutcomeSequence.isEmpty()) return submitComposerOutcome
        val outcome = submitComposerOutcomeSequence.getOrElse(submitComposerIndex) {
            submitComposerOutcomeSequence.last()
        }
        if (submitComposerIndex < submitComposerOutcomeSequence.size - 1) submitComposerIndex++
        return outcome
    }

    override fun lastUploadFailure(): String? = uploadFailure

    override fun pasteImage(fileName: String, mimeType: String, base64: String): Boolean {
        pasteCalls++
        calls += "pasteImage"
        return pasteDispatched
    }

    override fun prepareFileChooserUpload(fileName: String, mimeType: String, bytes: ByteArray): Boolean {
        calls += "prepareFileChooserUpload"
        return fileChooserPrepared
    }

    override fun verifyAttachment(): Boolean {
        verifyCalls++
        calls += "verifyAttachment"
        return attachmentVerified
    }

    override fun cleanupUploads() {
        cleanupCalls++
        calls += "cleanupUploads"
    }

    override fun awaitSubmitStarted(baseline: AnswerSnapshot): SubmitStart {
        calls += "awaitSubmitStarted"
        if (submitStartSequence.isEmpty()) return submitStart
        val state = submitStartSequence.getOrElse(submitStartIndex) { submitStartSequence.last() }
        if (submitStartIndex < submitStartSequence.size - 1) submitStartIndex++
        return state
    }

    override fun awaitSendEnabled(timeoutMillis: Long): Boolean {
        calls += "awaitSendEnabled"
        sendEnabledCalls++
        return sendEnabled
    }

    override fun diagnose(): DiagnoseResult? = DiagnoseResult(
        href = "https://example.invalid/chat",
        readyState = "complete",
        innerHeight = 800,
        inputCount = 1,
        sendCount = 1,
        responseCount = 1,
        fileInputCount = 1,
        loginIndicatorCount = 0,
        generatingCount = 0
    )
}
