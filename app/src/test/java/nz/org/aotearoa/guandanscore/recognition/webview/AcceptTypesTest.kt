package nz.org.aotearoa.guandanscore.recognition.webview

import org.junit.Assert.*
import org.junit.Test

/**
 * AI-009D 真机暴露的 accept 解析问题：
 * `accept` 可达 5868 字符、开头是与图片无关的 `.wxml,.wxss` 等，
 * 若按前 N 个截断日志会看不到 `hasImage=true` 的真相。
 */
class AcceptTypesTest {

    @Test
    fun recognisesMimeAndExtensionImageTypes() {
        assertTrue(AcceptTypes.isImageType("image/jpeg"))
        assertTrue(AcceptTypes.isImageType("image/*"))
        assertTrue(AcceptTypes.isImageType(".png"))
        assertTrue(AcceptTypes.isImageType(".JPG"))
        assertTrue(AcceptTypes.isImageType(".JPEG"))
        assertTrue(AcceptTypes.isImageType(" .webp "))
    }

    @Test
    fun rejectsNonImageTypes() {
        listOf(".wxml", ".wxss", ".pdf", ".txt", ".json", ".go", ".kt", "", "  ", "text/plain")
            .forEach { assertFalse("$it must not be image", AcceptTypes.isImageType(it)) }
    }

    @Test
    fun doesNotMatchExtensionPrefixOnly() {
        // 真实站点 accept 里存在 .pjp/.jfif 等真图片扩展；但 .jpgx 之类不是图片
        assertFalse(AcceptTypes.isImageType(".jpgx"))
        assertFalse(AcceptTypes.isImageType(".pngee"))
        assertTrue(AcceptTypes.isImageType(".pjp"))
        assertTrue(AcceptTypes.isImageType(".jfif"))
    }

    @Test
    fun summarySkipsLeadingNonImageEntries() {
        // 复刻真机 accept 开头：一堆 .wxml/.wxss/.pdf，图片在后
        val types = arrayOf(".wxml", ".wxss", ".wxs", ".pdf", ".png", ".jpg", ".jpeg")
        val summary = AcceptTypes.imageSummary(types, 6)
        assertEquals(".png|.jpg|.jpeg", summary)
        assertEquals(3, AcceptTypes.imageTypes(types).size)
    }

    @Test
    fun summaryTruncatesWithCountSuffix() {
        val types = arrayOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".ico", ".svg")
        val summary = AcceptTypes.imageSummary(types, 3)
        assertTrue(summary.startsWith(".png|.jpg|.jpeg"))
        assertTrue(summary.endsWith("...(+5)"))
        // 摘要必须短，不能把 5868 字符原样带进 logcat
        assertTrue(summary.length < 60)
    }

    @Test
    fun longRealWorldAcceptStaysShort() {
        // 构造接近真机的 900 项列表：90% 非图片
        val types = (1..800).map { ".ext$it" }.toTypedArray() + arrayOf(".png", ".jpg")
        val summary = AcceptTypes.imageSummary(types, 6)
        assertEquals(".png|.jpg", summary)
        assertTrue(summary.length < 60)
    }

    @Test
    fun emptyAndNullInputsAreSafe() {
        assertTrue(AcceptTypes.imageTypes(null).isEmpty())
        assertTrue(AcceptTypes.imageTypes(emptyArray()).isEmpty())
        assertEquals("", AcceptTypes.imageSummary(null))
        assertEquals("", AcceptTypes.imageSummary(emptyArray()))
    }

    @Test
    fun nonImageOnlyAcceptYieldsEmptySummary() {
        val types = arrayOf(".wxml", ".wxss", ".pdf", ".txt")
        assertTrue(AcceptTypes.imageTypes(types).isEmpty())
        assertEquals("", AcceptTypes.imageSummary(types))
    }
}
