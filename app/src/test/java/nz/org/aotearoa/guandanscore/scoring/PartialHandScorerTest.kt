package nz.org.aotearoa.guandanscore.scoring

import nz.org.aotearoa.guandanscore.model.Card
import nz.org.aotearoa.guandanscore.model.Rank
import nz.org.aotearoa.guandanscore.model.Suit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialHandScorerTest {
    @Test fun singleCardCanBeScored() {
        val result = HandScorer(Rank.TWO).score(listOf(Card(0, Rank.ACE, Suit.SPADE)))
        assertEquals(155, result.total)
        assertEquals(1, result.rounds)
        assertEquals(155.0, result.spi, 0.001)
    }

    @Test fun partialHandUsesOptimalPartition() {
        val cards = listOf(
            Card(0, Rank.EIGHT, Suit.SPADE),
            Card(1, Rank.EIGHT, Suit.HEART),
            Card(2, Rank.EIGHT, Suit.CLUB),
            Card(3, Rank.EIGHT, Suit.DIAMOND),
            Card(4, Rank.ACE, Suit.SPADE)
        )
        val result = HandScorer(Rank.TWO).score(cards)
        assertEquals(2, result.rounds)
        assertTrue(result.melds.any { it.type == MeldType.BOMB })
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyHandIsRejected() {
        HandScorer(Rank.TWO).score(emptyList())
    }
}
