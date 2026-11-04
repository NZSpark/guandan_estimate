package nz.org.aotearoa.guandanscore.recognition.webview

import nz.org.aotearoa.guandanscore.recognition.site.AnswerSnapshot
import nz.org.aotearoa.guandanscore.recognition.site.SubmitOutcome
import nz.org.aotearoa.guandanscore.recognition.site.UploadStrategy
import nz.org.aotearoa.guandanscore.recognition.source.AiSourceError
import nz.org.aotearoa.guandanscore.recognition.source.RecognizeRequest
import nz.org.aotearoa.guandanscore.recognition.source.SourceHealth
import org.junit.Assert.*
import org.junit.Test

/** [WebViewAiSource] 的状态机单测。全部使用 Fake，不触碰 WebView（AI-126）。 */
class WebViewAiSourceTest {

    private val cardsJson = """[{"rank":"A","suit":"SPADE"},{"rank":"K","suit":"HEART"}]"""

    private fun request() = RecognizeRequest(
        jpegBytes = byteArrayOf(1, 2, 3),
        prompt = "请识别手牌"
    )

    private fun source(
        pages: PageOps,
        adapter: FakeSiteAdapter = FakeSiteAdapter()
    ) = WebViewAiSource(
        adapter = adapter,
        pages = pages,
        // 真实时钟 + 真实 sleep：GenerationWatcher 的“静默 ≥1.5s”判据必须有时间推进。
        pollIntervalMillis = 10,
        sleepMillis = { Thread.sleep(it) },
        timeoutMillis = 15_000,
        base64Encoder = { "QUJD" }
    )

    /** 一个始终成功的 PageOps 骨架，测试只覆写关心的那一项。 */
    private fun okPages(
        answer: AnswerSnapshot,
        onPoll: (() -> Unit)? = null
    ): PageOps = object : PageOps {
        override fun ensureLoaded(force: Boolean) = true
        override fun applyPageFix() = Unit
        override fun isLoggedIn() = true
        override fun captureBaseline() = AnswerSnapshot(0, "", false, false, "")
        override fun pollSnapshot(): AnswerSnapshot {
            onPoll?.invoke()
            return answer
        }
        override fun fillAndSubmit(prompt: String, fileName: String, mimeType: String, base64: String) =
            SubmitOutcome(ok = true, why = null, inputFilled = true, attached = true)
        override fun pasteImage(fileName: String, mimeType: String, base64: String) = true
        override fun prepareFileChooserUpload(fileName: String, mimeType: String, bytes: ByteArray) = true
        override fun verifyAttachment() = true
        override fun awaitSubmitStarted(baseline: AnswerSnapshot) = SubmitStart.STARTED
        override fun diagnose() = null
    }

    /** 正常闭环：上传确认 → 提交 → 新回答 → 解析出牌。 */
    @Test
    fun happyPathParsesCards() {
        val pages = FakePageOps().apply {
            baselineSnapshot = AnswerSnapshot(0, "", false, false, "")
            answerSequence = listOf(AnswerSnapshot(1, cardsJson, false, false, ""))
        }
        val result = source(pages).recognize(request())
        assertEquals(2, result.size)
        assertTrue(pages.calls.indexOf("captureBaseline") < pages.calls.indexOf("verifyAttachment"))
        assertTrue(pages.calls.indexOf("verifyAttachment") < pages.calls.indexOf("fillAndSubmit"))
    }

    /**
     * AI-182：填文本 → 上传并在**提交前**确认附件 → 点发送。
     * 顺序来自 GeminiBridge / ChatGPTBridge 的图片直传文档。
     */
    @Test
    fun splitFlowVerifiesAttachmentBeforeSubmit() {
        val pages = FakePageOps().apply {
            answerSequence = listOf(AnswerSnapshot(1, cardsJson, false, false, ""))
        }
        val result = source(pages, FakeSiteAdapter(uploadsAfterFill = true)).recognize(request())
        assertEquals(2, result.size)
        assertEquals(1, pages.calls.count { it == "fillComposer" })
        assertEquals(1, pages.calls.count { it == "submitComposer" })
        assertFalse(pages.calls.contains("fillAndSubmit"))
        assertTrue(pages.calls.indexOf("fillComposer") < pages.calls.indexOf("verifyAttachment"))
        assertTrue(pages.calls.indexOf("verifyAttachment") < pages.calls.indexOf("submitComposer"))
    }

