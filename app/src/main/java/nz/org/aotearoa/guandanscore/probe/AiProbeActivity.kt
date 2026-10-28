package nz.org.aotearoa.guandanscore.probe

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import nz.org.aotearoa.guandanscore.recognition.site.SiteAdapter
import nz.org.aotearoa.guandanscore.recognition.source.AiSourceId
import nz.org.aotearoa.guandanscore.recognition.source.AiSourcePresets
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
        button("诊断") { runAdapterScript("DIAG") { adapter.diagnoseScript() } }
        button("快照") { runAdapterScript("SNAP") { adapter.snapshotScript() } }
        button("登录检查") { runAdapterScript("LOGIN") { adapter.loginCheckScript() } }
        button("附件检查") { runAdapterScript("ATTACH") { adapter.verifyAttachmentScript() } }
        button("准备测试图") { prepareChooserImage() }
        button("上传入口") { runAdapterScript("UPLOAD") { adapter.uploadTriggerScript() } }
        button("原生点上传") { tapUploadIcon() }
        button("输入区按钮") { runScript(COMPOSER_BUTTONS) { log("CBTNS", it) } }
        button("上传图标坐标") { runAdapterScript("ICON") { adapter.uploadIconScript() } }
        button("粘贴测试") { testPaste() }
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
                    log(
                        "FILECHOOSER",
                        "triggered; mode=${params?.mode} accept=${params?.acceptTypes?.joinToString()}"
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
            else -> log("AUTO", "unknown mode")
        }
    }

    private fun runAdapterScript(tag: String, script: () -> String) {
        val s = script()
        if (s.isBlank()) {
            log(tag, "<该适配器未实现此脚本>")
            return
        }
        runScript(s) { log(tag, it) }
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
                val text = raw?.trim()?.removeSurrounding("\"")?.replace("\\\"", "\"") ?: "null"
                onResult(pretty(text))
            }
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
        try {
            val png =
                android.util.Base64.decode(
                    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
                    android.util.Base64.DEFAULT
                )
            val dir = java.io.File(cacheDir, "probe")
            dir.mkdirs()
            val f = java.io.File(dir, "probe_upload.png")
            f.writeBytes(png)
            pendingChooserUri = Uri.fromFile(f)
            log("PREP", "wrote ${f.absolutePath} (${f.length()} bytes); chooser will deliver it")
        } catch (e: Exception) {
            log("PREP", "failed: ${e.message}")
        }
    }

    /**
     * AI-128：定位回形针图标，用原生触摸点击以产生 user activation，触发 onShowFileChooser。
     *
     * JS `.click()` 实测失败（`js-click-needs-user-activation`），只能走这条路径。
     */
    private fun tapUploadIcon() {
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
