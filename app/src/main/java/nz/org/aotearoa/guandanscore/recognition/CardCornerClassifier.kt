package nz.org.aotearoa.guandanscore.recognition

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import nz.org.aotearoa.guandanscore.model.Rank
import nz.org.aotearoa.guandanscore.model.Suit
import kotlin.math.ln
import kotlin.math.max

/** 单次分类结果；[unknown] 为真时调用方必须把它算进人工复核，不得当成已知牌。 */
data class Classification(
    val rankIndex: Int,
    val suitIndex: Int,
    val rank: Rank,
    val suit: Suit,
    val confidence: Float,
    val margin: Float,
    val unknown: Boolean,
)

/**
 * T4.3: 分类器适配层。
 *
 * - 按四点透视拉正到 manifest 的 96×128；
 * - 0°/180° 两次推理，取联合概率更高者（与 `predict.py` 的 TTA 一致）；
 * - 温度缩放后再比较，阈值来自 manifest，代码里不写死数字；
 * - 低置信度或低 margin 返回 `unknown`，交给人工核对。
 */
class CardCornerClassifier(
    private val runner: ModelRunner,
    private val manifest: ClassifierManifest,
) {
    val labelsValid: Boolean = manifest.rankLabels.size == RANKS_EXPECTED && manifest.suitLabels.size == SUITS_EXPECTED

    fun classify(bitmap: Bitmap, quad: Quad): Classification {
        val crop = warp(bitmap, quad)
        return try {
            val forward = infer(crop, 0f)
            val backward = infer(crop, 180f)
            refineJoker(pick(forward, backward), crop)
        } finally {
            if (!crop.isRecycled) crop.recycle()
        }
    }

    /** 从一张已拉正的 crop 推理；[rotation] 只影响输入，不影响标签顺序。 */
    fun infer(crop: Bitmap, rotation: Float = 0f): Classification {
        val source = if (rotation == 0f) crop else rotate(crop, rotation)
        val pixels = try {
            IntArray(source.width * source.height).also {
                source.getPixels(it, 0, source.width, 0, 0, source.width, source.height)
            }
        } finally {
            if (source !== crop && !source.isRecycled) source.recycle()
        }
        val outputs = runner.run(CardGeometry.toInputTensor(pixels, TensorLayout.NHWC))
        val rankRaw = outputs.firstOrNull { it.size == manifest.rankLabels.size } ?: FloatArray(manifest.rankLabels.size)
        val suitRaw = outputs.firstOrNull { it.size == manifest.suitLabels.size } ?: FloatArray(manifest.suitLabels.size)
        return decode(rankRaw, suitRaw)
    }

    /** 四点透视拉正到 crop 尺寸；四点顺序必须是 TL,TR,BR,BL。 */
    fun warp(bitmap: Bitmap, quad: Quad): Bitmap {
        val width = manifest.cropWidth
        val height = manifest.cropHeight
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.WHITE)
        val source = quad.points
        val matrix = Matrix()
        val mapped = matrix.setPolyToPoly(
            floatArrayOf(
                source[0].x, source[0].y,
                source[1].x, source[1].y,
                source[2].x, source[2].y,
                source[3].x, source[3].y,
            ),
            0,
            floatArrayOf(
                0f, 0f,
                (width - 1).toFloat(), 0f,
                (width - 1).toFloat(), (height - 1).toFloat(),
                0f, (height - 1).toFloat(),
            ),
            0,
            PC_COUNT,
        )
        if (!mapped) return output
        canvas.drawBitmap(bitmap, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        return output
    }

    /**
     * 大小王按颜色复核：大王彩色、小王黑白。只在模型判为 Joker 且颜色落在确定区时改判，
     * 灰区保留模型结论——颜色比模型头更稳，但不拿一个会重叠的阈值去硬猜。
     */
    private fun refineJoker(classification: Classification, crop: Bitmap): Classification {
        val refined = JokerColor.refine(classification.rank, crop)
        if (refined == classification.rank) return classification
        val index = when (refined) {
            Rank.BIG_JOKER -> manifest.rankLabels.indexOf("BJ")
            Rank.SMALL_JOKER -> manifest.rankLabels.indexOf("SJ")
            else -> -1
        }.takeIf { it >= 0 } ?: classification.rankIndex
        return classification.copy(rank = refined, rankIndex = index, suit = Suit.JOKER)
    }

    /** 两个方向的联合概率取更高者；分数相同时优先正向，结果可复现。 */
    fun pick(forward: Classification, backward: Classification): Classification =
        if (backward.confidence > forward.confidence) backward else forward

    fun decode(rankRaw: FloatArray, suitRaw: FloatArray): Classification {
        val rankProbabilities = softmaxWithTemperature(rankRaw, manifest.temperature)
        val suitProbabilities = softmaxWithTemperature(suitRaw, manifest.temperature)
        val rankIndex = argmax(rankProbabilities)
        val suitIndex = argmax(suitProbabilities)
        val confidence = rankProbabilities[rankIndex] * suitProbabilities[suitIndex]
        val margin = minOf(
            topMargin(rankProbabilities),
            topMargin(suitProbabilities),
        )
        val rank = rankOf(rankIndex)
        val suit = suitOf(suitIndex, rank)
        return Classification(
            rankIndex = rankIndex,
            suitIndex = suitIndex,
            rank = rank,
            suit = suit,
            confidence = confidence,
            margin = margin,
            unknown = confidence < manifest.minConfidence || margin < manifest.minMargin,
        )
    }

    private fun rankOf(index: Int): Rank {
        val label = manifest.rankLabels.getOrNull(index.coerceIn(0, manifest.rankLabels.lastIndex)) ?: "2"
        return Rank.parse(label) ?: Rank.TWO
    }

    private fun suitOf(index: Int, rank: Rank): Suit {
        if (rank == Rank.SMALL_JOKER || rank == Rank.BIG_JOKER) return Suit.JOKER
        val label = manifest.suitLabels.getOrNull(index.coerceIn(0, manifest.suitLabels.lastIndex)) ?: "SPADE"
        return Suit.entries.firstOrNull { it.name == label }?.takeIf { it != Suit.JOKER } ?: Suit.SPADE
    }

    private fun rotate(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    companion object {
        const val RANKS_EXPECTED = 15
        const val SUITS_EXPECTED = 5
        private const val PC_COUNT = 4

        /** 与 `predict.apply_temperature` 相同：概率 → log/温度 → softmax。 */
        fun softmaxWithTemperature(probabilities: FloatArray, temperature: Float): FloatArray {
            if (probabilities.isEmpty()) return FloatArray(0)
            if (!(temperature > 0f) || temperature == 1f) {
                val total = probabilities.sum().takeIf { it > 0f } ?: 1f
                return FloatArray(probabilities.size) { probabilities[it] / total }
            }
            val scaled = FloatArray(probabilities.size) { ln(max(1e-9f, probabilities[it])) / max(1e-6f, temperature) }
            val peak = scaled.max()
            val exps = FloatArray(scaled.size) { kotlin.math.exp(scaled[it] - peak) }
            val total = exps.sum()
            return FloatArray(exps.size) { exps[it] / total }
        }

        fun argmax(values: FloatArray): Int {
            var best = 0
            for (index in 1 until values.size) if (values[index] > values[best]) best = index
            return best
        }

        fun topMargin(values: FloatArray): Float {
            if (values.size < 2) return values.firstOrNull() ?: 0f
            val sorted = values.sortedDescending()
            return sorted[0] - sorted[1]
        }
    }
}
