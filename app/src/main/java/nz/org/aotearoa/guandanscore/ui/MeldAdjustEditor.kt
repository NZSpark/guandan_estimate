package nz.org.aotearoa.guandanscore.ui

import nz.org.aotearoa.guandanscore.model.Card
import nz.org.aotearoa.guandanscore.model.Rank
import nz.org.aotearoa.guandanscore.scoring.HandResult
import nz.org.aotearoa.guandanscore.scoring.Meld
import nz.org.aotearoa.guandanscore.scoring.MeldEvaluator
import nz.org.aotearoa.guandanscore.scoring.MeldGroup
import nz.org.aotearoa.guandanscore.scoring.MeldType
import nz.org.aotearoa.guandanscore.scoring.PartitionEvaluator
import nz.org.aotearoa.guandanscore.scoring.validatePartition

/** 手工拆牌的纯逻辑状态；不依赖 Android，可直接在 JVM 单测中驱动。 */
data class MeldAdjustState(
    val hand: List<Card>,
    val level: Rank,
    val base: ScoreSnapshot,
    val groups: List<MeldGroup>,
    val initial: List<MeldGroup>,
    val selection: Set<Int> = emptySet(),
    val undo: List<List<MeldGroup>> = emptyList(),
) {
    val result: HandResult get() = PartitionEvaluator.evaluate(hand, groups, level)
    val delta: Double get() = result.spi - base.spi
    val dirty: Boolean get() = groups != initial
}

sealed interface MeldEditOutcome {
    data class Ok(val state: MeldAdjustState) : MeldEditOutcome
    data class Rejected(val state: MeldAdjustState, val message: String) : MeldEditOutcome
}

enum class SplitMode { SINGLES, PRESET }

/**
 * 手工拆牌事件状态机。
 *
 * 所有成功编辑都会让分区保持在「合法牌型 + 每张牌恰好一次」的状态；
 * 非法输入返回 [MeldEditOutcome.Rejected]，不把异常抛给 UI。
 */
object MeldAdjustEditor {

    fun initial(
        hand: List<Card>,
        level: Rank,
        base: ScoreSnapshot,
        groups: List<MeldGroup>,
    ): MeldAdjustState {
        validatePartition(hand.size, groups)
        PartitionEvaluator.resolve(hand, groups, level)
        return MeldAdjustState(hand, level, base, groups, groups)
    }

    fun toggleSelection(state: MeldAdjustState, index: Int): MeldAdjustState =
        if (index !in state.groups.indices) state
        else state.copy(
            selection = if (index in state.selection) state.selection - index else state.selection + index
        )

    fun clearSelection(state: MeldAdjustState): MeldAdjustState = state.copy(selection = emptySet())

    /** 可供合并的合法候选；选中不足两组时为空。 */
    fun mergeCandidates(state: MeldAdjustState): List<Meld> {
        if (state.selection.size < 2) return emptyList()
        val indices = state.selection.sorted().flatMap { state.groups[it].indices }
        if (indices.size != indices.distinct().size) return emptyList()
        return fullInterpretations(state, indices)
            .sortedByDescending { it.score }
            .distinctBy { it.type to it.score }
    }

    /** 「拆成基础组」预览；返回 null 表示该牌型没有预设，UI 不应展示入口。 */
    fun presetPreview(state: MeldAdjustState, index: Int): List<MeldGroup>? =
        state.groups.getOrNull(index)?.let { presetGroups(state, it) }

    fun split(state: MeldAdjustState, index: Int, mode: SplitMode): MeldEditOutcome {
        val group = state.groups.getOrNull(index) ?: return MeldEditOutcome.Rejected(state, "分组不存在")
        if (group.indices.size < 2) return MeldEditOutcome.Rejected(state, "单张无法继续拆分")
        val parts = when (mode) {
            SplitMode.SINGLES -> group.indices.map { MeldGroup(listOf(it), MeldType.SINGLE) }
            SplitMode.PRESET -> presetGroups(state, group) ?: return MeldEditOutcome.Rejected(state, "该牌型暂不支持拆分")
        }
        val newGroups = state.groups.toMutableList().apply {
            removeAt(index)
            addAll(index, parts)
        }
        return applyGroups(state, newGroups)
    }

