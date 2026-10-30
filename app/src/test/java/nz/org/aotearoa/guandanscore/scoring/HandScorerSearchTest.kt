package nz.org.aotearoa.guandanscore.scoring

import nz.org.aotearoa.guandanscore.model.Card
import nz.org.aotearoa.guandanscore.model.Rank
import nz.org.aotearoa.guandanscore.model.Suit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 搜索层回归：预算退路、确定性与分区完整性。
 *
 * 优化前这些行为没有测试锁定，导致「3 秒上限」下的结果只能靠手工对比。
 */
class HandScorerSearchTest {

    private fun hand(vararg pairs: Pair<Rank, Int>): List<Card> {
        val out = mutableListOf<Card>()
        pairs.forEach { (rank, count) ->
            val suits = Suit.entries.filter { it != Suit.JOKER }
            repeat(count) { i -> out += Card(out.size, rank, suits[i % suits.size]) }
        }
        return out
    }

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

    @Test
    fun `budget zero falls back to one single per card`() {
        val cards = hand(Rank.TWO to 3, Rank.THREE to 4, Rank.FOUR to 2)
        val result = HandScorer(Rank.TWO, budgetNanos = 0L).score(cards)

        assertEquals(cards.size, result.rounds)
        assertEquals(cards.size, result.melds.size)
        assertTrue(result.melds.all { it.type == MeldType.SINGLE })
        assertEquals(cards.sumOf { it.id }, result.melds.flatMap(Meld::cards).sumOf { it.id })
    }

    @Test
    fun `state cap also falls back to singles`() {
        val cards = hand(Rank.TWO to 3, Rank.THREE to 4, Rank.FOUR to 2)
        val result = HandScorer(Rank.TWO, maxStates = 0).score(cards)

        assertEquals(cards.size, result.rounds)
        assertTrue(result.melds.all { it.type == MeldType.SINGLE })
    }

    @Test
    fun `same hand is deterministic across runs`() {
        val cards = randomHand(seed = 11, n = 16)
        val scorer = HandScorer(Rank.FIVE, budgetNanos = 60_000_000_000L)
        val first = scorer.score(cards)
        repeat(3) {
            val again = scorer.score(cards)
            assertEquals(first.spi, again.spi, 0.0)
            assertEquals(first.numerator, again.numerator, 0.0)
            assertEquals(first.rounds, again.rounds)
            assertEquals(first.waste, again.waste)
            assertEquals(first.melds.map(Meld::mask), again.melds.map(Meld::mask))
        }
    }

    @Test
    fun `every card is used exactly once`() {
        for (seed in 1..20) {
            val cards = randomHand(seed, n = (seed % 20) + 1)
            val result = HandScorer(Rank.SEVEN, budgetNanos = 60_000_000_000L).score(cards)
            val used = result.melds.flatMap(Meld::cards).map(Card::id).sorted()
            assertEquals("seed=$seed", cards.map(Card::id).sorted(), used)
            assertEquals(cards.size, result.melds.sumOf { it.cards.size })
        }
    }

    @Test
    fun `bomb plus single is scored as documented`() {
        // 4 张 2 + 1 张 6，级牌 3（无逢人配参与）。
        val cards = hand(Rank.TWO to 4, Rank.SIX to 1)
        val result = HandScorer(Rank.THREE).score(cards)

        assertEquals(2, result.rounds)
        assertEquals(250, result.total)
        assertEquals(278.8, result.numerator, 1e-9)
        assertEquals(139.4, result.spi, 1e-9)
        assertEquals(0, result.waste)
        assertTrue(result.melds.any { it.type == MeldType.BOMB })
    }

    @Test
    fun `wild card can complete a pair and still count as level single`() {
        // 级牌 3：♥3 逢人配可与 4 组成对子，另一张级牌单张按 15 点计。
        val cards = listOf(
            Card(0, Rank.FOUR, Suit.CLUB),
            Card(1, Rank.FOUR, Suit.DIAMOND),
            Card(2, Rank.THREE, Suit.HEART),
        )
        val result = HandScorer(Rank.THREE).score(cards)

        assertEquals(2, result.rounds)
        assertEquals(235.05, result.numerator, 1e-9)
        assertEquals(117.525, result.spi, 1e-9)
        assertTrue(result.melds.any { it.type == MeldType.PAIR })
        assertTrue(result.melds.any { it.type == MeldType.SINGLE })
    }
}
