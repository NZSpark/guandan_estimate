package nz.org.aotearoa.guandanscore.recognition.webview

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

/** WebView 的窗口挂接状态。必须显式上报，不能只报“已创建”。 */
sealed interface WindowStatus {
    /** 已挂进可见容器。 */
    data object Attached : WindowStatus

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

    @Volatile
    private var webView: WebView? = null

    /** 离屏测量容器：先给它真实尺寸，WebView 才有非零视口。 */
    private var offscreenHost: FrameLayout? = null

    /** 当前挂接的可见父容器。 */
    @Volatile
    private var visibleParent: FrameLayout? = null

    @Volatile
    private var windowStatus: WindowStatus = WindowStatus.NotCreated

    /** 由宿主注入：如何把 WebView 挂到可见容器里。 */
    @Volatile
    private var attachTargetProvider: (() -> FrameLayout?)? = null

    /** 由宿主注入：文件选择器接管。 */
    @Volatile
    var fileChooserHandler: FileChooserHandler? = null

    /** 页面最近一次挂起的文件选择回调；[destroy] 时用它释放页面等待。 */
    private var filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>? = null

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

    fun currentWebView(): WebView? = webView

    /**
     * 在 WebView 上按视口坐标派发一次原生触摸（DOWN + UP）。
     *
     * 为什么必须原生：部分站点（实测 DeepSeek）的文件选择器要求 user activation，
     * `evaluateJavascript` 里的 `.click()` 不满足，`onShowFileChooser` 不会触发。
     *
     * @param x CSS 像素（视口坐标）
     * @param y CSS 像素（视口坐标）
     * @param viewportHeight 页面 `window.innerHeight`，用于坐标换算
     * @return 是否成功派发
     */
    fun tapViewportPoint(x: Int, y: Int, viewportHeight: Int): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            val latch = java.util.concurrent.CountDownLatch(1)
            var result = false
            main.post {
                result = tapViewportPoint(x, y, viewportHeight)
                latch.countDown()
            }
            latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
            return result
        }
        val view = webView ?: return false
        if (viewportHeight <= 0) return false
        val density = appContext.resources.displayMetrics.density
        val viewH = view.height
        val viewW = view.width
        if (viewH <= 0 || viewW <= 0) return false
        // 视口高度（CSS px）→ 视图高度（物理 px）的换算；宽度同比例。
        val scale = (viewH.toFloat() / viewportHeight.toFloat())
        val px = (x * scale).toInt().coerceIn(0, viewW - 1)
        val py = (y * scale).toInt().coerceIn(0, viewH - 1)
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
        Log.i(TAG, "tapViewportPoint ($x,$y) css -> ($px,$py) px, scale=$scale density=$density")
        return ok
    }

    /** 供驱动层写 cache 文件用（FileProvider 需要 Context 与 packageName）。 */
    fun appContextForFiles(): Context? = appContext

    fun status(): WindowStatus = windowStatus

    /** 设置可见父容器的来源。Activity `onResume` 时重新提供，`onPause` 后置空。 */
    fun setAttachTarget(provider: (() -> FrameLayout?)?) {
        attachTargetProvider = provider
    }

    /**
     * 懒创建 WebView（必须在主线程）。
     * 配置项集中在这里，避免各处重复设置导致行为不一致。
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun ensureWebView(): WebView {
        requireMainThread()
        webView?.let { return it }
        val view = WebView(appContext).apply {
            // 华为/EMUI WebView 会给 DeepSeek 登录输入框选 RTL，导致输入字符倒序（SMSProxy 实测）。
            layoutDirection = android.view.View.LAYOUT_DIRECTION_LTR
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.mediaPlaybackRequiresUserGesture = false
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                // SPA 跳转中途 onPageFinished 可能迟迟不来；提前记录 URL，
                // 供 currentUrl 的读者拿到尽量新的值（只读缓存，不碰 WebView.url）。
                override fun onPageStarted(v: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    if (url != null) currentUrl = url
                }

                override fun onPageFinished(v: WebView?, url: String?) {
                    if (url != null) currentUrl = url
                    onPageFinished?.invoke(url.orEmpty())
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
                    // 记录挂起的回调，destroy() 时才能释放，否则页面侧永久等待（AI-020）。
                    this@WebViewHost.filePathCallback = filePathCallback
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
                        filePathCallback?.onReceiveValue(arrayOf(pending))
                        this@WebViewHost.filePathCallback = null
                        return true
                    }
                    val handler = fileChooserHandler
                    if (handler == null) {
                        Log.w(TAG, "onShowFileChooser but no handler registered")
                        // 无人接管也必须释放，否则下一次选择永久卡死。
                        filePathCallback?.onReceiveValue(null)
                        this@WebViewHost.filePathCallback = null
                        return false
                    }
                    // 若宿主无法接管，必须回调 null 释放，否则下一次选择永久卡死。
                    return try {
                        val handled = handler.onFileChooser(fileChooserParams?.acceptTypes)
                        if (!handled) filePathCallback?.onReceiveValue(null)
                        this@WebViewHost.filePathCallback = null
                        handled
                    } catch (e: Exception) {
                        Log.w(TAG, "file chooser handler threw", e)
                        filePathCallback?.onReceiveValue(null)
                        this@WebViewHost.filePathCallback = null
                        false
                    }
                }
            }
        }
        // 允许 chrome://inspect 调试；Release 由 R8 保留规则与正式流程另行把关。
        WebView.setWebContentsDebuggingEnabled(true)
        webView = view
        windowStatus = WindowStatus.NotAttached
        Log.i(TAG, "WebView created")
        return view
    }

    /**
     * 挂接：先离屏 measure/layout 拿到真实视口，再尝试挂进可见容器。
     * 重复调用安全。
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
        removeHostFromParent(dropWebView = false)
        val w = parent.width.takeIf { it > 0 } ?: parent.resources.displayMetrics.widthPixels
        val h = parent.height.takeIf { it > 0 } ?: parent.resources.displayMetrics.heightPixels
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
        }
    }

    /** 从可见容器摘下，但**保留** WebView（会话不丢）。 */
    fun detachFromVisible() {
        requireMainThread()
        removeHostFromParent(dropWebView = false)
        windowStatus = if (webView == null) WindowStatus.NotCreated else WindowStatus.NotAttached
    }

    /**
     * 彻底拆除：移除视图、释放挂起回调并置空引用。用于进程结束或源切换。
     *
     * 与 [detachFromVisible] 的区别（AI-021）：detach 只把 WebView 从可见容器摘下并保留会话；
     * destroy 是终态，WebView 不可再用，下一次 [ensureWebView] 会重建。
     *
     * 注意：不清除注入配置（fileChooserHandler / onPageFinished / attachTargetProvider）。
     * 它们属于宿主而非 WebView；PageDriver.ensureLoaded() 会先 destroy() 再 attach() 重建视图，
     * 若在此清空，重建后的 WebView 将无人接管文件选择器（实测会导致上传静默失败）。
     */
    fun destroy() {
        requireMainThread()
        // 必须先释放挂起的文件选择回调，否则页面侧会永久等待，下一次选择直接卡死。
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        removeHostFromParent(dropWebView = true)
        // 离屏容器里的残留子视图也要清掉，避免持有已销毁的 WebView。
        offscreenHost?.removeAllViews()
        offscreenHost = null
        webView?.destroy()
        webView = null
        visibleParent = null
        currentUrl = ""
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
        val parent = visibleParent
        if (parent != null && view != null && view.parent === parent) {
            parent.removeView(view)
        }
        visibleParent = null
        // 离屏容器同样要清理，否则下次 addView 会因已有 parent 抛异常。
        val off = offscreenHost
        if (off != null && view != null && view.parent === off) {
            off.removeView(view)
        }
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

    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "WebViewHost must be used on the main thread (M1)"
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
