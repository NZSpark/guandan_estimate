package nz.org.aotearoa.guandanscore.scoring

import nz.org.aotearoa.guandanscore.model.Card
import nz.org.aotearoa.guandanscore.model.Rank
import nz.org.aotearoa.guandanscore.model.Suit
import org.junit.Assert.assertEquals
import org.junit.Test

class DynamicHandEvaluationTest {
    @Test fun theoreticalMaximumRoundsFollowRevision() {
        assertEquals(5, HandResult.theoreticalMaxRounds(5))
        assertEquals(12, HandResult.theoreticalMaxRounds(13))
        assertEquals(14, HandResult.theoreticalMaxRounds(17))
        assertEquals(17, HandResult.theoreticalMaxRounds(26))
        assertEquals(15, HandResult.theoreticalMaxRounds(27))
    }

    @Test fun threeHighSinglesUseV3Mapping() {
        val cards = listOf(
            Card(0, Rank.BIG_JOKER, Suit.JOKER),
            Card(1, Rank.SMALL_JOKER, Suit.JOKER),
            Card(2, Rank.ACE, Suit.SPADE)
        )
        val result = HandScorer(Rank.TWO).score(cards)
        assertEquals(525, result.total)
        assertEquals(3, result.rounds)
        assertEquals(175.0, result.spi, 0.001)
        assertEquals(175.0 / 3.0 * 100.0, result.nspi, 0.001)
        assertEquals("均衡", result.grade)
    }

    @Test fun fiveCardStraightFlushIsStrong() {
        val cards = listOf(Rank.ACE,Rank.TWO,Rank.THREE,Rank.FOUR,Rank.FIVE)
            .mapIndexed { i,r -> Card(i,r,Suit.SPADE) }
        val result = HandScorer(Rank.SIX).score(cards)
        assertEquals(430, result.total)
        assertEquals(1, result.rounds)
        assertEquals("强势", result.grade)
    }
}
