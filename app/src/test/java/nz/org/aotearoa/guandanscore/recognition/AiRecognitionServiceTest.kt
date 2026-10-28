package nz.org.aotearoa.guandanscore.recognition

import nz.org.aotearoa.guandanscore.model.Rank
import nz.org.aotearoa.guandanscore.model.Suit
import org.junit.Assert.*
import org.junit.Test

class AiRecognitionServiceTest {

    private class FakeUpstream(
        var status: GeminiUpstream.Status = GeminiUpstream.Status.Ready,
        var attachResult: Boolean = true,
        var fillResult: Boolean = true,
        var submitResult: Boolean = true,
        var baseline: GeminiUpstream.ReplySnapshot = GeminiUpstream.ReplySnapshot(1, "旧回答", false),
        var frames: List<GeminiUpstream.ReplySnapshot> = emptyList()
    ) : GeminiUpstream {
        var attachCalls = 0
        var submitCalls = 0
        var promptSeen: String? = null
        private var frameIndex = 0

        fun resetFrames(list: List<GeminiUpstream.ReplySnapshot>) {
            frames = list
            frameIndex = 0
        }

        override fun checkReady() = status
        override fun captureBaseline() = baseline
        override fun attachImage(jpegBytes: ByteArray): Boolean { attachCalls++; return attachResult }
        override fun fillPrompt(prompt: String): Boolean { promptSeen = prompt; return fillResult }
        override fun submit(): Boolean { submitCalls++; return submitResult }
        override fun snapshot(): GeminiUpstream.ReplySnapshot {
            val f = frames.getOrNull(frameIndex) ?: frames.lastOrNull()
                ?: GeminiUpstream.ReplySnapshot(1, "旧回答", false)
            if (frameIndex < frames.size - 1) frameIndex++
            return f
        }
    }

    private val goodJson = """{"count":2,"cards":[{"rank":"A","suit":"S"},{"rank":"10","suit":"H"}]}"""

    /**
     * 构建一个使用“自动递增时钟”的服务。
     * 关键点：若时钟恒为 0，稳定判定所需的静默时长永远无法满足，会死循环。
     */
    private fun svc(
        up: GeminiUpstream,
        onSleep: (() -> Unit)? = null
    ): AiRecognitionService {
        var t = 0L
        return AiRecognitionService(
            upstream = up,
            watcher = GenerationWatcher(minStableSamples = 3, minQuietMillis = 1500, hardTimeoutMillis = 60_000),
            nowMillis = { t += 1000; t },
            sleepMillis = { onSleep?.invoke() }
        )
    }

    @Test
    fun happyPathReturnsCards() {
        val up = FakeUpstream(frames = listOf(GeminiUpstream.ReplySnapshot(2, goodJson, false)))
        val r = svc(up).recognize(ByteArray(1))
        assertTrue(r is AiRecognitionService.Result.Success)
        val cards = (r as AiRecognitionService.Result.Success).cards
        assertEquals(2, cards.size)
        assertEquals(Rank.ACE, cards[0].rank)
        assertEquals(Suit.SPADE, cards[0].suit)
        assertEquals(Suit.HEART, cards[1].suit)
        assertEquals(1, up.attachCalls)
        assertEquals(1, up.submitCalls)
    }

    @Test
    fun notLoggedInStopsBeforeUpload() {
        val up = FakeUpstream(status = GeminiUpstream.Status.NotLoggedIn)
        val r = svc(up).recognize(ByteArray(1))
        assertTrue(r is AiRecognitionService.Result.Failure)
        assertEquals(AiRecognitionService.Kind.NOT_LOGGED_IN, (r as AiRecognitionService.Result.Failure).kind)
        assertEquals(0, up.attachCalls)
    }

    @Test
    fun pageLoadingIsNotReady() {
        val up = FakeUpstream(status = GeminiUpstream.Status.PageLoading)
        val r = svc(up).recognize(ByteArray(1)) as AiRecognitionService.Result.Failure
        assertEquals(AiRecognitionService.Kind.NOT_READY, r.kind)
    }

    @Test
    fun pageUnhealthyReportsReason() {
        val up = FakeUpstream(status = GeminiUpstream.Status.Failed("窗口失效"))
        val r = svc(up).recognize(ByteArray(1)) as AiRecognitionService.Result.Failure
        assertEquals(AiRecognitionService.Kind.NOT_READY, r.kind)
        assertTrue(r.message.contains("窗口失效"))
    }

    @Test
    fun uploadFailureStopsBeforePrompt() {
        val up = FakeUpstream(attachResult = false)
        val r = svc(up).recognize(ByteArray(1)) as AiRecognitionService.Result.Failure
        assertEquals(AiRecognitionService.Kind.UPLOAD_FAILED, r.kind)
        assertEquals(0, up.submitCalls)
        assertNull(up.promptSeen)
    }

