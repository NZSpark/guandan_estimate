package nz.org.aotearoa.guandanscore.scoring

import org.junit.Assert.assertThrows
import org.junit.Test

class MeldGroupTest {

    @Test
    fun `a complete partition is accepted`() {
        validatePartition(
            5,
            listOf(
                MeldGroup(listOf(0, 1, 2), MeldType.TRIPLE),
                MeldGroup(listOf(3, 4), MeldType.PAIR),
            ),
        )
    }

    @Test
    fun `empty group is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            validatePartition(2, listOf(MeldGroup(emptyList(), MeldType.SINGLE), MeldGroup(listOf(0, 1), MeldType.PAIR)))
        }
    }

    @Test
    fun `duplicate index is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            validatePartition(3, listOf(MeldGroup(listOf(0, 0, 1), MeldType.TRIPLE), MeldGroup(listOf(2), MeldType.SINGLE)))
        }
    }

    @Test
    fun `index outside the hand is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            validatePartition(2, listOf(MeldGroup(listOf(0, 1, 2), MeldType.TRIPLE)))
        }
    }

    @Test
    fun `missing card is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            validatePartition(3, listOf(MeldGroup(listOf(0, 1), MeldType.PAIR)))
        }
    }

    @Test
    fun `overlapping groups are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            validatePartition(2, listOf(MeldGroup(listOf(0, 1), MeldType.PAIR), MeldGroup(listOf(1), MeldType.SINGLE)))
        }
    }

    @Test
    fun `empty partition is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            validatePartition(1, emptyList())
        }
    }
}
