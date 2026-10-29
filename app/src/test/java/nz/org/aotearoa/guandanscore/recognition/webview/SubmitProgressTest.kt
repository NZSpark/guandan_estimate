package nz.org.aotearoa.guandanscore.recognition.webview

import nz.org.aotearoa.guandanscore.recognition.site.AnswerSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** AI-174：提交三态判定必须是纯函数，可穷举（AI-177）。 */
class SubmitProgressTest {

    private val baseline = AnswerSnapshot(1, "上一轮回答", false, false, "https://chat.example/")

    @Test
    fun startedByNewResponseNode() {
        val s = AnswerSnapshot(2, "上一轮回答", false, false, "https://chat.example/")
        assertTrue(SubmitProgress.started(s, baseline, sessionUrlReached = false))
    }

    @Test
    fun startedByTextChangeOnSameNode() {
        val s = AnswerSnapshot(1, "新的回答", false, false, "https://chat.example/")
        assertTrue(SubmitProgress.started(s, baseline, sessionUrlReached = false))
    }

    @Test
    fun startedByGeneratingSignal() {
        val s = AnswerSnapshot(1, "上一轮回答", true, false, "https://chat.example/")
        assertTrue(SubmitProgress.started(s, baseline, sessionUrlReached = false))
    }

    @Test
    fun startedBySessionUrlEvenBeforeDomSettles() {
        val s = AnswerSnapshot(1, "上一轮回答", false, false, "https://chat.example/a/chat/s/x")
        assertTrue(SubmitProgress.started(s, baseline, sessionUrlReached = true))
    }

    @Test
    fun emptyNewAnswerIsNotEvidence() {
        val s = AnswerSnapshot(1, "", false, false, "https://chat.example/")
        assertFalse(SubmitProgress.started(s, baseline, sessionUrlReached = false))
    }

    @Test
    fun staleOnlyAfterWindowWithoutAnyChange() {
        assertFalse(SubmitProgress.stale(3_000, urlChanged = false, domChanged = false))
        assertTrue(SubmitProgress.stale(6_000, urlChanged = false, domChanged = false))
    }

    @Test
    fun urlOrDomChangeKeepsWaiting() {
        assertFalse(SubmitProgress.stale(10_000, urlChanged = true, domChanged = false))
        assertFalse(SubmitProgress.stale(10_000, urlChanged = false, domChanged = true))
    }
}
