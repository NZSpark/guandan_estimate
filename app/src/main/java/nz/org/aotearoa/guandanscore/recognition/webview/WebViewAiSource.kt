package nz.org.aotearoa.guandanscore.recognition.webview

import android.util.Base64
import android.util.Log
import nz.org.aotearoa.guandanscore.model.Card
import nz.org.aotearoa.guandanscore.recognition.AiCardResponseParser
import nz.org.aotearoa.guandanscore.recognition.GenerationWatcher
import nz.org.aotearoa.guandanscore.recognition.site.AnswerSnapshot
import nz.org.aotearoa.guandanscore.recognition.site.SiteAdapter
import nz.org.aotearoa.guandanscore.recognition.site.SubmitOutcome
import nz.org.aotearoa.guandanscore.recognition.site.UploadStrategy
import nz.org.aotearoa.guandanscore.recognition.source.AiSource
import nz.org.aotearoa.guandanscore.recognition.source.AiSourceError
import nz.org.aotearoa.guandanscore.recognition.source.RecognizeRequest
import nz.org.aotearoa.guandanscore.recognition.source.SourceHealth
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 网页 AI 源驱动层。站点无关：所有站点知识来自 [SiteAdapter]。
 *
 * 状态机：
 * EnsurePageReady → EnsureLoggedIn → CaptureBaseline
 *   → Upload（首选 + 回退） → FillAndSubmit → VerifyAttachment → WaitForNewAnswer → Parse
 *
 * 硬规则：
 * - 上传未确认前不提交（AI-123 / M11）。
 * - 上传首选失败后回退另一条策略（AI-124）。
 * - 回答必须有基线，避免追问拿到上一轮（M5）。
 * - 生成中不跑健康探测（M8）。
 *
 * 依赖 [PageOps] 而非具体 [PageDriver]，因此可用 Fake 完整单测（AI-126）。
 */
