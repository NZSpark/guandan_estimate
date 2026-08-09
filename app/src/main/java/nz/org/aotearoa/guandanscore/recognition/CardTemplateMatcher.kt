package nz.org.aotearoa.guandanscore.recognition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import nz.org.aotearoa.guandanscore.imaging.assetFileName
import nz.org.aotearoa.guandanscore.model.Card
import nz.org.aotearoa.guandanscore.model.Rank
import nz.org.aotearoa.guandanscore.model.Suit
import kotlin.math.max

internal data class TemplateMatch(val rank: Rank, val suit: Suit, val confidence: Double)

/**
 * Offline 54-card corner classifier. It compares the rank-and-pip corner rather than the full
 * artwork, so overlapping cards and face-card illustrations do not dominate the result.
 */
internal class CardTemplateMatcher(context: Context) {
    private data class Template(val rank: Rank, val suit: Suit, val signature: BooleanArray)

    private val assets = context.applicationContext.assets
    private val templates: List<Template> by lazy {
        buildList {
            Rank.entries.filter { it.value <= 14 }.forEach { rank ->
                Suit.entries.filter { it != Suit.JOKER }.forEach { suit ->
                    val card = Card(0, rank, suit)
                    load(card.assetFileName())?.let { bitmap ->
                        add(Template(rank, suit, signature(bitmap, templateCorner(bitmap))))
                        bitmap.recycle()
                    }
                }
            }
            listOf(Rank.SMALL_JOKER, Rank.BIG_JOKER).forEach { rank ->
                val card = Card(0, rank, Suit.JOKER)
                load(card.assetFileName())?.let { bitmap ->
                    add(Template(rank, Suit.JOKER, signature(bitmap, templateCorner(bitmap))))
                    bitmap.recycle()
                }
            }
        }
    }

    fun match(bitmap: Bitmap, rankBox: Rect): TemplateMatch? {
        if (templates.isEmpty() || rankBox.width() < 3 || rankBox.height() < 6) return null
        val w = rankBox.width()
        val h = rankBox.height()
        val corner = Rect(
            (rankBox.left - w * .45f).toInt().coerceAtLeast(0),
            (rankBox.top - h * .30f).toInt().coerceAtLeast(0),
            (rankBox.right + w * .55f).toInt().coerceAtMost(bitmap.width),
            (rankBox.bottom + h * 1.75f).toInt().coerceAtMost(bitmap.height)
        )
        if (corner.width() < 5 || corner.height() < 12) return null
        val observed = signature(bitmap, corner)
        val ranked = templates.map { it to similarity(observed, it.signature) }.sortedByDescending { it.second }
        val best = ranked.firstOrNull() ?: return null
        val second = ranked.getOrNull(1)?.second ?: 0.0
        // A margin over the runner-up prevents generic dark blobs from overriding a valid OCR hit.
        val confidence = (best.second * .82 + max(0.0, best.second - second) * .18).coerceIn(0.0, 1.0)
        return TemplateMatch(best.first.rank, best.first.suit, confidence)
    }

    private fun load(name: String): Bitmap? = assets.open(name).use(BitmapFactory::decodeStream)

    private fun templateCorner(bitmap: Bitmap) = Rect(
        (bitmap.width * .025f).toInt(),
        (bitmap.height * .018f).toInt(),
        (bitmap.width * .245f).toInt(),
        (bitmap.height * .34f).toInt()
    )

    private fun signature(bitmap: Bitmap, source: Rect): BooleanArray {
        val output = BooleanArray(SIGNATURE_WIDTH * SIGNATURE_HEIGHT)
        for (y in 0 until SIGNATURE_HEIGHT) for (x in 0 until SIGNATURE_WIDTH) {
            val sx = (source.left + (x + .5f) * source.width() / SIGNATURE_WIDTH).toInt().coerceIn(0, bitmap.width - 1)
            val sy = (source.top + (y + .5f) * source.height() / SIGNATURE_HEIGHT).toInt().coerceIn(0, bitmap.height - 1)
            val pixel = bitmap.getPixel(sx, sy)
            val red = Color.red(pixel); val green = Color.green(pixel); val blue = Color.blue(pixel)
            val darkness = 255 - minOf(red, green, blue)
            val chroma = maxOf(red, green, blue) - minOf(red, green, blue)
            output[y * SIGNATURE_WIDTH + x] = darkness > 72 || chroma > 58
        }
        return output
    }

    private fun similarity(a: BooleanArray, b: BooleanArray): Double {
        var intersection = 0
        var union = 0
        for (i in a.indices) {
            if (a[i] || b[i]) union++
            if (a[i] && b[i]) intersection++
        }
        return if (union == 0) 0.0 else intersection.toDouble() / union
    }

    private companion object {
        const val SIGNATURE_WIDTH = 36
        const val SIGNATURE_HEIGHT = 72
    }
}
