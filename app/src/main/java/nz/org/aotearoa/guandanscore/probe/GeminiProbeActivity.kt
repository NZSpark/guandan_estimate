package nz.org.aotearoa.guandanscore.probe

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import nz.org.aotearoa.guandanscore.recognition.GeminiScripts
import nz.org.aotearoa.guandanscore.recognition.site.DeepSeekAdapter
import org.json.JSONObject

/**
 * 阶段 0 专用：最小 Gemini WebView 验证入口。
 *
 * 用途：人工登录 → 读取真实 DOM → 验证图片上传路径。
 * 不属于正式 App 功能，验证完成后可整体移除。
 *
 * 启动：adb shell am start -n nz.org.aotearoa.guandanscore/.probe.GeminiProbeActivity
 */
class GeminiProbeActivity : Activity() {

    companion object {
        /** 从 AI 设置页跳过来时指定要登录的站点。 */
        const val EXTRA_START_URL = "start_url"
        const val DEFAULT_START_URL = "https://gemini.google.com/app"
    }

    private lateinit var webView: WebView
    private lateinit var output: TextView
    private lateinit var logView: TextView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { action() }
            bar.addView(this)
        }
        button("诊断") { runScript(GeminiScripts.diagnose()) { log("DIAG", it) } }
        button("快照") { runScript(GeminiScripts.snapshot()) { log("SNAP", it) } }
        button("附件检查") { runScript(GeminiScripts.verifyAttachment()) { log("ATTACH", it) } }
        button("上传测试图") { testPaste() }
        button("深探") { runScript(DEEP_PROBE) { log("DEEP", it) } }
        // DeepSeek 适配器诊断（AI-010D / AI-127）：独立于当前页面 URL，靠适配器选择器自检。
        val ds = DeepSeekAdapter()
        button("DS诊断") { runScript(ds.diagnoseScript()) { log("DS-DIAG", it) } }
        button("DS快照") { runScript(ds.snapshotScript()) { log("DS-SNAP", it) } }
        button("DS登录检查") { runScript(ds.loginCheckScript()) { log("DS-LOGIN", it) } }
        button("DS附件检查") { runScript(ds.verifyAttachmentScript()) { log("DS-ATTACH", it) } }
        button("DS上传入口") { runScript(ds.uploadTriggerScript()) { log("DS-UPLOAD", it) } }
        button("DS粘贴测试") { testDeepSeekPaste(ds) }
        button("UA") { runScript("navigator.userAgent") { log("UA", it) } }
        button("按钮枚举") { runScript(ENUM_BUTTONS) { log("BTNS", it) } }
        button("输入框详情") { runScript(ENUM_INPUT) { log("INPUT", it) } }
        button("文件框详情") { runScript(ENUM_FILE) { log("FILE", it) } }
        button("清日志") { output.text = "" }
        root.addView(android.widget.HorizontalScrollView(this).apply { addView(bar) })

        logView = TextView(this).apply {
            textSize = 10f
            setTextIsSelectable(true)
            setBackgroundColor(0x10000000)
        }
        root.addView(ScrollView(this).apply { addView(logView) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.25f))

        output = TextView(this).apply {
            textSize = 9f
            gravity = Gravity.TOP or Gravity.START
            setTextIsSelectable(true)
            setPadding(8, 8, 8, 8)
        }
        root.addView(ScrollView(this).apply { addView(output) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.75f))

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
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    log("JS", "${m.messageLevel()} ${m.message()}")
                    return true
                }
                override fun onShowFileChooser(
                    view: WebView?,
                    callback: android.webkit.ValueCallback<Array<android.net.Uri>>?,
                    params: FileChooserParams?
                ): Boolean {
                    log("FILECHOOSER", "onShowFileChooser triggered; accept=${params?.acceptTypes?.joinToString()}")
                    // 阶段 0 只记录，不接管；返回 false 让系统处理
                    return false
                }
            }
        }
        root.addView(webView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.2f))

        setContentView(root)
        val startUrl = intent?.getStringExtra(EXTRA_START_URL)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_START_URL
        webView.loadUrl(startUrl)
    }

    private fun log(tag: String, msg: String) {
        Log.i("GeminiProbe", "$tag: $msg")
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
          var ds=document.querySelectorAll('div[role="button"],div[class*="send"],div[class*="Send"],span[role="button"]');
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

    /** 输入框及其兄弟节点的结构，用来定位发送按钮。 */
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

    /** 文件输入框详情，用于 FILE_CHOOSER 路径。 */
    private val ENUM_FILE = """
        (function(){
          var fs=document.querySelectorAll('input[type="file"]');
          var out={ok:true,count:fs.length,items:[]};
          for(var i=0;i<fs.length;i++){
            var f=fs[i];
            out.items.push({accept:f.accept||'',multiple:f.multiple,cls:(f.className||'').toString().slice(0,80),w:f.offsetWidth,h:f.offsetHeight,parentCls:(f.parentElement&&f.parentElement.className||'').toString().slice(0,60)});
          }
          return JSON.stringify(out);
        })()
    """.trimIndent()

    private val DEEP_PROBE = """
        (function(){
          var out={ok:true};
          var eds=document.querySelectorAll('[contenteditable="true"]');
          out.editableCount=eds.length;
          out.editables=[];
          for(var i=0;i<eds.length;i++){
            var e=eds[i];
            out.editables.push({tag:e.tagName.toLowerCase(),role:e.getAttribute('role')||'',aria:(e.getAttribute('aria-label')||'').slice(0,40),cls:(e.className||'').toString().slice(0,60),w:e.offsetWidth,h:e.offsetHeight});
          }
          var bs=document.querySelectorAll('button');
          out.buttonCount=bs.length;
          out.buttons=[];
          for(var j=0;j<bs.length;j++){
            var b=bs[j];
            var label=b.getAttribute('aria-label')||'';
            var txt=(b.innerText||'').trim().slice(0,20);
            if(label||txt) out.buttons.push({aria:label.slice(0,40),text:txt,dis:b.disabled});
          }
          var fi=document.querySelector('input[type="file"]');
          out.fileInput=fi?{accept:fi.accept||'',multiple:fi.multiple,cls:(fi.className||'').toString().slice(0,60)}:null;
          return JSON.stringify(out);
        })()
    """.trimIndent()

    /**
     * AI-014：合成 paste 注入验证。
     * 注意：dispatched=true 不代表成功，必须紧接着点“附件检查”确认预览。
     */
    private fun testDeepSeekPaste(ds: DeepSeekAdapter) {
        val png1x1 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
        log("DS-PASTE", "dispatching synthetic paste")
        runScript(ds.pasteAttachmentScript("probe.png", "image/png", png1x1)) { r ->
            log("DS-PASTE", r)
        }
    }

    private fun testPaste() {
        val png1x1 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
        log("PASTE", "dispatching synthetic paste with 1x1 PNG")
        runScript(GeminiScripts.pasteImage("probe.png", "image/png", png1x1)) { r ->
            log("PASTE", r)
        }
    }
}
