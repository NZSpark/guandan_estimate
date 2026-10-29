package nz.org.aotearoa.guandanscore.probe

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import nz.org.aotearoa.guandanscore.recognition.site.GeminiAdapter
import nz.org.aotearoa.guandanscore.recognition.site.DeepSeekAdapter
import nz.org.aotearoa.guandanscore.recognition.site.JsonParse
import nz.org.aotearoa.guandanscore.recognition.site.JsonQuote
import nz.org.aotearoa.guandanscore.recognition.site.SiteAdapter
import nz.org.aotearoa.guandanscore.recognition.source.AiSourceId
import nz.org.aotearoa.guandanscore.recognition.source.AiSourcePresets
import nz.org.aotearoa.guandanscore.recognition.webview.AcceptTypes
import org.json.JSONObject

/**
 * 阶段 0 / 阶段 2 真机探测入口（AI-006D）。
 *
 * 与 [GeminiProbeActivity] 的区别：站点可在运行期切换，诊断按钮取自当前 [SiteAdapter]，
 * 不再把 Gemini 的脚本硬编码进 Activity。
 *
 * 启动：
 * ```
 * adb shell am start -n nz.org.aotearoa.guandanscore/.probe.AiProbeActivity \
 *   --es site deepseek_web
 * ```
 * `--es site` 接受 [AiSourceId.key]；`--es url` 可直接指定任意 URL（自定义源探测）。
 *
 * 保留旧入口：`GeminiProbeActivity` 继承本类，旧 adb 命令仍然可用。
 *
 * 本 Activity 只用于人工验证，不是正式 App 功能。所有诊断脚本只读 DOM，
 * 不读取 Cookie / localStorage / 完整图片 Base64。
 */
@SuppressLint("SetJavaScriptEnabled")
open class AiProbeActivity : Activity() {

    companion object {
        private const val TAG = "AiProbe"

        /** 指定站点，值为 [AiSourceId.key]。 */
        const val EXTRA_SITE = "site"

        /** 自动脚本：页面加载后自动执行一轮侦察/填文本，供 adb 无人值守验证。 */
        const val EXTRA_AUTO = "auto"

        /** 直接指定 URL，优先级高于 [EXTRA_SITE]。 */
        const val EXTRA_URL = "url"

        /** 兼容旧入口：`GeminiProbeActivity` 使用的参数名。 */
        const val EXTRA_START_URL = "start_url"

        /**
         * AI-132：真实测试图路径（设备上可读的绝对路径或 content URI）。
         * 设置后 [prepareChooserImage] 复制它作为上传附件；未设置则回退 1×1 占位图。
         */
        const val EXTRA_TEST_IMAGE = "test_image"

        const val DEFAULT_SITE = "gemini_web"
    }

    private lateinit var webView: WebView
    private lateinit var output: TextView
    private lateinit var logView: TextView
    private lateinit var siteBar: LinearLayout

    /** 当前适配器；切换站点时替换。 */
    private var adapter: SiteAdapter = GeminiAdapter()
    private var currentUrl: String = ""

    /** AI-128：`onShowFileChooser` 要回填的图片 URI。准备后一次消费即清空。 */
    @Volatile
    private var pendingChooserUri: Uri? = null

    /** [EXTRA_AUTO] 只跑一次，避免重定向再次触发。 */
    private var autoRan = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // ---- 站点切换栏 ----
        siteBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(
            android.widget.HorizontalScrollView(this).apply { addView(siteBar) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        rebuildSiteBar()

        // ---- 适配器诊断栏 ----
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { action() }
            bar.addView(this)
        }
        button("基线") { logBaseline() }
        button("诊断") { runAdapterScript("DIAG") { adapter.diagnoseScript() } }
        button("深度探测") { runAdapterScript("DEEP") { adapter.deepProbeScript() } }
        button("文件框属性") { runAdapterScript("FILEATTR") { adapter.fileInputProbeScript() } }
        button("停止控件") { runAdapterScript("STOPCTL") { adapter.stopControlProbeScript() } }
        button("快照") { runAdapterScript("SNAP") { adapter.snapshotScript() } }
        button("登录检查") { runAdapterScript("LOGIN") { adapter.loginCheckScript() } }
        button("附件检查") { runAdapterScript("ATTACH") { adapter.verifyAttachmentScript() } }
        button("准备测试图") { prepareChooserImage() }
        button("上传入口") { runAdapterScript("UPLOAD") { adapter.uploadTriggerScript() } }
        button("原生点上传") { tapUploadIcon() }
        button("输入区按钮") { runScript(COMPOSER_BUTTONS) { log("CBTNS", it) } }
        button("上传图标坐标") { runAdapterScript("ICON") { adapter.uploadIconScript() } }
        button("粘贴测试") { testPaste() }
        button("拖放测试") { testDrop() }
        button("系统剪贴板") { testSystemClipboard() }
        button("填+贴+发") { testFillPasteSubmit() }
        button("UA") { runScript("navigator.userAgent") { log("UA", it) } }
        button("按钮枚举") { runScript(ENUM_BUTTONS) { log("BTNS", it) } }
        button("Gemini发送枚举") { runScript(GEMINI_SEND_ENUM) { log("GSEND", it) } }
        button("Gemini填文本") { runScript(GEMINI_FILL_ONLY) { log("GFILL", it) } }
        button("输入框详情") { runScript(ENUM_INPUT) { log("INPUT", it) } }
        button("文件框详情") { runScript(ENUM_FILE) { log("FILE", it) } }
        button("清日志") { output.text = ""; logView.text = "" }
        root.addView(android.widget.HorizontalScrollView(this).apply { addView(bar) })

        logView = TextView(this).apply {
            textSize = 10f
            setTextIsSelectable(true)
            setBackgroundColor(0x10000000)
        }
        // 探测页把 WebView 放到最大：视口必须接近真实使用场景，否则 DOM 布局
        // 与全屏 App 内不同，坐标类实验（回形针位置、innerHeight）会失真。
        root.addView(
            ScrollView(this).apply { addView(logView) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.12f)
        )

        output = TextView(this).apply {
            textSize = 9f
            gravity = Gravity.TOP or Gravity.START
            setTextIsSelectable(true)
            setPadding(8, 8, 8, 8)
        }
        root.addView(
            ScrollView(this).apply { addView(output) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.20f)
        )

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.userAgentString = settings.userAgentString + " GuandanProbe/1.0"
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    log("PAGE", "onPageFinished $url")
                    val auto = intent?.getStringExtra(EXTRA_AUTO)
                    if (!auto.isNullOrBlank() && !autoRan) {
                        autoRan = true
                        // 等页面渲染完成再跑。
                        view?.postDelayed({ runAuto(auto) }, 4000)
                    }
                }

