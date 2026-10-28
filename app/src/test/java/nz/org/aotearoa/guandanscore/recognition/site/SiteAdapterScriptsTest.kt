package nz.org.aotearoa.guandanscore.recognition.site

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** 适配器脚本与解析单测，不依赖真机（AI-110/113）。 */
class SiteAdapterScriptsTest {

    private val tricky = "引号\" 反斜杠\\ 换行\n 制表\t 美元$ 分隔\u2028尾"

    // ---- DeepSeek ----

    @Test
    fun deepSeekScriptsAreValidJsAndJsonReturning() {
        val scripts = listOf(
            DeepSeekScripts.diagnose(),
            DeepSeekScripts.snapshot(),
            DeepSeekScripts.loginCheck(),
            DeepSeekScripts.verifyAttachment(),
            DeepSeekScripts.pageFix(),
            DeepSeekScripts.fillAndSubmit("hi"),
            DeepSeekScripts.pasteImage("a.jpg", "image/jpeg", "QUJD")
        )
        scripts.forEach {
            assertTrue("script should return JSON.stringify", it.contains("JSON.stringify"))
            assertTrue("script should not be blank", it.isNotBlank())
            // 大括号必须成对，粗检语法拼接错误
            assertEquals(it.count { c -> c == '{' }, it.count { c -> c == '}' })
        }
    }

    @Test
    fun deepSeekPromptWithSpecialCharsDoesNotBreakScript() {
        val script = DeepSeekScripts.fillAndSubmit(tricky)
        // JSONObject.quote 会把换行/引号编码掉，脚本里不应出现裸换行
        assertFalse(script.contains("\n引号\n"))
        assertTrue("quote must be escaped", script.contains("\\\""))
    }

    @Test
    fun deepSeekDiagnoseHasAllCounters() {
        val s = DeepSeekScripts.diagnose()
        listOf(
            "inputCount", "sendCount", "responseCount", "fileInputCount",
            "loginIndicatorCount", "passwordCount", "generatingCount", "attachmentCount"
        ).forEach { assertTrue("missing $it", s.contains(it)) }
    }

    @Test
    fun deepSeekSnapshotReportsLoginFields() {
        val s = DeepSeekScripts.snapshot()
        assertTrue(s.contains("passwordCount"))
        assertTrue(s.contains("loginIndicatorCount"))
        assertTrue(s.contains("lastAnswerText"))
    }

    @Test
    fun deepSeekParseSubmitSuccess() {
        val outcome = DeepSeekAdapter().parseSubmitResult(JSONObject("""{"ok":true,"inputFilled":true}"""))
        assertTrue(outcome.ok)
        assertNull(outcome.why)
    }

    @Test
    fun deepSeekParseSubmitFailureKeepsWhy() {
        val outcome = DeepSeekAdapter().parseSubmitResult(
            JSONObject("""{"ok":false,"why":"send-disabled","inputFilled":true}""")
        )
        assertFalse(outcome.ok)
        assertEquals("send-disabled", outcome.why)
        assertTrue(outcome.inputFilled)
    }

    @Test
    fun deepSeekParseSubmitMissingWhyIsStillFailure() {
        val outcome = DeepSeekAdapter().parseSubmitResult(JSONObject("""{"ok":false}"""))
        assertFalse(outcome.ok)
        assertNotNull(outcome.why)
    }

    /** 登录判据必须同时有密码框与登录 UI（M6）。 */
    @Test
    fun deepSeekLoginRequiresPasswordAndLoginUi() {
        val a = DeepSeekAdapter()
        assertTrue(a.parseLogin(JSONObject("""{"passwordCount":1,"loginIndicatorCount":1}""")))
        assertFalse(a.parseLogin(JSONObject("""{"passwordCount":1,"loginIndicatorCount":0}""")))
        assertFalse(a.parseLogin(JSONObject("""{"passwordCount":0,"loginIndicatorCount":3}""")))
    }