    /** AI-182：附件确认失败时绝不允许提交。 */
    @Test
    fun splitFlowUploadFailureNeverSubmits() {
        val pages = FakePageOps().apply {
            attachmentVerified = false
            pasteDispatched = false
        }
        assertThrows(AiSourceError.UploadFailed::class.java) {
            source(pages, FakeSiteAdapter(uploadsAfterFill = true)).recognize(request())
        }
        assertFalse(pages.calls.contains("submitComposer"))
    }

    /** AI-182：发送按钮延迟可用时只重试点击，不重填文本（重填会覆盖附件）。 */
    @Test
    fun splitSubmitRetriesWithoutRefilling() {
        val pages = FakePageOps().apply {
            submitComposerOutcomeSequence = listOf(
                SubmitOutcome.fail("send-not-ready", inputFilled = true),
                SubmitOutcome(ok = true, why = null, inputFilled = true, attached = true)
            )
            sendEnabled = true
            answerSequence = listOf(AnswerSnapshot(1, cardsJson, false, false, ""))
        }
        source(pages, FakeSiteAdapter(uploadsAfterFill = true)).recognize(request())
        assertEquals(1, pages.calls.count { it == "fillComposer" })
        assertEquals(2, pages.calls.count { it == "submitComposer" })
    }

    /** AI-183：页面给出的上传拒绝原因（额度用尽等）要带进错误文案。 */
    @Test
    fun uploadFailureCarriesPageReason() {
        val pages = FakePageOps().apply {
            attachmentVerified = false
            pasteDispatched = false
            uploadFailure = "站点上传额度已用尽，需等待额度恢复或升级账号"
        }
        val e = assertThrows(AiSourceError.UploadFailed::class.java) {
            source(pages).recognize(request())
        }
        assertTrue(e.message!!.contains("额度"))
    }

    /** 上传未被确认时不得提交（AI-123）。 */
    @Test
    fun uploadRejectedNeverSubmits() {
        val pages = FakePageOps().apply {
            attachmentVerified = false
            pasteDispatched = false
        }
        val e = assertThrows(AiSourceError.UploadFailed::class.java) {
            source(pages).recognize(request())
        }
        assertTrue(e.message!!.contains("未确认附件预览"))
        assertFalse(pages.calls.contains("fillAndSubmit"))
    }

    /** 首选策略失败后回退另一条（AI-124）。 */
    @Test
    fun uploadFallsBackToSecondStrategy() {
        var verifyCount = 0
        val pages = object : PageOps by okPages(AnswerSnapshot(1, cardsJson, false, false, "")) {
            override fun pasteImage(fileName: String, mimeType: String, base64: String) = false
            override fun verifyAttachment(): Boolean {
                verifyCount++
                return true
            }
        }
        val adapter = FakeSiteAdapter(preferredUpload = UploadStrategy.PASTE)
        val result = source(pages, adapter).recognize(request())
        assertEquals(2, result.size)
        assertEquals(1, verifyCount)
    }

    /** AI-182：PASTE 路径不得顺手走 FILE_CHOOSER 的准备/原生触摸。 */
    @Test
    fun pasteStrategyDoesNotPrepareFileChooser() {
        val pages = FakePageOps().apply {
            answerSequence = listOf(AnswerSnapshot(1, cardsJson, false, false, ""))
        }
        val adapter = FakeSiteAdapter(
            preferredUpload = UploadStrategy.PASTE,
            supportedUploads = setOf(UploadStrategy.PASTE)
        )
        source(pages, adapter).recognize(request())
        assertEquals(1, pages.pasteCalls)
        assertFalse(pages.calls.contains("prepareFileChooserUpload"))
    }

