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
    /** V4.1 SPI 分子 = Σ(Fᵢ×αᵢ) − P（残局 N<10 张时 P=0）。 */
    val numerator: Double = spi * rounds,
    /** 散牌惩罚涉及的点数<10孤立单张/小对子数量 N_waste（残局 N<10 时恒为 0）。 */
    val waste: Int = 0,
    /** 是否由穷举搜索证明为全局最优；false 表示达到时间/状态上限后的 anytime 最优解。 */
    val exhaustive: Boolean = true,
) {
    val penalty get() = 15.0 * waste
    val tMax get() = theoreticalMaxRounds(cardCount)
    val nspi get() = spi / tMax * 100.0

    /** V4.1 动态分级：残局1~12张 60/120/180；中局13~18张 70/130/200；开局(≥19张，含19~26过渡) 80/140/220。 */
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

/** V4.1 普通牌型线性映射：原始分从 rawMin..rawMax 线性映射到 finalMin..191（普通牌型最高 191）。 */
internal fun ordinaryMappedScore(raw: Int, rawMin: Int, finalMin: Int, rawMax: Int): Int =
    (finalMin + (raw - rawMin).toDouble() * (191 - finalMin) / (rawMax - rawMin))
        .roundToInt()

/** V4.1 高阶炸弹对数平滑项：floor(multiplier × log₂(1 + delta/100))。 */
private const val LN_2 = 0.6931471805599453
private fun logScale(multiplier: Int, delta: Int): Int =
    (multiplier * ln(1.0 + delta / 100.0) / LN_2).toInt()

/**
 * V4.1 炸弹分值，严格按评分方案 §四：
 * 4/5 炸为线性式，6~10 炸为 offset + floor(系数 × log₂(1 + delta/100))。
 * [value] 为炸弹点数（级牌为 15）；[count] 为总张数（逢人配已并入）。
 */
internal fun bombScore(count: Int, value: Int): Int {
    val sum = count * value
    return when (count) {
        4 -> sum + 180 + 2 * value
        5 -> sum + 280 + 4 * value
        6 -> 523 + logScale(55, sum + 5 * value)
        7 -> 577 + logScale(73, sum + 6 * value)
        8 -> 672 + logScale(75, sum + 7 * value)
        9 -> 784 + logScale(66, sum + 7 * value)
        10 -> 890 + logScale(60, sum + 5 * value)
        else -> throw IllegalArgumentException("炸弹张数必须为 4 至 10：$count")
    }
}

/**
 * 最优拆牌：多策略贪心保底 + 逐层束搜索 + 可中断的精确覆盖 DP。
 *
 * 精确 DP 是 NP-hard 的精确覆盖；达到状态/时间上限时不再用「剩余单张」收尾，
 * 而是保留贪心/束搜索找到的完整方案。穷举完成即证明全局最优并提前返回。
 */
class HandScorer(
    private val level: Rank,
    private val considerSuits: Boolean = true,
    /** 单次评分的总时间预算；穷举完成会提前返回，只有困难手牌才会用满。 */
    internal val budgetNanos: Long = 15_000_000_000L,
    /** 穷举状态上限；超出后保留束搜索/贪心结果。 */
    internal val maxStates: Int = 150_000,
) {
    /** 最近一次搜索的诊断信息；[exact] 为 false 表示穷举未完成、结果为 anytime 最优。 */
    internal data class SearchStats(
        val states: Int,
        val exact: Boolean,
        val beamPasses: Int = 0,
        val dpMs: Long = 0,
        val beamMs: Long = 0,
    )
    internal var lastStats: SearchStats = SearchStats(0, true)
        private set

    fun score(cards: List<Card>): HandResult {
        require(cards.size in 1..27) { "请确认 1 至 27 张牌" }
        require(cards.groupingBy { it.rank to it.suit }.eachCount().values.all { it <= 2 }) { "两副牌中同一张牌最多出现 2 次，请检查识别结果" }
        val n = cards.size
        val candidates = candidates(cards)
        // 分子用「1 = 0.01」的整数累计：α∈{1.00,1.15} 与 P=15 都落在百分位上。
        val net = IntArray(candidates.size) { netValue100(candidates[it], n) }
        val byCard = Array(n) { i ->
            val bit = 1 shl i
            candidates.indices.filter { candidates[it].mask and bit != 0 }
                .sortedByDescending { candidates[it].score }
                .toIntArray()
        }
        // 静态「候选最少优先」，同时作为束搜索与穷举的固定展开顺序。
        val cardOrder = (0 until n).sortedBy { byCard[it].size }.toIntArray()
        val full = (1 shl n) - 1
        val none = Int.MIN_VALUE / 2
        val startedAt = System.nanoTime()
        val deadline = startedAt + budgetNanos
        var beamPasses = 0

        /** 当前最优完整方案（分子按 0.01 累计）。 */
        var bestNum = none
        var bestRounds = -1
        var bestPicks: IntArray? = null

        fun better(num: Int, rounds: Int): Boolean = when {
            bestRounds < 0 -> true
            num.toLong() * bestRounds > bestNum.toLong() * rounds -> true
            num.toLong() * bestRounds < bestNum.toLong() * rounds -> false
            else -> rounds < bestRounds
        }

        fun record(num: Int, rounds: Int, picks: IntArray): Boolean {
            if (rounds <= 0 || picks.size != rounds || !better(num, rounds)) return false
            bestNum = num
            bestRounds = rounds
            bestPicks = picks
            return true
        }

        fun firstUncovered(mask: Int): Int {
            for (i in cardOrder) if (mask and (1 shl i) == 0) return i
            return -1
        }

        // ---------------------------------------------------------------- 贪心保底
        // 始终覆盖「候选最少」的未用牌，按 5 种局部策略各跑一次；即使预算为 0 也能给出完整方案。
        fun greedy(policy: Int) {
            var mask = 0
            var num = 0
            var rounds = 0
            val picks = IntArray(n)
            while (mask != full) {
                val first = firstUncovered(mask)
                var chosen = -1
                var chosenValue = Long.MIN_VALUE
                for (ci in byCard[first]) {
                    val meld = candidates[ci]
                    if (meld.mask and mask != 0) continue
                    val size = Integer.bitCount(meld.mask)
                    val value = when (policy) {
                        0 -> net[ci].toLong() * 1_000
                        1 -> size.toLong() * 1_000_000 + net[ci]
                        2 -> net[ci].toLong() * 1_000 / size
                        3 -> meld.score.toLong() * 1_000 / size
                        else -> meld.score.toLong() * 1_000
                    }
                    if (value > chosenValue) {
                        chosenValue = value
                        chosen = ci
                    }
                }
                check(chosen >= 0) { "拆牌失败：存在无法覆盖的牌" }
                picks[rounds++] = chosen
                num += net[chosen]
                mask = mask or candidates[chosen].mask
            }
            record(num, rounds, picks.copyOf(rounds))
        }
        for (policy in 0..4) greedy(policy)

        // ---------------------------------------------------------------- 束搜索
        class BeamNode(
            val mask: Int,
            val num: Int,
            val rounds: Int,
            val parent: BeamNode?,
            val pick: Int,
            /** 剩余牌的分子上界（0.01 为单位的 1000 倍，便于整数累加）。 */
            val ub: Long,
            /** 已用炸弹/火箭覆盖的牌数；用于保留“炸弹导向”的多样化分支。 */
            val bombCards: Int,
        )

        // 每张牌在任一划分中的分子份额 ≤ 包含它的候选里最大的 net/张数。
        // 求和得到任何补全方案的分子上界（忽略精确覆盖冲突，仍然合法）。
        val share = LongArray(n) { i ->
            var bestShare = Long.MIN_VALUE
            for (ci in byCard[i]) {
                val s = net[ci].toLong() * 1_000 / Integer.bitCount(candidates[ci].mask)
                if (s > bestShare) bestShare = s
            }
            bestShare
        }
        val shareSum = LongArray(candidates.size) { ci ->
            var sum = 0L
            var bits = candidates[ci].mask
            while (bits != 0) {
                sum += share[Integer.numberOfTrailingZeros(bits)]
                bits = bits and (bits - 1)
            }
            sum
        }
        val rootUb = share.sum()

        fun recordNode(node: BeamNode) {
            val picks = IntArray(node.rounds)
            var cur: BeamNode? = node
            var i = node.rounds - 1
            while (cur != null && i >= 0) {
                picks[i] = cur.pick
                cur = cur.parent
                i--
            }
            if (i < 0) record(node.num, node.rounds, picks)
        }

        fun beamBound(node: BeamNode): Double {
            val remaining = n - Integer.bitCount(node.mask)
            val lowerRounds = maxOf(1, (remaining + 9) / 10)
            return (node.num + node.ub / 1_000.0) / 100.0 / (node.rounds + lowerRounds)
        }

        /**
         * 一轮逐层束搜索；宽度越大越接近穷举。返回是否刷新了最优方案。
         * [maxOps] 是硬性展开上限（防挂死），[passDeadline] 是主限制；
         * 单层 [nextCap] 限制候选状态数，避免密堆手牌在内存里无限膨胀。
         */
        fun beamPass(width: Int, maxOps: Int, passDeadline: Long): Boolean {
            var beam = arrayListOf(BeamNode(0, 0, 0, null, -1, rootUb, 0))
            var depth = 0
            var improved = false
            var ops = 0
            while (beam.isNotEmpty() && depth < n && ops < maxOps) {
                val next = HashMap<Int, BeamNode>(minOf(width * 8, 200_000))
                val nextCap = 300_000
                var outOfTime = false
                for (node in beam) {
                    if (ops >= maxOps) break
                    val first = firstUncovered(node.mask)
                    if (first < 0) continue
                    for (ci in byCard[first]) {
                        if ((++ops and 0x3F) == 0 && (System.nanoTime() >= passDeadline || ops >= maxOps)) {
                            outOfTime = true
                            break
                        }
                        val meld = candidates[ci]
                        if (meld.mask and node.mask != 0) continue
                        val newMask = node.mask or meld.mask
                        val newNum = node.num + net[ci]
                        val rounds = node.rounds + 1
                        val bombCards = node.bombCards + if (meld.type == MeldType.BOMB || meld.type == MeldType.ROCKET) Integer.bitCount(meld.mask) else 0
                        if (newMask == full) {
                            if (better(newNum, rounds)) {
                                recordNode(BeamNode(newMask, newNum, rounds, node, ci, 0L, bombCards))
                                improved = true
                            }
                            continue
                        }
                        val old = next[newMask]
                        if (old != null && old.num >= newNum) continue
                        if (old == null && next.size >= nextCap) continue
                        next[newMask] = BeamNode(newMask, newNum, rounds, node, ci, node.ub - shareSum[ci], bombCards)
                    }
                    if (outOfTime) break
                }
                depth++
                if (next.isEmpty()) break
                val incumbent = if (bestRounds > 0) bestNum / 100.0 / bestRounds else Double.NEGATIVE_INFINITY
                var kept: List<BeamNode> = next.values.filter { beamBound(it) + 1e-9 >= incumbent }
                if (kept.size > width) {
                    val byBound = kept.sortedWith(compareByDescending<BeamNode> { beamBound(it) }.thenBy { it.mask })
                    // 保底：一半名额给综合上界最高者，一半给炸弹覆盖最多的分支。
                    // 密堆手牌的最优解常是多炸，但早期上界更偏爱同花顺，纯上界束会把炸弹路径裁掉。
                    val picked = LinkedHashMap<Int, BeamNode>(width * 2)
                    val half = maxOf(1, width / 2)
                    for (node in byBound) {
                        if (picked.size >= half) break
                        picked[node.mask] = node
                    }
                    val byBombs = kept.sortedWith(
                        compareByDescending<BeamNode> { it.bombCards }.thenByDescending { beamBound(it) }.thenBy { it.mask }
                    )
                    for (node in byBombs) {
                        if (picked.size >= width) break
                        if (!picked.containsKey(node.mask)) picked[node.mask] = node
                    }
                    if (picked.size < width) {
                        for (node in byBound) {
                            if (picked.size >= width) break
                            picked[node.mask] = node
                        }
                    }
                    kept = picked.values.toList()
                }
                beam = ArrayList(kept)
                if (outOfTime) break
            }
            beamPasses++
            return improved
        }

        // ---------------------------------------------------------------- 精确覆盖 DP（可中断）
        // 与旧实现的区别：达到上限后只把未穷举的子树按单张收尾，已穷举部分仍然保留；
        // 返回的完整方案即使非全局最优，也通常比纯束搜索更接近最优，最后与束搜索取较优者。
        val singleOf = IntArray(n) { -1 }
        candidates.forEachIndexed { index, meld ->
            if (Integer.bitCount(meld.mask) == 1) singleOf[Integer.numberOfTrailingZeros(meld.mask)] = index
        }
        class PartialStates(val num: IntArray, val pick: IntArray)

        /** @return 穷举是否完整（完整时结果即全局最优）以及记忆化状态数。 */
        fun partialSearch(passDeadline: Long): Pair<Boolean, Int> {
            val memo = HashMap<Int, PartialStates>()
            var fallbackUsed = false
            var stopped = false
            var checks = 0
            var ops = 0

            fun tail(mask: Int): PartialStates {
                fallbackUsed = true
                var num = 0
                var rest = mask.inv() and full
                while (rest != 0) {
                    num += net[singleOf[Integer.numberOfTrailingZeros(rest)]]
                    rest = rest and (rest - 1)
                }
                val rounds = n - Integer.bitCount(mask)
                val values = IntArray(rounds + 1) { none }
                values[rounds] = num
                return PartialStates(values, IntArray(rounds + 1))
            }

            fun solve(mask: Int): PartialStates {
                if (mask == full) return PartialStates(IntArray(1), IntArray(1))
                memo[mask]?.let { return it }
                if (stopped || memo.size >= maxStates || ((++checks and 0xFF) == 0 && System.nanoTime() >= passDeadline)) {
                    // 一旦停搜就冻结：继续展开父节点只会产生大量无意义的候选回填。
                    stopped = true
                    return tail(mask)
                }
                val first = firstUncovered(mask)
                val uncovered = n - Integer.bitCount(mask)
                val bestNum = IntArray(uncovered + 1) { none }
                val bestPick = IntArray(uncovered + 1)
                for (ci in byCard[first]) {
                    if ((++ops and 0x3F) == 0 && System.nanoTime() >= passDeadline) {
                        // 中途停搜同样意味着前沿不完整，必须标记为非穷举，否则会被误报成全局最优。
                        stopped = true
                        fallbackUsed = true
                        break
                    }
                    val meld = candidates[ci]
                    if (meld.mask and mask != 0) continue
                    val child = solve(mask or meld.mask)
                    if (stopped) break
                    val add = net[ci]
                    for (r in child.num.indices) {
                        val value = child.num[r]
                        if (value == none) continue
                        val nr = r + 1
                        val total = value + add
                        if (total > bestNum[nr]) {
                            bestNum[nr] = total
                            bestPick[nr] = ci
                        }
                    }
                }
                // 停搜时可能一个分支都没来得及展开；至少保留单张收尾，保证前沿非空。
                if (bestNum.all { it == none }) return tail(mask)
                return PartialStates(bestNum, bestPick).also { memo[mask] = it }
            }

            val root = solve(0)
            var rounds = -1
            for (r in 1 until root.num.size) {
                if (root.num[r] == none) continue
                if (rounds < 0 || root.num[r].toLong() * rounds > root.num[rounds].toLong() * r) rounds = r
            }
            if (rounds <= 0) return false to memo.size
            val picks = IntArray(rounds)
            var mask = 0
            var r = rounds
            var at = 0
            while (r > 0) {
                val states = memo[mask]
                if (states == null) {
                    // 该子问题在上限触发后按单张收尾；按同一规则重放。
                    var rest = mask.inv() and full
                    repeat(r) {
                        picks[at++] = singleOf[Integer.numberOfTrailingZeros(rest)]
                        rest = rest and (rest - 1)
                    }
                    break
                }
                val ci = states.pick[r]
                check(ci >= 0) { "拆牌重建失败" }
                picks[at++] = ci
                mask = mask or candidates[ci].mask
                r--
            }
            record(root.num[rounds], rounds, picks)
            return !fallbackUsed to memo.size
        }

        var beamMs = 0L
        var t0 = System.nanoTime()
        var exact = false
        var dpStates = 0
        var dpMs = 0L
        if (System.nanoTime() < deadline) {
            t0 = System.nanoTime()
            // 3/4 预算作为防挂死保险；正常情况下先撞状态上限，DP 结果是确定的。
            val dpDeadline = minOf(deadline, System.nanoTime() + budgetNanos * 3 / 4)
            val (complete, states) = partialSearch(dpDeadline)
            exact = complete
            dpStates = states
            dpMs = (System.nanoTime() - t0) / 1_000_000
        }
        if (!exact) {
            // 穷举未完成：按固定宽度阶梯逐级加宽，保留历史最优，结果只增不减。
            for (width in intArrayOf(512, 2_048, 8_192)) {
                if (System.nanoTime() >= deadline) break
                t0 = System.nanoTime()
                val passDeadline = minOf(deadline, System.nanoTime() + 2_000_000_000L)
                beamPass(width, maxOps = 100_000_000, passDeadline = passDeadline)
                beamMs += (System.nanoTime() - t0) / 1_000_000
            }
        }

        val rounds = bestRounds
        check(rounds > 0) { "拆牌失败：没有可用的分区" }
        val melds = bestPicks!!.map { candidates[it] }
        lastStats = SearchStats(
            states = dpStates,
            exact = exact,
            beamPasses = beamPasses,
            dpMs = dpMs,
            beamMs = beamMs,
        )
        val waste = if (n >= 10) melds.count { it.isLowWaste() } else 0
        return HandResult(
            spi = bestNum / 100.0 / rounds,
            total = melds.sumOf(Meld::score),
            rounds = rounds,
            melds = melds,
            cardCount = n,
            numerator = bestNum / 100.0,
            waste = waste,
            exhaustive = exact,
        )
    }

    // ---- V4.1 牌权控制力系数 α 与散牌惩罚 P ----

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

    /** V4.1 该拆牌单元对 SPI 分子的贡献（百分位整数）：F×α，孤立散牌另扣 15（残局 N<10 张不扣）。 */
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
            combinations(ids, 2).forEach { add(it, MeldType.PAIR, pairScore(rank.value)) }
            combinations(ids, 3).forEach { add(it, MeldType.TRIPLE, tripleScore(rank.value)) }
        }
        (2..14).forEach { v ->
            // 级牌对子/三张（含用于级牌自身的逢人配）按 15 计，不能走 3v/5v。
            assignments(listOf(v to 2)) { add(it, MeldType.PAIR, pairScore(v)) }
            assignments(listOf(v to 3)) { add(it, MeldType.TRIPLE, tripleScore(v)) }
        }
        // Bombs: natural cards plus zero or more wild hearts, never double-counting the level's wild cards.
        normalRanks.forEach { (rank, ids) ->
            for (naturalCount in 2..minOf(8, ids.size)) for (wc in 0..minOf(wild.size, 10 - naturalCount)) {
                val n = naturalCount + wc
                if (n < 4) continue
                combinations(ids, naturalCount).forEach { ns -> combinations(wild, wc).forEach { ws ->
                    // 逢人配按替代点数计入 Sum：三张5+配与四张5同分，不得越过四张6。
                    // V4.1：6~9 炸常数按方案 B 重标定，4/5/10 炸不变。
                    add(ns + ws, MeldType.BOMB, bombScore(n, base(rank)))
                } }
            }
        }
        // Four jokers.
        val jokers = cards.indices.filter { cards[it].rank.value > 14 }
        if (jokers.size == 4 && jokers.count { cards[it].rank == Rank.SMALL_JOKER } == 2) add(jokers, MeldType.ROCKET, 1000)

        // Straights and straight flushes, including A2345.
        // V_max 按顺子中的实际点数计（A2345 的 A 记 1；逢人配按填补点），不按级牌的 15。
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

    /** 对子候选分：级牌（含用于级牌自身的逢人配）按 15 计，其余按所代表点数。 */
    internal fun pairScore(value: Int): Int =
        ordinaryMappedScore(3 * baseValue(value), 6, 20, 51)

    /** 三张候选分：级牌（含用于级牌自身的逢人配）按 15 计，其余按所代表点数。 */
    internal fun tripleScore(value: Int): Int =
        ordinaryMappedScore(5 * baseValue(value), 10, 30, 75)

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
