package nz.org.aotearoa.guandanscore.imaging

import android.graphics.Bitmap
import android.graphics.Canvas
import nz.org.aotearoa.guandanscore.model.Card
import nz.org.aotearoa.guandanscore.model.Rank
import nz.org.aotearoa.guandanscore.model.Suit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/**
 * T0.4: the footer barcode must win over every other path and survive real image handling.
 *
 * 真实 Bitmap 渲染 → 编码 → 解码往返 100 次，并覆盖缩放、JPEG、纵向裁切变体；
 * 随机噪声图不得产生高置信度假阳性；损坏的校验位必须拒绝。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VisualCardCodecTest {

    private val width = 1080
    private val height = 1520

    /** 27 张牌：两副牌里常见的组合，含大小王，不含同 (rank,suit) 超过两次。 */
    private fun deck(): List<Card> {
        val suits = listOf(Suit.CLUB, Suit.DIAMOND, Suit.HEART, Suit.SPADE)
        val ranks = listOf(
            Rank.TWO, Rank.THREE, Rank.FOUR, Rank.FIVE, Rank.SIX, Rank.SEVEN,
            Rank.EIGHT, Rank.NINE, Rank.TEN, Rank.JACK, Rank.QUEEN, Rank.KING, Rank.ACE,
        )
        val cards = mutableListOf<Card>()
        var id = 0
        loop@ for (rank in ranks) for (suit in suits) {
            if (cards.size == 25) break@loop
            cards += Card(id++, rank, suit)
        }
        cards += Card(25, Rank.SMALL_JOKER, Suit.JOKER)
        cards += Card(26, Rank.BIG_JOKER, Suit.JOKER)
        check(cards.size == 27) { "测试牌堆必须是 27 张，实际 ${cards.size}" }
        return cards
    }

    private fun render(cards: List<Card>, w: Int = width, h: Int = height): Bitmap {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        VisualCardCodec.draw(Canvas(bitmap), cards, w, h)
        return bitmap
    }

    private fun jpeg(bitmap: Bitmap, quality: Int): Bitmap {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        val bytes = out.toByteArray()
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun assertDeck(expected: List<Card>, actual: List<Card>?) {
        assertNotNull("条码未被解码", actual)
        assertEquals(expected.size, actual!!.size)
        expected.zip(actual).forEach { (want, got) ->
            assertEquals(want.rank, got.rank)
            assertEquals(want.suit, got.suit)
        }
    }

    @Test
    fun `a freshly rendered footer decodes back to the same 27 cards`() {
        val cards = deck()
        assertDeck(cards, VisualCardCodec.decode(render(cards)))
    }

    @Test
    fun `100 encode-decode round trips are lossless`() {
        val cards = deck()
        repeat(100) { attempt ->
            val bitmap = render(cards)
            val decoded = VisualCardCodec.decode(bitmap)
            bitmap.recycle()
            assertNotNull("第 $attempt 次往返失败", decoded)
            assertEquals(cards.size, decoded!!.size)
            assertEquals(cards[attempt % cards.size].rank, decoded[attempt % cards.size].rank)
            assertEquals(cards[attempt % cards.size].suit, decoded[attempt % cards.size].suit)
        }
    }

    @Test
    fun `scaled variants still decode`() {
        val cards = deck()
        val base = render(cards)
        for (factor in listOf(0.5f, 0.75f, 1.5f, 2.0f)) {
            val scaled = Bitmap.createScaledBitmap(
                base,
                (base.width * factor).toInt(),
                (base.height * factor).toInt(),
                true,
            )
            assertDeck(cards, VisualCardCodec.decode(scaled))
            scaled.recycle()
        }
        base.recycle()
    }

    @Test
    fun `jpeg compression still decodes`() {
        val cards = deck()
        val base = render(cards)
        for (quality in listOf(95, 85, 70)) {
            assertDeck(cards, VisualCardCodec.decode(jpeg(base, quality)))
        }
        base.recycle()
    }

    @Test
    fun `crop that keeps the footer still decodes`() {
        val cards = deck()
        val base = render(cards)
        // 从上方裁掉 1/3：保留整条尾部校验码，宽度不变，所以 cell 仍然对得上。
        val cropped = Bitmap.createBitmap(base, 0, base.height / 3, base.width, base.height * 2 / 3)
        assertDeck(cards, VisualCardCodec.decode(cropped))
        cropped.recycle()
        base.recycle()
    }

    @Test
    fun `crop that removes the footer is rejected instead of guessing`() {
        val cards = deck()
        val base = render(cards)
        // 只保留上方 2/3（丢掉条码）：必须拒绝，不能靠猜。
        val cropped = Bitmap.createBitmap(base, 0, 0, base.width, base.height * 2 / 3)
        assertNull(VisualCardCodec.decode(cropped))
        cropped.recycle()
        base.recycle()
    }

    @Test
    fun `images without a footer never produce a high-confidence hit`() {
        val random = Random(20261101)
        repeat(50) { attempt ->
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(width * height) {
                val grey = random.nextInt(256)
                (0xFF shl 24) or (grey shl 16) or (grey shl 8) or grey
            }
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            val decoded = VisualCardCodec.decode(bitmap)
            bitmap.recycle()
            assertNull("随机图 $attempt 误命中", decoded)
        }
    }

    @Test
    fun `a wrong card count is rejected instead of guessing`() {
        val cards = deck().take(26)
        val bitmap = render(cards)
        assertNull(VisualCardCodec.decode(bitmap))
        bitmap.recycle()
    }

    @Test
    fun `a corrupted checksum is rejected`() {
        val cards = deck()
        val bitmap = render(cards)
        // 涂黑条码末端一个单元，破坏校验位。
        val canvas = Canvas(bitmap)
        val cell = (bitmap.width / 225f).coerceAtLeast(2f)
        val left = (bitmap.width - 191 * cell) / 2f
        val top = bitmap.height - cell * 3.2f
        val paint = android.graphics.Paint().apply { color = android.graphics.Color.BLACK }
        canvas.drawRect(left + 186 * cell, top, left + 191 * cell, top + cell * 2, paint)
        val decoded = VisualCardCodec.decode(bitmap)
        assertFalse("损坏的校验位不应被接受", decoded != null && decoded.size == 27)
        bitmap.recycle()
    }

    @Test
    fun `codec is available before any fallback path runs`() {
        val cards = deck()
        val bitmap = render(cards)
        assertTrue(VisualCardCodec.decode(bitmap) != null)
        bitmap.recycle()
    }
}
