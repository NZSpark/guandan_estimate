package nz.org.aotearoa.guandanscore.scoring

import nz.org.aotearoa.guandanscore.model.Card
import nz.org.aotearoa.guandanscore.model.Rank
import nz.org.aotearoa.guandanscore.model.Suit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeldScoringTest {

    private fun meld(score: Int, type: MeldType, vararg cards: Card) = Meld(0, cards.toList(), type, score)

    @Test
    fun `control types get 115 percent`() {
        assertEquals(115, controlPercent(meld(100, MeldType.BOMB, Card(0, Rank.ACE, Suit.SPADE)), Rank.TWO))
        assertEquals(115, controlPercent(meld(100, MeldType.STRAIGHT_FLUSH, Card(0, Rank.ACE, Suit.SPADE)), Rank.TWO))
        assertEquals(115, controlPercent(meld(100, MeldType.ROCKET, Card(0, Rank.BIG_JOKER, Suit.JOKER)), Rank.TWO))
    }

    @Test
    fun `big joker and level single are control cards`() {
        assertEquals(115, controlPercent(meld(100, MeldType.SINGLE, Card(0, Rank.BIG_JOKER, Suit.JOKER)), Rank.SEVEN))
        assertEquals(115, controlPercent(meld(100, MeldType.SINGLE, Card(0, Rank.SEVEN, Suit.SPADE)), Rank.SEVEN))
        assertEquals(100, controlPercent(meld(100, MeldType.SINGLE, Card(0, Rank.SMALL_JOKER, Suit.JOKER)), Rank.SEVEN))
        assertEquals(100, controlPercent(meld(100, MeldType.SINGLE, Card(0, Rank.ACE, Suit.SPADE)), Rank.SEVEN))
    }

    @Test
    fun `only low singles and pairs are waste`() {
        assertTrue(meld(10, MeldType.SINGLE, Card(0, Rank.NINE, Suit.CLUB)).isLowWaste(Rank.TWO))
        assertTrue(
            meld(10, MeldType.PAIR, Card(0, Rank.FIVE, Suit.CLUB), Card(1, Rank.FIVE, Suit.SPADE))
                .isLowWaste(Rank.TWO)
        )
        assertFalse(meld(10, MeldType.SINGLE, Card(0, Rank.TEN, Suit.CLUB)).isLowWaste(Rank.TWO))
        assertFalse(meld(10, MeldType.TRIPLE, Card(0, Rank.NINE, Suit.CLUB)).isLowWaste(Rank.TWO))
        assertFalse(meld(10, MeldType.SINGLE, Card(0, Rank.SEVEN, Suit.HEART)).isLowWaste(Rank.SEVEN))
    }

    @Test
    fun `waste penalty applies at every hand size`() {
        // 综合评估 §4.3 未设张数豁免：残局同样按出牌单元扣 15。
        val lowPair = meld(10, MeldType.PAIR, Card(0, Rank.TWO, Suit.CLUB), Card(1, Rank.TWO, Suit.SPADE))
        assertEquals(1000 - 1500, netValue100(lowPair, Rank.SEVEN))
    }

    @Test
    fun `control bonus is applied once per meld`() {
        val levelSingle = meld(100, MeldType.SINGLE, Card(0, Rank.SEVEN, Suit.SPADE))
        assertEquals(11500, netValue100(levelSingle, Rank.SEVEN))
        assertTrue(levelSingle.isControlMeld(Rank.SEVEN))
        assertFalse(lowSingle().isControlMeld(Rank.SEVEN))
    }

    private fun lowSingle() = meld(10, MeldType.SINGLE, Card(0, Rank.NINE, Suit.CLUB))
}
