package nz.org.aotearoa.guandanscore.imaging

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.InputStream

/**
 * T0.1: normalize any input image into exactly one working bitmap.
 *
 * Steps: read EXIF orientation -> coarse power-of-two downscale -> exact scale to
 * [ImagePreprocessMath.MAX_LONG_EDGE] on the long edge -> apply orientation -> ARGB_8888.
 *
 * Only one working bitmap survives: every intermediate bitmap is recycled before return.
 * This does CPU-heavy decoding; never call it on the main thread.
 */
object ImagePreprocessor {

    fun load(
        resolver: ContentResolver,
        uri: Uri,
        maxLongEdge: Int = ImagePreprocessMath.MAX_LONG_EDGE,
    ): Bitmap {
        val orientation = readOrientation(resolver, uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: throw IllegalArgumentException("cannot open image stream: " + uri)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalArgumentException("cannot read image size: " + uri)
        }

        // ImageDecoder does exact scaling, orientation and software allocation in one pass.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            decodeWithImageDecoder(resolver, uri, maxLongEdge)?.let { return it }
        }

        val sample = ImagePreprocessMath.powerOfTwoSample(bounds.outWidth, bounds.outHeight, maxLongEdge)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val coarse = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IllegalArgumentException("cannot decode image: " + uri)
        return finalize(coarse, maxLongEdge, orientation)
    }

    /**
     * Scale [decoded] to [maxLongEdge] and apply orientation.
     * [decoded] may be recycled; the returned bitmap is always usable.
     */
    fun finalize(decoded: Bitmap, maxLongEdge: Int, orientation: Int): Bitmap {
        val target = ImagePreprocessMath.targetSize(decoded.width, decoded.height, maxLongEdge)
        var current = decoded
        var owned = false

        if (current.width != target.width || current.height != target.height) {
            val scaled = Bitmap.createScaledBitmap(current, target.width, target.height, true)
            if (owned) current.recycle()
            current = scaled
            owned = true
        }

        val transform = ExifOrientation.transform(orientation)
        if (!transform.isIdentity) {
            val matrix = Matrix().apply {
                if (transform.flipHorizontal) {
                    postScale(-1f, 1f, current.width / 2f, current.height / 2f)
                }
                if (transform.degrees != 0) postRotate(transform.degrees.toFloat())
            }
            val rotated = Bitmap.createBitmap(current, 0, 0, current.width, current.height, matrix, true)
            if (owned) current.recycle()
            current = rotated
            owned = true
        }

        if (current.config == Bitmap.Config.ARGB_8888) return current
        val rgb = toRgb(current)
        if (owned || rgb !== current) current.recycle()
        return rgb
    }

    private fun decodeWithImageDecoder(
        resolver: ContentResolver,
        uri: Uri,
        maxLongEdge: Int,
    ): Bitmap? = try {
        val source = android.graphics.ImageDecoder.createSource(resolver, uri)
        val decoded = android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val size = info.size
            val target = ImagePreprocessMath.targetSize(size.width, size.height, maxLongEdge)
            decoder.setTargetSize(target.width, target.height)
            decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
        }
        // ImageDecoder already applied EXIF orientation; do not rotate again.
        val rgb = toRgb(decoded)
        if (rgb !== decoded) decoded.recycle()
        rgb
    } catch (e: Exception) {
        null
    }

    private fun toRgb(source: Bitmap): Bitmap {
        if (source.config == Bitmap.Config.ARGB_8888) return source
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(source, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
        return out
    }

    private fun readOrientation(resolver: ContentResolver, uri: Uri): Int = try {
        resolver.openInputStream(uri)?.use { stream: InputStream ->
            ExifInterface(stream)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (e: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }
}