    fun interpretationsFor(state: MeldAdjustState, index: Int): List<Meld> {
        val group = state.groups.getOrNull(index) ?: return emptyList()
        return fullInterpretations(state, group.indices)
            .sortedByDescending { it.score }
    }

    fun reinterpret(state: MeldAdjustState, index: Int, type: MeldType): MeldEditOutcome {
        val group = state.groups.getOrNull(index) ?: return MeldEditOutcome.Rejected(state, "分组不存在")
        if (group.type == type) return MeldEditOutcome.Ok(state)
        val legal = fullInterpretations(state, group.indices).any { it.type == type }
        if (!legal) return MeldEditOutcome.Rejected(state, "该组合不是合法牌型")
        val newGroups = state.groups.toMutableList().apply {
            this[index] = group.copy(type = type)
        }
        return applyGroups(state, newGroups)
    }

    fun merge(state: MeldAdjustState, candidate: Meld): MeldEditOutcome {
        if (state.selection.size < 2) return MeldEditOutcome.Rejected(state, "至少选择两组才能合并")
        val selected = state.selection.sorted()
        val indices = selected.flatMap { state.groups[it].indices }
        if (mask(indices) != candidate.mask) return MeldEditOutcome.Rejected(state, "所选牌无法组成合法牌型")
        val legal = MeldEvaluator.interpretations(state.hand, indices, state.level, considerSuits = true)
            .any { it.type == candidate.type }
        if (!legal) return MeldEditOutcome.Rejected(state, "所选牌无法组成合法牌型")
        val newGroups = state.groups.filterIndexed { i, _ -> i !in state.selection }.toMutableList()
        newGroups.add(selected.first().coerceAtMost(newGroups.size), MeldGroup(indices, candidate.type))
        return applyGroups(state, newGroups)
    }

    fun undo(state: MeldAdjustState): MeldAdjustState {
        val previous = state.undo.lastOrNull() ?: return state
        return state.copy(
            groups = previous,
            undo = state.undo.dropLast(1),
            selection = emptySet(),
        )
    }

    fun reset(state: MeldAdjustState): MeldAdjustState =
        state.copy(groups = state.initial, selection = emptySet(), undo = emptyList())

    /** 从 onSaveInstanceState 的分区 + 选择恢复；失败时返回 Rejected，由 UI 回退 initial。 */
    fun restoreDraft(state: MeldAdjustState, groupsRaw: String, selection: Set<Int>): MeldEditOutcome =
        try {
            val groups = PartitionCodec.decodeGroups(groupsRaw, state.hand.size)
            PartitionEvaluator.resolve(state.hand, groups, state.level)
            MeldEditOutcome.Ok(
                state.copy(
                    groups = groups,
                    selection = selection.filter { it in groups.indices }.toSet(),
                    undo = emptyList(),
                )
            )
        } catch (e: Exception) {
            // JSONException 等解析错误也必须回退，不能把损坏草稿抛给 Activity。
            MeldEditOutcome.Rejected(state, e.message ?: "手工拆牌草稿已失效，已恢复初始方案")
        }

    private fun applyGroups(state: MeldAdjustState, newGroups: List<MeldGroup>): MeldEditOutcome {
        return try {
            PartitionEvaluator.resolve(state.hand, newGroups, state.level)
            MeldEditOutcome.Ok(
                state.copy(
                    groups = newGroups,
                    selection = emptySet(),
                    undo = (state.undo + listOf(state.groups)).takeLast(20),
                )
            )
        } catch (e: IllegalArgumentException) {
            MeldEditOutcome.Rejected(state, e.message ?: "该组合不是合法牌型")
        }
    }

