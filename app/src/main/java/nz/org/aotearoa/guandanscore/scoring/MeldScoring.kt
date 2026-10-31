package nz.org.aotearoa.guandanscore.scoring

import nz.org.aotearoa.guandanscore.model.Rank
import nz.org.aotearoa.guandanscore.model.Suit
import kotlin.math.ln
import kotlin.math.roundToInt

/** V4.2 普通牌型线性映射：原始分从 rawMin..rawMax 线性映射到 finalMin..191（普通牌型最高 191，四舍五入）。 */
internal fun ordinaryMappedScore(raw: Int, rawMin: Int, finalMin: Int, rawMax: Int): Int =
    (finalMin + (raw - rawMin).toDouble() * (191 - finalMin) / (rawMax - rawMin))
        .roundToInt()

/** V4.2 高阶炸弹对数平滑项：floor(multiplier × log₂(1 + delta/100))，加微小量避免浮点误差落在 n-ε。 */
private const val LN_2 = 0.6931471805599453

private fun logScale(multiplier: Int, delta: Int): Int =
    kotlin.math.floor(multiplier * ln(1.0 + delta / 100.0) / LN_2 + 1e-9).toInt()

/**
 * V4.2 炸弹分值，严格按评分方案 §四：
 * 4/5 炸为线性式，6~10 炸为 offset + floor(系数 × log₂(1 + delta/100))。
 * [value] 为炸弹点数（级牌为 15）；[count] 为总张数（红心配已并入）。
 * 9/10 炸的 810 / 912 是跨级牌局的全局最小值；级牌为 2 时的有效最低构成见 §4.3（821 / 922）。
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

/** α 的百分数形式：控制型牌型（炸弹、同花顺、火箭、单张大王、单张自然级牌）115，其余 100。 */
internal fun controlPercent(meld: Meld, level: Rank): Int = when (meld.type) {
    MeldType.BOMB, MeldType.STRAIGHT_FLUSH, MeldType.ROCKET -> 115
    MeldType.SINGLE -> {
        val rank = meld.cards.single().rank
        if (rank == Rank.BIG_JOKER || rank == level) 115 else 100
    }
    else -> 100
}

/** 控制型出牌单元（N_control 与 α=1.15 共用同一判定，按出牌单元计数）。 */
internal fun Meld.isControlMeld(level: Rank): Boolean = controlPercent(this, level) == 115

/** 是否属于“点数<10且无法组成有效套牌”的孤立单张/小对子（N_waste 计数对象）。级牌/大小王计点≥15，不算散牌。 */
internal fun Meld.isLowWaste(level: Rank): Boolean {
    if (type != MeldType.SINGLE && type != MeldType.PAIR) return false
    val natural = cards.firstOrNull { !(it.rank == level && it.suit == Suit.HEART) }
    val point = when {
        natural == null -> 15
        natural.rank == level -> 15
        else -> natural.rank.value
    }
    return point < 10
}

/**
 * V4.2 该拆牌单元对 SPI 分子的贡献（百分位整数）：F×α，孤立散牌另扣 15。
 * 综合评估 §4.3 未设张数豁免：残局（N<10）同样按出牌单元计 P（见 §八 示例 2）。
 */
internal fun netValue100(meld: Meld, level: Rank): Int =
    meld.score * controlPercent(meld, level) - if (meld.isLowWaste(level)) 1500 else 0
