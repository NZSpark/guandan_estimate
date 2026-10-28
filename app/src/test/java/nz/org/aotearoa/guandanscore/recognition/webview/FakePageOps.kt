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
    var submitStarted = true

    // 回答序列：每次 pollSnapshot 依次返回，最后一个持续返回。
    var answerSequence: List<AnswerSnapshot> = listOf(AnswerSnapshot(1, "", false, false, ""))
    var baselineSnapshot: AnswerSnapshot = AnswerSnapshot(0, "", false, false, "")

    // 提交结果
    var submitOutcome: SubmitOutcome = SubmitOutcome(ok = true, why = null, inputFilled = true, attached = true)

    // 记录
    val calls = mutableListOf<String>()
    var lastPrompt: String? = null
    var lastBase64: String? = null
    var ensureLoadedCalls = 0
    var pasteCalls = 0
    var verifyCalls = 0

    private var pollIndex = 0

    override fun ensureLoaded(force: Boolean): Boolean {
        ensureLoadedCalls++
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
        return baselineSnapshot
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
        return submitOutcome
    }

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

    override fun awaitSubmitStarted(baseline: AnswerSnapshot): Boolean {
        calls += "awaitSubmitStarted"
        return submitStarted
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
