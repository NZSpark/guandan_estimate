package nz.org.aotearoa.guandanscore.imaging

import kotlin.math.roundToInt

/**
 * T0.1: pure arithmetic for input preprocessing (doc/local_engine_tasks.md section 2 M0).
 *
 * The long edge is clamped exactly to [MAX_LONG_EDGE] (not integer-fold downscale),
 * small images are never upscaled, aspect ratio is kept, min edge is at least 1px.
 * EXIF rotation is applied by ImagePreprocessor; this file only computes sizes.
 */
object ImagePreprocessMath {

    /** Long edge ceiling, aligned with UploadImageEncoder.MAX_LONG_EDGE. */
    const val MAX_LONG_EDGE = 1600

    data class Size(val width: Int, val height: Int) {
        val longEdge: Int get() = maxOf(width, height)
    }

    /**
     * Target size after scaling.
     *
     * - long edge <= [maxLongEdge]: returned unchanged (no upscaling).
     * - otherwise scale by maxLongEdge / longEdge, both sides rounded and >= 1.
     */
    fun targetSize(width: Int, height: Int, maxLongEdge: Int = MAX_LONG_EDGE): Size {
        require(width > 0 && height > 0) { "bad image size: " + width + "x" + height }
        require(maxLongEdge > 0) { "bad long edge limit: " + maxLongEdge }
        val longEdge = maxOf(width, height)
        if (longEdge <= maxLongEdge) return Size(width, height)
        val ratio = maxLongEdge.toDouble() / longEdge
        return Size(
            (width * ratio).roundToInt().coerceIn(1, maxLongEdge),
            (height * ratio).roundToInt().coerceIn(1, maxLongEdge),
        )
    }

    /** Scale factor when shrinking is needed; 1.0 otherwise. */
    fun sampleRatio(width: Int, height: Int, maxLongEdge: Int = MAX_LONG_EDGE): Float {
        val longEdge = maxOf(width, height)
        return if (longEdge <= maxLongEdge) 1f else maxLongEdge.toFloat() / longEdge
    }

    /**
     * Power-of-two sample size for BitmapFactory.Options.inSampleSize.
     * Guarantees longEdge / sample >= maxLongEdge, or 1 when no coarse step fits.
     */
    fun powerOfTwoSample(width: Int, height: Int, maxLongEdge: Int = MAX_LONG_EDGE): Int {
        require(width > 0 && height > 0) { "bad image size: " + width + "x" + height }
        val longEdge = maxOf(width, height)
        if (longEdge <= maxLongEdge) return 1
        var sample = 1
        while (longEdge / (sample * 2) >= maxLongEdge) sample *= 2
        return sample
    }
}

/**
 * T0.1: EXIF orientation (1..8) to rotation degrees plus optional horizontal mirror.
 */
object ExifOrientation {

    data class Transform(val degrees: Int, val flipHorizontal: Boolean) {
        val isIdentity: Boolean get() = degrees % 360 == 0 && !flipHorizontal
    }

    fun transform(orientation: Int): Transform = when (orientation) {
        2 -> Transform(0, true)
        3 -> Transform(180, false)
        4 -> Transform(180, true)
        5 -> Transform(90, true)
        6 -> Transform(90, false)
        7 -> Transform(270, true)
        8 -> Transform(270, false)
        else -> Transform(0, false)
    }

    /** Whether the orientation swaps width and height (90/270 degrees). */
    fun swapsAxes(orientation: Int): Boolean = transform(orientation).degrees % 180 != 0
}
