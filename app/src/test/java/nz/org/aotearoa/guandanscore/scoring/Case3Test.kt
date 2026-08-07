package nz.org.aotearoa.guandanscore.scoring

import nz.org.aotearoa.guandanscore.model.*
import org.junit.Test

class Case3Test {
    @Test fun calculateCase3LevelTwo() {
        val raw = listOf(
            Rank.TWO to Suit.CLUB,
            Rank.THREE to Suit.DIAMOND, Rank.THREE to Suit.SPADE,
            Rank.FOUR to Suit.HEART, Rank.FIVE to Suit.CLUB,
            Rank.SIX to Suit.DIAMOND, Rank.SIX to Suit.SPADE, Rank.SIX to Suit.HEART,
            Rank.SEVEN to Suit.CLUB, Rank.SEVEN to Suit.DIAMOND, Rank.SEVEN to Suit.SPADE,
            Rank.EIGHT to Suit.CLUB, Rank.EIGHT to Suit.DIAMOND, Rank.EIGHT to Suit.HEART, Rank.EIGHT to Suit.SPADE,
            Rank.NINE to Suit.CLUB, Rank.NINE to Suit.HEART,
            Rank.TEN to Suit.DIAMOND, Rank.TEN to Suit.SPADE,
            Rank.JACK to Suit.CLUB, Rank.JACK to Suit.HEART,
            Rank.QUEEN to Suit.DIAMOND, Rank.QUEEN to Suit.SPADE,
            Rank.KING to Suit.CLUB,
            Rank.ACE to Suit.DIAMOND, Rank.ACE to Suit.HEART,
            Rank.SMALL_JOKER to Suit.JOKER
        )
        val cards = raw.mapIndexed { id, (rank, suit) -> Card(id, rank, suit) }
        val result = HandScorer(Rank.TWO).score(cards)
        println("CASE3 SPI=${"%.4f".format(result.spi)} TOTAL=${result.total} ROUNDS=${result.rounds} GRADE=${result.grade}")
        result.melds.sortedByDescending(Meld::score).forEach { println(it.label) }
    }
}
