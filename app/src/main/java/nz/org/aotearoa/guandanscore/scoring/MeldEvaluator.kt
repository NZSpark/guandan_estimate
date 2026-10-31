package nz.org.aotearoa.guandanscore.scoring

import nz.org.aotearoa.guandanscore.model.Card
import nz.org.aotearoa.guandanscore.model.Rank
import nz.org.aotearoa.guandanscore.model.Suit

/**
 * 牌型解释与打分的唯一生成器。
 *
 * 优化器与手工拆牌都从这里取候选；禁止在 UI 或新的评估器中复制牌型公式。
 * 返回的 [Meld.mask] 使用传入手牌的下标位序（`1 shl index`）。
 */
internal object MeldEvaluator {

    /**
     * 指定下标子集的全部合法解释，按 `(mask, type)` 去重，同键取最高分。
     * 只对选中子集生成候选（等价于整手生成后按 mask 过滤），避免手工页每次编辑重扫 27 张。
     * 级牌、逢人配、同花顺的规则仍复用同一个 `generate`，与优化器完全一致。
     */
    fun interpretations(
        hand: List<Card>,
        indices: List<Int>,
        level: Rank,
        considerSuits: Boolean = true,
    ): List<Meld> {
        require(indices.isNotEmpty()) { "分组不能为空" }
        require(indices.distinct().size == indices.size) { "分组下标不能重复" }
        require(indices.all { it in hand.indices }) { "分组下标越界" }
        // 只对选中子集生成候选：手工页每次编辑都调用，不能为一个小分组重扫整手 27 张。
        val sorted = indices.sorted()
        val subset = sorted.map(hand::get)
        return generate(subset, level, considerSuits, keepAllTypes = true).map { meld ->
            val originalMask = meld.cards.fold(0) { acc, card ->
                // 用引用而非 data class equals：两副牌里同 rank+suit 的牌只有 id 不同。
                val local = subset.indices.first { subset[it] === card }
                acc or (1 shl sorted[local])
            }
            meld.copy(mask = originalMask)
        }
    }

    /**
     * 整手牌的候选牌型，按物理牌 mask 去重，同 mask 只保留最高分解释。
     * 优化器的搜索规模依赖这个列表，不能把多解释候选混入。
     */
    fun candidates(cards: List<Card>, level: Rank, considerSuits: Boolean): List<Meld> =
        generate(cards, level, considerSuits, keepAllTypes = false)

