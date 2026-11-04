package nz.org.aotearoa.guandanscore.recognition.webview

import org.junit.Assert.*
import org.junit.Test

/** [WebViewBridge] 纯逻辑部分单测，不需要 WebView（AI-116）。 */
class WebViewBridgeTest {

    @Test
    fun plainStringUnwraps() {
        assertEquals("abc", WebViewBridge.unescapeJsonString("\"abc\""))
    }

    @Test
    fun escapesAreDecoded() {
        assertEquals("a\nb", WebViewBridge.unescapeJsonString("\"a\\nb\""))
        assertEquals("a\tb", WebViewBridge.unescapeJsonString("\"a\\tb\""))
        assertEquals("a\rb", WebViewBridge.unescapeJsonString("\"a\\rb\""))
        assertEquals("a\"b", WebViewBridge.unescapeJsonString("\"a\\\"b\""))
        assertEquals("a\\b", WebViewBridge.unescapeJsonString("\"a\\\\b\""))
        assertEquals("a/b", WebViewBridge.unescapeJsonString("\"a\\/b\""))
    }

    @Test
    fun unicodeEscapeIsDecoded() {
        assertEquals("\u4e2d\u6587", WebViewBridge.unescapeJsonString("\"\\u4e2d\\u6587\""))
    }

    @Test
    fun nullAndEmptyBecomeEmptyString() {
        assertEquals("", WebViewBridge.unescapeJsonString(null))
        assertEquals("", WebViewBridge.unescapeJsonString(""))
        assertEquals("", WebViewBridge.unescapeJsonString("null"))
    }

    @Test
    fun nonStringPayloadPassesThrough() {
        // JSON 对象没有外层引号，应原样返回，交给 JSONObject 解析
        assertEquals("{\"ok\":true}", WebViewBridge.unescapeJsonString("{\"ok\":true}"))
    }

    @Test
    fun unbalancedQuotePassesThrough() {
        assertEquals("\"abc", WebViewBridge.unescapeJsonString("\"abc"))
    }

    @Test
    fun danglingBackslashIsPreserved() {
        assertEquals("a\\", WebViewBridge.unescapeJsonString("\"a\\\\\"").let { it })
    }
}
