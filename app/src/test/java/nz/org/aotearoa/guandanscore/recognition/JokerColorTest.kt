package nz.org.aotearoa.guandanscore.recognition

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import nz.org.aotearoa.guandanscore.model.Rank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 用户口径：**大王是彩色，小王是黑白**。
 *
 * 规则只在两端确定区覆盖模型：彩色占比 ≥ 0.12 判 BJ，≤ 0.01 判 SJ，中间保留模型结论。
 */
@RunWith(RobolectricTestRunner::class)
class JokerColorTest {

    private fun bitmap(color: Int): Bitmap =
        Bitmap.createBitmap(96, 128, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Test
    fun grayscale_crop_is_small_joker() {
        val fraction = JokerColor.coloredFraction(bitmap(Color.LTGRAY))
        assertTrue("灰色 crop 的彩色占比应为 0，实际 $fraction", fraction <= JokerColor.SMALL_JOKER_MAX_FRACTION)
        assertEquals(Rank.SMALL_JOKER, JokerColor.rankFromColor(bitmap(Color.LTGRAY)))
    }

    @Test
    fun saturated_crop_is_big_joker() {
        val fraction = JokerColor.coloredFraction(bitmap(Color.RED))
        assertTrue("纯红 crop 应为彩色，实际 $fraction", fraction >= JokerColor.BIG_JOKER_MIN_FRACTION)
        assertEquals(Rank.BIG_JOKER, JokerColor.rankFromColor(bitmap(Color.RED)))
    }

    @Test
    fun ambiguous_crop_does_not_override_the_model() {
        // 中心区域大部分灰、只有一小块红：占比落在灰区，规则必须弃权。
        val mixed = bitmap(Color.LTGRAY)
        Canvas(mixed).drawRect(10f, 20f, 40f, 50f, Paint().apply { color = Color.RED })
        val fraction = JokerColor.coloredFraction(mixed)
        assertTrue("期望灰区，实际 $fraction", fraction > JokerColor.SMALL_JOKER_MAX_FRACTION &&
            fraction < JokerColor.BIG_JOKER_MIN_FRACTION)
        assertNull(JokerColor.rankFromColor(mixed))
        assertEquals(Rank.SMALL_JOKER, JokerColor.refine(Rank.SMALL_JOKER, mixed))
        assertEquals(Rank.BIG_JOKER, JokerColor.refine(Rank.BIG_JOKER, mixed))
    }

    @Test
    fun ordinary_ranks_are_never_rewritten_by_color() {
        assertEquals(Rank.ACE, JokerColor.refine(Rank.ACE, bitmap(Color.RED)))
        assertEquals(Rank.FIVE, JokerColor.refine(Rank.FIVE, bitmap(Color.LTGRAY)))
    }

    @Test
    fun model_small_joker_on_a_coloured_crop_becomes_big_joker() {
        // 分类器两个 Joker 类串了：模型说 SJ，但 crop 是彩色的，按口径应改判 BJ。
        val manifest = classifierManifest(temperature = 1f, minConfidence = 0.5f)
        val runner = ModelRunner {
            listOf(
                FloatArray(15) { index -> if (index == 13) 0.95f else 0.05f / 14 },
                FloatArray(5) { index -> if (index == 4) 0.95f else 0.05f / 4 },
            )
        }
        val classifier = CardCornerClassifier(runner, manifest)
        val source = bitmap(Color.RED)
        val quad = CardGeometry.obbQuad(48f, 64f, 40f, 30f, 0f)
        val result = classifier.classify(source, quad)
        assertEquals(Rank.BIG_JOKER, result.rank)
        assertEquals(manifest.rankLabels.indexOf("BJ"), result.rankIndex)
    }
}
