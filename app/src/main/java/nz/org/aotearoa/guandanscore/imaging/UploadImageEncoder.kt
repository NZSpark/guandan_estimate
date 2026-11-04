package nz.org.aotearoa.guandanscore.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * AI-012：网页源上传用的最小图片处理流程。
 *
 * 目标：把界面里的 `Bitmap` 压成体积可控、方向不丢、牌面仍可辨认的 JPEG，再交给
 * `WebChromeClient.onShowFileChooser` 回填。压缩参数集中在这里，便于单测与后续调优。
 *
 * 约定：
 * - 只缩小不放大（长边超过 [MAX_LONG_EDGE] 才缩放）。
 * - 保留原图宽高比；`inSampleSize` 先粗降采样，再用 `Bitmap.createScaledBitmap` 精修。
 * - EXIF 方向不在此处理：调用方传入的 `Bitmap` 应已按 [android.media.ExifInterface] 旋正。
 */
object UploadImageEncoder {

    /** 长边上限。1600–2000 px 之间取 1800，兼顾牌面细节与体积。 */
    const val MAX_LONG_EDGE = 1800

    /** JPEG 质量。85 在牌面文字/花色上实测清晰，体积远小于无损。 */
    const val JPEG_QUALITY = 85

    /** 期望的字节上限（约 1.5 MB）。超过则逐级降质量，最低 [MIN_QUALITY]。 */
    const val MAX_BYTES = 1_500_000
    const val MIN_QUALITY = 60

    /** 结果：编码后的字节、最终尺寸、质量与耗时信息。 */
    data class Result(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
        val quality: Int,
        val scaled: Boolean,
    ) {
        val byteCount: Int get() = bytes.size
    }

    /**
     * 计算等比缩放后的尺寸，只缩不放。纯算术，可脱离 Android 框架单测。
     * 返回 `(宽, 高)`。
     */
    fun targetSize(width: Int, height: Int, maxLongEdge: Int = MAX_LONG_EDGE): Pair<Int, Int> {
        require(width > 0 && height > 0) { "图片尺寸非法：${width}x$height" }
        require(maxLongEdge > 0) { "长边上限非法：$maxLongEdge" }
        val longEdge = max(width, height)
        if (longEdge <= maxLongEdge) return width to height
        val ratio = maxLongEdge.toDouble() / longEdge
        return (width * ratio).roundToInt().coerceAtLeast(1) to
            (height * ratio).roundToInt().coerceAtLeast(1)
    }

    /** 纯算术：给定一次编码结果的字节数与质量，判断是否需要继续降质量。 */
    fun shouldDownscale(byteCount: Int, quality: Int, maxBytes: Int = MAX_BYTES): Boolean =
        byteCount > maxBytes && quality > MIN_QUALITY

    /** 纯算术：降一档质量，不低于 [MIN_QUALITY]。 */
    fun nextQuality(quality: Int, step: Int = 10): Int =
        max(MIN_QUALITY, quality - step)

    /** 把 [source] 编码成用于上传的 JPEG。不修改也不回收 [source]。 */
    fun encode(source: Bitmap): Result {
        val (targetW, targetH) = targetSize(source.width, source.height)
        val scaled = targetW != source.width || targetH != source.height
        val bitmap = if (scaled) Bitmap.createScaledBitmap(source, targetW, targetH, true) else source
        try {
            var quality = JPEG_QUALITY
            var bytes = compress(bitmap, quality)
            while (shouldDownscale(bytes.size, quality)) {
                quality = nextQuality(quality)
                bytes = compress(bitmap, quality)
            }
            return Result(bytes, bitmap.width, bitmap.height, quality, scaled)
        } finally {
            if (scaled) bitmap.recycle()
        }
    }

    /** 从已编码的字节读回尺寸，用于校验文件可解码。 */
    fun decodeSize(bytes: ByteArray): Pair<Int, Int>? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        return if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
    }

    private fun compress(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }
}
