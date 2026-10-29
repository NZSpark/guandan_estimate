package nz.org.aotearoa.guandanscore.ui

import nz.org.aotearoa.guandanscore.model.Card
import nz.org.aotearoa.guandanscore.model.Rank
import nz.org.aotearoa.guandanscore.model.Suit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** UI-003 / UI-006：状态分域与失效规则必须能在纯 JVM 层验证。 */
class WorkbenchStateTest {

    private fun cards(vararg specs: Pair<Rank, Suit>) =
        specs.mapIndexed { i, (r, s) -> Card(i, r, s) }

    private fun state() = WorkbenchState(
        hand = cards(Rank.ACE to Suit.SPADE, Rank.KING to Suit.HEART),
        level = Rank.TWO
    )

    @Test
    fun `cardCount follows hand size`() {
        assertEquals(0, WorkbenchState().cardCount)
        assertEquals(2, state().cardCount)
    }

    @Test
    fun `changing hand reindexes ids and invalidates score`() {
        val s = state()
        val next = s.withHand(cards(Rank.THREE to Suit.CLUB, Rank.FOUR to Suit.DIAMOND, Rank.FIVE to Suit.SPADE))
        assertEquals(listOf(0, 1, 2), next.hand.map { it.id })
        assertEquals(3, next.cardCount)
    }

    @Test
    fun `changing level invalidates score`() {
        val s = state()
        val next = s.withLevel(Rank.FIVE)
        assertEquals(Rank.FIVE, next.level)
    }

    @Test
    fun `same level is a no-op`() {
        val s = state()
        assertEquals(s, s.withLevel(Rank.TWO))
    }

    @Test
    fun `success score becomes stale after hand change`() {
        val result = nz.org.aotearoa.guandanscore.scoring.HandScorer(Rank.TWO).score(state().hand)
        val scored = state().copy(score = ScoreState.Success(result))
        val after = scored.withHand(cards(Rank.ACE to Suit.SPADE))
        assertTrue("手牌变化后旧评分必须过期", after.score is ScoreState.Stale)
    }

    @Test
    fun `none and failed scores are not turned into stale`() {
        assertEquals(ScoreState.None, ScoreState.None.invalidate())
        val failed = ScoreState.Failed("boom")
        assertEquals(failed, failed.invalidate())
    }

    @Test
    fun `running score stays running on invalidate`() {
        assertEquals(ScoreState.Running, ScoreState.Running.invalidate())
    }

    @Test
    fun `ai failure kinds are distinct`() {
        // 防止有人把各类失败合并成一个「网络错误」。
        val kinds = AiFailureKind.entries.toSet()
        assertTrue(kinds.contains(AiFailureKind.NOT_LOGGED_IN))
        assertTrue(kinds.contains(AiFailureKind.UPLOAD_FAILED))
        assertTrue(kinds.contains(AiFailureKind.TIMEOUT))
        assertTrue(kinds.contains(AiFailureKind.PARSE_FAILED))
        assertEquals(9, kinds.size)
    }
}
