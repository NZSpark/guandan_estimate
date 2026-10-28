package nz.org.aotearoa.guandanscore.recognition.webview

import android.util.Log
import nz.org.aotearoa.guandanscore.recognition.site.AnswerSnapshot
import nz.org.aotearoa.guandanscore.recognition.site.DiagnoseResult
import nz.org.aotearoa.guandanscore.recognition.site.JsonParse
import nz.org.aotearoa.guandanscore.recognition.site.JsonQuote
import nz.org.aotearoa.guandanscore.recognition.site.SiteAdapter
import nz.org.aotearoa.guandanscore.recognition.site.SubmitOutcome
import nz.org.aotearoa.guandanscore.recognition.source.AiSourceError

/** 页面活性三态。NOT_READY 只等待，绝不 reload（SMSProxy P23）。 */
enum class PageLiveness { ALIVE, NOT_READY, DEAD }

/** 可粘贴附件的适配器。用接口而非具体类型判断，避免驱动层耦合站点类。 */
interface PasteCapableAdapter {
    fun pasteAttachmentScript(fileName: String, mimeType: String, base64: String): String
}

/**
 * 站点无关的页面驱动层。
 *
 * 硬约束（AI-122）：本文件**不得**出现任何 CSS 选择器、站点 URL 字面量、
 * 任何具体站点名或其专属类。所有站点知识来自 [SiteAdapter] 接口。
 *
 * 分层：
 * - 本类负责：导航、就绪等待、注入调用、结果解析、错误分类
 * - [SiteAdapter] 负责：选择器、脚本、结构化解析
 * - [WebViewAiSource] 负责：状态机、超时、取消、基线
 */
