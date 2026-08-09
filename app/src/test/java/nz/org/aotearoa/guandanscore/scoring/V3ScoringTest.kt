package nz.org.aotearoa.guandanscore.scoring

import nz.org.aotearoa.guandanscore.model.Card
import nz.org.aotearoa.guandanscore.model.Rank
import nz.org.aotearoa.guandanscore.model.Suit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V3ScoringTest {
    private fun cards(vararg ranks: Rank): List<Card> = ranks.mapIndexed { index, rank ->
        Card(index, rank, if (rank.value > 14) Suit.JOKER else Suit.entries[index % 4])
    }

    @Test fun ordinaryMeldRangesMapExactlyToV3Bounds() {
        val ranges = listOf(
            intArrayOf(2, 10, 17),
            intArrayOf(6, 20, 51),
            intArrayOf(10, 30, 75),
            intArrayOf(20, 40, 74),
            intArrayOf(16, 50, 96),
            intArrayOf(18, 50, 106),
            intArrayOf(15, 60, 123)
        )
        ranges.forEach { (rawMin, finalMin, rawMax) ->
            assertEquals(finalMin, v3OrdinaryScore(rawMin, rawMin, finalMin, rawMax))
            assertEquals(191, v3OrdinaryScore(rawMax, rawMin, finalMin, rawMax))
        }
    }

    @Test fun ordinaryMeldMinimaUseV3Floors() {
        val level = Rank.SEVEN
        assertEquals(10, HandScorer(level).score(cards(Rank.TWO)).total)
        assertEquals(20, HandScorer(level).score(cards(Rank.TWO, Rank.TWO)).total)
        assertEquals(30, HandScorer(level).score(cards(Rank.TWO, Rank.TWO, Rank.TWO)).total)
        assertEquals(40, v3OrdinaryScore(20, 20, 40, 74))
    }

    @Test fun pairOfBigJokersIsRecognizedAndMapsTo191() {
        val result = HandScorer(Rank.SEVEN).score(cards(Rank.BIG_JOKER, Rank.BIG_JOKER))
        assertEquals(1, result.rounds)
        assertEquals(MeldType.PAIR, result.melds.single().type)
        assertEquals(191, result.total)
    }

    @Test fun smallestBombStillBeatsEveryOrdinaryMeld() {
        val result = HandScorer(Rank.SEVEN).score(cards(Rank.TWO, Rank.TWO, Rank.TWO, Rank.TWO))
        assertEquals(192, result.total)
        assertTrue(result.melds.single().type == MeldType.BOMB)
    }

    @Test fun dynamicGradeThresholdsFollowV3Table() {
        fun result(spi: Double, n: Int) = HandResult(spi, 0, 1, emptyList(), n)
        assertEquals("弱势", result(16.9, 5).grade)
        assertEquals("均衡", result(17.0, 5).grade)
        assertEquals("强势", result(300.0, 5).grade)
        assertEquals("碾压级", result(700.1, 27).grade)
        assertEquals("均衡", result(100.0, 27).grade)
    }
}
