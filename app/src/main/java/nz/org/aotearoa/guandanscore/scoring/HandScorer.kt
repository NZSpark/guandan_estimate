package nz.org.aotearoa.guandanscore.scoring

import nz.org.aotearoa.guandanscore.model.*
import kotlin.math.roundToInt

enum class MeldType(val title: String) { SINGLE("单张"), PAIR("对子"), TRIPLE("三张"), STRAIGHT("顺子"), FULL_HOUSE("三带二"), TRACTOR("连对"), PLATE("钢板"), BOMB("炸弹"), STRAIGHT_FLUSH("同花顺"), ROCKET("火箭") }
data class Meld(val mask: Int, val cards: List<Card>, val type: MeldType, val score: Int) {
    val label get() = "${type.title} ${cards.joinToString(" ") { it.display }} · $score"
}
data class HandResult(val spi: Double, val total: Int, val rounds: Int, val melds: List<Meld>, val cardCount: Int) {
    val tMax get() = theoreticalMaxRounds(cardCount)
    val nspi get() = spi / tMax * 100.0
    val grade get() = when (cardCount) {
        in 1..5 -> when { spi > 700 -> "碾压级"; spi >= 300 -> "强势"; spi >= 17 -> "均衡"; else -> "弱势" }
        in 6..12 -> when { spi > 700 -> "碾压级"; spi >= 180 -> "强势"; spi >= 60 -> "均衡"; else -> "弱势" }
        in 13..18 -> when { spi > 700 -> "碾压级"; spi >= 200 -> "强势"; spi >= 80 -> "均衡"; else -> "弱势" }
        else -> when { spi > 700 -> "碾压级"; spi >= 200 -> "强势"; spi >= 100 -> "均衡"; else -> "弱势" }
    }

    companion object {
        /** T_max(N), following the key nodes and ranges in the August 2026 revision. */
        fun theoreticalMaxRounds(n: Int): Int = when (n) {
            in 1..12 -> n
            13 -> 12; 14 -> 13; 15 -> 14; 16 -> 15; 17 -> 14
            18 -> 14; 19,20 -> 15; 21,22,23,24 -> 16; 25,26 -> 17
            27 -> 15
            else -> throw IllegalArgumentException("手牌张数必须为1至27")
        }
    }
}

/** V3.0 linear remapping for ordinary (non-bomb, non-straight-flush) melds. */
internal fun v3OrdinaryScore(raw: Int, rawMin: Int, finalMin: Int, rawMax: Int): Int =
    (finalMin + (raw - rawMin).toDouble() * (191 - finalMin) / (rawMax - rawMin))
        .roundToInt()

/** Exact-cover optimizer for a partial or complete hand, including heart-level-card substitutions. */
class HandScorer(private val level: Rank, private val considerSuits: Boolean = true) {
    fun score(cards: List<Card>): HandResult {
        require(cards.size in 1..27) { "请确认 1 至 27 张牌" }
        require(cards.groupingBy { it.rank to it.suit }.eachCount().values.all { it <= 2 }) { "两副牌中同一张牌最多出现 2 次，请检查识别结果" }
        val candidates = candidates(cards)
        val byCard = Array(cards.size) { i -> candidates.filter { it.mask and (1 shl i) != 0 }.sortedByDescending { it.score } }
        val singles = Array(cards.size) { i -> candidates.first { it.mask == (1 shl i) } }
        data class State(val total: Int, val melds: List<Meld>)
        val memo = HashMap<Int, Array<State?>>()
        val deadline = System.nanoTime() + 3_000_000_000L
        fun singleCompletion(mask: Int): Array<State?> {
            val tail = cards.indices.filter { mask and (1 shl it) == 0 }.map { singles[it] }
            return arrayOfNulls<State>(cards.size + 1).also { it[tail.size] = State(tail.sumOf(Meld::score), tail) }
        }
        fun solve(mask: Int): Array<State?> {
            if (mask == (1 shl cards.size) - 1) return arrayOf(State(0, emptyList()))
            memo[mask]?.let { return it }
            // Exact cover is NP-hard. Bound pathological hands so the UI always remains responsive;
            // the high-value branches are explored first and the remaining cards stay a valid partition.
            if (memo.size >= 120_000 || System.nanoTime() >= deadline) return singleCompletion(mask)
            // Choosing the most constrained uncovered card sharply reduces the exact-cover search tree.
            val first = (0 until cards.size).asSequence()
                .filter { mask and (1 shl it) == 0 }
                .minBy { i -> byCard[i].count { it.mask and mask == 0 } }
            val best = arrayOfNulls<State>(cards.size + 1)
            for (meld in byCard[first]) {
                if (meld.mask and mask != 0) continue
                val tails = solve(mask or meld.mask)
                for (r in tails.indices) tails[r]?.let { tail ->
                    val nr = r + 1
                    val total = tail.total + meld.score
                    if (best[nr] == null || total > best[nr]!!.total) best[nr] = State(total, listOf(meld) + tail.melds)
                }
            }
            memo[mask] = best
            return best
        }
        val options = solve(0).mapIndexedNotNull { rounds, state -> state?.takeIf { rounds > 0 }?.let { Triple(it.total.toDouble() / rounds, rounds, it) } }
        val best = options.maxWith(
            compareBy<Triple<Double, Int, State>> { it.first }
                .thenBy { -it.second } // Equal SPI: prefer fewer planned plays.
        )
        return HandResult(best.first, best.third.total, best.second, best.third.melds, cards.size)
    }

