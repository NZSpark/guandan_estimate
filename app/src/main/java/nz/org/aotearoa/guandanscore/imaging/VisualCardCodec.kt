package nz.org.aotearoa.guandanscore.imaging

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import nz.org.aotearoa.guandanscore.model.*

/** Lossless, scale-tolerant footer barcode: magic + count + 27 six-bit cards + checksum. */
object VisualCardCodec {
    private const val MAGIC = 0xA65C
    private const val CARD_COUNT = 27
    private const val BIT_COUNT = 16 + 5 + CARD_COUNT * 6 + 8

    fun draw(canvas: Canvas, cards: List<Card>, width: Int, height: Int) {
        if (cards.size != CARD_COUNT) return
        val codes = cards.map(::encode)
        val checksum = codes.sum() and 0xff
        val bits = mutableListOf<Int>()
        append(bits, MAGIC, 16); append(bits, CARD_COUNT, 5)
        codes.forEach { append(bits, it, 6) }; append(bits, checksum, 8)
        val cell = (width / 225f).coerceAtLeast(2f)
        val left = (width - BIT_COUNT * cell) / 2f
        val top = height - cell * 3.2f
        val p = Paint().apply { style = Paint.Style.FILL; color = Color.WHITE }
        canvas.drawRect(left-cell*2, top-cell, left+(BIT_COUNT+2)*cell, height.toFloat(), p)
        bits.forEachIndexed { i, bit ->
            p.color = if (bit == 1) Color.BLACK else Color.WHITE
            canvas.drawRect(left+i*cell, top, left+(i+1)*cell+.5f, top+cell*2, p)
        }
    }

    fun decode(bitmap: Bitmap): List<Card>? {
        val cell = (bitmap.width / 225f).coerceAtLeast(2f)
        val left = (bitmap.width - BIT_COUNT * cell) / 2f
        val y = (bitmap.height - cell*2.2f).toInt().coerceIn(0, bitmap.height-1)
        val bits = (0 until BIT_COUNT).map { i ->
            val x = (left+(i+.5f)*cell).toInt().coerceIn(0, bitmap.width-1)
            val c = bitmap.getPixel(x,y); if (Color.red(c)+Color.green(c)+Color.blue(c) < 384) 1 else 0
        }
        var at = 0
        fun read(n: Int): Int { var value=0; repeat(n) { value=(value shl 1) or bits[at++] }; return value }
        if (read(16) != MAGIC || read(5) != CARD_COUNT) return null
        val codes = List(CARD_COUNT) { read(6) }
        if (read(8) != (codes.sum() and 0xff)) return null
        return codes.mapIndexed { id, code -> decodeCard(id, code) ?: return null }
    }

    private fun append(out: MutableList<Int>, value: Int, count: Int) { for (i in count-1 downTo 0) out += (value shr i) and 1 }
    private fun encode(card: Card): Int = when (card.rank) {
        Rank.SMALL_JOKER -> 52; Rank.BIG_JOKER -> 53
        else -> card.rank.ordinal*4 + card.suit.ordinal
    }
    private fun decodeCard(id: Int, code: Int): Card? = when (code) {
        52 -> Card(id, Rank.SMALL_JOKER, Suit.JOKER)
        53 -> Card(id, Rank.BIG_JOKER, Suit.JOKER)
        in 0..51 -> Card(id, Rank.entries[code/4], Suit.entries[code%4])
        else -> null
    }
}
