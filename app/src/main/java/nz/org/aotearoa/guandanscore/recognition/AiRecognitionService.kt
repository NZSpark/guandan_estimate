package nz.org.aotearoa.guandanscore.recognition

import nz.org.aotearoa.guandanscore.model.Card
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 网页识牌的业务统一入口：互斥、状态机、取消、错误映射。
 *
 * 不直接依赖 WebView；通过 [GeminiUpstream] 隔离。所有页面操作由上游在其主线程执行，
 * 本类只负责顺序编排，因此可用 Fake 上游完整单测。
 */
class AiRecognitionService(
    private val upstream: GeminiUpstream,
    private val parser: AiCardResponseParser = AiCardResponseParser(),
    private val watcher: GenerationWatcher = GenerationWatcher(),
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val sleepMillis: (Long) -> Unit = { Thread.sleep(it) },
    private val pollIntervalMillis: Long = 500
) {

    sealed interface Result {
        data class Success(val cards: List<Card>, val warnings: List<String>) : Result
        data class Failure(val kind: Kind, val message: String) : Result
    }

    enum class Kind { NOT_READY, NOT_LOGGED_IN, UPLOAD_FAILED, SUBMIT_FAILED, TIMEOUT, PARSE_FAILED, CANCELLED, BUSY }

    private val running = AtomicBoolean(false)
    @Volatile private var cancelled = false

    /** 便于 UI 在任务开始前判断是否能进入。 */
    val isBusy: Boolean get() = running.get()

    /** 请求取消当前任务；迟到的回调不应覆盖 UI。 */
    fun cancel() { cancelled = true }

    /**
     * 执行一次完整识牌。
     *
     * 状态机：EnsurePageReady → EnsureLoggedIn → CaptureReplyBaseline → AttachImage
     *        → VerifyAttachment → FillPrompt → Submit → WaitForNewAnswer → ParseCards
     */
    fun recognize(jpegBytes: ByteArray, prompt: String = AiCardResponseParser.PROMPT): Result {
        if (!running.compareAndSet(false, true)) {
            return Result.Failure(Kind.BUSY, "已有识牌任务正在进行")
        }
        cancelled = false
        try {
            return runPipeline(jpegBytes, prompt)
        } finally {
            running.set(false)
        }
    }

    private fun runPipeline(jpegBytes: ByteArray, prompt: String): Result {
        when (val status = upstream.checkReady()) {
            GeminiUpstream.Status.Ready -> Unit
            GeminiUpstream.Status.NotLoggedIn ->
                return Result.Failure(Kind.NOT_LOGGED_IN, "请在 Gemini 页面重新登录")
            is GeminiUpstream.Status.Failed ->
                return Result.Failure(Kind.NOT_READY, status.reason)
            else -> return Result.Failure(Kind.NOT_READY, "Gemini 页面尚未就绪（$status）")
        }
        if (cancelled) return cancelledResult()

        val base = upstream.captureBaseline()
        watcher.reset(GenerationWatcher.Baseline(base.responseCount, base.lastAnswerText), nowMillis())

        if (!upstream.attachImage(jpegBytes)) {
            return Result.Failure(Kind.UPLOAD_FAILED, "图片未能附加到 Gemini 输入框")
        }
        if (cancelled) return cancelledResult()

        if (!upstream.fillPrompt(prompt)) {
            return Result.Failure(Kind.SUBMIT_FAILED, "无法把识牌指令写入输入框")
        }
        if (!upstream.submit()) {
            return Result.Failure(Kind.SUBMIT_FAILED, "发送按钮不可用或点击失败")
        }

        val answer = awaitAnswer() ?: return when {
            cancelled -> cancelledResult()
            else -> Result.Failure(Kind.TIMEOUT, "等待 Gemini 回答超时")
        }

        val outcome = parser.parseDetailed(answer)
        return if (outcome.cards.isEmpty()) {
            Result.Failure(Kind.PARSE_FAILED, outcome.warnings.lastOrNull() ?: "未能解析出有效牌面")
        } else {
            Result.Success(outcome.cards, outcome.warnings)
        }
    }

    /** 轮询到生成结束，返回最终回答文本；超时或取消返回 null。 */
    private fun awaitAnswer(): String? {
        while (true) {
            if (cancelled) return null
            val s = upstream.snapshot()
            val decision = watcher.onSample(
                GenerationWatcher.Sample(
                    responseCount = s.responseCount,
                    answerText = s.lastAnswerText,
                    generating = s.generating,
                    timestampMillis = nowMillis()
                )
            )
            when (decision) {
                GenerationWatcher.Decision.Finished -> return s.lastAnswerText.trim()
                is GenerationWatcher.Decision.Failed -> return null
                GenerationWatcher.Decision.Waiting -> sleepMillis(pollIntervalMillis)
            }
        }
    }

    private fun cancelledResult() = Result.Failure(Kind.CANCELLED, "识牌已取消")
}