    private fun generate(cards: List<Card>, level: Rank, considerSuits: Boolean, keepAllTypes: Boolean): List<Meld> {
        // For a fixed physical-card mask only the highest-scoring interpretation can ever be optimal.
        val out = LinkedHashMap<Long, Meld>()
        fun add(indices: List<Int>, type: MeldType, score: Int) {
            val mask = indices.fold(0) { a, i -> a or (1 shl i) }
            val key = if (keepAllTypes) (mask.toLong() shl 4) or type.ordinal.toLong() else mask.toLong()
            val meld = Meld(mask, indices.map(cards::get), type, score)
            if (score > (out[key]?.score ?: -1)) out[key] = meld
        }
        cards.indices.forEach {
            val raw = if (cards[it].rank == level) 15 else cards[it].rank.value
            add(listOf(it), MeldType.SINGLE, ordinaryMappedScore(raw, 2, 10, 17))
        }
        val wild = cards.indices.filter { cards[it].isWild(level) }
        val normalRanks = cards.indices.filterNot { cards[it].rank.value > 14 || cards[it].isWild(level) }.groupBy { cards[it].rank }
        val allRanks = cards.indices.filter { cards[it].rank.value <= 14 }.groupBy { cards[it].rank }

        fun assignments(spec: List<Pair<Int, Int>>, suit: Suit? = null, emit: (List<Int>) -> Unit) {
            fun walk(pos: Int, chosen: List<Int>, missing: Int) {
                if (missing > wild.size) return
                if (pos == spec.size) {
                    combinations(wild.filterNot(chosen::contains), missing).forEach { emit(chosen + it) }
                    return
                }
                val (value, need) = spec[pos]
                val pool = cards.indices.filter { i -> cards[i].rank.value == value && !cards[i].isWild(level) && (suit == null || cards[i].suit == suit) }
                for (take in 0..minOf(need, pool.size)) combinations(pool, take).forEach { walk(pos + 1, chosen + it, missing + need - take) }
            }
            walk(0, emptyList(), 0)
        }

        cards.indices.groupBy { cards[it].rank }.forEach { (rank, ids) ->
            combinations(ids, 2).forEach { add(it, MeldType.PAIR, pairScore(rank.value, level)) }
            combinations(ids, 3).forEach { add(it, MeldType.TRIPLE, tripleScore(rank.value, level)) }
        }
        (2..14).forEach { v ->
            // 级牌对子/三张（含用于级牌自身的逢人配）按 15 计，不能走 3v/5v。
            assignments(listOf(v to 2)) { add(it, MeldType.PAIR, pairScore(v, level)) }
            assignments(listOf(v to 3)) { add(it, MeldType.TRIPLE, tripleScore(v, level)) }
        }
        // Bombs: natural cards plus zero or more wild hearts, never double-counting the level's wild cards.
        normalRanks.forEach { (rank, ids) ->
            for (naturalCount in 2..minOf(8, ids.size)) for (wc in 0..minOf(wild.size, 10 - naturalCount)) {
                val n = naturalCount + wc
                if (n < 4) continue
                combinations(ids, naturalCount).forEach { ns -> combinations(wild, wc).forEach { ws ->
                    // 逢人配按替代点数计入 Sum：三张5+配与四张5同分，不得越过四张6。
                    // V4.1：6~9 炸常数按方案 B 重标定，4/5/10 炸不变。
                    add(ns + ws, MeldType.BOMB, bombScore(n, base(rank, level)))
                } }
            }
        }
        // Four jokers.
        val jokers = cards.indices.filter { cards[it].rank.value > 14 }
        if (jokers.size == 4 && jokers.count { cards[it].rank == Rank.SMALL_JOKER } == 2) add(jokers, MeldType.ROCKET, 1000)

        // Straights and straight flushes, including A2345.
        // V_max 按顺子中的实际点数计（A2345 的 A 记 1；逢人配按填补点），不按级牌的 15。
        val windows = listOf(listOf(14, 2, 3, 4, 5)) + (2..10).map { s -> (s until s + 5).toList() }
        windows.forEach { values ->
            val pools = values.map { v -> cards.indices.filter { cards[it].rank.value == v && !cards[it].isWild(level) } }
            cartesian(pools).forEach { ids ->
                val vals = values.map { if (it == 14 && values[1] == 2) 1 else it }
                val sum = vals.sum(); val vmax = vals.max()
                add(ids, MeldType.STRAIGHT, ordinaryMappedScore(sum + vmax, 20, 40, 74))
                if (considerSuits && ids.map { cards[it].suit }.distinct().size == 1) add(ids, MeldType.STRAIGHT_FLUSH, sum + 400 + 3 * vmax)
            }
            val vals = values.map { if (it == 14 && values[1] == 2) 1 else it }
            assignments(values.map { it to 1 }) { add(it, MeldType.STRAIGHT, ordinaryMappedScore(vals.sum() + vals.max(), 20, 40, 74)) }
            if (considerSuits) Suit.entries.filter { it != Suit.JOKER }.forEach { suit ->
                assignments(values.map { it to 1 }, suit) { add(it, MeldType.STRAIGHT_FLUSH, vals.sum() + 400 + 3 * vals.max()) }
            }
        }
        // Full houses: natural level cards count 15; a wild heart substituting a
        // non-level rank still counts the represented rank.
        allRanks.forEach { (tr, tids) -> allRanks.filterKeys { it != tr }.forEach { (pair, pids) ->
            val score = fullHouseScore(tr.value, pair.value, level)
            combinations(tids, 3).forEach { t -> combinations(pids, 2).forEach { p ->
                add(t + p, MeldType.FULL_HOUSE, score)
            } }
        } }
        for (tr in 2..14) for (pair in 2..14) if (tr != pair) assignments(listOf(tr to 3, pair to 2)) {
            add(it, MeldType.FULL_HOUSE, fullHouseScore(tr, pair, level))
        }
        // Exactly three consecutive pairs and exactly two consecutive triples.
        val seqRanks = listOf(14, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14)
        for (len in 3..3) for (start in 0..seqRanks.size - len) {
            val vals = seqRanks.subList(start, start + len); if (vals.distinct().size != vals.size) continue
            val pairPools = vals.map { v -> combinations(cards.indices.filter { cards[it].rank.value == v }, 2) }
            cartesianLists(pairPools).forEach { groups ->
                val ids = groups.flatten(); val numeric = vals.mapIndexed { i, v -> if (i == 0 && v == 14) 1 else v }
                add(ids, MeldType.TRACTOR, ordinaryMappedScore(2 * numeric.sum() + 2 * numeric.max(), 18, 50, 106))
            }
            val numeric = vals.mapIndexed { i, v -> if (i == 0 && v == 14) 1 else v }
            assignments(vals.map { it to 2 }) { add(it, MeldType.TRACTOR, ordinaryMappedScore(2 * numeric.sum() + 2 * numeric.max(), 18, 50, 106)) }
        }
        for (start in 0 until seqRanks.size - 1) {
            val vals = seqRanks.subList(start, start + 2); if (vals.distinct().size < 2) continue
            val pools = vals.map { v -> combinations(cards.indices.filter { cards[it].rank.value == v }, 3) }
            cartesianLists(pools).forEach { g ->
                val numeric = vals.mapIndexed { i, v -> if (i == 0 && v == 14) 1 else v }
                add(g.flatten(), MeldType.PLATE, ordinaryMappedScore(3 * numeric.sum() + 3 * numeric.max(), 15, 60, 123))
            }
            val numeric = vals.mapIndexed { i, v -> if (i == 0 && v == 14) 1 else v }
            assignments(vals.map { it to 3 }) { add(it, MeldType.PLATE, ordinaryMappedScore(3 * numeric.sum() + 3 * numeric.max(), 15, 60, 123)) }
        }
        return out.values.toList()
    }