class PageDriver(
    private val adapter: SiteAdapter,
    private val host: WebViewHost,
    private val bridge: WebViewBridge,
    private val sleepMillis: (Long) -> Unit = { Thread.sleep(it) },
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) : PageOps {

    private companion object {
        const val TAG = "PageDriver"

        /**
         * 同步同源 XHR 探活。与站点无关：只读 `location` 与一个同源静态资源。
         *
         * - `not_ready`：文档尚未 commit（origin 为空或 readyState 还是 loading）
         * - `alive`：请求返回且 status > 0
         * - `dead`：抛异常（跨域/被拦截）或请求未返回
         */
        val SYNC_XHR_PROBE = """
            (function(){
              try{
                var rs=document.readyState||'';
                var href=location.href||'';
                var origin='';try{origin=String(location.origin||'');}catch(e0){}
                var committed=(origin&&origin!=='null')&&(rs==='interactive'||rs==='complete');
                if(!committed){return JSON.stringify({state:'not_ready',rs:rs,origin:origin,href:href});}
                var x=new XMLHttpRequest();
                x.open('GET','/favicon.ico?_='+Date.now(),false);
                x.send();
                return JSON.stringify({state:x.status>0?'alive':'dead',status:x.status});
              }catch(e){
                var origin2='';try{origin2=String(location.origin||'');}catch(e2){}
                var rs2='';try{rs2=document.readyState||'';}catch(e3){}
                var committed2=(origin2&&origin2!=='null')&&(rs2==='interactive'||rs2==='complete');
                return JSON.stringify({state:committed2?'dead':'not_ready',err:String(e&&e.message||e)});
              }
            })();
        """.trimIndent()
        const val READY_TIMEOUT_MILLIS = 15_000L
        const val READY_POLL_MILLIS = 300L
        const val UPLOAD_TIMEOUT_MILLIS = 20_000L
        const val SUBMIT_CONFIRM_TIMEOUT_MILLIS = 15_000L
    }

    /** 记录是否已经为当前会话导航过，避免每次识牌都 reload。 */
    @Volatile
    private var navigatedTo: String? = null

    /** 导航到首页（或保持当前会话）。 */
    override fun ensureLoaded(force: Boolean): Boolean {
        val target = adapter.homeUrl
        if (target.isBlank()) throw AiSourceError.NotReady("未配置首页地址")
        val current = host.currentUrl
        val already = !force && navigatedTo == target && current.isNotBlank() &&
            current.startsWith(target.substringBefore('?'))
        if (already) return true

        host.destroy()
        host.attach()
        if (host.status() != WindowStatus.Attached) {
            throw AiSourceError.NotReady("WebView 未挂接窗口；请在应用界面内打开或授予悬浮窗权限")
        }
        navigatedTo = target
        bridge.evaluateOnMain("location.href = ${jsonQuote(target)};") {}
        Log.i(TAG, "navigating to configured home")
        return awaitReady()
    }

    /** 等待页面可操作：有输入框且视口非零。 */
    fun awaitReady(): Boolean {
        val deadline = nowMillis() + READY_TIMEOUT_MILLIS
        while (nowMillis() < deadline) {
            if (probeLiveness() == PageLiveness.ALIVE) return true
            sleepMillis(READY_POLL_MILLIS)
        }
        return false
    }

    /**
     * 三态活性判断。
     *
     * 用 `readyState` + `origin` + 输入框命中三项组合判断，而不是只看 `readyState == complete`：
     * 冷启动时 `readyState` 已是 complete 但 `location.origin` 仍为 `null`，
     * 把它当 DEAD 会触发自持的 reload 循环（SMSProxy P23）。
     */
    fun probeLiveness(): PageLiveness {
        val json = JsonParse.parse(runBlockingEvaluate(adapter.diagnoseScript()))
            ?: return PageLiveness.NOT_READY
        val diag = adapter.parseDiagnose(json)
        val ready = diag.readyState == "interactive" || diag.readyState == "complete"
        val hasOrigin = diag.href.isNotBlank() && !diag.href.startsWith("about:")
        if (!ready || !hasOrigin) return PageLiveness.NOT_READY
        val viewportOk = diag.innerHeight >= 100
        val composerOk = diag.inputCount > 0
        if (!viewportOk || !composerOk) return PageLiveness.NOT_READY
        // 静态诊断全部通过，仍可能是“URL 正确、元素在、但页面已卡死”的假活页。
        // 用一个同步同源 XHR 探活：健康页毫秒级返回；卡死的 WebView 永远不返回。
        // 同步（第三个参数 false）是刻意的：异步请求可能悬挂到 evaluate 结束之后，
        // 污染后续轮询（SMSProxy 实测）。超时/异常一律判 DEAD，交给 ensureLoaded 重载。
        return probeSyncXhrLiveness()
    }

    /**
     * 同步同源 XHR 探活（SMSProxy `probePageLiveness` 的可移植部分）。
     *
     * 只在已经确认 `readyState`、`origin`、输入框都就绪之后调用，所以这里
     * 不需要再区分 NOT_READY —— 走不到这里就说明还没就绪。
     */
    private fun probeSyncXhrLiveness(): PageLiveness {
        val raw = runBlockingEvaluate(SYNC_XHR_PROBE).trim()
        val json = JsonParse.parse(raw) ?: return PageLiveness.DEAD
        return when (json.optString("state")) {
            "alive" -> PageLiveness.ALIVE
            "not_ready" -> PageLiveness.NOT_READY
            else -> PageLiveness.DEAD
        }
    }

    override fun diagnose(): DiagnoseResult? {
        val json = JsonParse.parse(runBlockingEvaluate(adapter.diagnoseScript())) ?: return null
        return adapter.parseDiagnose(json)
    }

    /** 是否未登录。登录判据由适配器保证必须含密码框（M6）。 */
    override fun isLoggedIn(): Boolean {
        val json = JsonParse.parse(runBlockingEvaluate(adapter.loginCheckScript()))
            ?: return true // 解析失败不武断判未登录
        return !adapter.parseLogin(json)
    }

    override fun captureBaseline(): AnswerSnapshot {
        val json = JsonParse.parse(runBlockingEvaluate(adapter.snapshotScript()))
            ?: return AnswerSnapshot.EMPTY
        return adapter.parseSnapshot(json)
    }

    override fun pollSnapshot(): AnswerSnapshot {
        val json = JsonParse.parse(runBlockingEvaluate(adapter.snapshotScript()))
            ?: return AnswerSnapshot.EMPTY
        return adapter.parseSnapshot(json)
    }

    /** 应用站点特有布局修复；不需要时适配器返回空串。 */
    override fun applyPageFix() {
        val script = adapter.pageFixScript()
        if (script.isBlank()) return
        val json = JsonParse.parse(runBlockingEvaluate(script)) ?: return
        if (!json.optBoolean("ok", false)) {
            Log.w(TAG, "page fix reported: ${json.optString("why")}")
        }
    }

    /** 填 prompt 并提交。 */
    override fun fillAndSubmit(prompt: String, fileName: String, mimeType: String, base64: String): SubmitOutcome {
        val script = adapter.fillAndSubmitScript(prompt, fileName, mimeType, base64)
        val json = JsonParse.parse(runBlockingEvaluate(script))
            ?: return SubmitOutcome.fail("script-empty-or-invalid-json")
        return adapter.parseSubmitResult(json)
    }

    /**
     * 文件选择路径注入图片。
     *
     * 步骤：把字节写进 cache → [WebViewHost.prepareUpload] → 触发页面自身的上传入口。
     * 页面调用 `onShowFileChooser` 时宿主直接回填该 URI，不弹系统选择器。
     *
     * 返回 true 只代表“回填已就绪”，真实成功仍以 [verifyAttachment] 为准（M11）。
     */
    override fun prepareFileChooserUpload(fileName: String, mimeType: String, bytes: ByteArray): Boolean {
        val ctx = host.appContextForFiles() ?: return false
        return try {
            val dir = java.io.File(ctx.cacheDir, "ai-upload").apply { mkdirs() }
            val file = java.io.File(dir, fileName)
            file.writeBytes(bytes)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                ctx, "${ctx.packageName}.files", file
            )
            host.prepareUpload(uri)
            // 触发页面自己的上传入口；它最终会调用 onShowFileChooser。
            val ok = triggerUploadButton()
            if (!ok) host.clearPendingUpload()
            ok
        } catch (e: Exception) {
            Log.w(TAG, "prepareFileChooserUpload failed", e)
            host.clearPendingUpload()
            false
        }
    }

    /**
     * 触发页面上传入口。
     *
     * 优先用原生触摸点击图标（DeepSeek 实测：JS click 不满足 user activation，
     * 不会触发 onShowFileChooser）；适配器未提供图标脚本时退回 JS click。
     */
    private fun triggerUploadButton(): Boolean {
        val iconScript = adapter.uploadIconScript()
        if (iconScript.isNotBlank()) {
            val json = JsonParse.parse(runBlockingEvaluate(iconScript))
            if (json != null && json.optBoolean("ok", false)) {
                val x = json.optInt("x", -1)
                val y = json.optInt("y", -1)
                val viewportH = json.optInt("innerHeight", 0)
                if (x >= 0 && y >= 0 && viewportH > 0) {
                    if (host.tapViewportPoint(x, y, viewportH)) return true
                }
            }
        }
        val script = adapter.uploadTriggerScript()
        if (script.isBlank()) return false
        val json = JsonParse.parse(runBlockingEvaluate(script)) ?: return false
        return json.optBoolean("ok", false)
    }

    /** 粘贴路径注入图片。`dispatched=true` 不代表成功，调用方仍需 [verifyAttachment]。 */
    override fun pasteImage(fileName: String, mimeType: String, base64: String): Boolean {
        val pasteable = adapter as? PasteCapableAdapter ?: return false
        val json = JsonParse.parse(runBlockingEvaluate(pasteable.pasteAttachmentScript(fileName, mimeType, base64)))
            ?: return false
        return json.optBoolean("dispatched", false)
    }

    /** 校验附件预览。不通过即视为上传失败（M11）。 */
    override fun verifyAttachment(): Boolean {
        val deadline = nowMillis() + UPLOAD_TIMEOUT_MILLIS
        while (nowMillis() < deadline) {
            val json = JsonParse.parse(runBlockingEvaluate(adapter.verifyAttachmentScript()))
            if (json != null && adapter.parseAttachment(json)) return true
            sleepMillis(300)
        }
        return false
    }

    /** 提交后确认本轮真的开始了：节点数增加或文本变化。 */
    override fun awaitSubmitStarted(baseline: AnswerSnapshot): Boolean {
        val deadline = nowMillis() + SUBMIT_CONFIRM_TIMEOUT_MILLIS
        while (nowMillis() < deadline) {
            val s = pollSnapshot()
            if (s.responseCount > baseline.responseCount) return true
            if (s.lastAnswerText.isNotBlank() && s.lastAnswerText != baseline.lastAnswerText) return true
            sleepMillis(300)
        }
        return false
    }

    private fun runBlockingEvaluate(script: String): String =
        kotlinx.coroutines.runBlocking { bridge.evaluateRaw(script) }

    private fun jsonQuote(raw: String): String = JsonQuote.of(raw)
}
