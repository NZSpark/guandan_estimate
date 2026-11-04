package nz.org.aotearoa.guandanscore.recognition.site

import nz.org.aotearoa.guandanscore.recognition.GeminiScripts
import nz.org.aotearoa.guandanscore.recognition.GeminiSelectors
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
            DeepSeekScripts.fillAndSend("hi"),
            DeepSeekScripts.sendState(),
            DeepSeekScripts.pasteImage("a.jpg", "image/jpeg", "QUJD"),
            DeepSeekScripts.deepProbe(),
            DeepSeekScripts.fileInputProbe()
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
        val script = DeepSeekScripts.fillAndSend(tricky)
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
    fun deepSeekFileInputProbeIsReadOnlyAndReportsAttributes() {
        val s = DeepSeekScripts.fileInputProbe()
        // 只读探测：不得触发文件选择器（JS click 无 user activation，永远不会成功）
        assertFalse("probe must not click", s.contains(".click("))
        listOf("fileInputCount", "acceptLength", "acceptHasImage", "multiple", "capture", "hidden", "disabled")
            .forEach { assertTrue("missing $it", s.contains(it)) }
        // accept 实测可达 5868 字符，禁止全量回传撑爆 logcat
        assertTrue(s.contains("acceptHead"))
    }

    @Test
    fun deepSeekStopControlProbeIsReadOnlyAndDeclaresAbsence() {
        val s = DeepSeekScripts.stopControlProbe()
        assertTrue(s.contains("JSON.stringify"))
        assertFalse("probe must not click", s.contains(".click("))
        listOf("presetCount", "labelHits", "visibleButtonCount", "note")
            .forEach { assertTrue("missing $it", s.contains(it)) }
        // 0 命中时必须给出「不得作为结束判定前提」的机器可读结论
        assertTrue(s.contains("must-not-be-used-as-completion-signal"))
        assertTrue(s.contains("停止"))
    }

    @Test
    fun onlyDeepSeekImplementsStopControlProbe() {
        assertTrue(GeminiAdapter().stopControlProbeScript().isEmpty())
        assertTrue(DeepSeekAdapter().stopControlProbeScript().isNotEmpty())
    }

    @Test
    fun deepSeekDeepProbeReportsStructureFields() {
        val s = DeepSeekScripts.deepProbe()
        listOf("inputUnique", "inputIsInnermost", "sendClickable", "hasShadowDom", "readyState")
            .forEach { assertTrue("missing $it", s.contains(it)) }
    }

    @Test
    fun onlyDeepSeekSupportsFileInputProbe() {
        // 默认实现为空串：没有该能力的源不得假装具备
        assertTrue(GeminiAdapter().fileInputProbeScript().isEmpty())
        assertTrue(DeepSeekAdapter().fileInputProbeScript().isNotEmpty())
    }

    @Test
    fun geminiDeepProbeIsSelectorProbeAndReadOnly() {
        // AI-030：Gemini 用 deepProbe 挂点承载「逐候选命中数」探针，必须是只读的。
        val s = GeminiAdapter().deepProbeScript()
        assertTrue(s.isNotEmpty())
        assertTrue(s.contains("firstHit"))
        assertFalse(s.contains(".click("))
        assertFalse(s.contains("dispatchEvent"))
        assertFalse(s.contains("document.cookie"))
        assertFalse(s.contains("localStorage"))
        assertFalse(s.contains("while"))
        assertTrue(DeepSeekAdapter().deepProbeScript().isNotEmpty())
    }

    @Test
    fun deepSeekParseSubmitSuccess() {
        val outcome = DeepSeekAdapter().parseSubmitResult(
            JSONObject("""{"ok":true,"inputFilled":true,"sentEnter":true,"valueEmpty":false}""")
        )
        assertTrue(outcome.ok)
        assertNull(outcome.why)
    }

    /** AI-175：只返回 ok 但没有发送动作时不得判成功。 */
    @Test
    fun deepSeekSubmitWithoutSendEvidenceFails() {
        val a = DeepSeekAdapter()
        val noSend = a.parseSubmitResult(
            JSONObject("""{"ok":true,"inputFilled":true,"sentEnter":false,"sendClicked":false,"valueEmpty":false,"sendDisabled":true}""")
        )
        assertFalse(noSend.ok)
        assertEquals("send-disabled", noSend.why)
        assertTrue(noSend.inputFilled)

        val notFilled = a.parseSubmitResult(
            JSONObject("""{"ok":true,"inputFilled":false,"sentEnter":true}""")
        )
        assertFalse(notFilled.ok)
        assertEquals("composer-not-filled", notFilled.why)
    }

    /** AI-172：填文本与发送必须在同一个 JS turn 内，并回报可核对诊断字段。 */
    @Test
    fun deepSeekFillAndSendIsSingleTurnWithDiagnostics() {
        val script = DeepSeekScripts.fillAndSend("hi")
        listOf("inputFilled", "valueEmpty", "sentEnter", "sendClicked", "sendClickable", "url")
            .forEach { assertTrue("missing $it", script.contains(it)) }
        // 反证：不得出现“点击后等待返回”的同步忙等（会卡死事件循环）。
        assertFalse(script.contains("while ("))
        assertTrue("Enter path stays the primary submit", script.contains("KeyboardEvent('keydown'"))
    }

    /** AI-173：sendState 区分 disabled 与可点，并带上 composer 是否为空。 */
    @Test
    fun deepSeekParseSendState() {
        val a = DeepSeekAdapter()
        val disabled = a.parseSendState(
            JSONObject("""{"ok":true,"sendCount":1,"sendDisabled":true,"sendClickable":false,"valueEmpty":false}""")
        )
        assertTrue(disabled.sendDisabled)
        assertFalse(disabled.sendClickable)
        assertFalse(disabled.valueEmpty)

        val ready = a.parseSendState(
            JSONObject("""{"ok":true,"sendCount":1,"sendDisabled":false,"sendClickable":true,"valueEmpty":false}""")
        )
        assertTrue(ready.sendClickable)
        assertFalse(ready.sendDisabled)
    }

    /** AI-174：会话页 URL 形态由适配器判断，不在驱动层写死。 */
    @Test
    fun deepSeekSessionUrlDetection() {
        val a = DeepSeekAdapter()
        assertTrue(a.isSessionUrl("https://chat.deepseek.com/a/chat/s/abc123"))
        assertFalse(a.isSessionUrl("https://chat.deepseek.com/sign_in"))
        assertFalse(a.isSessionUrl("https://chat.deepseek.com"))
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
            JSONObject(
                """{"responseCount":3,"lastAnswerText":"abc","generating":false,"passwordCount":0,"loginIndicatorCount":0,"href":"https://x","inputText":" 残留 ","attachmentCount":1,"attached":true,"sessionId":"s1"}"""
            )
        )
        assertEquals(3, snap.responseCount)
        assertEquals("abc", snap.lastAnswerText)
        assertFalse(snap.loginFormPresent)
        assertEquals("https://x", snap.pageUrl)
        // AI-042：发送前输入区现状与会话标识必须可读，供脏输入区判断。
        assertEquals("残留", snap.inputText)
        assertTrue(snap.attached)
        assertTrue(snap.composerDirty)
        assertEquals("s1", snap.sessionId)
    }

    /** AI-042：所有适配器都必须能读出输入区/附件/会话标识，不能只对 DeepSeek 生效。 */
    @Test
    fun everyAdapterParsesComposerAndSessionFields() {
        val json = JSONObject(
            """{"responseCount":0,"lastAnswerText":"","generating":false,"passwordCount":0,"loginIndicatorCount":0,"href":"https://x","inputText":"abc","attached":true,"sessionId":"sid"}"""
        )
        listOf(DeepSeekAdapter(), GeminiAdapter(), ChatGPTAdapter()).forEach { adapter ->
            val snap = adapter.parseSnapshot(json)
            assertEquals(adapter.siteKey, "abc", snap.inputText)
            assertTrue(adapter.siteKey, snap.attached)
            assertEquals(adapter.siteKey, "sid", snap.sessionId)
        }
    }

    /**
     * AI-042/AI-082：附件判据必须限定在 composer 内。
     * 全局计数会把历史消息里的 blob 图片当成“待发送附件”（真机已复现：第二轮误判）。
     */
    @Test
    fun attachmentVerificationIsScopedToComposer() {
        listOf(DeepSeekAdapter(), GeminiAdapter(), ChatGPTAdapter()).forEach { adapter ->
            val script = adapter.verifyAttachmentScript()
            assertTrue(adapter.siteKey, script.contains("composer-not-found"))
            assertTrue(adapter.siteKey, script.contains("box.querySelectorAll"))
        }
    }

    /** 输入区干净时 snapshot 不得被误判为脏（否则每轮都白建新会话）。 */
    @Test
    fun cleanSnapshotIsNotDirty() {
        val snap = AnswerSnapshot(0, "", false, false, "", inputText = "  ", attached = false)
        assertFalse(snap.composerDirty)
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
            GeminiAdapter().fillAndSubmitScript("hi", "a.jpg", "image/jpeg", "QUJD"),
            GeminiAdapter().fillComposerScript("hi"),
            GeminiAdapter().submitComposerScript(),
            GeminiAdapter().uploadMenuIconScript()
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
        assertTrue(a.parseSubmitResult(JSONObject("""{"ok":true,"submitted":true}""")).ok)
        val failed = a.parseSubmitResult(JSONObject("""{"ok":false,"why":"composer-not-found"}"""))
        assertFalse(failed.ok)
        assertEquals("composer-not-found", failed.why)
    }

    // ---- Gemini 图片上传 ----

    /**
     * AI-182 真机结论：离屏宿主没有 document 焦点，Gemini 会丢弃合成 paste；
     * 生产路径改为原生文件选择，并且填文本、上传、提交三段拆开。
     */
    @Test
    fun geminiUsesFileChooserAfterFill() {
        val a = GeminiAdapter()
        assertTrue(a.uploadsAfterFill)
        assertFalse(a.uploadsInsideSubmit)
        assertEquals(UploadStrategy.FILE_CHOOSER, a.preferredUpload)
        assertEquals(setOf(UploadStrategy.FILE_CHOOSER), a.supportedUploads)
        assertTrue(a.uploadMenuIconScript().isNotEmpty())
    }

    /** AI-182：拆开的两个脚本各自给出可解析的完成证据。 */
    @Test
    fun geminiSplitScriptsCarryPositiveEvidence() {
        val fill = GeminiScripts.fillComposer("hi")
        assertTrue(fill.contains("inputFilled"))
        assertTrue(fill.contains("execCommand('insertText'"))

        val submit = GeminiScripts.submit()
        assertTrue(submit.contains("sendClicked"))
        assertTrue(submit.contains("send-button-disabled"))
        assertTrue(submit.contains("btn.click()"))
        assertFalse("submit must not rewrite the composer", submit.contains("textContent ="))
    }

    @Test
    fun geminiSubmitScriptFillsThenPastesThenClicksSend() {
        val script = GeminiScripts.fillPasteAndSubmit("hi", "a.png", "image/png", "QUJD")
        assertTrue(script.contains("JSON.stringify"))
        val openBraces = script.count { it == '{' }
        val closeBraces = script.count { it == '}' }
        assertEquals("balanced braces", openBraces, closeBraces)

        assertTrue("fills composer", script.contains("textContent = text"))
        assertTrue("constructs File", script.contains("new File("))
        assertTrue("uses DataTransfer", script.contains("new DataTransfer()"))
        assertTrue("dispatches ClipboardEvent paste", script.contains("ClipboardEvent('paste'"))
        assertTrue("focus before paste", script.contains("el.focus()"))
        assertTrue("clicks send", script.contains("btn.click()"))

        // 顺序必须：写文本 → 粘贴 → 发送。
        val fillAt = script.indexOf("textContent = text")
        val pasteAt = script.indexOf("ClipboardEvent('paste'")
        val sendAt = script.indexOf("btn.click()")
        assertTrue("fill before paste", fillAt in 0 until pasteAt)
        assertTrue("paste before send", pasteAt in 0 until sendAt)

        // 发送按钮由 Angular 异步渲染：必须用 setTimeout 重试，不能同步忙等
        // （忙等阻塞事件循环，Angular 永远渲染不出来）。
        assertTrue("async retry via setTimeout", script.contains("setTimeout(retry, 50)"))
        assertFalse("no synchronous busy-wait", script.contains("while ("))
        assertTrue("records submit path", script.contains("submitVia"))
    }

    @Test
    fun geminiPasteFailureDoesNotBlockSubmit() {
        val script = GeminiScripts.fillPasteAndSubmit("hi", "a.png", "image/png", "QUJD")
        assertTrue(script.contains("pasteWhy"))
        // 粘贴失败不得阻断提交：submitted 由发送分支赋值，不是硬编码常量。
        assertTrue(script.contains("result.submitted = true"))
        assertFalse(script.contains("submitted: true"))
    }

    @Test
    fun geminiSubmitRequiresSubmittedFlag() {
        val a = GeminiAdapter()
        // 仅写入文本、未提交 → 必须判失败
        val notSubmitted = a.parseSubmitResult(
            JSONObject("""{"ok":true,"filled":true,"pasted":false,"submitted":false}""")
        )
        assertFalse(notSubmitted.ok)
        assertTrue(notSubmitted.inputFilled)
        assertEquals("submit-not-dispatched", notSubmitted.why)

        val submitted = a.parseSubmitResult(
            JSONObject("""{"ok":true,"filled":true,"pasted":false,"submitted":true}""")
        )
        assertTrue(submitted.ok)
    }

    @Test
    fun geminiLoginRequiresPasswordAndLoginUi() {
        val a = GeminiAdapter()
        // 有账户菜单但无密码框（已登录页）→ 不得判为未登录
        assertFalse(a.parseLogin(JSONObject("""{"passwordCount":0,"loginIndicatorCount":3}""")))
        assertTrue(a.parseLogin(JSONObject("""{"passwordCount":1,"loginIndicatorCount":1}""")))
        assertFalse(a.parseLogin(JSONObject("""{"passwordCount":1,"loginIndicatorCount":0}""")))
    }

    @Test
    fun geminiDiagnoseExposesPasswordCountForLoginCheck() {
        val s = GeminiScripts.diagnose()
        assertTrue("diagnose must emit passwordCount", s.contains("passwordCount"))
        assertTrue(s.contains("loginIndicatorCount"))
    }

    @Test
    fun geminiVerifyAttachmentScopesToComposerContainer() {
        val script = GeminiScripts.verifyAttachment()
        assertTrue(script.contains("composer-not-found"))
        assertTrue(script.contains("box.querySelectorAll"))
        assertTrue(script.contains("thumbCount"))
        assertTrue(script.contains("data-node-type"))
    }

    /** AI-182：上传工具栏按钮也带 uploader- 前缀，绝不能算附件。 */
    @Test
    fun geminiAttachmentCheckIgnoresUploaderToolbar() {
        assertFalse(GeminiSelectors.ATTACHMENT.contains("data-test-id*='upload'"))
        val script = GeminiScripts.verifyAttachment()
        assertFalse(script.contains("[data-test-id*=\"upload\"]"))
        assertTrue(script.contains("uploader-file-preview"))
        assertTrue(script.contains("gem-media-attachment"))
    }

    // ---- ChatGPT（AI-157：结构同构，但未真机验证）----

    @Test
    fun chatGptScriptsAreValidJsAndJsonReturning() {
        val scripts = listOf(
            ChatGPTScripts.diagnose(),
            ChatGPTScripts.snapshot(),
            ChatGPTScripts.loginCheck(),
            ChatGPTScripts.verifyAttachment(),
            ChatGPTScripts.fillAndSubmit("hi"),
            ChatGPTScripts.fillComposer("hi"),
            ChatGPTScripts.submitComposer(),
            ChatGPTScripts.sendState(),
            ChatGPTScripts.clearComposer(),
            ChatGPTScripts.pasteImage("a.jpg", "image/jpeg", "QUJD")
        )
        scripts.forEach {
            assertTrue(it.contains("JSON.stringify"))
            assertEquals(it.count { c -> c == '{' }, it.count { c -> c == '}' })
        }
    }

    /** AI-182：ChatGPT 也按「填文本 → 粘贴并确认 → 提交」拆分。 */
    @Test
    fun chatGptUsesSplitFillAndSubmit() {
        val a = ChatGPTAdapter()
        assertTrue(a.uploadsAfterFill)
        val fill = ChatGPTScripts.fillComposer("hi")
        assertTrue(fill.contains("inputFilled"))
        assertFalse("fill must not click send", fill.contains("btn.click()"))
        val submit = ChatGPTScripts.submitComposer()
        assertTrue(submit.contains("sendClicked"))
        assertTrue(submit.contains("inputFilled: true"))
    }

    /** AI-183：额度用尽必须能识别，而不是干等 20s 后报「无预览」。 */
    @Test
    fun chatGptReportsQuotaExhaustion() {
        val paste = ChatGPTScripts.pasteImage("a.jpg", "image/jpeg", "QUJD")
        assertTrue(paste.contains("quotaBlocked"))
        assertTrue(paste.contains("upload-quota-exhausted"))
        val verify = ChatGPTScripts.verifyAttachment()
        assertTrue(verify.contains("quotaBlocked"))
    }

    @Test
    fun chatGptPromptWithSpecialCharsDoesNotBreakScript() {
        val script = ChatGPTScripts.fillAndSubmit(tricky)
        assertTrue(script.contains("\\\""))
        assertFalse(script.contains("while ("))
        // AI-158：ProseMirror 只认真正的输入事件，必须走 insertText 而不是写 textContent。
        assertTrue("uses real input events", script.contains("execCommand('insertText'"))
        // 按钮未就绪时明确失败，由驱动层轮询后重试，不在脚本里盲点 disabled 按钮。
        assertTrue("reports not-ready instead of faking submit", script.contains("send-not-ready"))
    }

    @Test
    fun chatGptSubmitRequiresPositiveEvidence() {
        val a = ChatGPTAdapter()
        assertFalse(a.parseSubmitResult(JSONObject("""{"ok":true,"inputFilled":true}""")).ok)
        assertTrue(
            a.parseSubmitResult(
                JSONObject("""{"ok":true,"inputFilled":true,"sentEnter":true}""")
            ).ok
        )
        val disabled = a.parseSubmitResult(
            JSONObject("""{"ok":true,"inputFilled":true,"sendDisabled":true}""")
        )
        assertFalse(disabled.ok)
        assertEquals("send-disabled", disabled.why)
    }

    @Test
    fun chatGptSendStateParses() {
        val a = ChatGPTAdapter()
        val s = a.parseSendState(
            JSONObject("""{"ok":true,"sendCount":0,"sendDisabled":false,"sendClickable":false,"valueEmpty":true}""")
        )
        assertFalse(s.sendClickable)
        assertTrue(s.valueEmpty)
    }

    // ---- Custom ----

    @Test
    fun customAdapterDelegatesParsing() {
        val a = CustomAdapter("https://example.test")
        assertTrue(a.parseAttachment(JSONObject("""{"attached":true}""")))
        assertFalse(a.parseSubmitResult(JSONObject("""{"ok":false}""")).ok)
    }

    // ---- 通用解析工具 ----

    // ---- AI-031: URL 登录页判据 ----

    @Test
    fun loginUrlRecognizedForCommonPaths() {
        // 真机实测：DeepSeek 未登录被重定向到这里，DOM 里没有任何密码框。
        val a = DeepSeekAdapter()
        assertTrue(a.isLoginUrl("https://chat.deepseek.com/sign_in"))
        assertTrue(a.isLoginUrl("https://chat.deepseek.com/sign_in?redirect=/"))
        assertTrue(a.isLoginUrl("https://example.com/login"))
        assertTrue(a.isLoginUrl("https://example.com/signin"))
        assertTrue(a.isLoginUrl("https://example.com/auth/callback"))
    }

    @Test
    fun normalPagesAreNotLoginUrls() {
        val a = DeepSeekAdapter()
        assertFalse(a.isLoginUrl("https://chat.deepseek.com/"))
        assertFalse(a.isLoginUrl("https://chat.deepseek.com/a/chat/s/123"))
        assertFalse(a.isLoginUrl(""))
        assertFalse(a.isLoginUrl("https://gemini.google.com/app"))
        // 已登录首页不能因为路径里带 auth 之外的词被误判。
        assertFalse(a.isLoginUrl("https://example.com/author"))
    }

    @Test
    fun loginUrlDoesNotMatchUnrelatedPathSegment() {
        // startsWith("auth") 会命中 /author、/authority —— 这类误判会导致
        // 正常页面被当成登录页而停止自动化。文档路径必须排除。
        val a = DeepSeekAdapter()
        assertFalse(a.isLoginUrl("https://example.com/authority/list"))
    }

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
