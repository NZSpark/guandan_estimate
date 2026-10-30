package nz.org.aotearoa.guandanscore.scoring

import nz.org.aotearoa.guandanscore.model.*
import kotlin.math.ln
import kotlin.math.roundToInt

enum class MeldType(val title: String) { SINGLE("单张"), PAIR("对子"), TRIPLE("三张"), STRAIGHT("顺子"), FULL_HOUSE("三带二"), TRACTOR("连对"), PLATE("钢板"), BOMB("炸弹"), STRAIGHT_FLUSH("同花顺"), ROCKET("火箭") }
data class Meld(val mask: Int, val cards: List<Card>, val type: MeldType, val score: Int) {
    val label get() = "${type.title} ${cards.joinToString(" ") { it.display }} · $score"
}
data class HandResult(
    val spi: Double,
    val total: Int,
    val rounds: Int,
    val melds: List<Meld>,
    val cardCount: Int,
    /** V4.0 SPI 分子 = Σ(Fᵢ×αᵢ) − P（残局 N<10 张时 P=0）。 */
    val numerator: Double = spi * rounds,
    /** 散牌惩罚涉及的点数<10孤立单张/小对子数量 N_waste（残局 N<10 时恒为 0）。 */
    val waste: Int = 0,
) {
    val penalty get() = 15.0 * waste
    val tMax get() = theoreticalMaxRounds(cardCount)
    val nspi get() = spi / tMax * 100.0

    /** V4.0（2026-09）动态分级：残局1~12张 60/120/180；中局13~18张 70/130/200；开局(≥19张，含19~26过渡) 80/140/220。 */
    val grade get() = when {
        cardCount <= 12 -> graded(60.0, 120.0, 180.0)
        cardCount <= 18 -> graded(70.0, 130.0, 200.0)
        else -> graded(80.0, 140.0, 220.0)
    }
    private fun graded(balanced: Double, strong: Double, crush: Double) = when {
        spi >= crush -> "碾压级"
        spi >= strong -> "强势"
        spi >= balanced -> "均衡"
        else -> "弱势"
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

/** V4.0 普通牌型线性映射：原始分从 rawMin..rawMax 线性映射到 finalMin..191（普通牌型最高 191）。 */
internal fun ordinaryMappedScore(raw: Int, rawMin: Int, finalMin: Int, rawMax: Int): Int =
    (finalMin + (raw - rawMin).toDouble() * (191 - finalMin) / (rawMax - rawMin))
        .roundToInt()

/** V4.0 高阶炸弹对数平滑项：floor(multiplier × log₂(1 + delta/100))。 */
private const val LN_2 = 0.6931471805599453
private fun logScale(multiplier: Int, delta: Int): Int =
    (multiplier * ln(1.0 + delta / 100.0) / LN_2).toInt()

/** Exact-cover optimizer for a partial or complete hand, including heart-level-card substitutions. */
class HandScorer(
    private val level: Rank,
    private val considerSuits: Boolean = true,
    /** 搜索时间上限；测试可放宽以获取参考最优值。 */
    internal val budgetNanos: Long = 3_000_000_000L,
    internal val maxStates: Int = 120_000,
) {
    fun score(cards: List<Card>): HandResult {
        require(cards.size in 1..27) { "请确认 1 至 27 张牌" }
        require(cards.groupingBy { it.rank to it.suit }.eachCount().values.all { it <= 2 }) { "两副牌中同一张牌最多出现 2 次，请检查识别结果" }
        val n = cards.size
        val candidates = candidates(cards)
        // 分子用「1 = 0.01」的整数累计：α∈{1.00,1.15} 与 P=15 都落在百分位上。
        // 原实现每个状态都要新建 State + 拷贝 melds 列表，是搜索阶段的主要 GC 负担。
        val net = IntArray(candidates.size) { netValue100(candidates[it], n) }
        val byCard = Array(n) { i ->
            val bit = 1 shl i
            candidates.indices.filter { candidates[it].mask and bit != 0 }
                .sortedByDescending { candidates[it].score }
                .toIntArray()
        }
        val singles = IntArray(n) { -1 }
        candidates.forEachIndexed { index, meld ->
            if (Integer.bitCount(meld.mask) == 1) singles[Integer.numberOfTrailingZeros(meld.mask)] = index
        }
        // 静态「候选最少优先」：原实现每个状态都重扫 byCard 统计可用候选，比较开销比分支本身还大。
        val cardOrder = (0 until n).sortedBy { byCard[it].size }.toIntArray()

        /** 每个 mask 的前沿：num[r] = 恰好 r 轮时的最大分子，pick[r] = 该轮选中的候选下标。 */
        class States(val num: IntArray, val pick: IntArray)

        val full = (1 shl n) - 1
        val none = Int.MIN_VALUE / 2
        val memo = HashMap<Int, States>()
        val deadline = System.nanoTime() + budgetNanos

        /** 预算耗尽后的合法退路：剩余牌全部按单张收尾（不写 memo，重建时按同一规则重走）。 */
        fun fallback(mask: Int): States {
            var num = 0
            var rest = mask.inv() and full
            while (rest != 0) {
                num += net[singles[Integer.numberOfTrailingZeros(rest)]]
                rest = rest and (rest - 1)
            }
            val rounds = n - Integer.bitCount(mask)
            val values = IntArray(rounds + 1) { none }
            values[rounds] = num
            return States(values, IntArray(rounds + 1))
        }

        fun solve(mask: Int): States {
            if (mask == full) return States(IntArray(1), IntArray(1))
            memo[mask]?.let { return it }
            // 精确覆盖是 NP-hard：状态数与时间双上限保证界面可交互，超限的部分用单张收尾。
            if (memo.size >= maxStates || System.nanoTime() >= deadline) return fallback(mask)
            var first = -1
            for (i in cardOrder) if (mask and (1 shl i) == 0) { first = i; break }
            val uncovered = n - Integer.bitCount(mask)
            val bestNum = IntArray(uncovered + 1) { none }
            val bestPick = IntArray(uncovered + 1)
            for (ci in byCard[first]) {
                val meld = candidates[ci]
                if (meld.mask and mask != 0) continue
                val tail = solve(mask or meld.mask)
                val add = net[ci]
                val tailNum = tail.num
                for (r in tailNum.indices) {
                    val value = tailNum[r]
                    if (value == none) continue
                    val nr = r + 1
                    val total = value + add
                    if (total > bestNum[nr]) {
                        bestNum[nr] = total
                        bestPick[nr] = ci
                    }
                }
            }
            return States(bestNum, bestPick).also { memo[mask] = it }
        }

        val root = solve(0)
        var bestRounds = -1
        for (r in 1 until root.num.size) {
            if (root.num[r] == none) continue
            // 精确比较 num/r，避免 Double 除法在临界值上的取舍差异。
            if (bestRounds < 0 || root.num[r].toLong() * bestRounds > root.num[bestRounds].toLong() * r) bestRounds = r
        }
        check(bestRounds > 0) { "拆牌失败：没有可用的分区" }
        val melds = ArrayList<Meld>(bestRounds)
        var mask = 0
        var rounds = bestRounds
        while (rounds > 0) {
            val states = memo[mask]
            if (states == null) {
                // 该子问题在预算耗尽后按单张收尾，重建时走同一条退路。
                var rest = mask.inv() and full
                repeat(rounds) {
                    melds += candidates[singles[Integer.numberOfTrailingZeros(rest)]]
                    rest = rest and (rest - 1)
                }
                break
            }
            val ci = states.pick[rounds]
            check(ci >= 0) { "拆牌重建失败" }
            val meld = candidates[ci]
            melds += meld
            mask = mask or meld.mask
            rounds -= 1
        }
        val waste = if (n >= 10) melds.count { it.isLowWaste() } else 0
        return HandResult(
            spi = root.num[bestRounds] / 100.0 / bestRounds,
            total = melds.sumOf(Meld::score),
            rounds = bestRounds,
            melds = melds,
            cardCount = n,
            numerator = root.num[bestRounds] / 100.0,
            waste = waste,
        )
    }

    // ---- V4.0 牌权控制力系数 α 与散牌惩罚 P ----

    /** α 的百分数形式：控制型牌型（炸弹、同花顺、火箭、单张大王、单张级牌）115，其余 100。 */
    private fun controlPercent(m: Meld): Int = when (m.type) {
        MeldType.BOMB, MeldType.STRAIGHT_FLUSH, MeldType.ROCKET -> 115
        MeldType.SINGLE -> {
            val rank = m.cards.single().rank
            if (rank == Rank.BIG_JOKER || rank == level) 115 else 100
        }
        else -> 100
    }

    /** 是否属于“点数<10且无法组成有效套牌”的孤立单张/小对子（N_waste 计数对象）。级牌/大小王计点≥15，不算散牌。 */
    private fun Meld.isLowWaste(): Boolean {
        if (type != MeldType.SINGLE && type != MeldType.PAIR) return false
        val natural = cards.firstOrNull { !(it.rank == level && it.suit == Suit.HEART) }
        val point = when {
            natural == null -> 15
            natural.rank == level -> 15
            else -> natural.rank.value
        }
        return point < 10
    }

    /** V4.0 该拆牌单元对 SPI 分子的贡献（百分位整数）：F×α，孤立散牌另扣 15（残局 N<10 张不扣）。 */
    private fun netValue100(m: Meld, handSize: Int): Int =
        m.score * controlPercent(m) - if (handSize >= 10 && m.isLowWaste()) 1500 else 0

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
            combinations(ids, 2).forEach { add(it, MeldType.PAIR, ordinaryMappedScore(3 * base(rank), 6, 20, 51)) }
            combinations(ids, 3).forEach { add(it, MeldType.TRIPLE, ordinaryMappedScore(5 * base(rank), 10, 30, 75)) }
        }
        (2..14).forEach { v ->
            assignments(listOf(v to 2)) { add(it, MeldType.PAIR, ordinaryMappedScore(3*v, 6, 20, 51)) }
            assignments(listOf(v to 3)) { add(it, MeldType.TRIPLE, ordinaryMappedScore(5*v, 10, 30, 75)) }
        }
        // Bombs: natural cards plus zero or more wild hearts, never double-counting the level's wild cards.
        normalRanks.forEach { (rank, ids) ->
            for (naturalCount in 2..minOf(8, ids.size)) for (wc in 0..minOf(wild.size, 10 - naturalCount)) {
                val n = naturalCount + wc
                if (n < 4) continue
                combinations(ids, naturalCount).forEach { ns -> combinations(wild, wc).forEach { ws ->
                    // 逢人配按替代点数计入 Sum：三张5+配与四张5同分，不得越过四张6。
                    val v = base(rank)
                    val sum = n * v
                    // V4.0：6 张及以上炸弹改走对数平滑公式，避免高阶炸弹分值膨胀。
                    val value = when (n) {
                        4 -> sum + 180 + 2*v
                        5 -> sum + 280 + 4*v
                        6 -> 510 + logScale(100, sum + 5*v)
                        7 -> 610 + logScale(90, sum + 6*v)
                        8 -> 710 + logScale(80, sum + 7*v)
                        9 -> 800 + logScale(70, sum + 7*v)
                        10 -> 890 + logScale(60, sum + 5*v)
                        else -> 0
                    }
                    add(ns + ws, MeldType.BOMB, value)
                } }
            }
        }
        // Four jokers.
        val jokers = cards.indices.filter { cards[it].rank.value > 14 }
        if (jokers.size == 4 && jokers.count { cards[it].rank == Rank.SMALL_JOKER } == 2) add(jokers, MeldType.ROCKET, 1000)

        // Straights and straight flushes, including A2345.
        val windows = listOf(listOf(14,2,3,4,5)) + (2..10).map { s -> (s until s+5).toList() }
        windows.forEach { values ->
            val pools = values.map { v -> cards.indices.filter { cards[it].rank.value == v && !cards[it].isWild(level) } }
            cartesian(pools).forEach { ids ->
                val vals = values.map { if (it == 14 && values[1] == 2) 1 else it }
                val sum = vals.sum(); val vmax = vals.max()
                add(ids, MeldType.STRAIGHT, ordinaryMappedScore(sum + vmax, 20, 40, 74))
                if (considerSuits && ids.map { cards[it].suit }.distinct().size == 1) add(ids, MeldType.STRAIGHT_FLUSH, sum + 400 + 3*vmax)
            }
            val vals = values.map { if (it == 14 && values[1] == 2) 1 else it }
            assignments(values.map { it to 1 }) { add(it, MeldType.STRAIGHT, ordinaryMappedScore(vals.sum() + vals.max(), 20, 40, 74)) }
            if (considerSuits) Suit.entries.filter { it != Suit.JOKER }.forEach { suit ->
                assignments(values.map { it to 1 }, suit) { add(it, MeldType.STRAIGHT_FLUSH, vals.sum() + 400 + 3*vals.max()) }
            }
        }
        // Full houses: natural level cards count 15; a wild heart substituting a
        // non-level rank still counts the represented rank.
        allRanks.forEach { (tr, tids) -> allRanks.filterKeys { it != tr }.forEach { (pair, pids) ->
            val score = fullHouseScore(tr.value, pair.value)
            combinations(tids, 3).forEach { t -> combinations(pids, 2).forEach { p ->
                add(t+p, MeldType.FULL_HOUSE, score)
            } }
        } }
        for (tr in 2..14) for (pair in 2..14) if (tr != pair) assignments(listOf(tr to 3, pair to 2)) {
            add(it, MeldType.FULL_HOUSE, fullHouseScore(tr, pair))
        }
        // Exactly three consecutive pairs and exactly two consecutive triples.
        val seqRanks = listOf(14,2,3,4,5,6,7,8,9,10,11,12,13,14)
        for (len in 3..3) for (start in 0..seqRanks.size-len) {
            val vals = seqRanks.subList(start, start+len); if (vals.distinct().size != vals.size) continue
            val pairPools = vals.map { v -> combinations(cards.indices.filter { cards[it].rank.value == v }, 2) }
            cartesianLists(pairPools).forEach { groups ->
                val ids = groups.flatten(); val numeric = vals.mapIndexed { i,v -> if (i==0 && v==14) 1 else v }
                add(ids, MeldType.TRACTOR, ordinaryMappedScore(2*numeric.sum() + 2*numeric.max(), 18, 50, 106))
            }
            val numeric = vals.mapIndexed { i,v -> if (i==0 && v==14) 1 else v }
            assignments(vals.map { it to 2 }) { add(it, MeldType.TRACTOR, ordinaryMappedScore(2*numeric.sum() + 2*numeric.max(), 18, 50, 106)) }
        }
        for (start in 0 until seqRanks.size-1) {
            val vals = seqRanks.subList(start,start+2); if (vals.distinct().size < 2) continue
            val pools = vals.map { v -> combinations(cards.indices.filter { cards[it].rank.value == v }, 3) }
            cartesianLists(pools).forEach { g ->
                val numeric = vals.mapIndexed { i,v -> if (i==0 && v==14) 1 else v }
                add(g.flatten(), MeldType.PLATE, ordinaryMappedScore(3*numeric.sum() + 3*numeric.max(), 15, 60, 123))
            }
            val numeric = vals.mapIndexed { i,v -> if (i==0 && v==14) 1 else v }
            assignments(vals.map { it to 3 }) { add(it, MeldType.PLATE, ordinaryMappedScore(3*numeric.sum() + 3*numeric.max(), 15, 60, 123)) }
        }
        return out.values.toList()
    }

    private fun base(rank: Rank) = if (rank == level) 15 else rank.value

    /** 三带二候选分：级牌（含用于级牌自身的逢人配）为 15，其余为所代表点数。 */
    internal fun fullHouseScore(triple: Int, pair: Int): Int =
        ordinaryMappedScore(5 * baseValue(triple) + 2 * baseValue(pair), 16, 50, 103)

    private fun baseValue(value: Int) = if (value == level.value) 15 else value
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
