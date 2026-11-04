package nz.org.aotearoa.guandanscore.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** AI-012：上传图片压缩参数的纯算术部分。Bitmap 编码路径需真机验证。 */
class UploadImageEncoderTest {

    @Test
    fun `长边未超限时尺寸不变`() {
        assertEquals(1200 to 900, UploadImageEncoder.targetSize(1200, 900))
        assertEquals(1800 to 100, UploadImageEncoder.targetSize(1800, 100))
    }

    @Test
    fun `横图按长边缩放并保持宽高比`() {
        val (w, h) = UploadImageEncoder.targetSize(4000, 3000)
        assertEquals(1800, w)
        assertEquals(1350, h)
    }

    @Test
    fun `竖图按长边缩放`() {
        val (w, h) = UploadImageEncoder.targetSize(3000, 4000)
        assertEquals(1350, w)
        assertEquals(1800, h)
    }

    @Test
    fun `极端窄长图不会缩到 0`() {
        val (w, h) = UploadImageEncoder.targetSize(100_000, 3)
        assertEquals(1800, w)
        assertTrue(h >= 1)
    }

    @Test
    fun `自定义长边上限`() {
        assertEquals(800 to 600, UploadImageEncoder.targetSize(1600, 1200, maxLongEdge = 800))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `非法尺寸抛异常`() {
        UploadImageEncoder.targetSize(0, 100)
    }

    @Test
    fun `超限且质量高于下限时继续降质量`() {
        assertTrue(UploadImageEncoder.shouldDownscale(byteCount = 2_000_000, quality = 85))
    }

    @Test
    fun `体积达标后停止降质量`() {
        assertFalse(UploadImageEncoder.shouldDownscale(byteCount = 500_000, quality = 85))
    }

    @Test
    fun `已到质量下限时不再继续`() {
        assertFalse(UploadImageEncoder.shouldDownscale(byteCount = 9_000_000, quality = UploadImageEncoder.MIN_QUALITY))
    }

    @Test
    fun `降质量一档且不低于下限`() {
        assertEquals(75, UploadImageEncoder.nextQuality(85))
        assertEquals(UploadImageEncoder.MIN_QUALITY, UploadImageEncoder.nextQuality(65))
        assertEquals(UploadImageEncoder.MIN_QUALITY, UploadImageEncoder.nextQuality(UploadImageEncoder.MIN_QUALITY))
    }

    @Test
    fun `默认参数落在 1600 至 2000 像素区间`() {
        assertTrue(UploadImageEncoder.MAX_LONG_EDGE in 1600..2000)
    }
}
