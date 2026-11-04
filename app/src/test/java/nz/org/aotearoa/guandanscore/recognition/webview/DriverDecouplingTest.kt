package nz.org.aotearoa.guandanscore.recognition.webview

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI-151 / G-13：驱动层不得含站点知识。
 *
 * 这条约束靠人读代码守不住——新增站点时顺手把选择器塞进驱动层是最省事、
 * 也最容易复发的退化。这里直接读源码做静态断言：站点名、URL 字面量、
 * CSS 选择器一律不允许出现在 `PageDriver` / `WebViewAiSource`。
 */
class DriverDecouplingTest {

    private val driverFiles = listOf(
        "src/main/java/nz/org/aotearoa/guandanscore/recognition/webview/PageDriver.kt",
        "src/main/java/nz/org/aotearoa/guandanscore/recognition/webview/WebViewAiSource.kt"
    )

    private val forbidden = listOf(
        "deepseek", "gemini", "chatgpt",
        "https://", "document.querySelector", "input[type=", "textarea[", "aria-label="
    )

    @Test
    fun driverLayerHasNoSiteKnowledge() {
        driverFiles.forEach { path ->
            val file = File(path)
            assertTrue("missing $path (cwd=${File(".").absolutePath})", file.exists())
            val text = file.readText()
            forbidden.forEach { token ->
                assertFalse("$path must not contain '$token'", text.contains(token, ignoreCase = true))
            }
        }
    }

    /** 反向自检：同样的断言对适配器必须成立，否则说明检查本身失效。 */
    @Test
    fun guardWouldCatchSiteKnowledge() {
        val adapter = File("src/main/java/nz/org/aotearoa/guandanscore/recognition/site/DeepSeekAdapter.kt")
        assertTrue(adapter.exists())
        assertTrue(adapter.readText().contains("deepseek", ignoreCase = true))
    }
}
