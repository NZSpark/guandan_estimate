package nz.org.aotearoa.guandanscore.ui

import nz.org.aotearoa.guandanscore.model.Card
import nz.org.aotearoa.guandanscore.model.Rank
import nz.org.aotearoa.guandanscore.model.Suit
import nz.org.aotearoa.guandanscore.scoring.HandResult
import nz.org.aotearoa.guandanscore.scoring.MeldGroup
import nz.org.aotearoa.guandanscore.scoring.MeldType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PartitionCodecTest {

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

    @Test
    fun `partition round-trips`() {
        val raw = PartitionCodec.encodePartition(Rank.SEVEN, hand(), groups())
        val decoded = PartitionCodec.decodePartition(raw)

        assertEquals(Rank.SEVEN, decoded.level)
        assertEquals(hand().map { it.rank to it.suit }, decoded.hand.map { it.rank to it.suit })
        assertEquals(groups(), decoded.groups)
    }

    @Test
    fun `groups round-trips against hand size`() {
        val raw = PartitionCodec.encodeGroups(groups())
        assertEquals(groups(), PartitionCodec.decodeGroups(raw, hand().size))
    }

    @Test
    fun `twenty-seven cards stay well under the binder comfort limit`() {
        val hand = (0 until 27).map { i ->
            Card(i, Rank.entries[i % 13], Suit.entries[i % 4])
        }
        val raw = PartitionCodec.encodePartition(
            Rank.TWO,
            hand,
            hand.indices.map { MeldGroup(listOf(it), MeldType.SINGLE) },
        )
        assertTrue("27 张分区体积 ${raw.length} 字节", raw.length < 4096)
        assertEquals(27, PartitionCodec.decodePartition(raw).groups.size)
    }

    @Test
    fun `intent payload must match the current hand and level`() {
        val raw = PartitionCodec.encodePartition(Rank.SEVEN, hand(), groups())
        val payload = PartitionCodec.decodePartition(raw)

        assertEquals(null, PartitionCodec.mismatch(payload, hand(), Rank.SEVEN))
        assertEquals(
            PartitionCodec.Mismatch.HAND,
            PartitionCodec.mismatch(payload, hand().dropLast(1), Rank.SEVEN),
        )
        assertEquals(PartitionCodec.Mismatch.LEVEL, PartitionCodec.mismatch(payload, hand(), Rank.EIGHT))
    }

    @Test
    fun `malformed partitions are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PartitionCodec.encodePartition(
                Rank.SEVEN,
                hand(),
                listOf(MeldGroup(listOf(0, 0, 1, 2, 3, 4), MeldType.STRAIGHT_FLUSH), MeldGroup(listOf(5, 6), MeldType.PAIR)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PartitionCodec.decodeGroups("""[{"type":"SINGLE","cards":[0]}]""", hand().size)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PartitionCodec.decodeGroups("""[{"type":"SINGLE","cards":[99]}]""", hand().size)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PartitionCodec.decodeGroups("""[{"type":"SINGLE","cards":[]}]""", hand().size)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PartitionCodec.decodeGroups("""[{"type":"BOGUS","cards":[0]}]""", hand().size)
        }
    }

    @Test
    fun `snapshot round-trips including approximate flag`() {
        val snapshot = ScoreSnapshot(138.2, 1658.4, 12, 921.3, "强势", false)
        val decoded = PartitionCodec.decodeSnapshot(PartitionCodec.encodeSnapshot(snapshot))
        assertEquals(snapshot, decoded)
        assertFalse(decoded.exhaustive)
    }

    @Test
    fun `snapshot projects the hand result exactly`() {
        val result = HandResult(
            spi = 138.2,
            total = 1200,
            rounds = 12,
            melds = emptyList(),
            cardCount = 27,
            numerator = 1658.4,
            waste = 3,
            exhaustive = true,
        )
        assertEquals(result.spi, result.toSnapshot().spi, 0.0)
        assertEquals(result.numerator, result.toSnapshot().numerator, 0.0)
        assertEquals(result.rounds, result.toSnapshot().rounds)
        assertEquals(result.nspi, result.toSnapshot().nspi, 0.0)
        assertEquals(result.grade, result.toSnapshot().grade)
        assertEquals(result.exhaustive, result.toSnapshot().exhaustive)
    }
}