    /** AI-182：FILE_CHOOSER 必须先准备上传，再验证预览（曾漏调导致必然失败）。 */
    @Test
    fun fileChooserStrategyPreparesUploadBeforeVerifying() {
        val pages = FakePageOps().apply {
            answerSequence = listOf(AnswerSnapshot(1, cardsJson, false, false, ""))
        }
        val adapter = FakeSiteAdapter(
            preferredUpload = UploadStrategy.FILE_CHOOSER,
            supportedUploads = setOf(UploadStrategy.FILE_CHOOSER)
        )
        source(pages, adapter).recognize(request())
        assertTrue(pages.calls.contains("prepareFileChooserUpload"))
        assertTrue(pages.calls.indexOf("prepareFileChooserUpload") < pages.calls.indexOf("verifyAttachment"))
    }

    /** AI-124：未声明为已验证的策略不得被驱动层自行尝试。 */
    @Test
    fun onlyDeclaredUploadStrategiesAreTried() {
        val pages = FakePageOps().apply {
            answerSequence = listOf(AnswerSnapshot(1, cardsJson, false, false, ""))
        }
        val adapter = FakeSiteAdapter(
            preferredUpload = UploadStrategy.FILE_CHOOSER,
            supportedUploads = setOf(UploadStrategy.FILE_CHOOSER)
        )
        source(pages, adapter).recognize(request())
        assertEquals(0, pages.pasteCalls)
    }

    /** AI-037：临时上传文件在成功与失败路径都要清理。 */
    @Test
    fun uploadCacheCleanedUpOnSuccessAndFailure() {
        val ok = FakePageOps().apply {
            answerSequence = listOf(AnswerSnapshot(1, cardsJson, false, false, ""))
        }
        source(ok).recognize(request())
        assertEquals(1, ok.cleanupCalls)

        val failed = FakePageOps().apply {
            attachmentVerified = false
            pasteDispatched = false
        }
        assertThrows(AiSourceError.UploadFailed::class.java) { source(failed).recognize(request()) }
        assertEquals(1, failed.cleanupCalls)
    }

    /** 未登录时不提交。 */
    @Test
    fun notLoggedInThrows() {
        val pages = FakePageOps().apply { loggedIn = false }
        assertThrows(AiSourceError.NotLoggedIn::class.java) { source(pages).recognize(request()) }
        assertFalse(pages.calls.contains("fillAndSubmit"))
    }

    /** 页面未就绪时不提交。 */
    @Test
    fun notReadyThrows() {
        val pages = FakePageOps().apply { loaded = false }
        assertThrows(AiSourceError.NotReady::class.java) { source(pages).recognize(request()) }
    }

    /** AI-031：未就绪时原因来自 PageOps 的分类结果，不再统一报「未就绪」。 */
    @Test
    fun notReadyCarriesClassifierReason() {
        val fake = FakePageOps().apply { loaded = false }
        val pages = object : PageOps by fake {
            override fun lastFailureReason() = "需要重新登录"
        }
        val e = assertThrows(AiSourceError.NotReady::class.java) { source(pages).recognize(request()) }
        assertTrue(e.message!!.contains("需要重新登录"))
    }

    /** 原因缺失时仍给通用措辞，不能抛 NPE 也不能空消息。 */
    @Test
    fun notReadyFallsBackWhenReasonMissing() {
        val pages = FakePageOps().apply { loaded = false }
        val e = assertThrows(AiSourceError.NotReady::class.java) { source(pages).recognize(request()) }
        assertTrue(e.message!!.contains("未就绪"))
    }

    /** 提交脚本失败 → SubmitFailed。 */
    @Test
    fun submitFailureThrows() {
        val pages = FakePageOps().apply {
            submitOutcome = SubmitOutcome.fail("send-disabled", inputFilled = true)
        }
        val e = assertThrows(AiSourceError.SubmitFailed::class.java) { source(pages).recognize(request()) }
        assertTrue(e.message!!.contains("send-disabled"))
    }

    /** 提交后未见新节点 → SubmitFailed，不是超时。 */
    @Test
    fun submitNotStartedThrows() {
        val pages = FakePageOps().apply { submitStart = SubmitStart.NOT_STARTED }
        assertThrows(AiSourceError.SubmitFailed::class.java) { source(pages).recognize(request()) }
    }

    /** AI-174：死文档只 reload 一次，重试后拿到回答。 */
    @Test
    fun stalePageReloadsOnceAndRetries() {
        val pages = FakePageOps().apply {
            submitStartSequence = listOf(SubmitStart.STALE_PAGE, SubmitStart.STARTED)
            answerSequence = listOf(AnswerSnapshot(1, cardsJson, false, false, ""))
        }
        val result = source(pages).recognize(request())
        assertEquals(2, result.size)
        assertEquals(1, pages.forcedReloadCalls)
        assertEquals(2, pages.calls.count { it == "fillAndSubmit" })
    }