                override fun onReceivedError(
                    view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?
                ) {
                    log("ERR", "${request?.url} -> ${error?.description}")
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    log("JS", "${m.messageLevel()} ${m.message()}")
                    return true
                }

                override fun onShowFileChooser(
                    view: WebView?,
                    callback: ValueCallback<Array<Uri>>?,
                    params: FileChooserParams?
                ): Boolean {
                    // 实测 acceptTypes 可达 5868 字符（约 900 个扩展名），全量打印会撑爆 logcat
                    // 单行并挤掉后续 delivering 日志；只输出条数 + 图片类型摘要。
                    val types = params?.acceptTypes ?: emptyArray()
                    log(
                        "FILECHOOSER",
                        "triggered; mode=${params?.mode} acceptCount=${types.size} " +
                            "imageTypes=${AcceptTypes.imageSummary(types, 8)} " +
                            "(imageCount=${AcceptTypes.imageTypes(types).size})"
                    )
                    // AI-128：点“上传入口”前先准备好 cache 里的测试图，这里回填给页面。
                    val pending = pendingChooserUri
                    if (pending != null) {
                        pendingChooserUri = null
                        log("FILECHOOSER", "delivering $pending")
                        callback?.onReceiveValue(arrayOf(pending))
                        return true
                    }
                    // 未准备时释放回调，否则下一次选择永久卡死。
                    callback?.onReceiveValue(null)
                    return false
                }
            }
        }
        root.addView(
            webView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 2.4f)
        )

        setContentView(root)

        // 初始站点：EXTRA_URL > EXTRA_START_URL > EXTRA_SITE > 默认
        val explicitUrl = intent?.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() }
            ?: intent?.getStringExtra(EXTRA_START_URL)?.takeIf { it.isNotBlank() }
        val siteKey = intent?.getStringExtra(EXTRA_SITE)?.takeIf { it.isNotBlank() } ?: DEFAULT_SITE
        switchTo(siteKey, explicitUrl)
    }

    /** 站点切换栏：DeepSeek / Gemini / 自定义。 */
    private fun rebuildSiteBar() {
        siteBar.removeAllViews()
        listOf(
            AiSourceId.DEEPSEEK_WEBVIEW,
            AiSourceId.GEMINI_WEBVIEW,
            AiSourceId.CUSTOM_WEBVIEW
        ).forEach { id ->
            siteBar.addView(Button(this).apply {
                text = id.label
                setOnClickListener { switchTo(id.key, null) }
            })
        }
        siteBar.addView(Button(this).apply {
            text = "重载"
            setOnClickListener { webView.reload() }
        })
    }

    /**
     * 切换当前探测站点。
     *
     * @param siteKey [AiSourceId.key]；未知值按 `custom_web` 处理（URL 必须显式给出）。
     * @param explicitUrl 非空时优先使用，支持自定义源。
     */
    private fun switchTo(siteKey: String, explicitUrl: String?) {
        requireUiThread("switchTo")
        val id = AiSourceId.entries.firstOrNull { it.key == siteKey }
        adapter = when (id) {
            AiSourceId.DEEPSEEK_WEBVIEW -> DeepSeekAdapter()
            AiSourceId.GEMINI_WEBVIEW -> GeminiAdapter()
            else -> DeepSeekAdapter() // 自定义源暂复用 DeepSeek 选择器集合（CustomAdapter 行为一致）
        }
        val url = explicitUrl
            ?: (id?.let { AiSourcePresets.homeUrlFor(it) })
            ?: AiSourcePresets.homeUrlFor(AiSourceId.GEMINI_WEBVIEW)!!
        currentUrl = url
        log("SITE", "switch -> ${adapter.siteKey} url=$url")
        webView.loadUrl(url)
    }

    /**
     * 无人值守侦察：`auto=gemini_send` 时先填文本、等 Angular 渲染出发送按钮，再枚举。
     * 结果全部走 logcat（tag=AiProbe），供 adb 侧判定。
     */
    private fun runAuto(mode: String) {
        log("AUTO", "mode=$mode")
        when (mode) {
            "gemini_send" -> {
                runScript(GEMINI_FILL_ONLY) { r ->
                    log("GFILL", r)
                    webView.postDelayed({
                        runScript(GEMINI_SEND_ENUM) { e -> log("GSEND", e) }
                    }, 1500)
                }
            }
            "gemini_fps" -> testFillPasteSubmit()
            "gemini_attach" -> runAdapterScript("ATTACH") { adapter.verifyAttachmentScript() }
            "gemini_senddiag" -> runScript(GEMINI_SEND_DIAG) { r -> log("GSDIAG", r) }
            "gemini_diag" -> runAdapterScript("DIAG") { adapter.diagnoseScript() }
            // AI-008D：页面结构深度探测（只读）。
            "deep_probe" -> runAdapterScript("DEEP") { adapter.deepProbeScript() }
            // AI-030：同一会话内验证 send / response / generating —— 填文本后探一次，
            // 提交并等回答后再探一次。静态首页这组候选恒为 0，只有对话态才能验证。
            "selector_probe" -> {
                runScript(GEMINI_FILL_ONLY) { r ->
                    log("SPROBE_FILL", r)
                    webView.postDelayed({
                        runAdapterScript("DEEP_FILLED") { adapter.deepProbeScript() }
                    }, 1500)
                    webView.postDelayed({ testFillPasteSubmit() }, 3000)
                    webView.postDelayed({
                        runAdapterScript("DEEP_ANSWERED") { adapter.deepProbeScript() }
                    }, 30000)
                }
            }
            // AI-009D：先读 file input 属性，再走一次真实原生触摸，验证 onShowFileChooser 是否触发。
            "file_probe" -> {
                runAdapterScript("FILEATTR") { adapter.fileInputProbeScript() }
                webView.postDelayed({
                    prepareChooserImage()
                    webView.postDelayed({ tapUploadIcon() }, 800)
                }, 800)
            }
            // AI-011D：停止生成控件探测（只读，不点击）。
            "stop_probe" -> runAdapterScript("STOPCTL") { adapter.stopControlProbeScript() }
            "fschooser" -> {
                prepareChooserImage()
                tapUploadIcon()
                webView.postDelayed({
                    runAdapterScript("ATTACH_AFTER_CHOOSER") { adapter.verifyAttachmentScript() }
                }, 5000)
            }
            // AI-017：候选上传路径失败时的替代方案评估。拖放与系统剪贴板各跑一次，
            // 每次之后用 verifyAttachment 判定页面是否真的接受了附件。
            "upload_alt" -> {
                log("ALT", "=== 0/3 基线（排除页面残留附件）===")
                runAdapterScript("ALT_BASELINE_ATTACH") { adapter.verifyAttachmentScript() }
                log("ALT", "=== 1/3 合成拖放 ===")
                testDrop()
                webView.postDelayed({
                    runAdapterScript("ALT_DROP_ATTACH") { adapter.verifyAttachmentScript() }
                    log("ALT", "=== 2/3 系统剪贴板 + KEYCODE_PASTE ===")
                    testSystemClipboard()
                    webView.postDelayed({
                        runAdapterScript("ALT_CLIP_ATTACH") { adapter.verifyAttachmentScript() }
                    }, 3000)
                }, 3000)
            }
            "baseline" -> logBaseline()
            "e2e" -> runEndToEnd()
            "textonly" -> runTextOnlyControl()
            else -> log("AUTO", "unknown mode")
        }
    }

    /**
     * AI-131：DeepSeek 单次识牌闭环，严格按状态机顺序，每一步都打日志。
     *
     * 基线 → 附件（FILE_CHOOSER）→ 附件验证 → 填 prompt 并提交 → 等新回答 → 解析 List<Card>。
     * 附件验证不通过就**不提交**（AI-123）。
     */
    private fun runEndToEnd() {
        val prompt =
            "请识别这张掼蛋手牌照片里的全部牌。只输出 JSON 数组，元素形如 {\"rank\":\"A\",\"suit\":\"SPADE\"}；" +
                "rank 取 2-10/J/Q/K/A，大小王用 suit=JOKER 且 rank=SMALL_JOKER/BIG_JOKER。"

        // 1) 基线
        runAdapterScript("E2E_BASELINE") { adapter.snapshotScript() }

        // 2) 附件：准备图 → 原生触摸 → onShowFileChooser 回填
        prepareChooserImage()
        tapUploadIcon()

        // 3) 附件验证（必须通过才继续）
        webView.postDelayed({
            val verify = adapter.verifyAttachmentScript()
            runScript(verify) { raw ->
                val o = JsonParse.parse(raw)
                val attached = o != null && adapter.parseAttachment(o)
                log("E2E_VERIFY", "attached=$attached raw=$raw")
                if (!attached) {
                    log("E2E", "ABORT: 附件未确认，按 AI-123 不提交纯文本")
                    return@runScript
                }
                // 4) 填 prompt 并提交（此时附件已在）
                val submit = adapter.fillAndSubmitScript(prompt, "", "", "")
                runScript(submit) { sr ->
                    log("E2E_SUBMIT", sr)
                    // 5) 轮询新回答
                    pollAnswerForEndToEnd(attempt = 0)
                }
            }
        }, 5000)
    }

    /**
     * AI-018：带图/不带图对照的「不带图」对照组。
     *
     * 用与 [runEndToEnd] 完全相同的 prompt，但**不上传任何附件**。
     * 若模型仍能给出与真图一致的牌面，说明它在凭 prompt 猜测而不是读像素。
     */
    private fun runTextOnlyControl() {
        val prompt =
            "请识别这张掼蛋手牌照片里的全部牌。只输出 JSON 数组，元素形如 {\"rank\":\"A\",\"suit\":\"SPADE\"}；" +
                "rank 取 2-10/J/Q/K/A，大小王用 suit=JOKER 且 rank=SMALL_JOKER/BIG_JOKER。"
        runAdapterScript("CTRL_BASELINE") { adapter.snapshotScript() }
        runAdapterScript("CTRL_ATTACH_BEFORE") { adapter.verifyAttachmentScript() }
        val submit = adapter.fillAndSubmitScript(prompt, "", "", "")
        runScript(submit) { sr ->
            log("CTRL_SUBMIT", sr)
            pollAnswerForControl(attempt = 0)
        }
    }

    /** 与 [pollAnswerForEndToEnd] 同构，只是日志前缀为 CTRL。 */
    private fun pollAnswerForControl(attempt: Int) {
        if (attempt * 3000L > 120_000L) {
            log("CTRL", "TIMEOUT: 120s 内未拿到稳定回答")
            return
        }
        runScript(adapter.snapshotScript()) { raw ->
            val o = JsonParse.parse(raw)
            val snap = o?.let { adapter.parseSnapshot(it) }
            if (snap == null) {
                log("CTRL_POLL", "snapshot null at attempt=$attempt")
                webView.postDelayed({ pollAnswerForControl(attempt + 1) }, 3000)
                return@runScript
            }
            log(
                "CTRL_POLL",
                "attempt=$attempt count=${snap.responseCount} generating=${snap.generating} len=${snap.lastAnswerText.length}"
            )
            if (!snap.generating && snap.lastAnswerText.isNotBlank()) {
                val outcome = nz.org.aotearoa.guandanscore.recognition.AiCardResponseParser()
                    .parseDetailed(snap.lastAnswerText)
                log("CTRL_ANSWER", snap.lastAnswerText.take(600))
                log(
                    "CTRL_PARSE",
                    "cards=${outcome.cards.size} warnings=${outcome.warnings} first=${outcome.cards.take(5)}"
                )
                return@runScript
            }
            webView.postDelayed({ pollAnswerForControl(attempt + 1) }, 3000)
        }
    }

    /** 每 3s 读一次快照，连续两次文本非空且未变化即视为生成结束；上限 120s。 */
    private fun pollAnswerForEndToEnd(attempt: Int) {
        if (attempt * 3000L > 120_000L) {
            log("E2E", "TIMEOUT: 120s 内未拿到稳定回答")
            return
        }
        runScript(adapter.snapshotScript()) { raw ->
            val o = JsonParse.parse(raw)
            val snap = o?.let { adapter.parseSnapshot(it) }
            if (snap == null) {
                log("E2E_POLL", "snapshot null at attempt=$attempt")
                webView.postDelayed({ pollAnswerForEndToEnd(attempt + 1) }, 3000)
                return@runScript
            }
            log(
                "E2E_POLL",
                "attempt=$attempt count=${snap.responseCount} generating=${snap.generating} len=${snap.lastAnswerText.length}"
            )
            if (!snap.generating && snap.lastAnswerText.isNotBlank()) {
                val outcome = nz.org.aotearoa.guandanscore.recognition.AiCardResponseParser()
                    .parseDetailed(snap.lastAnswerText)
                log("E2E_ANSWER", snap.lastAnswerText.take(600))
                log(
                    "E2E_PARSE",
                    "cards=${outcome.cards.size} warnings=${outcome.warnings} " +
                        "first=${outcome.cards.take(5)}"
                )
                return@runScript
            }
            webView.postDelayed({ pollAnswerForEndToEnd(attempt + 1) }, 3000)
        }
    }

    /**
     * AI-011：记录可见 WebView 的窗口基线，供后续切后台 / 重建对照。
     *
     * 视图侧：像素尺寸、density、是否 attachedToWindow、可见性、页面缩放。
     * 页面侧：[PAGE_BASELINE] 返回 viewport 尺寸、滚动位置与 document 高度。
     */
    private fun logBaseline() {
        val wv = webView
        val dm = resources.displayMetrics
        log(
            "BASELINE_VIEW",
            "view=${wv.width}x${wv.height} density=${dm.density} densityDpi=${dm.densityDpi} " +
                "screen=${dm.widthPixels}x${dm.heightPixels} attachedToWindow=${wv.isAttachedToWindow} " +
                "visibility=${wv.visibility} shown=${wv.isShown} scale=${wv.scale} " +
                "parent=${wv.parent?.javaClass?.simpleName}"
        )
        runScript(PAGE_BASELINE) { log("BASELINE_PAGE", it) }
    }

    private fun runAdapterScript(tag: String, script: () -> String) {
        val s = script()
        if (s.isBlank()) {
            log(tag, "<该适配器未实现此脚本>")
            return
        }
        runScript(s) { log(tag, it) }
    }

    /**
     * WebView API 的线程断言（AI-022）。
     *
     * 探针里所有 WebView 调用本应经 `runOnUiThread` / `webView.postDelayed`，
     * 但 adb `--es auto` 入口与按钮回调混在一起时容易漏。这里在入口显式断言，
     * 让「漏加 runOnUiThread」立刻暴露，而不是变成偶发的不刷新。
     */
    private fun requireUiThread(where: String) {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "AiProbeActivity.$where must run on the main thread (AI-022), " +
                "current=${Thread.currentThread().name}"
        }
    }

    private fun log(tag: String, msg: String) {
        Log.i(TAG, "$tag: $msg")
        runOnUiThread {
            logView.append("[$tag] $msg\n")
            output.append("[$tag] $msg\n")
        }
    }

    private fun runScript(script: String, onResult: (String) -> Unit) {
        runOnUiThread {
            webView.evaluateJavascript(script) { raw ->
                onResult(pretty(decodeJsResult(raw)))
            }
        }
    }

    /**
     * AI-133：`evaluateJavascript` 回传的是 **JSON 编码的字符串字面量**（含 `\n`、`\"`、`\\`、`\uXXXX`）。
     * 旧实现只做 `removeSurrounding(\"\")` + `replace(\"\\\\\\\"\", \"\\\"\")`，无法还原换行/反斜杠/unicode 转义，
     * 导致含引号的 JSON 数组回答无法被 [JsonParse.parse] 解析（`snapshot null`）。
     * 这里用 [org.json.JSONTokener] 正确反序列化字符串字面量。
     */
    private fun decodeJsResult(raw: String?): String {
        if (raw == null) return "null"
        val t = raw.trim()
        if (t.isEmpty() || t == "null") return "null"
        return try {
            val v = org.json.JSONTokener(t).nextValue()
            when (v) {
                is String -> v
                org.json.JSONObject.NULL -> "null"
                else -> v.toString()
            }
        } catch (_: Exception) {
            t
        }
    }

    private fun pretty(raw: String): String = try {
        JSONObject(raw).toString(2)
    } catch (_: Exception) {
        raw
    }

    /**
     * AI-128：把一张 1×1 PNG 写进 cacheDir，授权给本进程 FileProvider 之外的直接 file URI。
     *
     * 注意：华为 WebView 能直接读 `file://` URI（我们自己的进程写、自己读）。
     * 若真机拒绝，后续应换成 `FileProvider` + `content://`（AI-037）。
     */
    private fun prepareChooserImage() {
        requireUiThread("prepareChooserImage")
        try {
            val png =
                android.util.Base64.decode(
                    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
                    android.util.Base64.DEFAULT
                )
            val dir = java.io.File(cacheDir, "probe")
            dir.mkdirs()
            val f = java.io.File(dir, "probe_upload.jpg")

            // AI-132：优先使用外部传入的真实手牌照片，便于端到端验证"图片→JSON"。
            // AI-012：真实图先经 UploadImageEncoder 压成 JPEG（长边 1800 / 质量 85）。
            val src = intent?.getStringExtra(EXTRA_TEST_IMAGE)?.takeIf { it.isNotBlank() }
            if (src != null) {
                if (encodeExternalImage(src, f)) {
                    pendingChooserUri = Uri.fromFile(f)
                    return
                }
                log("PREP", "real image $src 不可读，回退占位图")
            }

            // 占位图仍是 1×1 PNG，保持原文件名以免影响既有验收命令。
            val placeholder = java.io.File(dir, "probe_upload.png")
            placeholder.writeBytes(png)
            pendingChooserUri = Uri.fromFile(placeholder)
            log("PREP", "wrote placeholder ${placeholder.absolutePath} (${placeholder.length()} bytes); chooser will deliver it")
        } catch (e: Exception) {
            log("PREP", "failed: ${e.message}")
        }
    }

    /**
     * AI-132 + AI-012：读入外部图片（绝对路径或 content:// URI），按 EXIF 旋正，
     * 用 [UploadImageEncoder] 压成 JPEG 写到 [dest]。成功返回 true。
     */
    private fun encodeExternalImage(src: String, dest: java.io.File): Boolean {
        return try {
        val uri = Uri.parse(if (src.startsWith("content://") || src.startsWith("file://")) src else "file://$src")
        val originalBytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
        if (originalBytes == null || originalBytes.isEmpty()) {
            log("PREP", "read $src 为空")
            return false
        }
        val decoded = android.graphics.BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size)
        if (decoded == null) {
            log("PREP", "decode $src 失败（${originalBytes.size} bytes）")
            return false
        }
        val sourceW = decoded.width
        val sourceH = decoded.height
        val oriented = applyExifOrientation(decoded, originalBytes)
        val result = nz.org.aotearoa.guandanscore.imaging.UploadImageEncoder.encode(oriented)
        if (oriented !== decoded) oriented.recycle()
        decoded.recycle()
        dest.writeBytes(result.bytes)
        log(
            "PREP",
            "encoded $src -> ${dest.name}: ${sourceW}x$sourceH → " +
                "${result.width}x${result.height} q=${result.quality} scaled=${result.scaled} " +
                "${result.byteCount} bytes (原图 ${originalBytes.size} bytes)"
        )
        result.byteCount > 0
        } catch (e: Exception) {
            log("PREP", "encodeExternalImage failed: ${e.message}")
            false
        }
    }

    /** 按 EXIF 方向把 [bitmap] 旋正；无方向信息或无旋转时返回原对象。 */
    private fun applyExifOrientation(bitmap: android.graphics.Bitmap, bytes: ByteArray): android.graphics.Bitmap {
        val degrees = try {
            val exif = android.media.ExifInterface(java.io.ByteArrayInputStream(bytes))
            when (exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (e: Exception) {
            log("PREP", "EXIF 读取失败，按原方向处理: ${e.message}")
            0f
        }
        if (degrees == 0f) return bitmap
        val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
        return android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * AI-128：定位回形针图标，用原生触摸点击以产生 user activation，触发 onShowFileChooser。
     *
     * JS `.click()` 实测失败（`js-click-needs-user-activation`），只能走这条路径。
     */
    private fun tapUploadIcon() {
        requireUiThread("tapUploadIcon")
        val script = adapter.uploadIconScript()
        if (script.isBlank()) {
            log("TAPICON", "<该适配器未实现 uploadIconScript>")
            return
        }
        runScript(script) { raw ->
            val o = JsonParse.parse(raw)
            if (o == null || !o.optBoolean("ok")) {
                log("TAPICON", "locate failed: $raw")
                return@runScript
            }
            val x = o.optInt("x")
            val y = o.optInt("y")
            val innerH = o.optInt("innerHeight")
            log("TAPICON", "icon at css ($x,$y) innerHeight=$innerH; dispatching native touch")
            // 探测页 WebView 是普通布局，直接用视图坐标比例换算。
            val wv = webView
            val scale = wv.height.toFloat() / innerH.toFloat()
            val px = (x * scale).toInt()
            val py = (y * scale).toInt()
            val now = android.os.SystemClock.uptimeMillis()
            val down = android.view.MotionEvent.obtain(
                now, now, android.view.MotionEvent.ACTION_DOWN, px.toFloat(), py.toFloat(), 0
            )
            val up = android.view.MotionEvent.obtain(
                now, now + 50, android.view.MotionEvent.ACTION_UP, px.toFloat(), py.toFloat(), 0
            )
            wv.dispatchTouchEvent(down)
            wv.dispatchTouchEvent(up)
            down.recycle()
            up.recycle()
            log("TAPICON", "native touch dispatched at ($px,$py) px")
        }
    }

    /**
     * AI-014 / AI-129：合成 paste 注入验证。
     *
     * 注意：`dispatched=true` 不代表成功，必须紧接着点“附件检查”确认预览出现。
     */
    /**
     * AI-017：评估拖放（DragEvent/drop）能否替代 paste。
     * 只派发合成 `dragenter`/`dragover`/`drop`；真实系统拖放需要外部输入设备，无法在 WebView 内合成。
     */
    private fun testDrop() {
        val png1x1 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
        val script = """
            (function(name, mime, b64) {
              var el = null;
              try { el = document.querySelector('div[contenteditable="true"], textarea'); } catch (e) {}
              if (!el) return JSON.stringify({ ok: false, why: 'composer-not-found' });
              var binary = atob(b64);
              var bytes = new Uint8Array(binary.length);
              for (var i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
              var file;
              try { file = new File([bytes], name, { type: mime }); } catch (e) { return JSON.stringify({ ok: false, why: 'file-construct-failed' }); }
              var dt = new DataTransfer();
              dt.items.add(file);
              el.focus();
              var types = ['dragenter', 'dragover', 'drop'];
              var results = {};
              for (var k = 0; k < types.length; k++) {
                try {
                  var ev = new DragEvent(types[k], { dataTransfer: dt, bubbles: true, cancelable: true, composed: true });
                  results[types[k]] = el.dispatchEvent(ev);
                } catch (e) { results[types[k]] = 'throw:' + e.message; }
              }
              return JSON.stringify({ ok: true, results: results, bytes: bytes.length });
            })(${JsonQuote.of("probe.png")}, ${JsonQuote.of("image/png")}, ${JsonQuote.of(png1x1)})
        """.trimIndent()
        runScript(script) { log("DROP", it) }
    }

    /**
     * AI-017：评估系统剪贴板。把 PNG 放进 [android.content.ClipboardManager] 后聚焦输入框，
     * 用原生 `KEYCODE_PASTE` 触发真实粘贴。这是唯一可能被页面当作可信粘贴的路径。
     */
    private fun testSystemClipboard() {
        try {
            val png = android.util.Base64.decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
                android.util.Base64.DEFAULT
            )
            // FileProvider 只暴露 cache/photos/（res/xml/file_paths.xml），必须放这个目录下。
            val dir = java.io.File(cacheDir, "photos").apply { mkdirs() }
            val f = java.io.File(dir, "clipboard.png").apply { writeBytes(png) }
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.files", f
            )
            val clip = android.content.ClipData.newUri(contentResolver, "probe", uri)
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            cm.setPrimaryClip(clip)
            log("CLIP", "set ${clip.itemCount} item(s), uri=$uri")
            // 聚焦输入框后发送真实粘贴快捷键
            runScript("document.querySelector('div[contenteditable=\"true\"], textarea')?.focus()") {
                val root = webView.rootView
                val t = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_PASTE)
                root.dispatchKeyEvent(t)
                root.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_PASTE))
                log("CLIP", "dispatched KEYCODE_PASTE")
            }
        } catch (e: Exception) {
            log("CLIP", "failed: ${e::class.simpleName}: ${e.message}")
        }
    }

    private fun testPaste() {
        val png1x1 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
        val a = adapter
        val pasteScript = (a as? nz.org.aotearoa.guandanscore.recognition.webview.PasteCapableAdapter)
            ?.pasteAttachmentScript("probe.png", "image/png", png1x1)
        if (pasteScript.isNullOrBlank()) {
            log("PASTE", "<该适配器未实现 pasteAttachmentScript>")
            return
        }
        log("PASTE", "dispatching synthetic paste via current adapter")
        runScript(pasteScript) { r -> log("PASTE", r) }
    }

    /**
     * 端到端验证：走适配器自己的 `fillAndSubmitScript`（Gemini 内部完成填文本 → 粘贴 → 提交）。
     * 成功后应立即在页面上看到用户消息与图片缩略图。
     */
    private fun testFillPasteSubmit() {
        val png1x1 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
        val script = adapter.fillAndSubmitScript("这是什么颜色？只回答颜色。", "probe.png", "image/png", png1x1)
        if (script.isBlank()) {
            log("FPS", "<该适配器未实现 fillAndSubmitScript>")
            return
        }
        runScript(script) { r -> log("FPS", r) }
    }

    /** 枚举 composer 容器内所有带 svg 的按钮：坐标 + 尺寸，用来确认回形针是哪个。 */
    private val COMPOSER_BUTTONS = """
        (function(){
          var input=document.querySelector('textarea[placeholder*=\"Message DeepSeek\"], textarea[name=\"search\"], textarea');
          if(!input) return JSON.stringify({ok:false,why:'no-input'});
          var ir=input.getBoundingClientRect();
          var box=input;
          for(var i=0;i<6&&box&&box.parentElement;i++){box=box.parentElement;}
          var out={ok:true, inputRect:{l:Math.round(ir.left),t:Math.round(ir.top),r:Math.round(ir.right),b:Math.round(ir.bottom)}, innerHeight:window.innerHeight, items:[]};
          if(!box) return JSON.stringify(out);
          var cs=box.querySelectorAll('div,button');
          for(var j=0;j<cs.length;j++){
            var c=cs[j];
            if(!c.querySelector('svg')) continue;
            var r=c.getBoundingClientRect();
            if(r.width<=0||r.height<=0||r.width>100||r.height>100) continue;
            out.items.push({tag:c.tagName.toLowerCase(),cls:(c.className||'').toString().slice(0,60),x:Math.round(r.left+r.width/2),y:Math.round(r.top+r.height/2),w:Math.round(r.width),h:Math.round(r.height)});
          }
          return JSON.stringify(out);
        })()
    """.trimIndent()

    /** 枚举页面上所有可点击元素，用来重建发送按钮选择器。 */
    private val ENUM_BUTTONS = """
        (function(){
          var out={ok:true,buttons:[],clickableDivs:[]};
          var bs=document.querySelectorAll('button');
          for(var i=0;i<bs.length;i++){
            var b=bs[i];
            out.buttons.push({
              type:b.getAttribute('type')||'',
              aria:(b.getAttribute('aria-label')||'').slice(0,50),
              cls:(b.className||'').toString().slice(0,80),
              dis:b.disabled,
              ariaDis:b.getAttribute('aria-disabled')||'',
              w:b.offsetWidth,h:b.offsetHeight,
              vis:b.offsetWidth>0&&b.offsetHeight>0,
              txt:(b.innerText||'').trim().slice(0,20)
            });
          }
          var ds=document.querySelectorAll('div[role=\"button\"],div[class*=\"send\"],div[class*=\"Send\"],span[role=\"button\"]');
          for(var j=0;j<ds.length;j++){
            var d=ds[j];
            out.clickableDivs.push({
              tag:d.tagName.toLowerCase(),
              role:d.getAttribute('role')||'',
              aria:(d.getAttribute('aria-label')||'').slice(0,50),
              cls:(d.className||'').toString().slice(0,80),
              w:d.offsetWidth,h:d.offsetHeight,
              vis:d.offsetWidth>0&&d.offsetHeight>0,
              txt:(d.innerText||'').trim().slice(0,20)
            });
          }
          return JSON.stringify(out);
        })()
    """.trimIndent()

    /** 输入框及其祖先结构，用来定位发送按钮。 */
    private val ENUM_INPUT = """
        (function(){
          var el=document.querySelector('textarea#chat-input')||document.querySelector('textarea');
          if(!el) return JSON.stringify({ok:false,why:'no-textarea'});
          var out={ok:true,id:el.id||'',cls:(el.className||'').toString().slice(0,80),placeholder:el.placeholder||'',rows:el.rows};
          out.ancestors=[];
          var p=el.parentElement;
          for(var i=0;i<5&&p;i++){
            var kids=[];
            for(var k=0;k<p.children.length;k++){
              var c=p.children[k];
              kids.push({tag:c.tagName.toLowerCase(),cls:(c.className||'').toString().slice(0,60),role:c.getAttribute('role')||'',w:c.offsetWidth,h:c.offsetHeight});
            }
            out.ancestors.push({tag:p.tagName.toLowerCase(),cls:(p.className||'').toString().slice(0,60),childCount:p.children.length,children:kids});
            p=p.parentElement;
          }
          return JSON.stringify(out);
        })()
    """.trimIndent()

    /**
     * Gemini 发送按钮侦察：列出 composer 容器附近所有可点元素及其 aria-label / 尺寸。
     * 空输入时 Gemini 只显示 Dictate，输入文本后才出现 Send；需先点「Gemini填文本」。
     */
    private val GEMINI_SEND_ENUM = """
        (function(){
          var input=document.querySelector('rich-textarea [contenteditable="true"], div[contenteditable="true"][role="textbox"], div[contenteditable="true"]');
          if(!input) return JSON.stringify({ok:false,why:'no-composer'});
          var box=input;
          for(var i=0;i<8&&box&&box.parentElement;i++){box=box.parentElement;}
          var out={ok:true,text:(input.textContent||'').slice(0,40),items:[],labels:[]};
          if(!box) return JSON.stringify(out);
          var els=box.querySelectorAll('button,[role="button"],[aria-label]');
          for(var j=0;j<els.length;j++){
            var e=els[j];
            var r=e.getBoundingClientRect();
            if(r.width<=0||r.height<=0) continue;
            var lab=e.getAttribute('aria-label')||'';
            out.items.push({tag:e.tagName.toLowerCase(),aria:lab.slice(0,40),cls:(e.className||'').toString().slice(0,40),dis:e.disabled,ariaDis:e.getAttribute('aria-disabled')||'',w:Math.round(r.width),h:Math.round(r.height),txt:(e.innerText||'').trim().slice(0,15)});
          }
          return JSON.stringify(out);
        })()
    """.trimIndent()

    /**
     * 发送按钮时序诊断：填文本 → 立即/100ms/300ms/800ms/1500ms 采样 send 选择器命中情况。
     * 用来判断 Angular 渲染延迟，以及粘贴图片是否破坏了 send 按钮。
     */
    private val GEMINI_SEND_DIAG = """
        (function(){
          var el=document.querySelector('rich-textarea [contenteditable="true"], div[contenteditable="true"][role="textbox"], div[contenteditable="true"]');
          if(!el) return JSON.stringify({ok:false,why:'no-composer'});
          el.focus();
          var sel=window.getSelection();var range=document.createRange();
          range.selectNodeContents(el);sel.removeAllRanges();sel.addRange(range);
          try{document.execCommand('insertText',false,'diag message');}catch(e){}
          function probe(tag){
            var bySel=0,byAria=0,byAriaLab='';
            try{bySel=document.querySelectorAll('button[aria-label*="Send message" i]').length;}catch(e){}
            var all=document.querySelectorAll('[aria-label]');
            for(var i=0;i<all.length;i++){
              var lab=(all[i].getAttribute('aria-label')||'').toLowerCase();
              if(lab.indexOf('send')<0&&lab.indexOf('发送')<0)continue;
              var r=all[i].getBoundingClientRect();
              if(r.width<=0||r.height<=0)continue;
              byAria++;byAriaLab=all[i].getAttribute('aria-label');
            }
            return {t:tag,bySel:bySel,byAria:byAria,lab:byAriaLab};
          }
          var s0=probe('sync');

          // 复刻 FPS：紧接 paste 一张 1x1 png，再忙等采样。
          try {
            var binary=atob('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==');
            var bytes=new Uint8Array(binary.length);
            for(var i=0;i<binary.length;i++)bytes[i]=binary.charCodeAt(i);
            var file=new File([bytes],'diag.png',{type:'image/png'});
            var dt=new DataTransfer();dt.items.add(file);
            var ev=new ClipboardEvent('paste',{clipboardData:dt,bubbles:true,cancelable:true,composed:true});
            el.focus();el.dispatchEvent(ev);
          } catch(e) {}

          var afterPaste=probe('after-paste');
          var dl=Date.now()+2000;
          while(Date.now()<dl){ var b=probe('wait'); if(b.bySel>0||b.byAria>0){afterPaste=b;break;} }
          return JSON.stringify({ok:true,sync:s0,afterPaste:afterPaste});
        })()
    """.trimIndent()

    /** 只填文本不提交，用于观察发送按钮是否出现。 */
    private val GEMINI_FILL_ONLY = """
        (function(){
          var el=document.querySelector('rich-textarea [contenteditable="true"], div[contenteditable="true"][role="textbox"], div[contenteditable="true"]');
          if(!el) return JSON.stringify({ok:false,why:'no-composer'});
          el.focus();
          var sel=window.getSelection();var range=document.createRange();
          range.selectNodeContents(el);sel.removeAllRanges();sel.addRange(range);
          var inserted=false;
          try{inserted=document.execCommand('insertText',false,'test message');}catch(e){}
          return JSON.stringify({ok:true,inserted:inserted,text:(el.textContent||'').slice(0,40)});
        })()
    """.trimIndent()

    /** 文件输入框详情，用于 FILE_CHOOSER 路径。 */
    private val ENUM_FILE = """
        (function(){
          var fs=document.querySelectorAll('input[type=\"file\"]');
          var out={ok:true,count:fs.length,items:[]};
          for(var i=0;i<fs.length;i++){
            var f=fs[i];
            out.items.push({accept:f.accept||'',multiple:f.multiple,cls:(f.className||'').toString().slice(0,80),w:f.offsetWidth,h:f.offsetHeight,parentCls:(f.parentElement&&f.parentElement.className||'').toString().slice(0,60)});
          }
          return JSON.stringify(out);
        })()
    """.trimIndent()
}

/**
 * AI-011：页面侧基线。只读，不触碰 Cookie / Storage / 图片字节。
 *
 * 返回 viewport 尺寸、滚动位置、文档高度与可见性，供切后台 / 重建后对比是否"页面实际更新"。
 */
private val PAGE_BASELINE = """
    (function() {
      var de = document.documentElement;
      var b = document.body;
      var out = {
        ok: true,
        href: location.href,
        readyState: document.readyState,
        visibility: document.visibilityState,
        hidden: document.hidden,
        innerWidth: window.innerWidth,
        innerHeight: window.innerHeight,
        devicePixelRatio: window.devicePixelRatio,
        scrollX: Math.round(window.scrollX),
        scrollY: Math.round(window.scrollY),
        docClientW: de ? de.clientWidth : 0,
        docClientH: de ? de.clientHeight : 0,
        docScrollH: de ? de.scrollHeight : 0,
        bodyScrollH: b ? b.scrollHeight : 0,
        title: (document.title || '').slice(0, 80)
      };
      return JSON.stringify(out);
    })()
""".trimIndent()
