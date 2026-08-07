package nz.org.aotearoa.guandanscore.imaging

import android.graphics.*
import nz.org.aotearoa.guandanscore.model.*
import nz.org.aotearoa.guandanscore.scoring.Meld

class HandImageRenderer {
    private val width = 1800
    private val height = 1200
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun render(cards: List<Card>, title: String, melds: List<Meld>? = null): Bitmap {
        require(cards.size == 27)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        c.drawColor(Color.rgb(20, 75, 52))
        paint.typeface = Typeface.create("sans", Typeface.BOLD)
        paint.textSize = 58f; paint.color = Color.WHITE
        c.drawText(title, 72f, 76f, paint)
        paint.textSize = 29f; paint.typeface = Typeface.DEFAULT
        c.drawText("Aotearoa 掼蛋牌力 · 27张", 72f, 112f, paint)

        val ordered = melds?.flatMap { it.cards } ?: cards
        val groupStarts = melds?.runningFold(0) { sum, m -> sum + m.cards.size }?.dropLast(1)?.toSet().orEmpty()
        val margin = 55f; val gap = 15f; val cardW = (width - margin*2 - gap*8) / 9f; val cardH = 310f
        ordered.forEachIndexed { i, card ->
            val row = i / 9; val col = i % 9
            val left = margin + col*(cardW+gap); val top = 145f + row*(cardH+25f)
            drawCard(c, card, RectF(left, top, left+cardW, top+cardH))
            if (melds != null && i in groupStarts && i > 0) {
                paint.color = Color.rgb(218,164,65); paint.strokeWidth = 7f
                c.drawLine(left-gap/2, top+8, left-gap/2, top+cardH-8, paint)
            }
        }
        VisualCardCodec.draw(c, ordered, width, height)
        return bitmap
    }

    private fun drawCard(c: Canvas, card: Card, r: RectF) {
        paint.color = Color.rgb(250,249,245); paint.style = Paint.Style.FILL
        c.drawRoundRect(r, 17f, 17f, paint)
        paint.color = Color.argb(45,0,0,0); paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f
        c.drawRoundRect(r, 17f, 17f, paint); paint.style = Paint.Style.FILL
        val red = card.suit == Suit.HEART || card.suit == Suit.DIAMOND
        paint.color = if (red) Color.rgb(190,35,39) else Color.rgb(25,27,27)
        paint.typeface = Typeface.create("sans", Typeface.BOLD)
        paint.textSize = if (card.rank.value > 14) 43f else 64f
        val rank = when (card.rank) { Rank.SMALL_JOKER -> "SJ"; Rank.BIG_JOKER -> "BJ"; else -> card.rank.label }
        c.drawText(rank, r.left+17, r.top+66, paint)
        paint.textSize = 62f
        c.drawText(if (card.suit == Suit.JOKER) "★" else card.suit.symbol, r.left+17, r.top+127, paint)
        paint.textSize = if (card.suit == Suit.JOKER) 54f else 118f
        paint.textAlign = Paint.Align.CENTER
        c.drawText(if (card.suit == Suit.JOKER) rank else card.suit.symbol, r.centerX(), r.centerY()+55, paint)
        paint.textAlign = Paint.Align.LEFT
    }
}
