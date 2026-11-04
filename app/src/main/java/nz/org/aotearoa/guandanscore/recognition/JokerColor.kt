package nz.org.aotearoa.guandanscore.recognition

import android.graphics.Bitmap
import android.graphics.Color
import nz.org.aotearoa.guandanscore.model.Rank

/**
 * 大小王按颜色区分：**大王（BJ）是彩色，小王（SJ）是黑白线稿**。
 *
 * 分类器两个 Joker 类偶尔会互相串，颜色是比模型更稳的物理依据，所以这里做一个确定性的
 * 覆盖：只在两端“确定区”改判，中间灰区返回 null 保留模型结论，不硬猜。
 *
 * 阈值来自 327 张 Joker crop（T3.1，rotation=0）的实测分布——中心 60% 区域、
 * 饱和度 > 0.25 的像素占比：SJ 中位数 0.012、BJ 中位数 0.163；
 * 两端重叠区（SJ max 0.085 / BJ min 0.023）不参与覆盖。
 */
object JokerColor {
    const val BIG_JOKER_MIN_FRACTION = 0.12f
    const val SMALL_JOKER_MAX_FRACTION = 0.01f
    const val SATURATION_MIN = 0.25f
    private const val CENTER_MARGIN = 0.2f

    /** 中心 60% 区域内高饱和像素比例。 */
    fun coloredFraction(bitmap: Bitmap): Float {
        val left = (bitmap.width * CENTER_MARGIN).toInt()
        val top = (bitmap.height * CENTER_MARGIN).toInt()
        val right = (bitmap.width - left).coerceAtLeast(left + 1)
        val bottom = (bitmap.height - top).coerceAtLeast(top + 1)
        val width = right - left
        val height = bottom - top
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, left, top, width, height)
        val hsv = FloatArray(3)
        var colored = 0
        for (pixel in pixels) {
            Color.RGBToHSV(Color.red(pixel), Color.green(pixel), Color.blue(pixel), hsv)
            if (hsv[1] > SATURATION_MIN) colored++
        }
        return colored.toFloat() / pixels.size
    }

    /** 彩色→[Rank.BIG_JOKER]，黑白→[Rank.SMALL_JOKER]，灰区返回 null。 */
    fun rankFromColor(bitmap: Bitmap): Rank? {
        val fraction = coloredFraction(bitmap)
        return when {
            fraction >= BIG_JOKER_MIN_FRACTION -> Rank.BIG_JOKER
            fraction <= SMALL_JOKER_MAX_FRACTION -> Rank.SMALL_JOKER
            else -> null
        }
    }

    /** 仅当模型判为 Joker 且颜色给出结论时才替换，普通牌不受影响。 */
    fun refine(rank: Rank, bitmap: Bitmap): Rank {
        if (rank != Rank.SMALL_JOKER && rank != Rank.BIG_JOKER) return rank
        return rankFromColor(bitmap) ?: rank
    }
}
