package nz.org.aotearoa.guandanscore.recognition

/**
 * Gemini 网页版 DOM 选择器的单一事实来源。
 *
 * ⚠️ 重要：以下选择器是**待真机验证的候选值**，不是已确认的常量。
 * Gemini 页面可能使用 Angular 组件、动态类名和 Shadow DOM，实际命中结果必须以
 * 目标 Android WebView 上的 `GeminiScripts.diagnose()` 输出为准。
 * 修改时同步更新 `GeminiScripts` 中的诊断脚本与对应的单元测试。
 */
object GeminiSelectors {
    const val HOME_URL = "https://gemini.google.com/app"

    /** 输入区：优先 contenteditable，其次 textarea。用逗号分隔表示候选并集。 */
    val INPUT_CANDIDATES = listOf(
        "rich-textarea [contenteditable=\"true\"]",
        "div[contenteditable=\"true\"][role=\"textbox\"]",
        "textarea[aria-label*=\"prompt\" i]",
        "textarea"
    )

    /** 发送按钮候选。实测 aria-label 为 `Send message`，且可能渲染为 div。 */
    val SEND_CANDIDATES = listOf(
        "button[aria-label*=\"Send message\" i]",
        "button[aria-label*=\"Send\" i]",
        "button[aria-label*=\"发送\"]",
        "[role=\"button\"][aria-label*=\"Send\" i]",
        "button.send-button",
        "button[mattooltip*=\"Send\" i]"
    )

    /** 助手回答正文候选。必须避免匹配用户自己的消息。 */
    val RESPONSE_CANDIDATES = listOf(
        "message-content .markdown",
        "model-response message-content",
        "div.response-content",
        ".model-response-text"
    )

    /** 密码输入框：只有未登录/登录页才有。已登录页 0 命中。 */
    const val PASSWORD = "input[type='password']"

    /** 登录/未登录指示。 */
    val LOGIN_CANDIDATES = listOf(
        "a[href*=\"accounts.google.com/ServiceLogin\"]",
        "form[action*=\"signin\"]",
        "[data-test-id=\"sign-in-button\"]"
    )

    /** 文件上传入口。 */
    val FILE_INPUT_CANDIDATES = listOf(
        "input[type=\"file\"]",
        "button[aria-label*=\"Upload\" i]",
        "button[aria-label*=\"上传\"]"
    )

    /** 生成中/停止控件，仅作辅助信号。 */
    val GENERATING_CANDIDATES = listOf(
        "button[aria-label*=\"Stop\" i]",
        "button[aria-label*=\"停止\"]",
        "mat-progress-bar"
    )

    fun input(): String = INPUT_CANDIDATES.joinToString(",")
    fun send(): String = SEND_CANDIDATES.joinToString(",")
    fun response(): String = RESPONSE_CANDIDATES.joinToString(",")
    fun password(): String = PASSWORD
    fun login(): String = LOGIN_CANDIDATES.joinToString(",")
    fun fileInput(): String = FILE_INPUT_CANDIDATES.joinToString(",")
    fun generating(): String = GENERATING_CANDIDATES.joinToString(",")
}
