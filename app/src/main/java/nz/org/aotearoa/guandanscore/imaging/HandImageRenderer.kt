package nz.org.aotearoa.guandanscore.imaging

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import nz.org.aotearoa.guandanscore.model.Card
import nz.org.aotearoa.guandanscore.scoring.Meld
import kotlin.math.min

class HandImageRenderer(context: Context) {
    private val assets = context.applicationContext.assets
    private val width = 1800
    private val height = 1200
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val cardImages = mutableMapOf<String, Bitmap>()

    fun render(cards: List<Card>, title: String, melds: List<Meld>? = null): Bitmap {
        require(cards.size == 27)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(20, 75, 52))
        drawHeading(canvas, title)

        val orderedMelds = melds?.sortedByDescending { it.score }
        if (orderedMelds == null) drawRandomGrid(canvas, cards)
        else drawMeldColumns(canvas, orderedMelds)

        VisualCardCodec.draw(canvas, orderedMelds?.flatMap(Meld::cards) ?: cards, width, height)
        return bitmap
    }

    private fun drawHeading(canvas: Canvas, title: String) {
        paint.typeface = Typeface.create("sans", Typeface.BOLD)
        paint.textSize = 58f
        paint.color = Color.WHITE
        canvas.drawText(title, 55f, 68f, paint)
        paint.textSize = 27f
        paint.typeface = Typeface.DEFAULT
        canvas.drawText("Aotearoa 掼蛋牌力 · 27张 · 使用标准牌面素材", 55f, 106f, paint)
    }

    private fun drawRandomGrid(canvas: Canvas, cards: List<Card>) {
        val margin = 55f
        val gapX = 12f
        val gapY = 25f
        val cardWidth = (width - margin * 2 - gapX * 8) / 9f
        val cardHeight = cardWidth * CARD_RATIO
        cards.forEachIndexed { index, card ->
            val row = index / 9
            val column = index % 9
            val left = margin + column * (cardWidth + gapX)
            val top = 135f + row * (cardHeight + gapY)
            drawCard(canvas, card, RectF(left, top, left + cardWidth, top + cardHeight))
        }
    }

    /** Each meld owns one left-to-right column; cards overlap vertically to keep ranks visible. */
    private fun drawMeldColumns(canvas: Canvas, melds: List<Meld>) {
        val margin = 35f
        val gap = 8f
        val columnWidth = (width - margin * 2) / melds.size
        val cardWidth = min(180f, columnWidth - gap)
        val cardHeight = cardWidth * CARD_RATIO
        val cardsTop = 174f
        val bottom = 36f

        melds.forEachIndexed { column, meld ->
            val columnLeft = margin + column * columnWidth
            val left = columnLeft + (columnWidth - cardWidth) / 2f
            paint.color = Color.WHITE
            paint.typeface = Typeface.create("sans", Typeface.BOLD)
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = min(25f, columnWidth * 0.19f)
            canvas.drawText("${meld.type.title} ${meld.score}", columnLeft + columnWidth / 2f, 148f, paint)
            paint.textAlign = Paint.Align.LEFT

            val step = if (meld.cards.size <= 1) 0f else min(
                cardHeight + 8f,
                (height - cardsTop - bottom - cardHeight) / (meld.cards.size - 1)
            )
            meld.cards.forEachIndexed { index, card ->
                val top = cardsTop + index * step
                drawCard(canvas, card, RectF(left, top, left + cardWidth, top + cardHeight))
            }
        }
    }

    private fun drawCard(canvas: Canvas, card: Card, target: RectF) {
        val fileName = card.assetFileName()
        val image = cardImages.getOrPut(fileName) {
            assets.open(fileName).use(BitmapFactory::decodeStream)
                ?: error("无法读取牌面素材：$fileName")
        }
        canvas.drawBitmap(image, null, target, paint)
    }

    private companion object {
        const val CARD_RATIO = 1044f / 750f
    }
}
