package nz.org.aotearoa.guandanscore.recognition

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GeminiScriptsTest {

    @Test
    fun quoteEscapesQuotesAndBackslashes() {
        val q = GeminiScripts.quote("he said \"hi\" and \\ back")
        assertEquals("\"he said \\\"hi\\\" and \\\\ back\"", q)
    }

    @Test
    fun quoteEscapesNewlines() {
        val q = GeminiScripts.quote("line1\nline2")
        assertFalse(q.contains('\n'))
        assertEquals("line1\nline2", JSONObject("{\"v\":$q}").getString("v"))
    }

    @Test
    fun quoteNeutralisesScriptInjection() {
        val evil = "\"); alert('pwned'); (\""
        val q = GeminiScripts.quote(evil)
        // 除首尾包裹引号外，内部不得出现未转义引号
        val inner = q.substring(1, q.length - 1)
        var escaped = 0
        inner.forEachIndexed { i, c -> if (c == '"' && (i == 0 || inner[i - 1] != '\\')) escaped++ }
        assertEquals(0, escaped)
        assertEquals(evil, JSONObject("{\"v\":$q}").getString("v"))
    }

    @Test
    fun quoteHandlesUnicodeLineSeparators() {
        val evil = "a\u2028b\u2029c"
        val q = GeminiScripts.quote(evil)
        assertFalse(q.contains('\u2028'))
        assertFalse(q.contains('\u2029'))
    }

    @Test
    fun fillPromptEmbedsEncodedPrompt() {
        val prompt = "识别\"手牌\"\n并返回 JSON"
        val script = GeminiScripts.fillPrompt(prompt)
        assertTrue(script.contains(GeminiScripts.quote(prompt)))
        // 原始换行不得出现在脚本中
        assertFalse(script.contains(prompt))
    }

    @Test
    fun pasteImageEmbedsEncodedArgs() {
        val name = "a\"b.jpg"
        val script = GeminiScripts.pasteImage(name, "image/jpeg", "QUJD")
        assertTrue(script.contains(GeminiScripts.quote(name)))
        assertTrue(script.contains(GeminiScripts.quote("image/jpeg")))
        assertTrue(script.contains("DataTransfer"))
        assertTrue(script.contains("ClipboardEvent"))
    }

    @Test
    fun diagnoseContainsAllSelectorGroups() {
        val s = GeminiScripts.diagnose()
        assertTrue(s.contains("inputCount"))
        assertTrue(s.contains("responseCount"))
        assertTrue(s.contains("fileInputCount"))
        assertTrue(s.contains("loginIndicatorCount"))
        assertTrue(s.contains("readyState"))
    }

    @Test
    fun diagnoseNeverReadsCookies() {
        val s = GeminiScripts.diagnose().lowercase()
        assertFalse(s.contains("document.cookie"))
        assertFalse(s.contains("localstorage"))
        assertFalse(s.contains("sessionstorage"))
    }

    @Test
    fun snapshotReportsGeneratingFlag() {
        val s = GeminiScripts.snapshot()
        assertTrue(s.contains("lastAnswerText"))
        assertTrue(s.contains("generating"))
    }

    @Test
    fun allScriptsReturnOkField() {
        listOf(
            GeminiScripts.diagnose(),
            GeminiScripts.snapshot(),
            GeminiScripts.submit(),
            GeminiScripts.verifyAttachment()
        ).forEach { assertTrue(it.contains("ok:")) }
    }

    @Test
    fun verifyAttachmentRequiresRealPreview() {
        val s = GeminiScripts.verifyAttachment()
        // 必须有 attached 判定，且基于真实 img/preview 计数
        assertTrue(s.contains("attached"))
        assertTrue(s.contains("thumbCount"))
        assertTrue(s.contains("previewCount"))
    }

    @Test
    fun selectorsAreNonEmpty() {
        listOf(
            GeminiSelectors.input(), GeminiSelectors.send(),
            GeminiSelectors.response(), GeminiSelectors.login(),
            GeminiSelectors.fileInput(), GeminiSelectors.generating()
        ).forEach { assertTrue(it.isNotBlank()) }
    }
}
