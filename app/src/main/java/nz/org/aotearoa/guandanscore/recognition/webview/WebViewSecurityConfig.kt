package nz.org.aotearoa.guandanscore.recognition.webview

/**
 * WebView 安全与隐私配置契约（AI-025）。
 *
 * 为什么把开关抽出来：这些设置原先散落在 `ensureWebView()` 里，只有"写对了"这一种
 * 保证，没有任何东西能阻止后来者顺手打开 `allowFileAccess` 或关掉混合内容拦截。
 * 抽成数据类后，默认值即契约，改动会同时触发单测失败，评审时也能一眼看到差异。
 *
 * 取舍原则：
 * - **要联网识牌** → `javaScriptEnabled` / `domStorageEnabled` 必须开。
 * - **不需要读本地文件** → `allowFileAccess` / `allowContentAccess` 关闭，缩小攻击面。
 * - **页面全是 https** → 混合内容 `NEVER_ALLOW`，防止 https 页面被降级加载 http 脚本。
 * - **绝不落盘表单/密码** → `saveFormData` 关闭；密码本身由 WebView 自带密码库管理，
 *   我们既不开启 `savePassword`，也不读取任何凭据。
 * - **不给页面定位权限** → `geolocationEnabled` 关闭。
 * - **保留用户登录态** → Cookie 必须接受（含第三方，Google 登录跳转依赖它）。
 */
internal data class WebViewSecurityConfig(
    /** 识牌脚本依赖 JS，必须为 true。 */
    val javaScriptEnabled: Boolean = true,

    /** 站点把会话信息放在 DOM Storage，关掉会反复要求登录。 */
    val domStorageEnabled: Boolean = true,

    /** 旧式 localStorage 兜底，与 DOM Storage 同开。 */
    val databaseEnabled: Boolean = true,

    /** 不读 `file://`，识牌流程用不到。 */
    val allowFileAccess: Boolean = false,

    /** 不读 `content://`，同上；上传走 FileProvider + onShowFileChooser，不靠页面直读。 */
    val allowContentAccess: Boolean = false,

    /** 禁止 https 页面加载 http 资源。 */
    val mixedContentNeverAllow: Boolean = true,

    /** 不把表单数据（可能含账号）写进 WebView 私有库。 */
    val saveFormData: Boolean = false,

    /** 页面不许拿定位。 */
    val geolocationEnabled: Boolean = false,

    /** 接受 Cookie。 */
    val acceptCookie: Boolean = true,

    /** 接受第三方 Cookie —— Google / OAuth 登录跳转依赖。 */
    val acceptThirdPartyCookies: Boolean = true
) {
    /**
     * 自检：返回违反契约的项，空列表表示合规。
     *
     * 供单测与 `ensureWebView()` 启动断言共用，避免"配置对象改了但没生效"。
     */
    fun violations(): List<String> = buildList {
        if (!javaScriptEnabled) add("javaScriptEnabled=false: 识牌脚本无法执行")
        if (!domStorageEnabled) add("domStorageEnabled=false: 会话会丢失")
        if (allowFileAccess) add("allowFileAccess=true: 无需求，扩大攻击面")
        if (allowContentAccess) add("allowContentAccess=true: 无需求，扩大攻击面")
        if (!mixedContentNeverAllow) add("mixedContentNeverAllow=false: 允许 https 降级")
        if (saveFormData) add("saveFormData=true: 账号可能落盘")
        if (geolocationEnabled) add("geolocationEnabled=true: 无需求")
        if (!acceptCookie) add("acceptCookie=false: 登录态无法保持")
    }
}
