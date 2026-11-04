package nz.org.aotearoa.guandanscore.ui

import nz.org.aotearoa.guandanscore.model.Card
import nz.org.aotearoa.guandanscore.model.Rank
import nz.org.aotearoa.guandanscore.model.Suit
import nz.org.aotearoa.guandanscore.scoring.MeldGroup
import nz.org.aotearoa.guandanscore.scoring.MeldType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeldAdjustApplyTest {

    private fun hand() = listOf(
        Card(0, Rank.ACE, Suit.SPADE),
        Card(1, Rank.KING, Suit.SPADE),
        Card(2, Rank.QUEEN, Suit.SPADE),
        Card(3, Rank.JACK, Suit.SPADE),
        Card(4, Rank.TEN, Suit.SPADE),
        Card(5, Rank.TWO, Suit.HEART),
        Card(6, Rank.TWO, Suit.CLUB),
    )

    private fun groups() = listOf(
        MeldGroup(listOf(0, 1, 2, 3, 4), MeldType.STRAIGHT_FLUSH),
        MeldGroup(listOf(5, 6), MeldType.PAIR),
    )

    private fun partition(level: Rank = Rank.SEVEN) =
        PartitionCodec.encodePartition(level, hand(), groups())

    @Test
    fun `valid result is re-evaluated without trusting returned scores`() {
        val outcome = MeldAdjustApply.apply(
            groupsRaw = PartitionCodec.encodeGroups(groups()),
            partitionRaw = partition(),
            hand = hand(),
            level = Rank.SEVEN,
        ) as MeldAdjustApplyResult.Applied

        assertEquals(2, outcome.result.rounds)
        assertEquals(7, outcome.result.cardCount)
        assertEquals(2, outcome.result.melds.size)
        assertTrue(outcome.result.melds[0].type == MeldType.STRAIGHT_FLUSH)
    }

    @Test
    fun `missing fields are rejected`() {
        assertEquals(
            MeldAdjustApply.INVALID,
            (MeldAdjustApply.apply(null, partition(), hand(), Rank.SEVEN) as MeldAdjustApplyResult.Rejected).message,
        )
        assertEquals(
            MeldAdjustApply.INVALID,
            (MeldAdjustApply.apply(PartitionCodec.encodeGroups(groups()), null, hand(), Rank.SEVEN) as MeldAdjustApplyResult.Rejected).message,
        )
    }

    @Test
    fun `hand and level changes are distinguished`() {
        val groupsRaw = PartitionCodec.encodeGroups(groups())
        assertEquals(
            MeldAdjustApply.HAND_CHANGED,
            (MeldAdjustApply.apply(groupsRaw, partition(), hand().dropLast(1), Rank.SEVEN) as MeldAdjustApplyResult.Rejected).message,
        )
        assertEquals(
            MeldAdjustApply.LEVEL_CHANGED,
            (MeldAdjustApply.apply(groupsRaw, partition(), hand(), Rank.EIGHT) as MeldAdjustApplyResult.Rejected).message,
        )
    }

    @Test
    fun `invalid groups or illegal meld types are rejected`() {
        val invalidRaw = PartitionCodec.encodeGroups(
            listOf(
                MeldGroup(listOf(0, 1, 2, 3, 4), MeldType.PAIR),
                MeldGroup(listOf(5, 6), MeldType.PAIR),
            )
        )
        assertEquals(
            MeldAdjustApply.INVALID,
            (MeldAdjustApply.apply(invalidRaw, partition(), hand(), Rank.SEVEN) as MeldAdjustApplyResult.Rejected).message,
        )
        assertEquals(
            MeldAdjustApply.INVALID,
            (MeldAdjustApply.apply("not-json", partition(), hand(), Rank.SEVEN) as MeldAdjustApplyResult.Rejected).message,
        )
    }
}