    @Test
    fun deepSeekAttachmentRequiresAttachedTrue() {
        val a = DeepSeekAdapter()
        assertTrue(a.parseAttachment(JSONObject("""{"attached":true}""")))
        assertFalse(a.parseAttachment(JSONObject("""{"attached":false,"attachmentCount":0}""")))
    }

    @Test
    fun deepSeekSnapshotMapsFields() {
        val snap = DeepSeekAdapter().parseSnapshot(
            JSONObject("""{"responseCount":3,"lastAnswerText":"abc","generating":false,"passwordCount":0,"loginIndicatorCount":0,"href":"https://x"}""")
        )
        assertEquals(3, snap.responseCount)
        assertEquals("abc", snap.lastAnswerText)
        assertFalse(snap.loginFormPresent)
        assertEquals("https://x", snap.pageUrl)
    }

    /**
     * 2026-10-28 真机实测（LIO-N29 / WebView 114）：
     * - JS `input[type=file].click()` 不触发 onShowFileChooser（需 user activation）
     * - 合成 ClipboardEvent paste 被页面接受并显示预览（虽 dispatchEvent 返回 false）
     * 因此首选 PASTE；FILE_CHOOSER 作为回退，走原生触摸。
     */
    @Test
    fun deepSeekPreferredUploadIsPaste() {
        assertEquals(UploadStrategy.PASTE, DeepSeekAdapter().preferredUpload)
    }

    @Test
    fun deepSeekExposesUploadIconScriptForNativeTap() {
        val script = DeepSeekAdapter().uploadIconScript()
        assertTrue(script.contains("getBoundingClientRect"))
        assertTrue(script.contains("innerHeight"))
    }

    // ---- Gemini ----

    @Test
    fun geminiScriptsAreValidJsonReturning() {
        val scripts = listOf(
            GeminiAdapter().diagnoseScript(),
            GeminiAdapter().snapshotScript(),
            GeminiAdapter().verifyAttachmentScript(),
            GeminiAdapter().fillAndSubmitScript("hi", "a.jpg", "image/jpeg", "QUJD")
        )
        scripts.forEach {
            assertTrue(it.contains("JSON.stringify"))
            assertEquals(it.count { c -> c == '{' }, it.count { c -> c == '}' })
        }
    }

    @Test
    fun geminiPromptWithSpecialCharsDoesNotBreakScript() {
        val script = GeminiAdapter().fillAndSubmitScript(tricky, "a.jpg", "image/jpeg", "QUJD")
        assertTrue("quote must be escaped", script.contains("\\\""))
    }

    @Test
    fun geminiParseSubmitMapsOk() {
        val a = GeminiAdapter()
        assertTrue(a.parseSubmitResult(JSONObject("""{"ok":true}""")).ok)
        val failed = a.parseSubmitResult(JSONObject("""{"ok":false,"why":"composer-not-found"}"""))
        assertFalse(failed.ok)
        assertEquals("composer-not-found", failed.why)
    }

    // ---- Custom ----

    @Test
    fun customAdapterDelegatesParsing() {
        val a = CustomAdapter("https://example.test")
        assertTrue(a.parseAttachment(JSONObject("""{"attached":true}""")))
        assertFalse(a.parseSubmitResult(JSONObject("""{"ok":false}""")).ok)
    }

    // ---- 通用解析工具 ----

    @Test
    fun jsonParseHandlesNullAndGarbage() {
        assertNull(JsonParse.parse(""))
        assertNull(JsonParse.parse("null"))
        assertNull(JsonParse.parse("not json"))
        assertNotNull(JsonParse.parse("""{"ok":true}"""))
    }

    @Test
    fun jsonParseWhyReturnsNullWhenBlank() {
        assertNull(JsonParse.why(JSONObject("""{}""")))
        assertNull(JsonParse.why(JSONObject("""{"why":""}""")))
        assertEquals("x", JsonParse.why(JSONObject("""{"why":"x"}""")))
    }
}
