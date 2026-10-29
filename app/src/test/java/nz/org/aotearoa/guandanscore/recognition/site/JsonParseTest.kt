package nz.org.aotearoa.guandanscore.recognition.site

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `evaluateJavascript` 对字符串返回值会再做一次 JSON 编码，
 * 因此 `JSON.stringify(obj)` 到这里变成 `"{\"ok\":true}"`。
 * 漏解这一层会让健康页面一律被判成「未就绪」。
 */
class JsonParseTest {

    @Test
    fun `parses plain object text`() {
        val o = JsonParse.parse("""{"ok":true,"innerHeight":360}""")
        assertNotNull(o)
        assertEquals(true, o!!.optBoolean("ok"))
        assertEquals(360, o.optInt("innerHeight"))
    }

    @Test
    fun `parses quoted json string literal`() {
        // 模拟 evaluateJavascript 对字符串结果做二次编码后的形态。
        val raw = "\"{\\\"ok\\\":true,\\\"href\\\":\\\"https://chat.deepseek.com/\\\",\\\"innerHeight\\\":360}\""
        val o = JsonParse.parse(raw)
        assertNotNull(o)
        assertEquals(true, o!!.optBoolean("ok"))
        assertEquals("https://chat.deepseek.com/", o.optString("href"))
        assertEquals(360, o.optInt("innerHeight"))
    }

    @Test
    fun `blank and null literals return null`() {
        assertNull(JsonParse.parse(""))
        assertNull(JsonParse.parse("   "))
        assertNull(JsonParse.parse("null"))
    }

    @Test
    fun `garbage returns null`() {
        assertNull(JsonParse.parse("not json at all"))
    }
}