    private fun presetGroups(state: MeldAdjustState, group: MeldGroup): List<MeldGroup>? {
        val indices = group.indices
        return when (group.type) {
            MeldType.PAIR -> indices.map { MeldGroup(listOf(it), MeldType.SINGLE) }
            MeldType.TRIPLE -> partitionMixed(state, indices, MeldType.PAIR, MeldType.SINGLE)
            MeldType.FULL_HOUSE -> partitionMixed(state, indices, MeldType.TRIPLE, MeldType.PAIR)
            MeldType.STRAIGHT, MeldType.STRAIGHT_FLUSH -> indices.map { MeldGroup(listOf(it), MeldType.SINGLE) }
            MeldType.TRACTOR -> partitionSame(state, indices, 2, MeldType.PAIR)
            MeldType.PLATE -> partitionSame(state, indices, 3, MeldType.TRIPLE)
            MeldType.BOMB -> partitionBomb(state, indices)
            MeldType.ROCKET -> partitionSame(state, indices, 2, MeldType.PAIR)
            MeldType.SINGLE -> null
        }
    }

    private fun partitionMixed(
        state: MeldAdjustState,
        indices: List<Int>,
        firstType: MeldType,
        secondType: MeldType,
    ): List<MeldGroup>? {
        val firstSize = if (firstType == MeldType.TRIPLE) 3 else 2
        for (first in combinations(indices, firstSize)) {
            if (!isLegal(state, first, firstType)) continue
            val rest = indices - first
            if (isLegal(state, rest, secondType)) {
                return listOf(MeldGroup(first, firstType), MeldGroup(rest, secondType))
            }
        }
        return null
    }

    private fun partitionSame(
        state: MeldAdjustState,
        indices: List<Int>,
        size: Int,
        type: MeldType,
    ): List<MeldGroup>? = partitionInto(state, indices, size, type)

    private fun partitionBomb(state: MeldAdjustState, indices: List<Int>): List<MeldGroup>? {
        if (indices.size % 2 == 0) return partitionInto(state, indices, 2, MeldType.PAIR)
        for (single in indices) {
            val rest = indices - single
            val pairs = partitionInto(state, rest, 2, MeldType.PAIR)
            if (pairs != null) return pairs + MeldGroup(listOf(single), MeldType.SINGLE)
        }
        return null
    }

    private fun partitionInto(
        state: MeldAdjustState,
        indices: List<Int>,
        size: Int,
        type: MeldType,
    ): List<MeldGroup>? {
        if (indices.isEmpty()) return emptyList()
        if (indices.size % size != 0) return null
        val first = indices.first()
        for (restOfGroup in combinations(indices.drop(1), size - 1)) {
            val group = listOf(first) + restOfGroup
            if (!isLegal(state, group, type)) continue
            val tail = partitionInto(state, indices - group, size, type)
            if (tail != null) return listOf(MeldGroup(group, type)) + tail
        }
        return null
    }

    private fun isLegal(state: MeldAdjustState, indices: List<Int>, type: MeldType): Boolean =
        fullInterpretations(state, indices).any { it.type == type }

    /** 编辑动作只接受覆盖整组的解释；子集解释会把一组牌“改判”成更小的牌型。 */
    private fun fullInterpretations(state: MeldAdjustState, indices: List<Int>): List<Meld> {
        val expected = mask(indices)
        return MeldEvaluator.interpretations(state.hand, indices, state.level, considerSuits = true)
            .filter { it.mask == expected }
    }

    private fun mask(indices: List<Int>): Int = indices.fold(0) { acc, i -> acc or (1 shl i) }

    private fun combinations(items: List<Int>, n: Int): List<List<Int>> {
        if (n == 0) return listOf(emptyList())
        if (items.size < n) return emptyList()
        val out = mutableListOf<List<Int>>()
        fun go(at: Int, chosen: MutableList<Int>) {
            if (chosen.size == n) { out += chosen.toList(); return }
            for (i in at..items.size - (n - chosen.size)) {
                chosen += items[i]
                go(i + 1, chosen)
                chosen.removeAt(chosen.lastIndex)
            }
        }
        go(0, mutableListOf())
        return out
    }
}
