package nz.org.aotearoa.guandanscore.scoring

import nz.org.aotearoa.guandanscore.model.*
import org.junit.Test

class Case2Test {
    @Test fun calculateCase2IgnoringSuits() {
        val counts = linkedMapOf(
            Rank.ACE to 4, Rank.QUEEN to 2, Rank.JACK to 2, Rank.TEN to 2,
            Rank.NINE to 2, Rank.EIGHT to 4, Rank.SEVEN to 3, Rank.SIX to 3,
            Rank.FIVE to 1, Rank.FOUR to 1, Rank.THREE to 2, Rank.TWO to 1
        )
        val cards = mutableListOf<Card>()
        counts.forEach { (rank, count) -> repeat(count) { copy -> cards += Card(cards.size, rank, Suit.entries[copy % 4]) } }
        val result = HandScorer(Rank.TWO, considerSuits = false).score(cards)
        println("CASE2 SPI=${"%.4f".format(result.spi)} TOTAL=${result.total} ROUNDS=${result.rounds} GRADE=${result.grade}")
        result.melds.sortedByDescending(Meld::score).forEach { println(it.label) }
    }
}
