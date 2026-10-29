package nz.org.aotearoa.guandanscore.recognition.webview

/**
 * URL 日志脱敏（AI-025）：**任何**打印页 URL 的地方都必须先过这里。
 *
 * 为什么要脱敏：SPA 登录后 URL 常带 `?token=…`、`?code=…`、`#access_token=…`，
 * 分享链接常带 `?key=` / `?sig=` / `?X-Amz-Signature=`。原样进 logcat 等于把凭据写进
 * 系统日志（任何持有 `READ_LOGS` 或可调试包的人都能读到），违反 CRITICAL §6/§8。
 *
 * 策略：**保留结构，抹掉值**。scheme / host / path 对排障有用（判断是否在首页、是否
 * 被重定向到登录页），一律保留；query 与 fragment 的**值**替换为 [MASK]，键名保留，
 * 这样日志仍能看出“带了 token”而不泄露 token 本身。
 *
 * 纯字符串逻辑，不依赖 Android，便于 JVM 单测。
 */
internal object UrlRedactor {

    /** 被脱敏部分显示的占位符。 */
    const val MASK = "<redacted>"

    /**
     * 脱敏一个 URL 或裸 URL 片段。
     *
     * - `https://a.com/chat?token=abc#x=1` → `https://a.com/chat?token=<redacted>#x=<redacted>`
     * - 无 query / fragment 时原样返回（不留多余字符）
     * - 空串与 `null` 输入返回空串，调用方不必判空
     */
    fun redact(url: String?): String {
        if (url.isNullOrEmpty()) return ""

        val fragmentAt = url.indexOf('#')
        val beforeFragment = if (fragmentAt >= 0) url.substring(0, fragmentAt) else url
        val fragment = if (fragmentAt >= 0) url.substring(fragmentAt + 1) else null

        val queryAt = beforeFragment.indexOf('?')
        val base = if (queryAt >= 0) beforeFragment.substring(0, queryAt) else beforeFragment
        val query = if (queryAt >= 0) beforeFragment.substring(queryAt + 1) else null

        val sb = StringBuilder(base)
        if (query != null) {
            sb.append('?').append(maskParams(query, separator = '&'))
        }
        if (fragment != null) {
            // fragment 既可能是 `a=b&c=d` 也可能是 SPA 路由 `/path`。
            // 只有出现 `=` 才当参数表处理，否则视为路由原样保留（不含凭据）。
            sb.append('#')
            sb.append(if (fragment.contains('=')) maskParams(fragment, separator = '&') else fragment)
        }
        return sb.toString()
    }

    /**
     * 把 `k=v&k2=v2` 中每个 `v` 换成 [MASK]。
     *
     * 无 `=` 的裸片段（如 `?12345` 缓存击穿参数）也整体抹掉——它可能是一次性票据。
     */
    private fun maskParams(params: String, separator: Char): String {
        if (params.isEmpty()) return ""
        return params.split(separator).joinToString(separator.toString()) { pair ->
            val eq = pair.indexOf('=')
            if (eq <= 0) {
                // 无键名的裸值：整段是值，抹掉。
                if (pair.isEmpty()) pair else MASK
            } else {
                pair.substring(0, eq + 1) + MASK
            }
        }
    }
}