    /** AI-174：第二次仍无变化就失败，不得无限 reload。 */
    @Test
    fun stalePageRetriesOnlyOnce() {
        val pages = FakePageOps().apply {
            submitStartSequence = listOf(SubmitStart.STALE_PAGE, SubmitStart.STALE_PAGE)
        }
        val e = assertThrows(AiSourceError.SubmitFailed::class.java) { source(pages).recognize(request()) }
        assertTrue(e.message!!.contains("已重载一次"))
        assertEquals(1, pages.forcedReloadCalls)
    }

    /** AI-173：首次提交失败但文本已填入、发送按钮随后可用 → 重填一次并成功。 */
    @Test
    fun sendEnabledRetryResubmitsOnce() {
        val pages = FakePageOps().apply {
            submitOutcomeSequence = listOf(
                SubmitOutcome.fail("send-disabled", inputFilled = true),
                SubmitOutcome(ok = true, why = null, inputFilled = true, attached = true)
            )
            sendEnabled = true
            answerSequence = listOf(AnswerSnapshot(1, cardsJson, false, false, ""))
        }
        val result = source(pages).recognize(request())
        assertEquals(2, result.size)
        assertEquals(2, pages.calls.count { it == "fillAndSubmit" })
        assertTrue(pages.sendEnabledCalls >= 1)
    }

    /** AI-173：发送按钮始终不可用时不重填，直接失败。 */
    @Test
    fun disabledSendDoesNotRetryBlindly() {
        val pages = FakePageOps().apply {
            submitOutcome = SubmitOutcome.fail("send-disabled", inputFilled = true)
            sendEnabled = false
        }
        assertThrows(AiSourceError.SubmitFailed::class.java) { source(pages).recognize(request()) }
        assertEquals(1, pages.calls.count { it == "fillAndSubmit" })
    }

    /** AI-173：第二次仍失败 → SubmitFailed，不当作成功。 */
    @Test
    fun sendEnabledRetryStillFails() {
        val pages = FakePageOps().apply {
            submitOutcomeSequence = listOf(
                SubmitOutcome.fail("send-disabled", inputFilled = true),
                SubmitOutcome.fail("send-disabled", inputFilled = true)
            )
            sendEnabled = true
        }
        assertThrows(AiSourceError.SubmitFailed::class.java) { source(pages).recognize(request()) }
        assertEquals(2, pages.calls.count { it == "fillAndSubmit" })
    }

    /** 回答无法解析 → ParseFailed。 */
    @Test
    fun unparsableAnswerThrows() {
        val pages = FakePageOps().apply {
            answerSequence = listOf(AnswerSnapshot(1, "我看不清这张图片", false, false, ""))
        }
        assertThrows(AiSourceError.ParseFailed::class.java) { source(pages).recognize(request()) }
    }

    /** 会话在等待期间过期 → NotLoggedIn。 */
    @Test
    fun sessionExpiredDuringWaitThrowsNotLoggedIn() {
        val pages = FakePageOps().apply {
            answerSequence = listOf(AnswerSnapshot(1, "", false, true, ""))
        }
        assertThrows(AiSourceError.NotLoggedIn::class.java) { source(pages).recognize(request()) }
    }

    /** 取消后返回 Cancelled。 */
    @Test
    fun cancelDuringWaitThrowsCancelled() {
        lateinit var src: WebViewAiSource
        val pages = okPages(AnswerSnapshot(1, "", false, false, "")) { src.cancel() }
        src = WebViewAiSource(
            adapter = FakeSiteAdapter(),
            pages = pages,
            pollIntervalMillis = 10,
            sleepMillis = { Thread.sleep(it) },
            timeoutMillis = 15_000,
            base64Encoder = { "QUJD" }
        )
        assertThrows(AiSourceError.Cancelled::class.java) { src.recognize(request()) }
    }

