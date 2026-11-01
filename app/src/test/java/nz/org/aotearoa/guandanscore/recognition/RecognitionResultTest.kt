package nz.org.aotearoa.guandanscore.recognition

import nz.org.aotearoa.guandanscore.model.Card
import nz.org.aotearoa.guandanscore.model.Rank
import nz.org.aotearoa.guandanscore.model.Suit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionResultTest {

    private fun hand(size: Int) = List(size) { Card(it, Rank.TWO, Suit.CLUB) }

    @Test
    fun issues_default_to_warnings_as_warning_level() {
        val result = RecognitionResult(hand(3), listOf("第一条", "第二条"))
        assertEquals(2, result.issues.size)
        assertTrue(result.issues.all { it.level == IssueLevel.WARNING })
    }

    @Test
    fun stats_carry_review_count_and_engine() {
        val result = RecognitionResult(
            cards = hand(27),
            warnings = emptyList(),
            stats = RecognitionStats(
                engine = RecognitionEngine.LEARNED,
                detected = 30,
                classified = 27,
                unknown = 3,
                merged = 3,
                elapsedMillis = 812,
                modelVersion = "20261101.1",
                learnedStatus = "used",
            ),
        )
        assertEquals(3, result.reviewCount)
        assertEquals(RecognitionEngine.LEARNED, result.stats.engine)
        assertEquals(812, result.stats.elapsedMillis)
        assertEquals("20261101.1", result.stats.modelVersion)
    }

    @Test
    fun summary_reports_success_for_full_hand_without_warnings() {
        val summary = RecognitionResult(hand(27), emptyList()).summary()
        assertEquals(RecognitionDisplayState.SUCCESS, summary.state)
        assertEquals(27, summary.cardCount)
        assertEquals("识别 27 张", summary.message)
    }

    @Test
    fun summary_reports_partial_and_counts_cards_under_review() {
        val summary = RecognitionResult(
            cards = hand(24),
            warnings = listOf("被完全遮挡的点数请补录"),
            stats = RecognitionStats(unknown = 3),
        ).summary()
        assertEquals(RecognitionDisplayState.PARTIAL, summary.state)
        assertEquals(24, summary.cardCount)
        assertEquals(3, summary.reviewCount)
        assertTrue(summary.message.contains("需核对"))
    }

    @Test
    fun summary_reports_failure_when_nothing_was_read() {
        val summary = RecognitionResult(emptyList(), listOf("无法读取图片")).summary()
        assertEquals(RecognitionDisplayState.FAILED, summary.state)
        assertEquals("无法读取图片", summary.message)
    }

    @Test
    fun blank_warnings_are_ignored_by_summary() {
        val summary = RecognitionResult(hand(27), listOf("  ", "")).summary()
        assertEquals(RecognitionDisplayState.SUCCESS, summary.state)
    }
}
