package nz.org.aotearoa.guandanscore.recognition

import nz.org.aotearoa.guandanscore.model.Card
import nz.org.aotearoa.guandanscore.model.Rank
import nz.org.aotearoa.guandanscore.model.Suit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MissingCardFillTest {

    private fun card(id: Int, rank: Rank, suit: Suit) = Card(id, rank, suit)

    @Test
    fun fill_adds_only_missing_copies_and_marks_them_for_review() {
        val primary = listOf(card(0, Rank.ACE, Suit.SPADE))
        val secondary = listOf(
            card(0, Rank.ACE, Suit.SPADE),
            card(1, Rank.ACE, Suit.SPADE),
            card(2, Rank.KING, Suit.HEART),
        )

        val result = fillMissingCards(primary, secondary)

        assertEquals(
            listOf(
                Rank.ACE to Suit.SPADE,
                Rank.ACE to Suit.SPADE,
                Rank.KING to Suit.HEART,
            ),
            result.cards.map { it.rank to it.suit },
        )
        assertEquals(listOf(0, 1, 2), result.cards.map { it.id })
        assertFalse(result.cards[0].needsReview)
        assertTrue(result.cards[1].needsReview)
        assertTrue(result.cards[2].needsReview)
        assertEquals(2, result.added.size)
    }

    @Test
    fun learned_card_is_never_replaced_by_a_different_heuristic_card() {
        val primary = listOf(card(0, Rank.ACE, Suit.SPADE))
        val secondary = listOf(card(0, Rank.KING, Suit.HEART))

        val result = fillMissingCards(primary, secondary)

        assertEquals(Rank.ACE to Suit.SPADE, result.cards.first().rank to result.cards.first().suit)
        assertEquals(1, result.added.size)
        assertEquals(Rank.KING to Suit.HEART, result.added.single().rank to result.added.single().suit)
    }

    @Test
    fun same_label_with_no_missing_copy_is_not_duplicated() {
        val primary = listOf(card(0, Rank.ACE, Suit.SPADE))
        val secondary = listOf(card(0, Rank.ACE, Suit.SPADE))

        val result = fillMissingCards(primary, secondary)

        assertEquals(1, result.cards.size)
        assertTrue(result.added.isEmpty())
    }

    @Test
    fun two_deck_limit_is_enforced_for_heuristic_fill() {
        val secondary = listOf(
            card(0, Rank.ACE, Suit.SPADE),
            card(1, Rank.ACE, Suit.SPADE),
            card(2, Rank.ACE, Suit.SPADE),
        )

        val result = fillMissingCards(emptyList(), secondary)

        assertEquals(2, result.cards.size)
        assertEquals(2, result.added.size)
        assertTrue(result.cards.all { it.needsReview })
    }

    @Test
    fun full_primary_hand_ignores_heuristic_fill() {
        val primary = List(27) { index -> card(index, Rank.entries[index % 13], Suit.SPADE) }
        val secondary = listOf(card(0, Rank.ACE, Suit.HEART))

        val result = fillMissingCards(primary, secondary)

        assertEquals(27, result.cards.size)
        assertTrue(result.added.isEmpty())
    }
}
