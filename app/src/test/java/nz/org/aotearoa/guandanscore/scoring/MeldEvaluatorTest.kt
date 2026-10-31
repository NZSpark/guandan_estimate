package nz.org.aotearoa.guandanscore.scoring

import nz.org.aotearoa.guandanscore.model.Card
import nz.org.aotearoa.guandanscore.model.Rank
import nz.org.aotearoa.guandanscore.model.Suit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MeldEvaluatorTest {

    private fun card(id: Int, rank: Rank, suit: Suit) = Card(id, rank, suit)

    @Test
    fun `same cards can be both a straight flush and a straight`() {
        val hand = listOf(
            card(0, Rank.ACE, Suit.SPADE),
            card(1, Rank.KING, Suit.SPADE),
            card(2, Rank.QUEEN, Suit.SPADE),
            card(3, Rank.JACK, Suit.SPADE),
            card(4, Rank.TEN, Suit.SPADE),
        )

        val all = MeldEvaluator.interpretations(hand, hand.indices.toList(), Rank.TWO)
        val types = all.map { it.type }.toSet()

        assertTrue(types.contains(MeldType.STRAIGHT))
        assertTrue(types.contains(MeldType.STRAIGHT_FLUSH))
        assertTrue(all.filter { it.type == MeldType.STRAIGHT || it.type == MeldType.STRAIGHT_FLUSH }
            .all { it.mask == 0b11111 })
        assertTrue(all.first { it.type == MeldType.STRAIGHT_FLUSH }.score > all.first { it.type == MeldType.STRAIGHT }.score)
    }

    @Test
    fun `A2345 is generated as a straight flush`() {
        val hand = listOf(
            card(0, Rank.ACE, Suit.CLUB),
            card(1, Rank.TWO, Suit.CLUB),
            card(2, Rank.THREE, Suit.CLUB),
            card(3, Rank.FOUR, Suit.CLUB),
            card(4, Rank.FIVE, Suit.CLUB),
        )

        val types = MeldEvaluator.interpretations(hand, hand.indices.toList(), Rank.SEVEN).map { it.type }.toSet()
        assertTrue(types.contains(MeldType.STRAIGHT))
        assertTrue(types.contains(MeldType.STRAIGHT_FLUSH))
    }

    @Test
    fun `five natural aces include the five-card bomb, not only a four-card bomb`() {
        val hand = listOf(
            card(0, Rank.ACE, Suit.SPADE),
            card(1, Rank.ACE, Suit.HEART),
            card(2, Rank.ACE, Suit.DIAMOND),
            card(3, Rank.ACE, Suit.SPADE),
            card(4, Rank.ACE, Suit.DIAMOND),
        )

        val bombs = MeldEvaluator.interpretations(hand, hand.indices.toList(), Rank.FIVE)
            .filter { it.type == MeldType.BOMB }

        val five = bombs.single { it.cards.size == 5 }
        assertEquals(0b11111, five.mask)
        assertTrue(five.score > bombs.filter { it.cards.size == 4 }.maxOfOrNull { it.score }!!)

        // 手工分区必须解析成同一 mask 下的最高分解释，不能退化成四张炸弹。
        val resolved = PartitionEvaluator.resolve(
            hand,
            listOf(MeldGroup(hand.indices.toList(), MeldType.BOMB)),
            Rank.FIVE,
        ).single()
        assertEquals(5, resolved.cards.size)
        assertEquals(five.score, resolved.score)
    }

    @Test
    fun `four jokers produce a rocket`() {
        val hand = listOf(
            card(0, Rank.SMALL_JOKER, Suit.JOKER),
            card(1, Rank.SMALL_JOKER, Suit.JOKER),
            card(2, Rank.BIG_JOKER, Suit.JOKER),
            card(3, Rank.BIG_JOKER, Suit.JOKER),
        )

        val rocket = MeldEvaluator.interpretations(hand, hand.indices.toList(), Rank.TWO)
            .single { it.type == MeldType.ROCKET }

        assertEquals(1000, rocket.score)
        assertEquals(0b1111, rocket.mask)
    }

    @Test
    fun `level heart fills a pair of eights`() {
        val hand = listOf(
            card(0, Rank.SEVEN, Suit.HEART),
            card(1, Rank.EIGHT, Suit.CLUB),
        )

        val pair = MeldEvaluator.interpretations(hand, listOf(0, 1), Rank.SEVEN)
            .single { it.type == MeldType.PAIR }

        assertEquals(MeldEvaluator.pairScore(8, Rank.SEVEN), pair.score)
    }

    @Test
    fun `invalid index sets are rejected`() {
        val hand = listOf(card(0, Rank.TWO, Suit.CLUB), card(1, Rank.THREE, Suit.CLUB))

        assertThrows(IllegalArgumentException::class.java) {
            MeldEvaluator.interpretations(hand, listOf(0, 0), Rank.TWO)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MeldEvaluator.interpretations(hand, listOf(9), Rank.TWO)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MeldEvaluator.interpretations(hand, emptyList(), Rank.TWO)
        }
    }
}
