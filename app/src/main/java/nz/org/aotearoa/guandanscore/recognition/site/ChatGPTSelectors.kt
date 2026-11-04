package nz.org.aotearoa.guandanscore.recognition.site

/**
 * ChatGPT 网页版 DOM 选择器的单一事实来源。
 *
 * ## 验证状态：真机回填（2026-10-30，AI-158）
 * 已在目标设备 `chatgpt.com` 实测：输入框 1、发送按钮 1、助手消息单元 1（新结构
 * `div[data-content-search-unit-key$=':assistant']`），登录态下 password/login 均为 0。
 * 旧结构（`data-message-author-role`）保留作为兼容路径。
 *
 * ## 已知结构（未验证）
 * - 输入框是 **ProseMirror `contenteditable` div**（`#prompt-textarea`），不是 textarea。
 * - 发送按钮是 `<button data-testid="send-button">`，生成中会换成停止按钮。
 * - 回答节点是 `div[data-message-author-role="assistant"]`。
 * - 附件预览用 `img[src^="blob:"]` 表示。
 */
object ChatGPTSelectors {

    /** 输入框。ProseMirror 编辑器；`#prompt-textarea` 是最稳的锚点。 */
    const val INPUT =
        "#prompt-textarea, div.ProseMirror[contenteditable='true'], div[contenteditable='true'][data-placeholder]"

    /** 发送按钮。生成中会变成 `stop-button`，故两者都列出。 */
    const val SEND =
        "button[data-testid='send-button'], button[aria-label*='Send' i], button[aria-label*='发送']"

    /** 助手回答节点。只取 assistant 角色，绝不混入用户消息。 */
    const val RESPONSE =
        "div[data-message-author-role='assistant'], " +
            "div[data-content-search-unit-key$=':assistant'], " +
            "article[data-testid^='conversation-turn'] div.markdown"

    /** 登录入口。必须与密码框同时存在才算未登录（M6）。 */
    const val LOGIN =
        "a[href*='/auth/login'], a[href*='auth0'], button[data-testid='login-button']"

    /** 密码输入框：只有登录页才有。 */
    const val PASSWORD = "input[type='password']"

    /** 文件输入框。是否可用取决于站点是否渲染 `<input type=file>`。 */
    const val FILE_INPUT = "input[type='file']"

    /**
     * 生成中/停止控件。ChatGPT 有真实的停止按钮，可作为辅助信号，
     * 但结束判定仍须以回答文本稳定为准（M4）。
     */
    const val GENERATING =
        "button[data-testid='stop-button'], button[aria-label*='Stop' i], button[aria-label*='停止']"

    /** 附件预览。上传成功判据。 */
    const val ATTACHMENT =
        "img[src^='blob:'], div[class*='attachment'], div[data-testid*='attachment']"

    fun input(): String = INPUT
    fun send(): String = SEND
    fun response(): String = RESPONSE
    fun login(): String = LOGIN
    fun password(): String = PASSWORD
    fun fileInput(): String = FILE_INPUT
    fun generating(): String = GENERATING
    fun attachment(): String = ATTACHMENT
}