    @Test
    fun fillFailureStopsBeforeSubmit() {
        val up = FakeUpstream(fillResult = false)
        val r = svc(up).recognize(ByteArray(1)) as AiRecognitionService.Result.Failure
        assertEquals(AiRecognitionService.Kind.SUBMIT_FAILED, r.kind)
        assertEquals(0, up.submitCalls)
    }

    @Test
    fun submitFailureReported() {
        val up = FakeUpstream(submitResult = false)
        val r = svc(up).recognize(ByteArray(1)) as AiRecognitionService.Result.Failure
        assertEquals(AiRecognitionService.Kind.SUBMIT_FAILED, r.kind)
    }

    @Test
    fun oldAnswerOnlyTimesOut() {
        val up = FakeUpstream(frames = listOf(GeminiUpstream.ReplySnapshot(1, "旧回答", false)))
        val r = svc(up).recognize(ByteArray(1)) as AiRecognitionService.Result.Failure
        assertEquals(AiRecognitionService.Kind.TIMEOUT, r.kind)
    }

    @Test
    fun parseFailureReported() {
        val up = FakeUpstream(frames = listOf(GeminiUpstream.ReplySnapshot(2, "我看不清这张图", false)))
        val r = svc(up).recognize(ByteArray(1)) as AiRecognitionService.Result.Failure
        assertEquals(AiRecognitionService.Kind.PARSE_FAILED, r.kind)
    }

    @Test
    fun cancelDuringWaitReturnsCancelled() {
        val up = FakeUpstream(frames = listOf(GeminiUpstream.ReplySnapshot(1, "旧回答", false)))
        lateinit var service: AiRecognitionService
        service = svc(up) { service.cancel() }
        val r = service.recognize(ByteArray(1)) as AiRecognitionService.Result.Failure
        assertEquals(AiRecognitionService.Kind.CANCELLED, r.kind)
    }

    @Test
    fun secondCallWhileRunningIsBusy() {
        lateinit var service: AiRecognitionService
        var reentrant: AiRecognitionService.Result? = null
        val up = object : GeminiUpstream {
            override fun checkReady() = GeminiUpstream.Status.Ready
            override fun captureBaseline() = GeminiUpstream.ReplySnapshot(1, "旧", false)
            override fun attachImage(jpegBytes: ByteArray): Boolean {
                reentrant = service.recognize(ByteArray(1))
                return false
            }
            override fun fillPrompt(prompt: String) = true
            override fun submit() = true
            override fun snapshot() = GeminiUpstream.ReplySnapshot(1, "旧", false)
        }
        service = svc(up)
        service.recognize(ByteArray(1))
        assertTrue(reentrant is AiRecognitionService.Result.Failure)
        assertEquals(AiRecognitionService.Kind.BUSY, (reentrant as AiRecognitionService.Result.Failure).kind)
    }

    @Test
    fun serviceUsableAgainAfterFailure() {
        val up = FakeUpstream(attachResult = false)
        val service = svc(up)
        service.recognize(ByteArray(1))
        assertFalse(service.isBusy)
        up.attachResult = true
        up.resetFrames(listOf(GeminiUpstream.ReplySnapshot(2, goodJson, false)))
        assertTrue(service.recognize(ByteArray(1)) is AiRecognitionService.Result.Success)
    }

    @Test
    fun usesSharedPrompt() {
        val up = FakeUpstream(frames = listOf(GeminiUpstream.ReplySnapshot(2, goodJson, false)))
        svc(up).recognize(ByteArray(1))
        assertEquals(AiCardResponseParser.PROMPT, up.promptSeen)
    }

    @Test
    fun answerGrowthDoesNotFinishEarly() {
        val up = FakeUpstream(frames = listOf(
            GeminiUpstream.ReplySnapshot(2, "第一段", false),
            GeminiUpstream.ReplySnapshot(2, "第一段第二段", false),
            GeminiUpstream.ReplySnapshot(2, goodJson, false)
        ))
        val r = svc(up).recognize(ByteArray(1))
        assertTrue(r is AiRecognitionService.Result.Success)
        assertEquals(2, (r as AiRecognitionService.Result.Success).cards.size)
    }

    @Test
    fun cancelledFlagResetOnNewRun() {
        val up = FakeUpstream(attachResult = false)
        val service = svc(up)
        service.cancel()
        val first = service.recognize(ByteArray(1))
        // 新一次运行会重置 cancelled，不应因上次取消而立即返回 CANCELLED
        assertTrue(first is AiRecognitionService.Result.Failure)
        assertEquals(AiRecognitionService.Kind.UPLOAD_FAILED, (first as AiRecognitionService.Result.Failure).kind)
    }
}
