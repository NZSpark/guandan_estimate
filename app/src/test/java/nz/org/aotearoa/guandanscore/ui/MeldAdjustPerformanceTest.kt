package nz.org.aotearoa.guandanscore.ui

import nz.org.aotearoa.guandanscore.model.Card
import nz.org.aotearoa.guandanscore.model.Rank
import nz.org.aotearoa.guandanscore.model.Suit
import nz.org.aotearoa.guandanscore.scoring.MeldGroup
import nz.org.aotearoa.guandanscore.scoring.MeldType
import org.junit.Assert.assertTrue
import org.junit.Test

class MeldAdjustPerformanceTest {

    private fun hand27(): List<Card> = buildList {
        add(Card(size, Rank.TWO, Suit.SPADE))
        add(Card(size, Rank.TWO, Suit.SPADE))
        add(Card(size, Rank.TWO, Suit.HEART))
        add(Card(size, Rank.TWO, Suit.HEART))
        val ranks = Rank.entries.filter { it.value <= 14 && it != Rank.TWO }
        val suits = Suit.entries.filter { it != Suit.JOKER }
        for (rank in ranks) for (suit in suits) {
            if (size >= 27) break
            add(Card(size, rank, suit))
        }
    }.take(27)

    @Test
    fun `fifty merge and undo cycles on twenty-seven cards stay fast`() {
        val hand = hand27()
        val groups = hand.indices.map { MeldGroup(listOf(it), MeldType.SINGLE) }
        var state = MeldAdjustEditor.initial(
            hand,
            Rank.SEVEN,
            ScoreSnapshot(100.0, 1000.0, 10, 50.0, "均衡", true),
            groups,
        )

        val startedAt = System.nanoTime()
        repeat(50) {
            state = state.copy(selection = setOf(0, 1))
            val candidates = MeldAdjustEditor.mergeCandidates(state)
            val pair = candidates.first { it.type == MeldType.PAIR }
            state = (MeldAdjustEditor.merge(state, pair) as MeldEditOutcome.Ok).state
            state = MeldAdjustEditor.undo(state)
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000.0

        println("MeldAdjust: 50 merge/undo on 27 cards = %.1f ms (%.2f ms/cycle)".format(elapsedMs, elapsedMs / 50))
        assertTrue("27 张连续编辑不应出现明显卡顿：${elapsedMs}ms", elapsedMs < 10_000)
    }
}
