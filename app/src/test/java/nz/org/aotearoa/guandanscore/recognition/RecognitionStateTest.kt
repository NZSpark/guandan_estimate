package nz.org.aotearoa.guandanscore.recognition

import nz.org.aotearoa.guandanscore.model.Card
import nz.org.aotearoa.guandanscore.model.Rank
import nz.org.aotearoa.guandanscore.model.Suit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** UI 只按 [RecognitionSummary] 显示，避免“有牌但被警告掩盖”与“空结果还算成功”。 */
class RecognitionStateTest {

    private fun hand(size: Int) = List(size) { Card(it, Rank.ACE, Suit.SPADE) }

    @Test
    fun barcode_hit_is_success_and_exposes_no_review_work() {
        val result = RecognitionResult(
            cards = hand(27),
            warnings = listOf("已通过图片校验码准确读取 27 张牌"),
            stats = RecognitionStats(detected = 27, classified = 27, learnedStatus = "barcode"),
        )
        val summary = result.summary()
        assertEquals(RecognitionDisplayState.PARTIAL, summary.state)
        assertEquals(0, summary.reviewCount)
        assertTrue(summary.message.contains("27"))
    }

    @Test
    fun learned_result_without_warnings_maps_to_success() {
        val result = RecognitionResult(
            cards = hand(27),
            warnings = emptyList(),
            issues = listOf(RecognitionIssue(IssueLevel.INFO, "模型识别：27 张")),
            stats = RecognitionStats(engine = RecognitionEngine.LEARNED, detected = 27, classified = 27),
        )
        assertEquals(RecognitionDisplayState.SUCCESS, result.summary().state)
    }

    @Test
    fun fallback_notice_keeps_the_heuristic_result_visible() {
        val result = RecognitionResult(
            cards = hand(20),
            warnings = listOf("模型路径回退：检测不到牌角"),
            stats = RecognitionStats(
                engine = RecognitionEngine.LEARNED,
                detected = 22,
                classified = 20,
                unknown = 7,
                learnedStatus = "fallback",
                learnedReason = "检测不到牌角",
            ),
        )
        val summary = result.summary()
        assertEquals(RecognitionDisplayState.PARTIAL, summary.state)
        assertEquals(20, summary.cardCount)
        assertEquals(
            "检测不到牌角",
            result.stats.learnedReason,
        )
    }

    @Test
    fun empty_result_with_reason_maps_to_failed() {
        val summary = RecognitionResult(emptyList(), listOf("无可用牌角")).summary()
        assertEquals(RecognitionDisplayState.FAILED, summary.state)
        assertEquals(0, summary.cardCount)
    }
}
