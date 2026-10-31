package nz.org.aotearoa.guandanscore.ui

import nz.org.aotearoa.guandanscore.scoring.HandResult
import org.junit.Assert.assertEquals
import org.junit.Test

class ScoreSnapshotTest {

    private fun result(exhaustive: Boolean) = HandResult(
        spi = 138.2,
        total = 1200,
        rounds = 12,
        melds = emptyList(),
        cardCount = 27,
        numerator = 1658.4,
        waste = 1,
        exhaustive = exhaustive,
    )

    @Test
    fun `projection keeps every displayed field`() {
        listOf(true, false).forEach { exhaustive ->
            val result = result(exhaustive)
            val snapshot = result.toSnapshot()
            assertEquals(result.spi, snapshot.spi, 0.0)
            assertEquals(result.numerator, snapshot.numerator, 0.0)
            assertEquals(result.rounds, snapshot.rounds)
            assertEquals(result.nspi, snapshot.nspi, 0.0)
            assertEquals(result.grade, snapshot.grade)
            assertEquals(exhaustive, snapshot.exhaustive)
        }
    }

    @Test
    fun `json round-trip keeps exhaustive`() {
        listOf(true, false).forEach { exhaustive ->
            val snapshot = result(exhaustive).toSnapshot()
            assertEquals(snapshot, PartitionCodec.decodeSnapshot(PartitionCodec.encodeSnapshot(snapshot)))
        }
    }
}