    /** 追问：节点数不变但文本变化，拿到新答案而非上一轮（M5）。 */
    @Test
    fun followUpGetsNewAnswerNotPrevious() {
        val oldAnswer = """[{"rank":"2","suit":"CLUB"}]"""
        val pages = FakePageOps().apply {
            baselineSnapshot = AnswerSnapshot(1, oldAnswer, false, false, "")
            answerSequence = listOf(
                AnswerSnapshot(1, oldAnswer, false, false, ""),
                AnswerSnapshot(1, cardsJson, false, false, "")
            )
        }
        val result = source(pages).recognize(request())
        assertEquals(2, result.size)
    }

    /** AI-042/AI-082：输入框有残留文本时，先切到经确认的新会话再提交。 */
    @Test
    fun leftoverInputStartsConfirmedNewConversation() {
        val pages = FakePageOps().apply {
            baselineSequence = listOf(
                AnswerSnapshot(0, "", false, false, "", inputText = "上一轮的指令"),
                AnswerSnapshot(0, "", false, false, "")
            )
            answerSequence = listOf(AnswerSnapshot(1, cardsJson, false, false, ""))
        }
        val result = source(pages).recognize(request())
        assertEquals(2, result.size)
        assertEquals(1, pages.startNewConversationCalls)
        assertTrue(pages.calls.indexOf("startNewConversation") < pages.calls.indexOf("fillAndSubmit"))
    }

    /** AI-082：上一轮附件未清空时也必须先新建会话，不能把图片 A 带进图片 B。 */
    @Test
    fun leftoverAttachmentStartsConfirmedNewConversation() {
        val pages = FakePageOps().apply {
            baselineSequence = listOf(
                AnswerSnapshot(0, "", false, false, "", attached = true),
                AnswerSnapshot(0, "", false, false, "")
            )
            answerSequence = listOf(AnswerSnapshot(1, cardsJson, false, false, ""))
        }
        source(pages).recognize(request())
        assertEquals(1, pages.startNewConversationCalls)
        assertEquals(1, pages.verifyCalls)
        assertTrue(pages.calls.indexOf("startNewConversation") < pages.calls.indexOf("verifyAttachment"))
    }

    /** 无法确认新会话时停止本轮，绝不带着残留输入区提交。 */
    @Test
    fun dirtyComposerWithoutNewConversationFailsBeforeSubmit() {
        val pages = FakePageOps().apply {
            baselineSnapshot = AnswerSnapshot(0, "", false, false, "", inputText = "残留")
            startNewConversationResult = false
        }
        val e = assertThrows(AiSourceError.SubmitFailed::class.java) { source(pages).recognize(request()) }
        assertTrue(e.message!!.contains("残留"))
        assertFalse(pages.calls.contains("fillAndSubmit"))
        assertFalse(pages.calls.contains("verifyAttachment"))
    }

    /** AI-158：站点恢复草稿、新会话确认失败时，只清纯文本残留并用快照复验。 */
    @Test
    fun restoredTextDraftIsClearedWhenNewConversationFails() {
        val pages = FakePageOps().apply {
            baselineSequence = listOf(
                AnswerSnapshot(0, "", false, false, "", inputText = "恢复的草稿"),
                AnswerSnapshot(0, "", false, false, "")
            )
            startNewConversationResult = false
            clearComposerResult = true
            answerSequence = listOf(AnswerSnapshot(1, cardsJson, false, false, ""))
        }
        val result = source(pages).recognize(request())
        assertEquals(2, result.size)
        assertEquals(1, pages.clearComposerCalls)
        assertTrue(pages.calls.indexOf("clearComposer") < pages.calls.indexOf("fillAndSubmit"))
    }

    /** 附件残留不允许用清文本兜底：必须新建会话，否则可能把旧图带进去。 */
    @Test
    fun attachmentResidueNeverClearsComposer() {
        val pages = FakePageOps().apply {
            baselineSnapshot = AnswerSnapshot(0, "", false, false, "", attached = true)
            startNewConversationResult = false
            clearComposerResult = true
        }
        assertThrows(AiSourceError.SubmitFailed::class.java) { source(pages).recognize(request()) }
        assertEquals(0, pages.clearComposerCalls)
        assertFalse(pages.calls.contains("fillAndSubmit"))
    }