class WebViewAiSource(
    private val adapter: SiteAdapter,
    private val pages: PageOps,
    private val displayNameOverride: String? = null,
    private val parser: AiCardResponseParser = AiCardResponseParser(),
    private val watcherFactory: () -> GenerationWatcher = { GenerationWatcher() },
    private val pollIntervalMillis: Long = 500,
    private val timeoutMillis: Long = 120_000,
    private val sleepMillis: (Long) -> Unit = { Thread.sleep(it) },
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    /** Base64 编码器。抽成参数以便单测不依赖 android.util.Base64。 */
    private val base64Encoder: (ByteArray) -> String = { Base64.encodeToString(it, Base64.NO_WRAP) }
) : AiSource {

    /** 生产构造：由宿主 + 桥构造真实驱动。 */
    constructor(
        adapter: SiteAdapter,
        host: WebViewHost,
        bridge: WebViewBridge,
        parser: AiCardResponseParser = AiCardResponseParser(),
        watcherFactory: () -> GenerationWatcher = { GenerationWatcher() },
        pollIntervalMillis: Long = 500,
        timeoutMillis: Long = 120_000
    ) : this(
        adapter = adapter,
        pages = PageDriver(adapter, host, bridge),
        displayNameOverride = null,
        parser = parser,
        watcherFactory = watcherFactory,
        pollIntervalMillis = pollIntervalMillis,
        timeoutMillis = timeoutMillis
    )

    private companion object {
        const val TAG = "WebViewAiSource"

        /** AI-042：等上一轮生成的宽限；超时后改用确认过的新会话。 */
        const val GENERATION_DRAIN_TIMEOUT_MILLIS = 15_000L
    }

    override val id: String = adapter.siteKey
    override val displayName: String = displayNameOverride ?: adapter.siteKey
    override val requiresNetworkConfig: Boolean = false
    override val requiresWebLogin: Boolean = true

    private val running = AtomicBoolean(false)

    @Volatile
    private var cancelled = false

    /** 生成中标志：健康探测此时必须直接返回缓存（M8）。 */
    @Volatile
    private var generating = false

    @Volatile
    private var cachedHealth: SourceHealth = SourceHealth.Unavailable("尚未检查")

    override fun healthCheck(): SourceHealth {
        if (generating) return cachedHealth
        val health = try {
            // AI-033：先做只读分类，避免「把选择器失败报成未登录、把未登录报成未就绪」。
            // pageState() 不导航；不支持只读诊断的实现返回 null，落到旧的探测路径。
            val state = pages.pageState()
            when {
                state == null -> legacyHealthProbe()
                state == PageState.READY -> SourceHealth.Ready
                // 账号问题：需要用户去网页里登录，重试无用。
                state == PageState.LOGIN_REQUIRED ->
                    SourceHealth.NotLoggedIn("登录已失效，请在页面中重新登录")
                // 以下是页面/宿主问题，绝不能报成未登录。
                state == PageState.LOADING ->
                    SourceHealth.Unavailable(PageStateClassifier.reason(state, pages.diagnose()))
                state == PageState.COMPOSER_MISSING ->
                    SourceHealth.Unavailable(PageStateClassifier.reason(state, pages.diagnose()))
                state == PageState.VIEWPORT_UNHEALTHY ->
                    SourceHealth.Unavailable(PageStateClassifier.reason(state, pages.diagnose()))
                // AI-036：宿主没挂接是宿主问题，既不是未登录也不是「页面未就绪」。
                // 必须给出可执行提示，而不是走 legacyHealthProbe 报一句笼统失败。
                state == PageState.HOST_NOT_ATTACHED ->
                    SourceHealth.Unavailable(PageStateClassifier.reason(state, null))
                else -> SourceHealth.Unavailable("未知页面状态")
            }
        } catch (e: AiSourceError) {
            SourceHealth.Unavailable(e.message ?: "未知错误")
        } catch (e: Exception) {
            SourceHealth.Unavailable(e.message ?: "未知错误")
        }
        cachedHealth = health
        return health
    }

    /**
     * 不支持只读诊断时的旧探测路径，保留以保证实现方零改动可用。
     *
     * 注意其中的顺序：先确认页面能加载，再判断登录，最后才 Ready。
     */
    private fun legacyHealthProbe(): SourceHealth = when {
        !pages.ensureLoaded() -> SourceHealth.Unavailable("页面未就绪")
        !pages.isLoggedIn() -> SourceHealth.NotLoggedIn("请在页面中手动登录")
        else -> SourceHealth.Ready
    }

    override fun recognize(request: RecognizeRequest): List<Card> {
        if (!running.compareAndSet(false, true)) throw AiSourceError.Busy()
        cancelled = false
        generating = true
        // AI-035：业务任务期间禁止自动 reload，否则会把正在生成的请求直接丢掉。
        pages.setReloadSuspended(true)
        try {
            return runPipeline(request)
        } finally {
            pages.setReloadSuspended(false)
            // AI-037：临时 JPEG 在成功、失败、取消后都不留残。
            runCatching { pages.cleanupUploads() }
            generating = false
            running.set(false)
        }
    }

    private fun runPipeline(request: RecognizeRequest): List<Card> {
        if (cancelled) throw AiSourceError.Cancelled()

        if (!pages.ensureLoaded()) {
            // AI-031: 带上分类给出的具体原因（登录失效 / 选择器缺失 / 视口异常），
            // 上层据此提示用户，而不是统一报「未就绪」让人无从下手。
            val why = pages.lastFailureReason()
            throw AiSourceError.NotReady(
                if (why.isNullOrBlank()) "页面在超时内未就绪（输入框或视口不可用）" else why
            )
        }
        pages.applyPageFix()

        if (!pages.isLoggedIn()) {
            throw AiSourceError.NotLoggedIn("请在页面中手动登录后重试")
        }
        val base64 = base64Encoder(request.jpegBytes)

        // AI-174：死文档只允许重载一次，且不占用上层的重试次数。
        var staleRetried = false
        while (true) {
            if (cancelled) throw AiSourceError.Cancelled()

            // AI-042 / AI-082：先确认输入区干净、上一轮已结束，再为新回答建基线。
            val baseline = prepareBaseline(pages.captureBaseline())
            val watcher = watcherFactory()
            watcher.reset(
                GenerationWatcher.Baseline(baseline.responseCount, baseline.lastAnswerText),
                nowMillis()
            )

            if (adapter.uploadsAfterFill) {
                // AI-182：填文本 → 上传并在提交前确认 → 点发送。
                // 顺序来自网页版图片直传方案：只有文本已在框内、附件挂上并确认后，
                // 才能提交同一条消息。
                if (!pages.fillComposer(request.prompt)) {
                    throw AiSourceError.SubmitFailed("输入框未确认填入")
                }
                val uploadError = uploadWithFallback(request, base64)
                if (uploadError != null) throw AiSourceError.UploadFailed(uploadError)
                if (cancelled) throw AiSourceError.Cancelled()
                submitAfterFill()
            } else {
                val uploadError = if (adapter.uploadsInsideSubmit) null else uploadWithFallback(request, base64)
                if (uploadError != null) throw AiSourceError.UploadFailed(uploadError)
                if (cancelled) throw AiSourceError.Cancelled()

                // AI-173：提交失败但文本已填入 → 等发送按钮可用后重填一次。
                submitWithSendEnabledRetry(request, base64)

                // 上传由提交脚本内联完成（`uploadsInsideSubmit`）：提交后仍要确认预览真的出现。
                if (adapter.uploadsInsideSubmit && !pages.verifyAttachment()) {
                    throw AiSourceError.UploadFailed("提交后未确认附件预览")
                }
            }
            if (cancelled) throw AiSourceError.Cancelled()

            when (pages.awaitSubmitStarted(baseline)) {
                SubmitStart.STARTED -> {
                    val answer = awaitAnswer(watcher) ?: throw if (cancelled) {
                        AiSourceError.Cancelled()
                    } else {
                        AiSourceError.Timeout(timeoutMillis)
                    }
                    val parsed = parser.parseDetailed(answer)
                    if (parsed.cards.isEmpty()) {
                        throw AiSourceError.ParseFailed(
                            parsed.warnings.lastOrNull() ?: "未能解析出有效牌面"
                        )
                    }
                    return parsed.cards
                }

                SubmitStart.NOT_STARTED ->
                    throw AiSourceError.SubmitFailed("提交后未见新回答节点或文本变化")

                SubmitStart.STALE_PAGE -> {
                    if (staleRetried) {
                        throw AiSourceError.SubmitFailed("页面无响应（已重载一次仍无变化）")
                    }
                    staleRetried = true
                    Log.w(TAG, "submit stale: reloading home once and retrying")
                    if (!pages.ensureLoaded(force = true)) {
                        val why = pages.lastFailureReason()
                        throw AiSourceError.NotReady(
                            if (why.isNullOrBlank()) "页面无响应且重载后未就绪" else why
                        )
                    }
                    pages.applyPageFix()
                    if (!pages.isLoggedIn()) {
                        throw AiSourceError.NotLoggedIn("请在页面中手动登录后重试")
                    }
                }
            }
        }
    }

    /**
     * AI-042 / AI-082：把“页面现状”变成可以安全提交的基线。
     *
     * 三种必须拦在前面的脏状态：
     * 1. 上一轮还在生成：继续提交会把两轮回答混在一起，先等结束；
     * 2. 输入框有残留文本：本轮 prompt 会接在残留后面一起发出去；
     * 3. 输入区已有附件预览：图片 A 会跟着图片 B 一起提交，模型看到的不是本轮图。
     *
     * 2/3 首选处置是切换到**经 DOM 确认**的新会话（[PageOps.startNewConversation]）。
     * 只有站点会恢复未发送草稿、新会话确认失败时，才允许对**纯文本**残留调用
     * [PageOps.clearComposer] 并用快照复验；附件残留绝不手动清，必须新建会话。
     */
    private fun prepareBaseline(initial: AnswerSnapshot): AnswerSnapshot {
        var base = initial
        if (base.generating) {
            Log.i(TAG, "page still generating; waiting before a new request")
            val deadline = nowMillis() + GENERATION_DRAIN_TIMEOUT_MILLIS
            while (base.generating && nowMillis() < deadline) {
                if (cancelled) throw AiSourceError.Cancelled()
                sleepMillis(pollIntervalMillis)
                base = pages.pollSnapshot()
            }
            if (base.generating) {
                Log.w(TAG, "generation did not settle; starting a confirmed new conversation")
                if (!pages.startNewConversation()) throw AiSourceError.Busy()
                base = pages.captureBaseline()
            }
        }
        if (base.composerDirty) {
            Log.w(
                TAG,
                "composer dirty (inputChars=${base.inputText.length} attached=${base.attached}); " +
                    "starting a confirmed new conversation"
            )
            if (!pages.startNewConversation()) {
                // AI-158：部分站点会把未发送文本恢复成草稿（真机实测），新建会话也清不掉。
                // 只有「纯文字残留」才允许显式清空；附件一律不手动清，必须靠新会话。
                if (!base.attached && base.inputText.isNotBlank() && pages.clearComposer()) {
                    Log.i(TAG, "new conversation unconfirmed; cleared text draft, re-reading baseline")
                    base = pages.captureBaseline()
                } else {
                    throw AiSourceError.SubmitFailed("输入框有残留内容或附件，且无法确认新会话已建立")
                }
            } else {
                base = pages.captureBaseline()
            }
        }
        if (base.composerDirty) {
            throw AiSourceError.SubmitFailed("新会话仍不干净（输入框残留或附件未清除）")
        }
        return base
    }

    /**
     * AI-173：提交一次；失败且文本已填入时，等发送按钮可用后重填一次。
     *
     * 第二次仍失败则抛 [AiSourceError.SubmitFailed]——绝不对 disabled 按钮
     * 盲点后当成成功。
     */
    private fun submitWithSendEnabledRetry(request: RecognizeRequest, base64: String): SubmitOutcome {
        var outcome = pages.fillAndSubmit(request.prompt, request.fileName, request.mimeType, base64)
        if (!outcome.ok && outcome.inputFilled) {
            val enabled = pages.awaitSendEnabled()
            Log.i(TAG, "submit retry gate: sendEnabled=$enabled why=${outcome.why}")
            if (enabled) {
                Log.i(TAG, "send button became usable; retrying submit once")
                outcome = pages.fillAndSubmit(request.prompt, request.fileName, request.mimeType, base64)
            }
        }
        if (!outcome.ok) throw AiSourceError.SubmitFailed(outcome.why ?: "未知原因")
        return outcome
    }

    /**
     * 上传：按适配器首选策略，失败后换另一条。
     * @return null 表示成功；非 null 是包含两条策略原因的失败说明。
     */
    private fun uploadWithFallback(request: RecognizeRequest, base64: String): String? {
        // AI-124：只尝试适配器声明「已验证」的策略；首选排在最前。
        val candidates = adapter.supportedUploads
            .sortedBy { if (it == adapter.preferredUpload) 0 else 1 }
        if (candidates.isEmpty()) return "适配器未声明任何已验证的上传策略"
        val reasons = mutableListOf<String>()
        for (strategy in candidates) {
            if (tryUpload(strategy, request, base64)) return null
            // AI-183：把页面给出的具体原因（额度用尽 / 未获得焦点）带出来，
            // 否则用户只看到「未确认附件预览」，无从判断该等额度还是该重登录。
            val why = pages.lastUploadFailure()
            reasons += if (why.isNullOrBlank()) {
                "$strategy 未确认附件预览"
            } else {
                "$strategy 未确认附件预览（$why）"
            }
        }
        return reasons.joinToString("；")
    }

    /**
     * AI-182：附件已确认后的提交。
     *
     * 只重试点击发送，**绝不重填文本**：重填可能覆盖已挂好的附件，
     * 也违背「附件必须在提交前确认」的不变量。
     */
    private fun submitAfterFill(): SubmitOutcome {
        var outcome = pages.submitComposer()
        if (!outcome.ok && outcome.inputFilled) {
            val enabled = pages.awaitSendEnabled()
            Log.i(TAG, "split submit retry gate: sendEnabled=$enabled why=${outcome.why}")
            if (enabled) outcome = pages.submitComposer()
        }
        if (!outcome.ok) throw AiSourceError.SubmitFailed(outcome.why ?: "未知原因")
        return outcome
    }

    private fun tryUpload(strategy: UploadStrategy, request: RecognizeRequest, base64: String): Boolean {
        Log.i(TAG, "trying upload strategy $strategy")
        if (strategy == UploadStrategy.PASTE) {
            // 派发失败直接判本策略失败；派发成功也仍要验证预览（M11）。
            if (!pages.pasteImage(request.fileName, request.mimeType, base64)) {
                Log.w(TAG, "paste event was not dispatched")
                return false
            }
            if (pages.verifyAttachment()) return true
            // AI-158：首屏 SPA 可能重渲染 composer，第一次 paste 被丢弃；
            // 此时预览计数为 0（没有残留），补派发一次不会造成重复附件。
            Log.w(TAG, "paste dispatched but no preview; re-dispatching once")
            if (!pages.pasteImage(request.fileName, request.mimeType, base64)) return false
            return pages.verifyAttachment()
        }
        // AI-182：FILE_CHOOSER 必须先准备缓存文件并原生触摸上传入口，否则
        // WebViewHost.fileChooserHandler 没有可回填的 URI，后续必然等不到预览。
        if (!pages.prepareFileChooserUpload(request.fileName, request.mimeType, request.jpegBytes)) {
            Log.w(TAG, "file chooser upload was not prepared")
            return false
        }
        return pages.verifyAttachment()
    }

    private fun awaitAnswer(watcher: GenerationWatcher): String? {
        while (true) {
            if (cancelled) return null
            val s = pages.pollSnapshot()
            if (s.loginFormPresent) throw AiSourceError.NotLoggedIn("会话已过期")
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

    override fun cancel() {
        cancelled = true
    }

    override fun currentSessionUrl(): String? = runCatching { pages.diagnose()?.href }.getOrNull()
}
