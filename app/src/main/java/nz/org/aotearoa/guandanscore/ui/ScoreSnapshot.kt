package nz.org.aotearoa.guandanscore.ui

import nz.org.aotearoa.guandanscore.scoring.HandResult

/**
 * 最优拆牌的只读摘要，传给手工拆牌页做对照。
 *
 * 刻意不携带 melds：子页面只能展示最优分值，不能伪造或改写最优分组。
 */
data class ScoreSnapshot(
    val spi: Double,
    val numerator: Double,
    val rounds: Int,
    val nspi: Double,
    val grade: String,
    val exhaustive: Boolean,
)

fun HandResult.toSnapshot(): ScoreSnapshot = ScoreSnapshot(
    spi = spi,
    numerator = numerator,
    rounds = rounds,
    nspi = nspi,
    grade = grade,
    exhaustive = exhaustive,
)