    /** 新会话建立后输入区仍脏（页面未真正重置）→ 仍然失败，不赌运气。 */
    @Test
    fun newConversationStillDirtyFails() {
        val pages = FakePageOps().apply {
            baselineSequence = listOf(
                AnswerSnapshot(0, "", false, false, "", attached = true),
                AnswerSnapshot(0, "", false, false, "", attached = true)
            )
            answerSequence = listOf(AnswerSnapshot(1, cardsJson, false, false, ""))
        }
        assertThrows(AiSourceError.SubmitFailed::class.java) { source(pages).recognize(request()) }
        assertFalse(pages.calls.contains("fillAndSubmit"))
    }

    /** AI-042：上一轮仍在生成时先等它结束，不并发提交。 */
    @Test
    fun generatingBaselineWaitsInsteadOfSubmittingImmediately() {
        val pages = FakePageOps().apply {
            baselineSnapshot = AnswerSnapshot(1, "旧回答", true, false, "")
            answerSequence = listOf(
                AnswerSnapshot(1, "旧回答", false, false, ""),
                AnswerSnapshot(2, cardsJson, false, false, "")
            )
        }
        val result = source(pages).recognize(request())
        assertEquals(2, result.size)
        assertEquals(0, pages.startNewConversationCalls)
    }

    /** 成功后 running 已释放，可以再次调用。 */
    @Test
    fun secondCallAfterSuccessIsNotBusy() {
        val pages = FakePageOps().apply {
            answerSequence = listOf(AnswerSnapshot(1, cardsJson, false, false, ""))
        }
        // 用真实时钟：GenerationWatcher 依赖“静默 ≥1.5s”，假时钟不动会永不完成。
        val src = WebViewAiSource(
            adapter = FakeSiteAdapter(),
            pages = pages,
            pollIntervalMillis = 10,
            sleepMillis = { Thread.sleep(it) },
            timeoutMillis = 10_000,
            base64Encoder = { "QUJD" }
        )
        assertEquals(2, src.recognize(request()).size)
        assertEquals(2, src.recognize(request()).size)
    }

    /** 健康检查在未登录时返回 NotLoggedIn。 */
    @Test
    fun healthCheckReportsNotLoggedIn() {
        val pages = FakePageOps().apply { loggedIn = false }
        assertTrue(source(pages).healthCheck() is SourceHealth.NotLoggedIn)
    }

    // ---- AI-033：健康检查必须区分账号问题与页面问题 ----

    /** 未登录（含 URL 落在登录页）→ NotLoggedIn，而不是「页面未就绪」。 */
    @Test
    fun healthCheckMapsLoginRequiredToNotLoggedIn() {
        val pages = object : PageOps by FakePageOps() {
            override fun pageState() = PageState.LOGIN_REQUIRED
        }
        val h = source(pages).healthCheck()
        assertTrue(h is SourceHealth.NotLoggedIn)
        assertTrue((h as SourceHealth.NotLoggedIn).hint.contains("登录"))
    }

    /** 选择器缺失是页面问题，绝不能报成未登录（AI-033 明确要求）。 */
    @Test
    fun healthCheckDoesNotReportSelectorMissingAsNotLoggedIn() {
        val pages = object : PageOps by FakePageOps() {
            override fun pageState() = PageState.COMPOSER_MISSING
            override fun diagnose() = null
        }
        val h = source(pages).healthCheck()
        assertTrue(h is SourceHealth.Unavailable)
    }

    @Test
    fun healthCheckDoesNotReportViewportProblemAsNotLoggedIn() {
        val pages = object : PageOps by FakePageOps() {
            override fun pageState() = PageState.VIEWPORT_UNHEALTHY
            override fun diagnose() = null
        }
        assertTrue(source(pages).healthCheck() is SourceHealth.Unavailable)
    }

    /** 加载中属于暂时状态，也不该被报成未登录。 */
    @Test
    fun healthCheckMapsLoadingToUnavailable() {
        val pages = object : PageOps by FakePageOps() {
            override fun pageState() = PageState.LOADING
            override fun diagnose() = null
        }
        assertTrue(source(pages).healthCheck() is SourceHealth.Unavailable)
    }

    // ---- AI-184：冷启动由后台健康检查自动完成挂接与首页导航 ----

