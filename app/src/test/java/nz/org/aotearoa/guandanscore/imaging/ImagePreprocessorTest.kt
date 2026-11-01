package nz.org.aotearoa.guandanscore.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T0.1: pure arithmetic for exact long-edge clamping plus EXIF orientation mapping. */
class ImagePreprocessorTest {

    @Test
    fun `real photos are clamped to exactly 1600 on the long edge`() {
        val wide = ImagePreprocessMath.targetSize(750, 492)
        assertEquals(750, wide.width)
        assertEquals(492, wide.height)

        val tall = ImagePreprocessMath.targetSize(1440, 1920)
        assertEquals(1200, tall.width)
        assertEquals(1600, tall.height)

        val phone = ImagePreprocessMath.targetSize(2736, 3648)
        assertEquals(1200, phone.width)
        assertEquals(1600, phone.height)
    }

    @Test
    fun `small images are never upscaled`() {
        val tiny = ImagePreprocessMath.targetSize(100, 60)
        assertEquals(100, tiny.width)
        assertEquals(60, tiny.height)
        assertEquals(1f, ImagePreprocessMath.sampleRatio(100, 60), 0f)
    }

    @Test
    fun `long edge is capped even for extreme aspect ratios`() {
        val strip = ImagePreprocessMath.targetSize(100_000, 3)
        assertEquals(1600, strip.width)
        assertTrue(strip.height >= 1)
    }

    @Test
    fun `coarse sample never overshoots the target`() {
        assertEquals(1, ImagePreprocessMath.powerOfTwoSample(1600, 1200))
        assertEquals(2, ImagePreprocessMath.powerOfTwoSample(3200, 2400))
        assertEquals(2, ImagePreprocessMath.powerOfTwoSample(2736, 3648))
        assertEquals(4, ImagePreprocessMath.powerOfTwoSample(7000, 5000))
    }

    @Test
    fun `exif orientation maps to rotation and mirror`() {
        assertTrue(ExifOrientation.transform(1).isIdentity)
        assertEquals(ExifOrientation.Transform(180, false), ExifOrientation.transform(3))
        assertEquals(ExifOrientation.Transform(90, false), ExifOrientation.transform(6))
        assertEquals(ExifOrientation.Transform(270, false), ExifOrientation.transform(8))
        assertEquals(ExifOrientation.Transform(0, true), ExifOrientation.transform(2))
        assertTrue(ExifOrientation.transform(6).degrees % 180 != 0)
        assertFalse(ExifOrientation.transform(3).degrees % 180 != 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero sized input is rejected`() {
        ImagePreprocessMath.targetSize(0, 100)
    }
}