    private fun candidates(cards: List<Card>): List<Meld> {
        // For a fixed physical-card mask only the highest-scoring interpretation can ever be optimal.
        val out = LinkedHashMap<Int, Meld>()
        fun add(indices: List<Int>, type: MeldType, score: Int) {
            val mask = indices.fold(0) { a, i -> a or (1 shl i) }
            val meld = Meld(mask, indices.map(cards::get), type, score)
            if (score > (out[mask]?.score ?: -1)) out[mask] = meld
        }
        cards.indices.forEach {
            val raw = if (cards[it].rank == level) 15 else cards[it].rank.value
            add(listOf(it), MeldType.SINGLE, v3OrdinaryScore(raw, 2, 10, 17))
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
            combinations(ids, 2).forEach { add(it, MeldType.PAIR, v3OrdinaryScore(3 * base(rank), 6, 20, 51)) }
            combinations(ids, 3).forEach { add(it, MeldType.TRIPLE, v3OrdinaryScore(5 * base(rank), 10, 30, 75)) }
        }
        (2..14).forEach { v ->
            assignments(listOf(v to 2)) { add(it, MeldType.PAIR, v3OrdinaryScore(3*v, 6, 20, 51)) }
            assignments(listOf(v to 3)) { add(it, MeldType.TRIPLE, v3OrdinaryScore(5*v, 10, 30, 75)) }
        }
        // Bombs: natural cards plus zero or more wild hearts, never double-counting the level's wild cards.
        normalRanks.forEach { (rank, ids) ->
            for (naturalCount in 2..minOf(8, ids.size)) for (wc in 0..minOf(wild.size, 10 - naturalCount)) {
                val n = naturalCount + wc
                if (n < 4) continue
                combinations(ids, naturalCount).forEach { ns -> combinations(wild, wc).forEach { ws ->
                    val sum = naturalCount * base(rank) + wc * 15
                    val v = base(rank)
                    val value = when (n) { 4 -> sum + 180 + 2*v; 5 -> sum + 280 + 4*v; 6 -> sum + 550 + 5*v; 7 -> sum + 750 + 6*v; 8 -> sum + 1000 + 7*v; 9 -> sum + 1200 + 7*v; 10 -> sum + 1400 + 5*v; else -> 0 }
                    add(ns + ws, MeldType.BOMB, value)
                } }
            }
        }
        // Four jokers.
        val jokers = cards.indices.filter { cards[it].rank.value > 14 }
        if (jokers.size == 4 && jokers.count { cards[it].rank == Rank.SMALL_JOKER } == 2) add(jokers, MeldType.ROCKET, 1700)

        // Straights and straight flushes, including A2345.
        val windows = listOf(listOf(14,2,3,4,5)) + (2..10).map { s -> (s until s+5).toList() }
        windows.forEach { values ->
            val pools = values.map { v -> cards.indices.filter { cards[it].rank.value == v && !cards[it].isWild(level) } }
            cartesian(pools).forEach { ids ->
                val vals = values.map { if (it == 14 && values[1] == 2) 1 else it }
                val sum = vals.sum(); val vmax = vals.max()
                add(ids, MeldType.STRAIGHT, v3OrdinaryScore(sum + vmax, 20, 40, 74))
                if (considerSuits && ids.map { cards[it].suit }.distinct().size == 1) add(ids, MeldType.STRAIGHT_FLUSH, sum + 400 + 3*vmax)
            }
            val vals = values.map { if (it == 14 && values[1] == 2) 1 else it }
            assignments(values.map { it to 1 }) { add(it, MeldType.STRAIGHT, v3OrdinaryScore(vals.sum() + vals.max(), 20, 40, 74)) }
            if (considerSuits) Suit.entries.filter { it != Suit.JOKER }.forEach { suit ->
                assignments(values.map { it to 1 }, suit) { add(it, MeldType.STRAIGHT_FLUSH, vals.sum() + 400 + 3*vals.max()) }
            }
        }
        // Full houses.
        allRanks.forEach { (tr, tids) -> allRanks.filterKeys { it != tr }.forEach { (_, pids) ->
            combinations(tids, 3).forEach { t -> combinations(pids, 2).forEach { p ->
                // In compound melds a level card takes the represented face value, not 15.
                val raw = 5 * tr.value + 2 * cards[p.first()].rank.value
                add(t+p, MeldType.FULL_HOUSE, v3OrdinaryScore(raw, 16, 50, 96))
            } }
        } }
        for (tr in 2..14) for (pair in 2..14) if (tr != pair) assignments(listOf(tr to 3, pair to 2)) {
            add(it, MeldType.FULL_HOUSE, v3OrdinaryScore(5*tr + 2*pair, 16, 50, 96))
        }
        // Exactly three consecutive pairs and exactly two consecutive triples.
        val seqRanks = listOf(14,2,3,4,5,6,7,8,9,10,11,12,13,14)
        for (len in 3..3) for (start in 0..seqRanks.size-len) {
            val vals = seqRanks.subList(start, start+len); if (vals.distinct().size != vals.size) continue
            val pairPools = vals.map { v -> combinations(cards.indices.filter { cards[it].rank.value == v }, 2) }
            cartesianLists(pairPools).forEach { groups ->
                val ids = groups.flatten(); val numeric = vals.mapIndexed { i,v -> if (i==0 && v==14) 1 else v }
                add(ids, MeldType.TRACTOR, v3OrdinaryScore(2*numeric.sum() + 2*numeric.max(), 18, 50, 106))
            }
            val numeric = vals.mapIndexed { i,v -> if (i==0 && v==14) 1 else v }
            assignments(vals.map { it to 2 }) { add(it, MeldType.TRACTOR, v3OrdinaryScore(2*numeric.sum() + 2*numeric.max(), 18, 50, 106)) }
        }
        for (start in 0 until seqRanks.size-1) {
            val vals = seqRanks.subList(start,start+2); if (vals.distinct().size < 2) continue
            val pools = vals.map { v -> combinations(cards.indices.filter { cards[it].rank.value == v }, 3) }
            cartesianLists(pools).forEach { g ->
                val numeric = vals.mapIndexed { i,v -> if (i==0 && v==14) 1 else v }
                add(g.flatten(), MeldType.PLATE, v3OrdinaryScore(3*numeric.sum() + 3*numeric.max(), 15, 60, 123))
            }
            val numeric = vals.mapIndexed { i,v -> if (i==0 && v==14) 1 else v }
            assignments(vals.map { it to 3 }) { add(it, MeldType.PLATE, v3OrdinaryScore(3*numeric.sum() + 3*numeric.max(), 15, 60, 123)) }
        }
        return out.values.toList()
    }

    private fun base(rank: Rank) = if (rank == level) 15 else rank.value
    private fun <T> combinations(items: List<T>, n: Int): List<List<T>> {
        if (n == 0) return listOf(emptyList()); if (items.size < n) return emptyList()
        val out = mutableListOf<List<T>>()
        fun go(at: Int, chosen: MutableList<T>) { if (chosen.size == n) { out += chosen.toList(); return }; for (i in at..items.size-(n-chosen.size)) { chosen += items[i]; go(i+1, chosen); chosen.removeAt(chosen.lastIndex) } }
        go(0, mutableListOf()); return out
    }
    private fun cartesian(pools: List<List<Int>>): List<List<Int>> = cartesianLists(pools.map { it.map(::listOf) }).map { it.flatten() }
    private fun <T> cartesianLists(pools: List<List<List<T>>>): List<List<List<T>>> {
        if (pools.any { it.isEmpty() }) return emptyList(); var acc = listOf(emptyList<List<T>>())
        pools.forEach { pool -> acc = acc.flatMap { a -> pool.map { a + listOf(it) } } }; return acc
    }
}