    /** 宿主未挂接时先自动挂接/导航，再按新状态报 Ready，不再要求用户先打开 AI 页面。 */
    @Test
    fun healthCheckWarmsUpHostAndReportsReady() {
        var pageState: PageState = PageState.HOST_NOT_ATTACHED
        var loads = 0
        val pages = object : PageOps by FakePageOps() {
            override fun pageState() = pageState
            override fun ensureLoaded(force: Boolean): Boolean {
                loads++
                pageState = PageState.READY
                return true
            }
        }
        assertEquals(SourceHealth.Ready, source(pages).healthCheck())
        assertEquals(1, loads)
    }

    /** 自动挂接后进入登录页：仍要报账号问题，不能报成页面未就绪。 */
    @Test
    fun healthCheckWarmUpMapsLoginRequiredToNotLoggedIn() {
        var pageState: PageState = PageState.HOST_NOT_ATTACHED
        val pages = object : PageOps by FakePageOps() {
            override fun pageState() = pageState
            override fun ensureLoaded(force: Boolean): Boolean {
                pageState = PageState.LOGIN_REQUIRED
                return false
            }
        }
        val h = source(pages).healthCheck()
        assertTrue(h is SourceHealth.NotLoggedIn)
        assertTrue((h as SourceHealth.NotLoggedIn).hint.contains("登录"))
    }

    /** AI-076：已有页面时健康检查必须保持只读，绝不能借自动挂接去导航或 reload。 */
    @Test
    fun healthCheckDoesNotNavigateWhenHostAlreadyAttached() {
        var loads = 0
        val pages = object : PageOps by FakePageOps() {
            override fun pageState() = PageState.READY
            override fun ensureLoaded(force: Boolean): Boolean {
                loads++
                return true
            }
        }
        assertEquals(SourceHealth.Ready, source(pages).healthCheck())
        assertEquals(0, loads)
    }

    /** 自动挂接失败时保留真实原因（如离屏锚点挂不上），不折叠成「未登录」。 */
    @Test
    fun healthCheckWarmUpFailureKeepsActionableReason() {
        val pages = object : PageOps by FakePageOps() {
            override fun pageState() = PageState.HOST_NOT_ATTACHED
            override fun ensureLoaded(force: Boolean): Boolean =
                throw AiSourceError.NotReady("WebView 未挂接窗口；请先打开一次 AI 页面")
        }
        val h = source(pages).healthCheck()
        assertTrue(h is SourceHealth.Unavailable)
        assertTrue((h as SourceHealth.Unavailable).reason.contains("未挂接窗口"))
    }

    /** AI-036：自动挂接后依然没有 WebView 时，保留手动打开的兜底提示。 */
    @Test
    fun healthCheckReportsHintWhenWarmUpCannotAttach() {
        val pages = object : PageOps by FakePageOps() {
            override fun pageState() = PageState.HOST_NOT_ATTACHED
        }
        val h = source(pages).healthCheck()
        assertTrue(h is SourceHealth.Unavailable)
        assertTrue((h as SourceHealth.Unavailable).reason.contains("挂接"))
    }

    @Test
    fun healthCheckMapsReadyToReady() {
        val pages = object : PageOps by FakePageOps() {
            override fun pageState() = PageState.READY
        }
        assertEquals(SourceHealth.Ready, source(pages).healthCheck())
    }

    /** 不支持只读诊断的实现继续走旧路径，行为不变。 */
    @Test
    fun healthCheckFallsBackWhenPageStateUnsupported() {
        val pages = object : PageOps by FakePageOps() {
            override fun pageState(): PageState? = null
            override fun ensureLoaded(force: Boolean) = false
        }
        assertTrue(source(pages).healthCheck() is SourceHealth.Unavailable)
    }

    @Test
    fun healthCheckReportsUnavailable() {
        val pages = FakePageOps().apply { loaded = false }
        assertTrue(source(pages).healthCheck() is SourceHealth.Unavailable)
    }

    /** 元数据：网页源不需要网络配置，需要网页登录。 */
    @Test
    fun sourceMetadata() {
        val src = source(FakePageOps())
        assertFalse(src.requiresNetworkConfig)
        assertTrue(src.requiresWebLogin)
        assertEquals("fake_site", src.id)
    }
}
