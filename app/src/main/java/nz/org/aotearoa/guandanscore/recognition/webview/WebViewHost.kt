package nz.org.aotearoa.guandanscore.recognition.webview

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

/** WebView 的窗口挂接状态。必须显式上报，不能只报“已创建”。 */
sealed interface WindowStatus {
    /** 已挂进可见容器。 */
    data object Attached : WindowStatus

    /**
     * 已挂进**离屏宿主**（AI-166/AI-167）。
     *
     * 语义与 [Attached] 不同：用户看不到 WebView，但它在视图树里、有真实全屏 layout
     * （`displayMetrics.widthPixels × heightPixels`），因此 `innerHeight` 等于屏高、
     * 页面会出帧、`evaluateJavascript` 与 DOM 测量都正常。
     * 对驱动层而言它与 [Attached] **等价可用**，区别只在于是否需要用户可见。
     */
    data object Offscreen : WindowStatus

    /** 有 WebView 但没有任何父容器。页面不会出帧，AI 会超时。 */
    data object NotAttached : WindowStatus

    /** 尚未创建 WebView。 */
    data object NotCreated : WindowStatus
}

/** 文件选择请求的转发接口。由宿主 Activity / Fragment 提供实际的选择器。 */
fun interface FileChooserHandler {
    /**
     * @param acceptTypes 来自 `FileChooserParams.acceptTypes`
     * @return 是否已接管；false 表示交回系统默认处理
     */
    fun onFileChooser(acceptTypes: Array<String>?): Boolean
}

/**
 * 进程级 WebView 宿主。
 *
 * 关键约束（`doc/multi_ai_update.md` §6）：
 * - 全进程只有一个 WebView；两个实例意味着两份 Cookie、两个会话、双倍内存。
 * - 一个 WebView 同一时刻只能有一个父容器。重挂前必须先 `removeHostFromParent(dropWebView=false)`，
 *   否则抛 `IllegalStateException`（M12）。
 * - 首版只实现**可见挂接**（AI-118）。overlay 后台宿主见 [attachOverlay] 的 TODO。
 * - 视口为 0 时禁止写高度（M3）；先 measure/layout 再挂窗口，否则 `innerHeight == 0`。
 */
class WebViewHost private constructor(private val appContext: Context) {

    private val main = Handler(Looper.getMainLooper())

    /** AI-025：默认安全配置；改动即改契约，单测会同步报警。 */
    private val securityConfig: WebViewSecurityConfig = WebViewSecurityConfig()

    /**
     * 把 [config] 应用到 `WebSettings` 与 `CookieManager`（AI-025）。
     *
     * 单独抽方法是为了让"契约对象 → 真实 WebView"这一步可读、可审；
     * `CookieManager` 侧的两个开关也一并在这里设置，避免与 settings 分散。
     */
    private fun applySecurityConfig(settings: WebSettings, config: WebViewSecurityConfig) {
        config.violations().forEach { Log.w(TAG, "security config violation: $it") }
        settings.javaScriptEnabled = config.javaScriptEnabled
        settings.domStorageEnabled = config.domStorageEnabled
        settings.databaseEnabled = config.databaseEnabled
        settings.allowFileAccess = config.allowFileAccess
        settings.allowContentAccess = config.allowContentAccess
        settings.mixedContentMode = if (config.mixedContentNeverAllow) {
            WebSettings.MIXED_CONTENT_NEVER_ALLOW
        } else {
            WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        settings.saveFormData = config.saveFormData
        settings.setGeolocationEnabled(config.geolocationEnabled)
        // 接受 Cookie 是全局开关，创建期即可设置。
        CookieManager.getInstance().setAcceptCookie(config.acceptCookie)
    }

    /**
     * 第三方 Cookie 必须绑定具体 WebView 实例，创建期还没有实例，
     * 因此在 `webView = view` 之后单独设置（AI-025）。
     */
    private fun applyThirdPartyCookies(view: WebView, config: WebViewSecurityConfig) {
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, config.acceptThirdPartyCookies)
    }

