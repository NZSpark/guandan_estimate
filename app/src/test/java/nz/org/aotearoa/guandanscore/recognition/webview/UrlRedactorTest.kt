package nz.org.aotearoa.guandanscore.recognition.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [UrlRedactor] 日志脱敏单测（AI-025）。纯 JVM。 */
class UrlRedactorTest {

    @Test
    fun nullAndEmptyBecomeEmpty() {
        assertEquals("", UrlRedactor.redact(null))
        assertEquals("", UrlRedactor.redact(""))
    }

    @Test
    fun plainUrlUnchanged() {
        assertEquals("https://chat.deepseek.com/", UrlRedactor.redact("https://chat.deepseek.com/"))
    }

    @Test
    fun queryValuesMaskedKeysKept() {
        val out = UrlRedactor.redact("https://a.com/cb?code=SECRET&state=xyz")
        assertEquals("https://a.com/cb?code=${UrlRedactor.MASK}&state=${UrlRedactor.MASK}", out)
        assertFalse(out.contains("SECRET"))
        assertFalse(out.contains("xyz"))
    }

    @Test
    fun fragmentTokenMasked() {
        val out = UrlRedactor.redact("https://a.com/#access_token=SECRET&token_type=bearer")
        assertFalse(out.contains("SECRET"))
        assertTrue(out.startsWith("https://a.com/#access_token="))
    }

    @Test
    fun bareFragmentRouteKept() {
        // SPA 路由片段不含 '='，视为路径，保留便于判断在哪个页面。
        assertEquals("https://a.com/#/chat/123", UrlRedactor.redact("https://a.com/#/chat/123"))
    }

    @Test
    fun pathAndHostPreserved() {
        val out = UrlRedactor.redact("https://gemini.google.com/app/abc?key=SECRET")
        assertTrue(out.startsWith("https://gemini.google.com/app/abc?"))
        assertFalse(out.contains("SECRET"))
    }

    @Test
    fun bareQueryValueMaskedEntirely() {
        // 缓存击穿式裸参数（无键名）也可能是票据。
        assertFalse(UrlRedactor.redact("https://a.com/?123456").contains("123456"))
    }
}
