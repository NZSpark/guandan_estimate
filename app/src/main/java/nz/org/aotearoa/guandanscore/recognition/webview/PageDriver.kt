package nz.org.aotearoa.guandanscore.recognition.webview

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    /**
     * 执行宿主同步 API 的调度器（AI-022）。默认主线程；单测可注入 test dispatcher。
     * 注意：真实运行时它必须是主线程，否则 [WebViewHost.requireMainThread] 会断言失败。
     */
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main
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

        /** AI-034：轮询退避上限。页面刚 commit 时密集轮询有效，之后放慢以省电与减少脚本调用。 */
        const val READY_POLL_MAX_MILLIS = 1_200L

        /** AI-034：就绪等待期间允许的最大重载次数。防止「选择器缺失 → 重载 → 仍缺失」无限循环。 */
        const val READY_MAX_RELOADS = 1
        const val UPLOAD_TIMEOUT_MILLIS = 20_000L
        const val SUBMIT_CONFIRM_TIMEOUT_MILLIS = 15_000L
    }

    /** 记录是否已经为当前会话导航过，避免每次识牌都 reload。 */
    @Volatile
    private var navigatedTo: String? = null

    /** AI-035：刚完成导航，SPA 初始化中。此期间的 reload 一律跳过。 */
    @Volatile
    private var navigatedJustNow = false

    /**
     * AI-035：业务生成期间禁止自动 reload。
     *
     * 生成中 reload 会直接丢弃当前请求与已产生的回答，比「再等一会儿」糟糕得多。
     * 由 [WebViewAiSource.recognize] 在进入/退出时置位。
     */
    @Volatile
    private var reloadSuspended = false

    override fun setReloadSuspended(suspended: Boolean) {
        reloadSuspended = suspended
        if (suspended) Log.i(TAG, "auto-reload suspended (business task running)")
    }

    /** 导航到首页（或保持当前会话）。 */
    override fun ensureLoaded(force: Boolean): Boolean {
        lastReadyFailure = null
        val target = adapter.homeUrl
        if (target.isBlank()) throw AiSourceError.NotReady("未配置首页地址")
        val current = host.currentUrl
        val already = !force && navigatedTo == target && current.isNotBlank() &&
            current.startsWith(target.substringBefore('?'))
        if (already) return true

        // AI-021：这里要的是“换页重建”，必须走终态销毁；若只想临时隐藏，
        // 调用方应改用 detachFromVisible()/attach() 以保留会话。
        // AI-022：destroy/attach/status 都要求主线程，本方法在 worker 线程被调用，必须投递。
        val attachStatus = runOnMainSync("PageDriver.attach") {
            host.destroy()
            host.attach()
            host.status()
        }
        if (attachStatus != WindowStatus.Attached) {
            throw AiSourceError.NotReady("WebView 未挂接窗口；请在应用界面内打开或授予悬浮窗权限")
        }
        navigatedTo = target
        runOnMainSync("PageDriver.navigatedTarget") { host.navigatedTarget = target }
        bridge.evaluateOnMain("location.href = ${jsonQuote(target)};") {}
        Log.i(TAG, "navigating to configured home")
        // AI-035：刚发起导航时不允许 awaitReady 再补一次 reload ——
        // SPA 初始化阶段的 reload 会打断首屏加载，表现为「越等越不出现」。
        navigatedJustNow = true
        return try {
            awaitReady()
        } finally {
            navigatedJustNow = false
        }
    }

    /**
     * 等待页面可操作：有输入框且视口非零。
     *
     * 轮询间隔用可注入的 [sleepMillis]（AI-023）：真实运行时它是 `Thread.sleep`，
     * 因此**只能在 worker 线程**调用；单测注入 no-op 以免真的睡。主线程调用由
     * [awaitReadyOnWorker] 之外的调用方负责避免，这里不做隐式切线程。
     */
    fun awaitReady(): Boolean {
        val deadline = nowMillis() + READY_TIMEOUT_MILLIS
        var polls = 0
        var reloads = 0
        var delay = READY_POLL_MILLIS
        var lastLoggedState: PageState? = null
        while (nowMillis() < deadline) {
            polls++
            if (probeLiveness() == PageLiveness.ALIVE) {
                Log.i(TAG, "awaitReady ok after ${polls} polls")
                return true
            }
            val state = lastProbeState
            // AI-034：只在状态变化时打日志，避免每 300ms 刷一行。
            if (state != null && state != lastLoggedState) {
                Log.i(TAG, "awaitReady poll#$polls state=$state")
                lastLoggedState = state
            }
            if (state != null) {
                when (PageStateClassifier.actionFor(state)) {
                    // AI-031: NEEDS_USER states never improve by retrying; fail fast with a reason.
                    PageAction.NEEDS_USER -> {
                        lastReadyFailure = PageStateClassifier.reason(state, lastProbeDiagnose)
                        Log.w(TAG, "awaitReady aborted: $lastReadyFailure")
                        return false
                    }
                    // AI-034：结构失效允许有限次重载，超过上限即放弃并说明原因。
                    PageAction.RELOAD -> {
                        if (reloadSuspended) {
                            // AI-035：生成中绝不 reload，保持等待由超时兜底。
                            lastLoggedState = null
                            sleepMillis(delay)
                            delay = (delay * 3 / 2).coerceAtMost(READY_POLL_MAX_MILLIS)
                            continue
                        }
                        if (navigatedJustNow) {
                            // AI-035：导航刚发出，页面还在初始化；此刻 reload 只会打断它。
                            Log.i(TAG, "awaitReady skip reload: just navigated, still initializing")
                            lastLoggedState = null
                            sleepMillis(delay)
                            delay = (delay * 3 / 2).coerceAtMost(READY_POLL_MAX_MILLIS)
                            continue
                        }
                        if (reloads >= READY_MAX_RELOADS) {
                            lastReadyFailure = PageStateClassifier.reason(state, lastProbeDiagnose)
                            Log.w(TAG, "awaitReady giving up after $reloads reload(s): $lastReadyFailure")
                            return false
                        }
                        reloads++
                        val why = AttachGate.viewportRejectReason(lastProbeDiagnose?.innerHeight ?: 0)
                        Log.w(TAG, "awaitReady reload #$reloads (state=$state viewport=$why)")
                        bridge.evaluateOnMain("location.reload();") { }
                        lastLoggedState = null
                    }
                    PageAction.PROCEED, PageAction.RETRY -> Unit
                }
            }
            sleepMillis(delay)
            // 指数退避但不超过上限：早期密集、后期稀疏。
            delay = (delay * 3 / 2).coerceAtMost(READY_POLL_MAX_MILLIS)
        }
        lastReadyFailure = lastProbeState?.let { PageStateClassifier.reason(it, lastProbeDiagnose) }
            ?: "page not ready within timeout"
        Log.w(TAG, "awaitReady timeout after $polls polls reloads=$reloads: $lastReadyFailure")
        return false
    }

    /** AI-031: concrete reason for the last readiness failure, for AiSourceError.NotReady. */
    @Volatile
    var lastReadyFailure: String? = null
        private set

    override fun lastFailureReason(): String? = lastReadyFailure

    /**
     * AI-033：只读诊断，不导航、不 reload。
     *
     * 与 [probeLiveness] 的区别：后者只在已挂接且可求值时有意义，且在未就绪时
     * 会继续探活；这里只取一次静态诊断并分类，供健康检查与 UI 使用。
     * 求值失败（WebView 已销毁 / 未挂接）返回 [PageState.HOST_NOT_ATTACHED]，
     * **不再返回 null**：null 的语义是「实现不支持只读诊断」，会让调用方退回旧的
     * `ensureLoaded + isLoggedIn` 路径，把「宿主没挂接」这个明确事实降级成一句笼统的
     * 「页面未就绪」，用户已登录也会看到「不可用」（AI-036 真机回归）。
     * 只有支持只读诊断、但脚本求值结果不可解析时才返回 null。
     */
    override fun pageState(): PageState? {
        if (runOnMainSync("PageDriver.currentWebView") { host.currentWebView() } == null) {
            Log.w(TAG, "pageState: webView is null (host not attached)")
            return PageState.HOST_NOT_ATTACHED
        }
        val diag = diagnose() ?: return null
        lastProbeDiagnose = diag
        val state = PageStateClassifier.classify(diag, adapter.isLoginUrl(diag.href))
        lastProbeState = state
        return state
    }

    @Volatile
    private var lastProbeState: PageState? = null

    @Volatile
    private var lastProbeDiagnose: DiagnoseResult? = null

    /**
     * 三态活性判断。
     *
     * 用 `readyState` + `origin` + 输入框命中三项组合判断，而不是只看 `readyState == complete`：
     * 冷启动时 `readyState` 已是 complete 但 `location.origin` 仍为 `null`，
     * 把它当 DEAD 会触发自持的 reload 循环（SMSProxy P23）。
     */
    fun probeLiveness(): PageLiveness {
        val raw = runBlockingEvaluate(adapter.diagnoseScript())
        val json = JsonParse.parse(raw)
        if (json == null) {
            // 诊断脚本本身失败（页面未 commit / evaluate 返回空 / JSON 不合法）。
            // 这是「页面在但读不到」与「页面还没到」的分界点，必须留痕。
            Log.i(TAG, "probeLiveness NOT_READY: diagnose unparsable raw=${raw.take(200)}")
            return PageLiveness.NOT_READY
        }
        val diag = adapter.parseDiagnose(json)
        // AI-031: 把静态诊断分类成五种状态并存下来，供 awaitReady 失败时给具体原因、
        // 也供 NEEDS_USER 类状态立刻中止重试（登录失效 / 视口塌陷重试无用）。
        lastProbeDiagnose = diag
        lastProbeState = PageStateClassifier.classify(diag, adapter.isLoginUrl(diag.href))
        val ready = diag.readyState == "interactive" || diag.readyState == "complete"
        val hasOrigin = diag.href.isNotBlank() && !diag.href.startsWith("about:")
        if (!ready || !hasOrigin) {
            Log.i(
                TAG,
                "probeLiveness NOT_READY: ready=$ready(${diag.readyState}) hasOrigin=$hasOrigin " +
                    "href=${UrlRedactor.redact(diag.href)}"
            )
            return PageLiveness.NOT_READY
        }
        // AI-024：阈值只在 AttachGate 定义一次（MIN_VIEWPORT_HEIGHT），避免两处各写 100 漂移。
        val viewportOk = AttachGate.viewportRejectReason(diag.innerHeight) == null
        val composerOk = diag.inputCount > 0
        if (!viewportOk || !composerOk) {
            // AI-031/AI-024：这两项失败是「页面在但不可用」的最常见原因，必须能从日志分辨。
            Log.i(
                TAG,
                "probeLiveness NOT_READY: viewportOk=$viewportOk innerHeight=${diag.innerHeight} " +
                    "composerOk=$composerOk inputCount=${diag.inputCount} " +
                    "readyState=${diag.readyState} href=${UrlRedactor.redact(diag.href)}"
            )
            return PageLiveness.NOT_READY
        }
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
            runOnMainSync("PageDriver.prepareUpload") { host.prepareUpload(uri) }
            // 触发页面自己的上传入口；它最终会调用 onShowFileChooser。
            val ok = triggerUploadButton()
            if (!ok) runOnMainSync("PageDriver.clearPendingUpload") { host.clearPendingUpload() }
            ok
        } catch (e: Exception) {
            Log.w(TAG, "prepareFileChooserUpload failed", e)
            runOnMainSync("PageDriver.clearPendingUpload") { host.clearPendingUpload() }
            false
        }
    }

    /**
     * 触发页面上传入口。
     *
     * 优先用原生触摸点击图标（真机实测：JS click 不满足 user activation，
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
                // 视口宽度可能缺省（旧脚本），0 表示由宿主按高度比例兜底。
                val viewportW = json.optInt("innerWidth", 0)
                if (x >= 0 && y >= 0 && viewportH > 0) {
                    if (runOnMainSync("PageDriver.tapViewportPoint") {
                            host.tapViewportPoint(x, y, viewportH, viewportW)
                        }
                    ) return true
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

    /**
     * 同步壳：把挂起的 [WebViewBridge.evaluateRaw] 变成同步返回。
     *
     * 经 [BlockingBridge] 统一把关（AI-023）：主线程调用会立即失败，而不是
     * 卡在 `runBlocking` 上等一个永远排不上主线程的回调。合法调用方是 worker 线程。
     */
    private fun runBlockingEvaluate(script: String): String =
        BlockingBridge.awaitBlocking("PageDriver.evaluate") { bridge.evaluateRaw(script) }

    /**
     * 同步壳：在**主线程**执行一段必须主线程的宿主调用，并把结果带回 worker 线程。
     *
     * 与 [runBlockingEvaluate] 的分工（AI-022）：
     * - [runBlockingEvaluate] 走协程，把挂起回调 resume 到主线程；
     * - 本方法用于 [WebViewHost] 的**同步** API（destroy/attach/status/prepareUpload/
     *   tapViewportPoint …），它们内部会 [WebViewHost.requireMainThread] 断言。
     *
     * 主线程上调用会立即失败（同 AI-023 的理由：主线程阻塞等自己 = 死锁）。
     */
    private fun <T> runOnMainSync(where: String, block: () -> T): T =
        BlockingBridge.awaitBlocking(where) { withContext(mainDispatcher) { block() } }

    private fun jsonQuote(raw: String): String = JsonQuote.of(raw)
}
