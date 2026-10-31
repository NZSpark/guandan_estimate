package nz.org.aotearoa.guandanscore.ui

import nz.org.aotearoa.guandanscore.scoring.HandResult
import java.util.Locale
import kotlin.math.abs

/** 主界面与手工拆牌页共用的分数文案；纯函数，便于 JVM 测试。 */
object ScoreText {
    fun optimalTitle(exhaustive: Boolean): String =
        if (exhaustive) "最优拆牌" else "最优拆牌（近似 · 未穷举）"

    fun delta(delta: Double, optimalRounds: Int, manualRounds: Int): String {
        val roundText = when {
            manualRounds > optimalRounds -> "多 ${manualRounds - optimalRounds} 轮"
            manualRounds < optimalRounds -> "少 ${optimalRounds - manualRounds} 轮"
            else -> ""
        }
        val prefix = when {
            abs(delta) <= 0.005 -> "与最优相同"
            delta < 0 -> "较最优 " + String.format(Locale.US, "%.2f", delta)
            else -> "优于最优 +" + String.format(Locale.US, "%.2f", delta)
        }
        return if (roundText.isBlank()) prefix else "$prefix · $roundText"
    }

    /** 主界面来源行：手工拆牌 · 最优 X → Y（差值 · 轮数差）。 */
    fun origin(base: HandResult, manual: HandResult): String = buildString {
        append("手工拆牌 · 最优 ")
        append(String.format(Locale.US, "%.2f", base.spi))
        append(" → ")
        append(String.format(Locale.US, "%.2f", manual.spi))
        append("（")
        append(delta(manual.spi - base.spi, base.rounds, manual.rounds))
        append("）")
        if (!base.exhaustive) append(" · 最优（近似）")
    }

    fun imageLabel(origin: ScoreOrigin): String =
        if (origin is ScoreOrigin.Adjusted) "手工拆牌图" else "最优拆牌图"

    fun optimalBreakdown(base: HandResult): String =
        base.melds.sortedByDescending { it.score }.joinToString("\n") { it.label }
}