    @Volatile
    private var webView: WebView? = null

    /** 离屏测量容器：先给它真实尺寸，WebView 才有非零视口。 */
    private var offscreenHost: FrameLayout? = null

    /**
     * 离屏挂接容器（AI-166）：1×1 的不可见 FrameLayout，只作为视图树锚点。
     * WebView 在其中按全屏像素 measure/layout，窗口小不代表视口小。
     */
    private var offscreenAnchor: FrameLayout? = null

    /** 最近一次离屏挂接的 WebView layout 尺寸，供日志与断言使用（AI-167）。 */
    @Volatile
    var lastLayoutWidth: Int = 0
        private set

    @Volatile
    var lastLayoutHeight: Int = 0
        private set

    /** 当前挂接的可见父容器。 */
    @Volatile
    private var visibleParent: FrameLayout? = null

    @Volatile
    private var windowStatus: WindowStatus = WindowStatus.NotCreated

    /** 最近一次导航的目标首页；[destroy] 时清空，避免重建后跳过导航。 */
    @Volatile
    internal var navigatedTarget: String = ""

    /** 由宿主注入：如何把 WebView 挂到可见容器里。 */
    @Volatile
    private var attachTargetProvider: (() -> FrameLayout?)? = null

    /**
     * 由宿主注入：离屏挂接的视图树锚点（AI-166/AI-167）。
     *
     * 与 [attachTargetProvider] 的分工：那个要的是**可见**容器（用户看得见页面）；
     * 这个要的是任意一个 `isAttachedToWindow` 的视图，只用来让 WebView 进入视图树。
     * 主界面刻意不插可见 WebView（AI-165），因此识牌只能走离屏路径。
     */
    @Volatile
    private var offscreenAnchorProvider: ((FrameLayout) -> Unit)? = null

    /** 由宿主注入：文件选择器接管。 */
    @Volatile
    var fileChooserHandler: FileChooserHandler? = null

    /**
     * 页面挂起的文件选择回调（AI-073：唯一所有者语义，见 [FileChooserSlot]）。
     * [destroy] 时用它释放页面等待。
     */
    private val fileChooser = FileChooserSlot<android.webkit.ValueCallback<Array<android.net.Uri>>>()

    /** 由宿主注入：页面加载完成回调（用于就绪等待）。 */
    @Volatile
    var onPageFinished: ((String) -> Unit)? = null

    @Volatile
    var currentUrl: String = ""
        private set

    /**
     * 待投递的图片 URI。
     *
     * 流程：驱动层先把图片写进 cache 并 [prepareUpload]，然后点上传按钮；
     * 页面触发 `onShowFileChooser` 时直接把该 URI 回填，不经过系统选择器。
     * 一次消费后清空，避免下一轮误用旧图片。
     */
    @Volatile
    var pendingUploadUri: android.net.Uri? = null
        private set

    /** 准备一次文件选择回填。必须在主线程。 */
    fun prepareUpload(uri: android.net.Uri) {
        requireMainThread()
        pendingUploadUri = uri
        Log.i(TAG, "prepareUpload: pending uri set")
    }

    /** 清空待投递 URI（任务失败/取消时调用）。 */
    fun clearPendingUpload() {
        pendingUploadUri = null
    }

    /**
     * 当前 WebView 引用。**只能主线程调用**（AI-022）。
     *
     * 读取本身不碰 WebView 内部状态，但所有调用方都会立刻在返回值上执行
     * `evaluateJavascript` / `loadUrl` / `dispatchTouchEvent` / `addView`，
     * 因此在出口处断言，比等 WebView 抛 `CalledFromWrongThread` 更容易定位。
     */
    fun currentWebView(): WebView? {
        requireMainThread()
        return webView
    }

