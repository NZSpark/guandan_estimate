package nz.org.aotearoa.guandanscore.scoring

import nz.org.aotearoa.guandanscore.model.Card
import nz.org.aotearoa.guandanscore.model.Rank
import nz.org.aotearoa.guandanscore.model.Suit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 搜索层回归：always-valid 退路、预算单调性、确定性与分区完整性。
 *
 * 旧实现在状态上限后把剩余牌按单张收尾；现在即使预算为 0 也返回完整贪心方案，
 * 困难手牌用 anytime 近似并在结果上标记 `exhaustive=false`。
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
    fun `zero budget still returns a legal greedy partition`() {
        val cards = hand(Rank.TWO to 3, Rank.THREE to 4, Rank.FOUR to 2)
        val result = HandScorer(Rank.TWO, budgetNanos = 0L).score(cards)

        // 新契约：预算为 0 也不再把整手牌拆成单张，贪心保底必须给出完整合法方案。
        assertEquals(result.melds.size, result.rounds)
        assertEquals(cards.map(Card::id).sorted(), result.melds.flatMap(Meld::cards).map(Card::id).sorted())
        assertTrue("rounds=${result.rounds} melds=${result.melds.map { it.type }}", result.rounds < cards.size)
        assertTrue("melds=${result.melds.map { it.type }}", result.melds.any { it.type == MeldType.BOMB })
        assertTrue("exhaustive=${result.exhaustive}", !result.exhaustive)
    }

    @Test
    fun `zero state cap still returns a legal greedy partition`() {
        val cards = hand(Rank.TWO to 3, Rank.THREE to 4, Rank.FOUR to 2)
        val result = HandScorer(Rank.TWO, budgetNanos = 0L, maxStates = 0).score(cards)

        assertEquals(result.melds.size, result.rounds)
        assertEquals(cards.map(Card::id).sorted(), result.melds.flatMap(Meld::cards).map(Card::id).sorted())
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
        // 4 张 2 + 1 张 6，级牌 3（无红心配参与）。
        val cards = hand(Rank.TWO to 4, Rank.SIX to 1)
        val result = HandScorer(Rank.THREE).score(cards)

        assertEquals(2, result.rounds)
        assertEquals(250, result.total)
        // 单张 6 低于 10 且未并入更高结构：残局同样计 P=15（综合评估 §4.3）
        assertEquals(263.8, result.numerator, 1e-9)
        assertEquals(131.9, result.spi, 1e-9)
        assertEquals(1, result.waste)
        assertTrue(result.melds.any { it.type == MeldType.BOMB })
    }

    @Test
    fun `dense hand finds bomb-heavy plan instead of flush-heavy fallback`() {
        // 旧实现在 12 万状态上限后把剩余牌按单张收尾，这手牌只能拿到 403.5（5 个同花顺 + 2 单张）。
        // 新搜索保留“炸弹导向”分支后，5 个炸弹覆盖 27 张，SPI 507.38。
        val cards = hand(Rank.SIX to 5, Rank.SEVEN to 6, Rank.EIGHT to 6, Rank.NINE to 5, Rank.TEN to 5)
        val result = HandScorer(Rank.TWO).score(cards)

        assertEquals(5, result.rounds)
        assertEquals(5, result.melds.count { it.type == MeldType.BOMB })
        assertEquals(507.38, result.spi, 0.01)
        assertTrue("exhaustive=${result.exhaustive}", !result.exhaustive)
        assertEquals(cards.map(Card::id).sorted(), result.melds.flatMap(Meld::cards).map(Card::id).sorted())
    }

    @Test
    fun `longer budget never lowers the achieved spi`() {
        // 这手 8/8/8/3 在 1 秒内只到 456.54，3 秒能拿到 678.21；更长预算不应回退。
        val cards = hand(Rank.TWO to 8, Rank.THREE to 8, Rank.FOUR to 8, Rank.FIVE to 3)
        val short = HandScorer(Rank.TWO, budgetNanos = 1_000_000_000L).score(cards)
        val long = HandScorer(Rank.TWO, budgetNanos = 3_000_000_000L).score(cards)

        // 搜索只接受更优方案；增加预算只会追加束搜索阶梯，不应把结果改差。
        assertTrue("long=${long.spi} short=${short.spi}", long.spi + 1e-9 >= short.spi)
    }

    @Test
    fun `small hand is solved exhaustively`() {
        val result = HandScorer(Rank.TWO, budgetNanos = 15_000_000_000L).score(hand(Rank.TWO to 3, Rank.THREE to 4, Rank.FOUR to 2))
        assertTrue(result.exhaustive)
    }

    @Test
    fun `wild card can complete a pair and still count as level single`() {
        // 级牌 3：♥3 红心配可与 4 组成对子，另一张级牌单张按 15 点计。
        val cards = listOf(
            Card(0, Rank.FOUR, Suit.CLUB),
            Card(1, Rank.FOUR, Suit.DIAMOND),
            Card(2, Rank.THREE, Suit.HEART),
        )
        val result = HandScorer(Rank.THREE).score(cards)

        assertEquals(2, result.rounds)
        // 对 4 属低点数散牌单元（计 1 个，不按两张牌计），残局同样扣 15。
        assertEquals(220.05, result.numerator, 1e-9)
        assertEquals(110.025, result.spi, 1e-9)
        assertEquals(1, result.waste)
        assertTrue(result.melds.any { it.type == MeldType.PAIR })
        assertTrue(result.melds.any { it.type == MeldType.SINGLE })
    }
}
