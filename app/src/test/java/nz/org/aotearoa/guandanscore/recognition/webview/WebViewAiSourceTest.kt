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
        override fun awaitSubmitStarted(baseline: AnswerSnapshot) = true
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
        val pages = FakePageOps().apply { submitStarted = false }
        assertThrows(AiSourceError.SubmitFailed::class.java) { source(pages).recognize(request()) }
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

    /** 健康检查在页面不可用时返回 Unavailable。 */
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