    /**
     * 在 WebView 上按视口坐标派发一次原生触摸（DOWN + UP）。
     *
     * 为什么必须原生：部分站点（实测 DeepSeek）的文件选择器要求 user activation，
     * `evaluateJavascript` 里的 `.click()` 不满足，`onShowFileChooser` 不会触发。
     *
     * @param x CSS 像素（视口坐标）
     * @param y CSS 像素（视口坐标）
     * @param viewportHeight 页面 `window.innerHeight`，用于纵向坐标换算
     * @param viewportWidth 页面 `window.innerWidth`，用于横向坐标换算。
     *   不传则退回按纵向比例缩放横向 —— 只在视口与视图严格等比时才对，
     *   而 WebView 会被布局拉伸出非等比尺寸（AI-015 真机点偏的根因）。
     * @return 是否成功派发
     */
    fun tapViewportPoint(
        x: Int,
        y: Int,
        viewportHeight: Int,
        viewportWidth: Int = 0
    ): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            val latch = java.util.concurrent.CountDownLatch(1)
            var result = false
            main.post {
                result = tapViewportPoint(x, y, viewportHeight, viewportWidth)
                latch.countDown()
            }
            latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
            return result
        }
        val view = webView ?: return false
        if (viewportHeight <= 0) return false
        val density = appContext.resources.displayMetrics.density
        val mapped = ViewportTapMapper.map(
            cssX = x,
            cssY = y,
            viewWidth = view.width,
            viewHeight = view.height,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight
        ) ?: return false
        val viewW = view.width
        val viewH = view.height
        val px = mapped.x
        val py = mapped.y
        val now = android.os.SystemClock.uptimeMillis()
        val down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, px.toFloat(), py.toFloat(), 0)
        val up = android.view.MotionEvent.obtain(now, now + 40, android.view.MotionEvent.ACTION_UP, px.toFloat(), py.toFloat(), 0)
        val ok = try {
            view.dispatchTouchEvent(down)
            view.dispatchTouchEvent(up)
            true
        } catch (e: Exception) {
            Log.w(TAG, "tapViewportPoint failed", e)
            false
        } finally {
            down.recycle()
            up.recycle()
        }
        Log.i(
            TAG,
            "tapViewportPoint ($x,$y) css -> ($px,$py) px, view=${viewW}x${viewH} " +
                "viewport=${viewportWidth}x$viewportHeight " +
                "scaleX=${mapped.scaleX} scaleY=${mapped.scaleY} density=$density"
        )
        return ok
    }

    /**
     * 离屏挂接（AI-166）：把 WebView 挂进 1×1 不可见容器，但按全屏像素 layout。
     *
     * 为什么不是直接 1px×1px layout：那样 `innerHeight == 1`，DeepSeek composer 会塌陷，
     * 比不挂接更糟（见 `doc/ai_tasks.md` 阶段 8 的约束提醒）。正确做法是
     * **窗口小 + WebView layout 全屏**，`innerHeight` 才能等于屏高。
     *
     * 调用方需自行保证 [anchor] 已经 `addView` 到某个 `isAttachedToWindow` 的视图树里；
     * 本方法只负责子视图尺寸与状态，不替调用方创建窗口（那需要 Activity/WindowManager）。
     *
     * @return 挂接是否成功；失败时状态回落 [WindowStatus.NotAttached]
     */
    fun attachOffscreen(anchor: FrameLayout): Boolean {
        requireMainThread()
        val view = ensureWebView()
        val metrics = appContext.resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        if (w <= 0 || h <= 0) {
            windowStatus = WindowStatus.NotAttached
            Log.w(TAG, "attachOffscreen: bad displayMetrics ${w}x$h")
            return false
        }
        enforceSingleParent(view)
        val params = FrameLayout.LayoutParams(w, h)
        try {
            anchor.removeAllViews()
            anchor.addView(view, params)
        } catch (e: IllegalStateException) {
            windowStatus = WindowStatus.NotAttached
            Log.e(TAG, "attachOffscreen: addView failed", e)
            return false
        }
        // 窗口只有 1×1，必须显式 measure/layout 到全屏，否则子视图只有锚点大小。
        anchor.measure(
            View.MeasureSpec.makeMeasureSpec(1, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1, View.MeasureSpec.EXACTLY)
        )
        anchor.layout(0, 0, 1, 1)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, w, h)
        offscreenAnchor = anchor
        visibleParent = null
        lastLayoutWidth = view.width
        lastLayoutHeight = view.height
        windowStatus = WindowStatus.Offscreen
        Log.i(
            TAG,
            "attachOffscreen: window=1x1 webviewLayout=${view.width}x${view.height} " +
                "display=$w×$h anchorAttached=${anchor.isAttachedToWindow}"
        )
        return true
    }

    /** 从离屏宿主摘下（保留 WebView 与会话）。 */
    fun detachFromOffscreen() {
        requireMainThread()
        val anchor = offscreenAnchor
        val view = webView
        if (anchor != null && view != null && view.parent === anchor) {
            anchor.removeView(view)
        }
        offscreenAnchor = null
        if (windowStatus == WindowStatus.Offscreen) windowStatus = WindowStatus.NotAttached
        Log.i(TAG, "detachFromOffscreen: webview kept, status=$windowStatus")
    }

    /** 供驱动层写 cache 文件用（FileProvider 需要 Context 与 packageName）。 */
    fun appContextForFiles(): Context? = appContext

    fun status(): WindowStatus = windowStatus

    /** 设置可见父容器的来源。Activity `onResume` 时重新提供，`onPause` 后置空。 */
    fun setAttachTarget(provider: (() -> FrameLayout?)?) {
        attachTargetProvider = provider
    }

    /**
     * 设置离屏锚点挂接器（AI-166）。
     *
     * 回调收到一个 1×1 的不可见 `FrameLayout`，宿主负责把它 `addView` 到
     * **已 attached 到窗口**的视图树里（通常是 `decorView` / `android.R.id.content`）。
     * 宿主不接管（传 null，或回调里不加进视图树）时 [attachOffscreenViaProvider] 返回 false。
     */
    fun setOffscreenAnchorProvider(provider: ((FrameLayout) -> Unit)?) {
        offscreenAnchorProvider = provider
    }

    /**
     * 按当前注入的 provider 做离屏挂接（AI-166）。主线程调用。
     *
     * @return 是否已进入视图树并完成全屏 layout；false 表示没有可用锚点，
     *   调用方应回落可见挂接或报未就绪
     */
    fun attachOffscreenViaProvider(): Boolean {
        requireMainThread()
        val provider = offscreenAnchorProvider
        if (provider == null) {
            Log.w(TAG, "attachOffscreenViaProvider: no provider registered")
            return false
        }
        val anchor = FrameLayout(appContext)
        return try {
            provider(anchor)
            if (!anchor.isAttachedToWindow) {
                // 宿主没把锚点加进窗口树：挂上去也不出帧，如实拒绝。
                Log.w(TAG, "attachOffscreenViaProvider: anchor not attached to window")
                false
            } else {
                attachOffscreen(anchor)
            }
        } catch (e: Exception) {
            Log.e(TAG, "attachOffscreenViaProvider failed", e)
            false
        }
    }

    /**
     * 懒创建 WebView（必须在主线程）。
     * 配置项集中在这里，避免各处重复设置导致行为不一致。
     *
     * AI-025：安全与隐私开关不写死在此，统一由 [WebViewSecurityConfig] 声明，
     * 并在启动时用 [WebViewSecurityConfig.violations] 自检。
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun ensureWebView(): WebView {
        requireMainThread()
        webView?.let { return it }
        val view = WebView(appContext).apply {
            // 华为/EMUI WebView 会给 DeepSeek 登录输入框选 RTL，导致输入字符倒序（SMSProxy 实测）。
            layoutDirection = android.view.View.LAYOUT_DIRECTION_LTR
            // AI-025：安全/隐私开关由契约对象统一声明并自检，不再散落硬编码。
            applySecurityConfig(settings, securityConfig)
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = object : WebViewClient() {
                // SPA 跳转中途 onPageFinished 可能迟迟不来；提前记录 URL，
                // 供 currentUrl 的读者拿到尽量新的值（只读缓存，不碰 WebView.url）。
                override fun onPageStarted(v: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    if (url != null) currentUrl = url
                    // AI-025：URL 可能带 token / code，进日志前必须脱敏。
                    Log.d(TAG, "page started: ${UrlRedactor.redact(url)}")
                }

                override fun onPageFinished(v: WebView?, url: String?) {
                    if (url != null) currentUrl = url
                    Log.d(TAG, "page finished: ${UrlRedactor.redact(url)}")
                    onPageFinished?.invoke(url.orEmpty())
                }

                // AI-025：加载失败要可诊断，但不能把 WebView 内部描述（可能含 URL 参数）直出。
                override fun onReceivedError(
                    v: WebView?,
                    request: android.webkit.WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    Log.w(
                        TAG,
                        "page error: mainFrame=${request?.isForMainFrame} " +
                            "url=${UrlRedactor.redact(request?.url?.toString())} " +
                            "code=${error?.errorCode}"
                    )
                }
            }
            webChromeClient = object : WebChromeClient() {
                // P9：不实现回调会让 alert/confirm 被静默丢弃，页面卡在灰色遮罩。
                override fun onJsAlert(v: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean {
                    result?.confirm()
                    return true
                }

                override fun onJsConfirm(v: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean {
                    result?.confirm()
                    return true
                }

                override fun onJsPrompt(
                    v: WebView?, url: String?, message: String?, defaultValue: String?, result: android.webkit.JsPromptResult?
                ): Boolean {
                    result?.confirm(defaultValue.orEmpty())
                    return true
                }

                // onCreateWindow 返回 false：把 OAuth 弹窗留在同一个 WebView（C 系列约束）。
                override fun onCreateWindow(
                    view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?
                ): Boolean = false

                override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
                    request?.grant(request.resources)
                }

                override fun onShowFileChooser(
                    view: WebView?,
                    filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    // AI-073：本字段是「唯一所有者」语义 —— 系统再次回调时旧回调若还挂着，
                    // 必须先释放，否则旧页面请求会永久等待（比泄漏更难查：表现为某次上传后
                    // 整个页面再也不响应 input[type=file]）。
                    // 记录挂起的回调，destroy() 时才能释放，否则页面侧永久等待（AI-020）。
                    // AI-073：唯一所有者语义 —— 旧回调若还挂着，先以 null 释放，
                    // 否则旧页面请求会永久等待（表现为此后该 WebView 再也不响应 input[type=file]）。
                    val orphaned = this@WebViewHost.fileChooser.offer(filePathCallback) { orphan ->
                        try { orphan.onReceiveValue(null) } catch (_: Exception) {}
                    }
                    if (orphaned) {
                        Log.w(TAG, "onShowFileChooser: released orphaned callback before replacing")
                    }
                    // 已有待投递的图片就立即回填，不再弹系统选择器（AI-124 的 FILE_CHOOSER 路径）。
                    // AI-009D：如实记录系统回调的入参，作为“真实触发”的证据。
                    val acceptTypes = fileChooserParams?.acceptTypes ?: emptyArray()
                    // 实测 acceptTypes 可达 2000+ 字符，全量打印会被 logcat 截断并挤掉后续字段。
                    // 优先列图片类型：accept 开头通常是 .wxml/.wxss 之类，截断前 N 个看不到关键信息。
                    val accept = AcceptTypes.imageSummary(acceptTypes, 6)
                    val hasImage = AcceptTypes.imageTypes(acceptTypes).isNotEmpty()
                    val allowMultiple = fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE
                    Log.i(
                        TAG,
                        "onShowFileChooser: imageAccept=[$accept] hasImage=$hasImage " +
                            "acceptCount=${acceptTypes.size} imageCount=${AcceptTypes.imageTypes(acceptTypes).size} " +
                            "multiple=$allowMultiple mode=${fileChooserParams?.mode}"
                    )
                    val pending = pendingUploadUri
                    if (pending != null) {
                        pendingUploadUri = null
                        Log.i(TAG, "onShowFileChooser: delivering pending image")
                        val cb = this@WebViewHost.fileChooser.take()
                        try { cb?.onReceiveValue(arrayOf(pending)) } catch (_: Exception) {}
                        return true
                    }
                    val handler = fileChooserHandler
                    if (handler == null) {
                        Log.w(TAG, "onShowFileChooser but no handler registered")
                        // 无人接管也必须释放，否则下一次选择永久卡死。
                        this@WebViewHost.fileChooser.release { cb ->
                            try { cb.onReceiveValue(null) } catch (_: Exception) {}
                        }
                        return false
                    }
                    // 若宿主无法接管，必须回调 null 释放，否则下一次选择永久卡死。
                    return try {
                        val handled = handler.onFileChooser(fileChooserParams?.acceptTypes)
                        if (!handled) {
                            this@WebViewHost.fileChooser.release { cb ->
                                try { cb.onReceiveValue(null) } catch (_: Exception) {}
                            }
                        }
                        handled
                    } catch (e: Exception) {
                        Log.w(TAG, "file chooser handler threw", e)
                        this@WebViewHost.fileChooser.release { cb ->
                            try { cb.onReceiveValue(null) } catch (_: Exception) {}
                        }
                        false
                    }
                }
            }
        }
        // 允许 chrome://inspect 调试；Release 由 R8 保留规则与正式流程另行把关。
        WebView.setWebContentsDebuggingEnabled(true)
        webView = view
        applyThirdPartyCookies(view, securityConfig)
        windowStatus = WindowStatus.NotAttached
        Log.i(TAG, "WebView created")
        return view
    }

    /**
     * 挂接：先离屏 measure/layout 拿到真实视口，再挂进可见容器。
     *
     * AI-024 的三条硬规则：
     * 1. **单父容器**：`addView` 前先无条件把 WebView 从任何现存父容器摘下
     *    （[enforceSingleParent]），不只认自己记的 [visibleParent]。
     * 2. **容器体检**：容器必须 `isAttachedToWindow` / `isShown` / 尺寸非零，
     *    否则拒绝挂接并回落 `NotAttached`（见 [AttachGate]）。
     * 3. **页面体检**：挂接后用 `window.innerHeight` 复核是否真的出帧；
     *    为 0 说明这次挂接没生效，回落 `NotAttached` 交给调用方重试。
     *
     * 重复调用安全；失败不抛异常，只回报 [status]。
     */
    fun attach() {
        requireMainThread()
        val view = ensureWebView()
        val parent = attachTargetProvider?.invoke()
        if (parent == null) {
            windowStatus = WindowStatus.NotAttached
            Log.w(TAG, "attach: no visible parent available")
            return
        }
        val container = AttachGate.ContainerState(
            isAttachedToWindow = parent.isAttachedToWindow,
            isShown = parent.isShown,
            widthPx = parent.width,
            heightPx = parent.height,
            alreadyParent = view.parent === parent
        )
        AttachGate.rejectReason(container)?.let { why ->
            // 容器当前不可用：不挂接，保持可重试状态。MainActivity.onResume 会再调一次。
            windowStatus = WindowStatus.NotAttached
            Log.w(TAG, "attach: rejected container, $why")
            return
        }
        if (container.alreadyParent) {
            // 已是该容器的子视图：无需重挂，但状态可能因其它路径被改错，这里纠正。
            visibleParent = parent
            windowStatus = WindowStatus.Attached
            Log.i(TAG, "attach: already child of target container, status corrected")
            return
        }
        enforceSingleParent(view)
        val w = container.widthPx
        val h = container.heightPx
        // M3 / P16：先给非零视口，再挂窗口。
        if (w > 0 && h > 0) {
            measureAndLayout(view, w, h)
        }
        try {
            parent.addView(view, FrameLayout.LayoutParams(w, h))
            visibleParent = parent
            windowStatus = WindowStatus.Attached
            Log.i(TAG, "attach: attached to visible parent ${w}x$h")
        } catch (e: IllegalStateException) {
            windowStatus = WindowStatus.NotAttached
            Log.e(TAG, "attach failed", e)
            return
        }
        verifyViewportAfterAttach()
    }

    /**
     * 确保 WebView 没有挂在未知父容器上（AI-024）。
     *
     * 只靠 [visibleParent] 不够：异常路径（离屏测量中断、外部误 `addView`）会让
     * `view.parent` 指向别的容器，此时直接 `addView` 必抛 `IllegalStateException`。
     */
    private fun enforceSingleParent(view: WebView) {
        val current = view.parent as? android.view.ViewGroup
        if (current != null) {
            Log.w(TAG, "attach: webview still parented by ${current.javaClass.simpleName}, removing")
            current.removeView(view)
        }
        visibleParent = null
    }

    /**
     * 挂接后的页面出帧判定（AI-024）——**接线点，不做同步取值的假实现**。
     *
     * `addView` 成功只说明视图树挂上了；布局未完成或容器在屏幕外时页面仍是 0 视口。
     * 但 `evaluateJavascript` 只能异步回调，在 `attach()` 里同步取值不可能。
     * 因此这里不做阻塞等待，也不谎报校验结果：真正的判定由下游完成——
     * [PageDriver.probeLiveness] 用诊断脚本读 `innerHeight`，
     * `AttachGate.viewportRejectReason(innerHeight)` 的阈值（100）在那里生效，
     * 低于阈值一律 `NOT_READY`，由 `ensureLoaded()` 重试。
     */
    private fun verifyViewportAfterAttach() {
        Log.d(
            TAG,
            "attach: viewport check deferred to driver liveness probe " +
                "(threshold=${AttachGate.MIN_VIEWPORT_HEIGHT})"
        )
    }

    /**
     * 从可见容器摘下，但**保留** WebView（会话不丢）。
     *
     * AI-021：这是“移除”，不是“销毁”。WebView 实例、Cookie、DOM Storage、
     * 已加载页面都保留，[visibleParent] 置空、[WindowStatus] 变为 NotAttached；
     * 再调 [attach] 即可挂回同一个会话。
     */
    fun detachFromVisible() {
        requireMainThread()
        removeHostFromParent(dropWebView = false)
        visibleParent = null
        windowStatus = if (webView == null) WindowStatus.NotCreated else WindowStatus.NotAttached
        Log.i(TAG, "detachFromVisible: webview kept, status=$windowStatus")
    }

    /**
     * 彻底拆除：移除视图、释放挂起回调并置空引用。用于进程结束或源切换。
     *
     * 与 [detachFromVisible] 的区别（AI-021）：
     * - detach：仅从父容器移除，WebView / Cookie / 页面 / 会话全部保留，可 `attach()` 恢复；
     * - destroy：终态。移除视图、释放挂起回调、`WebView.destroy()` 并置空引用，
     *   会话与页面状态一并丢弃，下一次 [ensureWebView] 重建一个全新 WebView。
     * 调用方按“是否还要复用登录态与当前页面”二选一，不得混用。
     *
     * 注意：不清除注入配置（fileChooserHandler / onPageFinished / attachTargetProvider）。
     * 它们属于宿主而非 WebView；PageDriver.ensureLoaded() 会先 destroy() 再 attach() 重建视图，
     * 若在此清空，重建后的 WebView 将无人接管文件选择器（实测会导致上传静默失败）。
     */
    fun destroy() {
        requireMainThread()
        // 必须先释放挂起的文件选择回调，否则页面侧会永久等待，下一次选择直接卡死。
        fileChooser.release { cb ->
            try { cb.onReceiveValue(null) } catch (_: Exception) {}
        }
        removeHostFromParent(dropWebView = true)
        // 离屏容器里的残留子视图也要清掉，避免持有已销毁的 WebView。
        offscreenHost?.removeAllViews()
        offscreenHost = null
        offscreenAnchor?.removeAllViews()
        offscreenAnchor = null
        lastLayoutWidth = 0
        lastLayoutHeight = 0
        webView?.destroy()
        webView = null
        visibleParent = null
        currentUrl = ""
        // 置空导航记录，避免重建后 ensureLoaded 误判“已在该页”而跳过导航。
        navigatedTarget = ""
        pendingUploadUri = null
        windowStatus = WindowStatus.NotCreated
        Log.i(TAG, "destroy: webview released")
    }

    /**
     * 后台宿主（overlay）。
     *
     * TODO(AI-118)：本轮不实现。需要 `SYSTEM_ALERT_WINDOW` 权限与 1×1 透明悬浮窗；
     * 未授权时降级为可见挂接。见 `doc/multi_ai_update.md` §6 M2。
     */
    fun attachOverlay(): Boolean = false

    private fun removeHostFromParent(dropWebView: Boolean) {
        val view = webView
        // AI-024：不依赖 visibleParent 是否记对，按真实 parent 摘除。
        (view?.parent as? android.view.ViewGroup)?.let { actual ->
            if (actual !== offscreenHost) {
                actual.removeView(view)
            }
        }
        visibleParent = null
        // AI-021：移除后不再有可见父容器，状态必须如实回落（destroy 随后会置 NotCreated）。
        if (windowStatus == WindowStatus.Attached) windowStatus = WindowStatus.NotAttached
        // 离屏容器同样要清理，否则下次 addView 会因已有 parent 抛异常。
        val off = offscreenHost
        if (off != null && view != null && view.parent === off) {
            off.removeView(view)
        }
        val anchor = offscreenAnchor
        if (anchor != null && view != null && view.parent === anchor) {
            anchor.removeView(view)
        }
        offscreenAnchor = null
        if (windowStatus == WindowStatus.Offscreen) windowStatus = WindowStatus.NotAttached
        if (dropWebView) offscreenHost = null
    }

    private fun measureAndLayout(view: WebView, w: Int, h: Int) {
        // 独立离屏容器：避免把测量结果写回真实父容器。
        val host = offscreenHost ?: FrameLayout(appContext).also { offscreenHost = it }
        if (view.parent == null) host.addView(view)
        host.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY)
        )
        host.layout(0, 0, w, h)
        if (view.parent === host) host.removeView(view)
    }

    /**
     * WebView API 的线程断言（AI-022 / M1）。
     *
     * 用 `check` 而不是静默 post：静默 post 会把“错误线程调用”变成时序 bug，
     * 表现为偶发空 WebView 或 UI 不刷新。宁可崩在调用点。
     */
    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "WebViewHost must be used on the main thread (M1/AI-022), " +
                "current=${Thread.currentThread().name}"
        }
    }

    companion object {
        private const val TAG = "WebViewHost"

        @Volatile
        private var instance: WebViewHost? = null

        /** 进程级单例。必须在主线程首次调用。 */
        fun get(context: Context): WebViewHost = synchronized(this) {
            instance ?: WebViewHost(context.applicationContext).also { instance = it }
        }

        /** 仅供测试重置。 */
        internal fun resetForTest() {
            synchronized(this) { instance = null }
        }
    }
}
