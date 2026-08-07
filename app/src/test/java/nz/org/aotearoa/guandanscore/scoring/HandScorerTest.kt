package nz.org.aotearoa.guandanscore.scoring

import nz.org.aotearoa.guandanscore.model.*
import org.junit.Assert.*
import org.junit.Test

class HandScorerTest {
    @Test fun `four twos score 192 inside a complete hand`() {
        val cards = mutableListOf<Card>()
        repeat(4) { cards += Card(cards.size, Rank.TWO, Suit.entries[it]) }
        val fillers = listOf(Rank.THREE,Rank.FIVE,Rank.SEVEN,Rank.NINE,Rank.JACK,Rank.KING,Rank.ACE)
        while (cards.size < 27) { val r = fillers[(cards.size-4) % fillers.size]; cards += Card(cards.size, r, Suit.entries[(cards.size-4)/fillers.size]) }
        val result = HandScorer(Rank.SIX).score(cards)
        assertTrue(result.melds.any { it.type == MeldType.BOMB && it.score == 192 })
        assertEquals(result.total.toDouble()/result.rounds, result.spi, .0001)
    }

    @Test fun `rocket has fixed score`() {
        val cards = mutableListOf<Card>()
        repeat(2) { cards += Card(cards.size, Rank.SMALL_JOKER, Suit.JOKER); cards += Card(cards.size, Rank.BIG_JOKER, Suit.JOKER) }
        val ranks = Rank.entries.filter { it.value <= 14 }
        var n = 0
        while (cards.size < 27) { cards += Card(cards.size, ranks[n % ranks.size], Suit.entries[(n / ranks.size) % 4]); n++ }
        assertTrue(HandScorer(Rank.FIVE).score(cards).melds.any { it.type == MeldType.ROCKET && it.score == 1700 })
    }
}
