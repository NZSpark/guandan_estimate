package nz.org.aotearoa.guandanscore.recognition.webview

import nz.org.aotearoa.guandanscore.recognition.site.DiagnoseResult

/**
 * AI-031：页面状态分类。
 *
 * 只看 readyState == complete 会把五种完全不同的情况混成一个「没就绪」：
 * 冷启动尚未 commit、SPA 正在渲染、被重定向到登录页、选择器全部失效、
 * 以及页面卡死。它们的处置手段互斥——有的该等，有的该重载，有的必须让人介入。
 * 混在一起会导致「无限重试」或「把登录失效当成未就绪」两类误判。
 */
enum class PageState {
    /** 文档已 commit、视口正常、输入框在，可以继续。 */
    READY,

    /** 文档尚未 commit（origin 为空 / about:blank / readyState 仍是 loading）。重试即可。 */
    LOADING,

    /** 文档已 commit 但输入框缺失。SPA 可能仍在渲染；持续缺失说明选择器或页面结构变了。 */
    COMPOSER_MISSING,

    /** 视口高度异常（WebView 未挂接、容器塌陷、切后台）。属于宿主问题，不是页面问题。 */
    VIEWPORT_UNHEALTHY,

    /** 出现密码框或登录 UI：需要用户重新登录，重试无用。 */
    LOGIN_REQUIRED,
}

/** 分类后建议的处置动作。调用方据此决定「等 / 重载 / 报错 / 求人」。 */
enum class PageAction {
    /** 可以继续执行。 */
    PROCEED,

    /** 继续轮询，超时再说。 */
    RETRY,

    /** 页面结构失效，可以尝试重载一次。 */
    RELOAD,

    /** 停止自动化，提示用户处理（登录 / 挂接）。 */
    NEEDS_USER,
}

/**
 * 纯函数状态判定：不读 Android、不碰 JS，便于单测穷举。
 *
 * 判定顺序刻意固定：先排除「连文档都没有」，再排除「需要人介入」，
 * 最后才看宿主问题与选择器问题。顺序颠倒会把登录页误报成选择器缺失。
 */
object PageStateClassifier {

    /**
     * @param urlIsLoginPage URL 本身是否就是登录页。DOM 判据会漏时以此兜底
     *   （实测未登录被重定向到 `/sign_in`，页面上没有任何密码框或登录锚点）。
     */
    fun classify(d: DiagnoseResult, urlIsLoginPage: Boolean = false): PageState {
        val readyState = d.readyState.lowercase()
        val href = d.href

        // 1) 文档尚未 commit：origin 为空、about:blank、或 readyState 仍是 loading。
        val committed = href.isNotBlank() && !href.startsWith("about:") &&
            (readyState == "interactive" || readyState == "complete")
        if (!committed) return PageState.LOADING

        // 2) 登录页优先：已登录页的账户菜单不得被判成未登录（M6），
        // 但真出现密码框 + 登录 UI 时必须立刻停手，不能继续当「没就绪」重试。
        // URL 落在登录路径时同样立刻停手：否则会被归成 COMPOSER_MISSING 触发
        // RELOAD，而重载只会再次被踢回同一登录页，形成空转。
        if (urlIsLoginPage) return PageState.LOGIN_REQUIRED
        if (d.passwordCount > 0) return PageState.LOGIN_REQUIRED
        if (d.loginIndicatorCount > 0 && d.inputCount == 0) return PageState.LOGIN_REQUIRED

        // 3) 宿主问题：视口高度异常时输入框即使存在也点不到。
        if (AttachGate.viewportRejectReason(d.innerHeight) != null) {
            return PageState.VIEWPORT_UNHEALTHY
        }

        // 4) 选择器问题。
        if (d.inputCount <= 0) return PageState.COMPOSER_MISSING

        return PageState.READY
    }

    fun actionFor(state: PageState): PageAction = when (state) {
        PageState.READY -> PageAction.PROCEED
        PageState.LOADING -> PageAction.RETRY
        PageState.COMPOSER_MISSING -> PageAction.RELOAD
        PageState.VIEWPORT_UNHEALTHY -> PageAction.NEEDS_USER
        PageState.LOGIN_REQUIRED -> PageAction.NEEDS_USER
    }

    /** 人类可读原因，用于日志与 AiSourceError.NotReady。 */
    fun reason(state: PageState, d: DiagnoseResult?): String = when (state) {
        PageState.READY -> ""
        PageState.LOADING -> "页面尚未加载完成（readyState=" + (d?.readyState ?: "?") + "）"
        PageState.COMPOSER_MISSING -> "输入框缺失（inputCount=" + (d?.inputCount ?: 0) + "）"
        PageState.VIEWPORT_UNHEALTHY -> "视口高度异常（innerHeight=" + (d?.innerHeight ?: 0) + "）"
        PageState.LOGIN_REQUIRED -> "需要重新登录"
    }
}
