package nz.org.aotearoa.guandanscore.scoring

import nz.org.aotearoa.guandanscore.model.Card
import nz.org.aotearoa.guandanscore.model.Rank
import nz.org.aotearoa.guandanscore.model.Suit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PartitionEvaluatorTest {

    private fun randomHand(seed: Int, n: Int): List<Card> {
        val random = Random(seed)
        val used = HashMap<Pair<Rank, Suit>, Int>()
        val out = mutableListOf<Card>()
        while (out.size < n) {
            val rank = Rank.entries[random.nextInt(15)]
            val suit = Suit.entries[random.nextInt(4)]
            val key = rank to suit
            if ((used[key] ?: 0) >= 2) continue
            used[key] = (used[key] ?: 0) + 1
            out += Card(out.size, rank, suit)
        }
        return out
    }

    private fun groupsOf(hand: List<Card>, result: HandResult): List<MeldGroup> =
        result.melds.map { meld ->
            MeldGroup(meld.cards.map { card -> hand.indexOf(card) }, meld.type)
        }

    @Test
    fun `optimal partition re-evaluates to the same HandResult`() {
        for (seed in 1..200) {
            val hand = randomHand(seed, n = 5 + seed % 8)
            val level = Rank.entries[seed % 12]
            val optimal = HandScorer(level, budgetNanos = 20_000_000L).score(hand)
            val evaluated = PartitionEvaluator.evaluate(hand, groupsOf(hand, optimal), level)

            assertEquals("seed=$seed spi", optimal.spi, evaluated.spi, 1e-9)
            assertEquals("seed=$seed numerator", optimal.numerator, evaluated.numerator, 1e-9)
            assertEquals("seed=$seed rounds", optimal.rounds, evaluated.rounds)
            assertEquals("seed=$seed total", optimal.total, evaluated.total)
            assertEquals("seed=$seed waste", optimal.waste, evaluated.waste)
            assertEquals("seed=$seed melds", optimal.melds.map { it.mask to it.score }, evaluated.melds.map { it.mask to it.score })
            assertFalse("手工复算不应声明全局穷举", evaluated.exhaustive)
        }
    }

    @Test
    fun `invalid partitions are rejected`() {
        val hand = listOf(
            Card(0, Rank.ACE, Suit.SPADE),
            Card(1, Rank.KING, Suit.SPADE),
            Card(2, Rank.QUEEN, Suit.SPADE),
            Card(3, Rank.JACK, Suit.SPADE),
            Card(4, Rank.TEN, Suit.SPADE),
            Card(5, Rank.TWO, Suit.HEART),
        )

        assertThrows(IllegalArgumentException::class.java) {
            PartitionEvaluator.resolve(hand, listOf(MeldGroup(listOf(0, 0, 1, 2, 3), MeldType.STRAIGHT_FLUSH)), Rank.TWO)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PartitionEvaluator.resolve(hand, listOf(MeldGroup(listOf(0, 1, 2, 3), MeldType.STRAIGHT_FLUSH)), Rank.TWO)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PartitionEvaluator.resolve(
                hand,
                listOf(
                    MeldGroup(listOf(0, 1, 2, 3, 4), MeldType.ROCKET),
                    MeldGroup(listOf(5), MeldType.SINGLE),
                ),
                Rank.TWO,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PartitionEvaluator.resolve(hand, listOf(MeldGroup(emptyList(), MeldType.SINGLE)), Rank.TWO)
        }
    }

    @Test
    fun `straight flush can be reinterpreted as a straight`() {
        val hand = listOf(
            Card(0, Rank.ACE, Suit.SPADE),
            Card(1, Rank.KING, Suit.SPADE),
            Card(2, Rank.QUEEN, Suit.SPADE),
            Card(3, Rank.JACK, Suit.SPADE),
            Card(4, Rank.TEN, Suit.SPADE),
        )
        val straight = PartitionEvaluator.evaluate(hand, listOf(MeldGroup(listOf(0, 1, 2, 3, 4), MeldType.STRAIGHT)), Rank.TWO)

        assertEquals(5, straight.cardCount)
        assertEquals(1, straight.rounds)
        assertTrue(straight.melds.single().type == MeldType.STRAIGHT)
    }
}
