package nz.org.aotearoa.guandanscore.scoring

import nz.org.aotearoa.guandanscore.model.*

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
    /** SPI 分子 = Σ(Fᵢ×αᵢ) − P；综合评估 §4.3 未设张数豁免，残局同样计散牌惩罚。 */
    val numerator: Double = spi * rounds,
    /** 散牌惩罚涉及的点数<10孤立单张/小对子数量 N_waste（按出牌单元计数）。 */
    val waste: Int = 0,
    /** 是否由穷举搜索证明为全局最优；false 表示达到时间/状态上限后的 anytime 最优解。 */
    val exhaustive: Boolean = true,
    /** 控制型出牌单元数量 N_control（炸弹、同花顺、火箭、单张大王、单张自然级牌）。 */
    val control: Int = 0,
) {
    val penalty get() = 15.0 * waste
    val tMax get() = theoreticalMaxRounds(cardCount)
    val nspi get() = spi / tMax * 100.0

    /** 残局控制覆盖比 R_control = N_control / (T_plan − 1)；T_plan=1 时无定义（单次出牌方案）。 */
    val controlRatio: Double? get() = if (rounds >= 2) control.toDouble() / (rounds - 1) else null

    /** 阶段划分（综合评估 §5.1）：19~27 开局/前中局、10~18 中局、1~9 残局。 */
    val stage get() = when (cardCount) {
        in 19..27 -> "开局/前中局"
        in 10..18 -> "中局"
        else -> "残局"
    }

    /**
     * 经验参考评级（综合评估 §5.2）：27 张 80/140/220；13~18 张 70/130/200；1~9 张（辅助）60/120/180。
     * 19~26 张与 10~12 张没有阈值依据，按 §5.2 / T13 输出「待标定」，不外推相邻区间。
     */
    val grade get() = when (cardCount) {
        27 -> graded(80.0, 140.0, 220.0)
        in 13..18 -> graded(70.0, 130.0, 200.0)
        in 1..9 -> graded(60.0, 120.0, 180.0)
        else -> "待标定"
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
        val candidates = MeldEvaluator.candidates(cards, level, considerSuits)
        // 分子用「1 = 0.01」的整数累计：α∈{1.00,1.15} 与 P=15 都落在百分位上。
        val net = IntArray(candidates.size) { netValue100(candidates[it], level) }
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

        check(bestRounds > 0) { "拆牌失败：没有可用的分区" }
        val melds = bestPicks!!.map { candidates[it] }
        lastStats = SearchStats(
            states = dpStates,
            exact = exact,
            beamPasses = beamPasses,
            dpMs = dpMs,
            beamMs = beamMs,
        )
        return PartitionEvaluator.summarize(n, level, melds, exhaustive = exact)
    }

    /** 对子候选分：级牌（含用于级牌自身的红心配）按 15 计，其余按所代表点数。 */
    internal fun pairScore(value: Int): Int = MeldEvaluator.pairScore(value, level)

    /** 三张候选分：级牌（含用于级牌自身的红心配）按 15 计，其余按所代表点数。 */
    internal fun tripleScore(value: Int): Int = MeldEvaluator.tripleScore(value, level)

    /** 三带二候选分：级牌（用于级牌自身或替代非级牌）按规则计分，其余按所代表点数。 */
    internal fun fullHouseScore(triple: Int, pair: Int): Int = MeldEvaluator.fullHouseScore(triple, pair, level)

}
