package nz.org.aotearoa.guandanscore.ui

import nz.org.aotearoa.guandanscore.model.Card
import nz.org.aotearoa.guandanscore.model.Rank
import nz.org.aotearoa.guandanscore.model.Suit
import nz.org.aotearoa.guandanscore.scoring.MeldGroup
import nz.org.aotearoa.guandanscore.scoring.MeldType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeldAdjustEditorTest {

    private fun snapshot() = ScoreSnapshot(100.0, 700.0, 7, 800.0, "强势", true)

    private fun spadeFlushHand() = listOf(
        Card(0, Rank.ACE, Suit.SPADE),
        Card(1, Rank.KING, Suit.SPADE),
        Card(2, Rank.QUEEN, Suit.SPADE),
        Card(3, Rank.JACK, Suit.SPADE),
        Card(4, Rank.TEN, Suit.SPADE),
        Card(5, Rank.TWO, Suit.HEART),
        Card(6, Rank.TWO, Suit.CLUB),
    )

    private fun flushState(): MeldAdjustState = MeldAdjustEditor.initial(
        spadeFlushHand(),
        Rank.SEVEN,
        snapshot(),
        listOf(
            MeldGroup(listOf(0, 1, 2, 3, 4), MeldType.STRAIGHT_FLUSH),
            MeldGroup(listOf(5, 6), MeldType.PAIR),
        ),
    )

    @Test
    fun `initial state is clean and scored`() {
        val state = flushState()
        assertEquals(2, state.result.rounds)
        assertEquals(state.result.spi - 100.0, state.delta, 1e-9)
        assertFalse(state.dirty)
        assertTrue(state.selection.isEmpty())
        assertTrue(state.undo.isEmpty())
    }

    @Test
    fun `delta follows the edited partition`() {
        val state = flushState()
        val split = (MeldAdjustEditor.split(state, 0, SplitMode.SINGLES) as MeldEditOutcome.Ok).state
        assertEquals(split.result.spi - state.base.spi, split.delta, 1e-9)
        assertNotEquals(0.0, split.delta)
        assertTrue(state.copy(base = snapshot().copy(spi = 0.0)).delta > 0)
        assertTrue(state.copy(base = snapshot().copy(spi = 10_000.0)).delta < 0)
        assertTrue(split.dirty)
        assertEquals(state, MeldAdjustEditor.undo(split))
    }

    @Test
    fun `split flush into singles and undo`() {
        val state = flushState()
        val split = MeldAdjustEditor.split(state, 0, SplitMode.SINGLES) as MeldEditOutcome.Ok
        assertEquals(6, split.state.groups.size)
        assertTrue(split.state.groups.take(5).all { it.type == MeldType.SINGLE })
        assertTrue(split.state.dirty)
        assertEquals(1, split.state.undo.size)

        val undone = MeldAdjustEditor.undo(split.state)
        assertEquals(state.groups, undone.groups)
        assertFalse(undone.dirty)
    }

    @Test
    fun `split full house into triple and pair`() {
        val hand = listOf(
            Card(0, Rank.TWO, Suit.SPADE),
            Card(1, Rank.TWO, Suit.HEART),
            Card(2, Rank.TWO, Suit.CLUB),
            Card(3, Rank.THREE, Suit.SPADE),
            Card(4, Rank.THREE, Suit.HEART),
        )
        val state = MeldAdjustEditor.initial(
            hand,
            Rank.SEVEN,
            snapshot(),
            listOf(MeldGroup(listOf(0, 1, 2, 3, 4), MeldType.FULL_HOUSE)),
        )
        val outcome = MeldAdjustEditor.split(state, 0, SplitMode.PRESET) as MeldEditOutcome.Ok
        assertEquals(2, outcome.state.groups.size)
        assertTrue(outcome.state.groups.any { it.type == MeldType.TRIPLE })
        assertTrue(outcome.state.groups.any { it.type == MeldType.PAIR })
        assertEquals(hand.size, outcome.state.groups.sumOf { it.indices.size })
    }

    @Test
    fun `reinterpret flush as straight`() {
        val state = flushState()
        val outcome = MeldAdjustEditor.reinterpret(state, 0, MeldType.STRAIGHT) as MeldEditOutcome.Ok
        assertEquals(MeldType.STRAIGHT, outcome.state.groups[0].type)
        assertEquals(2, outcome.state.groups.size)
        assertTrue(outcome.state.dirty)
    }

    @Test
    fun `merge two pairs into a bomb`() {
        val hand = listOf(
            Card(0, Rank.TWO, Suit.SPADE),
            Card(1, Rank.TWO, Suit.HEART),
            Card(2, Rank.TWO, Suit.CLUB),
            Card(3, Rank.TWO, Suit.DIAMOND),
        )
        val state = MeldAdjustEditor.initial(
            hand,
            Rank.SEVEN,
            snapshot(),
            listOf(
                MeldGroup(listOf(0, 1), MeldType.PAIR),
                MeldGroup(listOf(2, 3), MeldType.PAIR),
            ),
        ).let { MeldAdjustEditor.toggleSelection(it, 0) }
            .let { MeldAdjustEditor.toggleSelection(it, 1) }

        val candidates = MeldAdjustEditor.mergeCandidates(state)
        assertTrue(candidates.any { it.type == MeldType.BOMB })
        val merged = MeldAdjustEditor.merge(state, candidates.first { it.type == MeldType.BOMB }) as MeldEditOutcome.Ok
        assertEquals(1, merged.state.groups.size)
        assertEquals(MeldType.BOMB, merged.state.groups.single().type)
        assertTrue(merged.state.selection.isEmpty())
        assertEquals(1, merged.state.undo.size)
    }

    @Test
    fun `reset restores initial partition`() {
        val state = flushState()
        val changed = MeldAdjustEditor.split(state, 0, SplitMode.SINGLES) as MeldEditOutcome.Ok
        val reset = MeldAdjustEditor.reset(changed.state)
        assertEquals(state.groups, reset.groups)
        assertFalse(reset.dirty)
        assertTrue(reset.undo.isEmpty())
        assertTrue(reset.selection.isEmpty())
    }

    @Test
    fun `undo stack is capped at twenty edits`() {
        var state = flushState()
        repeat(21) { i ->
            val type = if (i % 2 == 0) MeldType.STRAIGHT else MeldType.STRAIGHT_FLUSH
            state = (MeldAdjustEditor.reinterpret(state, 0, type) as MeldEditOutcome.Ok).state
        }
        assertEquals(20, state.undo.size)
        assertEquals(MeldType.STRAIGHT, state.groups[0].type)
    }

    @Test
    fun `reinterpret never shrinks a group to a subset`() {
        val state = flushState()
        val alternatives = MeldAdjustEditor.interpretationsFor(state, 0)
        assertTrue(alternatives.isNotEmpty())
        assertTrue(alternatives.all { it.mask == 0b11111 })

        val outcome = MeldAdjustEditor.reinterpret(state, 0, MeldType.PAIR) as MeldEditOutcome.Rejected
        assertEquals("该组合不是合法牌型", outcome.message)
        assertEquals(state.groups, outcome.state.groups)
    }

    @Test
    fun `merge candidates must cover every selected card`() {
        val hand = listOf(
            Card(0, Rank.TWO, Suit.SPADE),
            Card(1, Rank.TWO, Suit.HEART),
            Card(2, Rank.NINE, Suit.CLUB),
        )
        val state = MeldAdjustEditor.initial(
            hand,
            Rank.SEVEN,
            snapshot(),
            listOf(
                MeldGroup(listOf(0, 1), MeldType.PAIR),
                MeldGroup(listOf(2), MeldType.SINGLE),
            ),
        ).let { MeldAdjustEditor.toggleSelection(it, 0) }
            .let { MeldAdjustEditor.toggleSelection(it, 1) }

        // 子集里的对子(0,1)不能作为合并候选，否则合并会丢掉 2。
        assertTrue(MeldAdjustEditor.mergeCandidates(state).isEmpty())
    }

    @Test
    fun `merge needs at least two selected groups`() {
        val state = flushState()
        val candidate = MeldAdjustEditor.interpretationsFor(state, 1).first()
        val outcome = MeldAdjustEditor.merge(state, candidate) as MeldEditOutcome.Rejected
        assertEquals("至少选择两组才能合并", outcome.message)
        assertTrue(outcome.state.undo.isEmpty())
    }

    @Test
    fun `illegal reinterpret leaves state unchanged`() {
        val state = flushState()
        val outcome = MeldAdjustEditor.reinterpret(state, 1, MeldType.TRIPLE) as MeldEditOutcome.Rejected
        assertEquals("该组合不是合法牌型", outcome.message)
        assertTrue(outcome.state.undo.isEmpty())
    }

    @Test
    fun `preset preview exposes only supported splits`() {
        val state = flushState()
        val pair = MeldAdjustEditor.presetPreview(state, 1)
        assertTrue(pair!!.all { it.type == MeldType.SINGLE })

        val singleState = MeldAdjustEditor.initial(
            listOf(Card(0, Rank.ACE, Suit.SPADE)),
            Rank.SEVEN,
            snapshot(),
            listOf(MeldGroup(listOf(0), MeldType.SINGLE)),
        )
        assertEquals(null, MeldAdjustEditor.presetPreview(singleState, 0))
    }

    @Test
    fun `five-card bomb preset splits into pairs plus one single`() {
        val hand = listOf(
            Card(0, Rank.TWO, Suit.SPADE),
            Card(1, Rank.TWO, Suit.HEART),
            Card(2, Rank.TWO, Suit.CLUB),
            Card(3, Rank.TWO, Suit.DIAMOND),
            Card(4, Rank.TWO, Suit.SPADE),
        )
        val state = MeldAdjustEditor.initial(
            hand,
            Rank.SEVEN,
            snapshot(),
            listOf(MeldGroup(hand.indices.toList(), MeldType.BOMB)),
        )
        val preset = MeldAdjustEditor.presetPreview(state, 0)!!
        assertEquals(2, preset.count { it.type == MeldType.PAIR })
        assertEquals(1, preset.count { it.type == MeldType.SINGLE })
        assertEquals(hand.size, preset.sumOf { it.indices.size })
    }

    private fun presetOf(type: MeldType, vararg specs: Pair<Rank, Suit>): List<MeldGroup> {
        val hand = specs.mapIndexed { i, (rank, suit) -> Card(i, rank, suit) }
        val state = MeldAdjustEditor.initial(
            hand,
            Rank.SEVEN,
            snapshot(),
            listOf(MeldGroup(hand.indices.toList(), type)),
        )
        return MeldAdjustEditor.presetPreview(state, 0)
            ?: error("$type 缺少基础组预设")
    }

    @Test
    fun `preset covers every supported meld type`() {
        val triple = presetOf(MeldType.TRIPLE, Rank.TWO to Suit.SPADE, Rank.TWO to Suit.HEART, Rank.TWO to Suit.CLUB)
        assertEquals(listOf(MeldType.PAIR, MeldType.SINGLE), triple.map { it.type })

        val straight = presetOf(
            MeldType.STRAIGHT,
            Rank.ACE to Suit.SPADE, Rank.TWO to Suit.CLUB, Rank.THREE to Suit.HEART,
            Rank.FOUR to Suit.DIAMOND, Rank.FIVE to Suit.SPADE,
        )
        assertTrue(straight.all { it.type == MeldType.SINGLE })

        val tractor = presetOf(
            MeldType.TRACTOR,
            Rank.TWO to Suit.SPADE, Rank.TWO to Suit.HEART,
            Rank.THREE to Suit.SPADE, Rank.THREE to Suit.HEART,
            Rank.FOUR to Suit.SPADE, Rank.FOUR to Suit.HEART,
        )
        assertEquals(3, tractor.size)
        assertTrue(tractor.all { it.type == MeldType.PAIR })

        val plate = presetOf(
            MeldType.PLATE,
            Rank.TWO to Suit.SPADE, Rank.TWO to Suit.HEART, Rank.TWO to Suit.CLUB,
            Rank.THREE to Suit.SPADE, Rank.THREE to Suit.HEART, Rank.THREE to Suit.CLUB,
        )
        assertEquals(2, plate.size)
        assertTrue(plate.all { it.type == MeldType.TRIPLE })

        val rocket = presetOf(
            MeldType.ROCKET,
            Rank.SMALL_JOKER to Suit.JOKER, Rank.SMALL_JOKER to Suit.JOKER,
            Rank.BIG_JOKER to Suit.JOKER, Rank.BIG_JOKER to Suit.JOKER,
        )
        assertEquals(2, rocket.size)
        assertTrue(rocket.all { it.type == MeldType.PAIR })

        val bomb = presetOf(
            MeldType.BOMB,
            Rank.TWO to Suit.SPADE, Rank.TWO to Suit.HEART,
            Rank.TWO to Suit.CLUB, Rank.TWO to Suit.DIAMOND,
        )
        assertEquals(2, bomb.size)
        assertTrue(bomb.all { it.type == MeldType.PAIR })
    }

    @Test
    fun `draft restores groups and valid selection only`() {
        val state = flushState()
        val changed = (MeldAdjustEditor.split(state, 0, SplitMode.SINGLES) as MeldEditOutcome.Ok).state
        val raw = PartitionCodec.encodeGroups(changed.groups)

        val restored = MeldAdjustEditor.restoreDraft(state, raw, setOf(0, 99)) as MeldEditOutcome.Ok
        assertEquals(changed.groups, restored.state.groups)
        assertEquals(setOf(0), restored.state.selection)
        assertTrue(restored.state.undo.isEmpty())
    }

    @Test
    fun `corrupt or incompatible draft falls back`() {
        val state = flushState()
        assertTrue(MeldAdjustEditor.restoreDraft(state, "not-json", emptySet()) is MeldEditOutcome.Rejected)

        val raw = PartitionCodec.encodeGroups(state.groups)
        val smaller = state.copy(hand = state.hand.dropLast(1))
        assertTrue(MeldAdjustEditor.restoreDraft(smaller, raw, emptySet()) is MeldEditOutcome.Rejected)

        val wrongType = PartitionCodec.encodeGroups(
            listOf(MeldGroup(listOf(0, 1, 2, 3, 4), MeldType.PAIR), MeldGroup(listOf(5, 6), MeldType.PAIR))
        )
        assertTrue(MeldAdjustEditor.restoreDraft(state, wrongType, emptySet()) is MeldEditOutcome.Rejected)
    }

    @Test
    fun `invalid split leaves state unchanged`() {
        val state = flushState()
        val outcome = MeldAdjustEditor.split(state, 99, SplitMode.SINGLES) as MeldEditOutcome.Rejected
        assertEquals(state.groups, outcome.state.groups)
        assertEquals("分组不存在", outcome.message)
    }
}