    internal fun pairScore(value: Int, level: Rank): Int =
        ordinaryMappedScore(3 * baseValue(value, level), 6, 20, 51)

    internal fun tripleScore(value: Int, level: Rank): Int =
        ordinaryMappedScore(5 * baseValue(value, level), 10, 30, 75)

    internal fun fullHouseScore(triple: Int, pair: Int, level: Rank): Int =
        ordinaryMappedScore(5 * baseValue(triple, level) + 2 * baseValue(pair, level), 16, 50, 103)

    private fun base(rank: Rank, level: Rank) = if (rank == level) 15 else rank.value
    private fun baseValue(value: Int, level: Rank) = if (value == level.value) 15 else value

    private fun <T> combinations(items: List<T>, n: Int): List<List<T>> {
        if (n == 0) return listOf(emptyList()); if (items.size < n) return emptyList()
        val out = mutableListOf<List<T>>()
        fun go(at: Int, chosen: MutableList<T>) {
            if (chosen.size == n) { out += chosen.toList(); return }
            for (i in at..items.size - (n - chosen.size)) { chosen += items[i]; go(i + 1, chosen); chosen.removeAt(chosen.lastIndex) }
        }
        go(0, mutableListOf()); return out
    }

    private fun cartesian(pools: List<List<Int>>): List<List<Int>> =
        cartesianLists(pools.map { it.map(::listOf) }).map { it.flatten() }

    private fun <T> cartesianLists(pools: List<List<List<T>>>): List<List<List<T>>> {
        if (pools.any { it.isEmpty() }) return emptyList()
        var acc = listOf(emptyList<List<T>>())
        pools.forEach { pool -> acc = acc.flatMap { a -> pool.map { a + listOf(it) } } }
        return acc
    }
}
