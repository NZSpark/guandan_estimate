package nz.org.aotearoa.guandanscore.ui

import nz.org.aotearoa.guandanscore.model.Card
import nz.org.aotearoa.guandanscore.model.Rank
import nz.org.aotearoa.guandanscore.model.Suit
import nz.org.aotearoa.guandanscore.scoring.HandResult
import nz.org.aotearoa.guandanscore.scoring.Meld
import nz.org.aotearoa.guandanscore.scoring.MeldType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreTextTest {

    private fun result(spi: Double, rounds: Int, exhaustive: Boolean = true) = HandResult(
        spi = spi,
        total = 100,
        rounds = rounds,
        melds = listOf(
            Meld(0b11, listOf(Card(0, Rank.ACE, Suit.SPADE), Card(1, Rank.KING, Suit.HEART)), MeldType.SINGLE, 50),
        ),
        cardCount = 27,
        numerator = spi * rounds,
        exhaustive = exhaustive,
    )

    @Test
    fun `delta combines sign and round difference`() {
        assertEquals("与最优相同", ScoreText.delta(0.0, 12, 12))
        assertEquals("较最优 -8.02 · 多 1 轮", ScoreText.delta(-8.02, 12, 13))
        assertEquals("优于最优 +1.00 · 少 2 轮", ScoreText.delta(1.0, 12, 10))
        assertEquals("较最优 -8.02", ScoreText.delta(-8.02, 12, 12))
    }

    @Test
    fun `origin line keeps both absolute values`() {
        val line = ScoreText.origin(result(138.2, 12), result(130.18, 13))
        assertEquals("手工拆牌 · 最优 138.20 → 130.18（较最优 -8.02 · 多 1 轮）", line)
    }

    @Test
    fun `approximate base is labelled`() {
        val line = ScoreText.origin(result(138.2, 12, exhaustive = false), result(130.18, 13))
        assertTrue(line.endsWith(" · 最优（近似）"))
    }

    @Test
    fun `optimal title distinguishes approximate results`() {
        assertEquals("最优拆牌", ScoreText.optimalTitle(true))
        assertEquals("最优拆牌（近似 · 未穷举）", ScoreText.optimalTitle(false))
    }

    @Test
    fun `image label follows the result origin`() {
        val base = result(138.2, 12)
        assertEquals("最优拆牌图", ScoreText.imageLabel(ScoreOrigin.Optimal))
        assertEquals("手工拆牌图", ScoreText.imageLabel(ScoreOrigin.Adjusted(base)))
    }

    @Test
    fun `optimal breakdown is read-only and sorted by score`() {
        val base = result(100.0, 1).copy(
            melds = listOf(
                Meld(0b1, listOf(Card(0, Rank.TWO, Suit.CLUB)), MeldType.SINGLE, 10),
                Meld(0b10, listOf(Card(1, Rank.ACE, Suit.SPADE)), MeldType.BOMB, 1000),
            )
        )
        val lines = ScoreText.optimalBreakdown(base).lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("炸弹"))
        assertTrue(lines[1].startsWith("单张"))
    }
}
