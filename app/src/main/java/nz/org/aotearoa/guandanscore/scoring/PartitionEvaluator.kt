package nz.org.aotearoa.guandanscore.scoring

import nz.org.aotearoa.guandanscore.model.Card
import nz.org.aotearoa.guandanscore.model.Rank

/**
 * 手工分区 → Meld → HandResult。
 *
 * 与优化器共用 [MeldEvaluator] 与 [MeldScoring]；这里不重新实现任何牌型或计分公式。
 */
object PartitionEvaluator {

    /** 把手牌 + 手工分区解析成 Meld 列表；任一不合法即抛 IllegalArgumentException。 */
    fun resolve(hand: List<Card>, groups: List<MeldGroup>, level: Rank): List<Meld> {
        validatePartition(hand.size, groups)
        return groups.map { group ->
            MeldEvaluator.interpretations(hand, group.indices, level, considerSuits = true)
                .filter { it.type == group.type }
                // 同一组牌可能有多条同类型解释（如 5 张 A 的 4 炸/5 炸），
                // 必须取该类型下的最高分，否则会静默退化成较小炸弹。
                .maxByOrNull { it.score }
                ?: throw IllegalArgumentException("该组合不是合法牌型")
        }
    }

    /** 与 HandScorer.score() 完全相同的汇总口径；手工结果不是搜索证明的全局最优。 */
    fun evaluate(hand: List<Card>, groups: List<MeldGroup>, level: Rank): HandResult =
        summarize(hand.size, level, resolve(hand, groups, level), exhaustive = false)

    /**
     * 优化器与手工路径共用；level 参与 α 与散牌判定，handSize 决定阶段与 T_max。
     * 散牌惩罚按出牌单元计，残局（N<10）同样适用（综合评估 §4.3）。
     * [exhaustive] 只描述来源：优化器通过时 true，手工分区固定 false。
     */
    internal fun summarize(
        handSize: Int,
        level: Rank,
        melds: List<Meld>,
        exhaustive: Boolean = true,
    ): HandResult {
        require(melds.isNotEmpty()) { "分区不完整，请重置后重试" }
        val rounds = melds.size
        val numerator = melds.sumOf { netValue100(it, level) } / 100.0
        return HandResult(
            spi = numerator / rounds,
            total = melds.sumOf(Meld::score),
            rounds = rounds,
            melds = melds,
            cardCount = handSize,
            numerator = numerator,
            waste = melds.count { it.isLowWaste(level) },
            exhaustive = exhaustive,
            control = melds.count { it.isControlMeld(level) },
        )
    }
}
