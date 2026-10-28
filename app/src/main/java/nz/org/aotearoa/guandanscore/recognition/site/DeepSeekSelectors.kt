package nz.org.aotearoa.guandanscore.recognition.site

/**
 * DeepSeek 网页版 DOM 选择器的单一事实来源。
 *
 * ## 实测记录 2026-10-28
 * 设备：HUAWEI LIO-N29 / Android 10 / com.huawei.webview 114.0.5.302
 * 页面：https://chat.deepseek.com/ （已登录）
 * 交叉验证：SMSProxy `DeepSeekSelectors`（2026-10-25 CDP 实测）
 *
 * | 选择器组 | 命中 | 结论 |
 * |---|---|---|
 * | INPUT | 1（textarea, placeholder='Message DeepSeek'） | ✅ 无 id、class 为哈希，只能靠 placeholder |
 * | SEND | 1 | ✅ `div.ds-button--circle.ds-button--primary` |
 * | RESPONSE | 待回答页验证 | ✅ `.ds-assistant-message-main-content` |
 * | FILE_INPUT | 1 | ✅ 但 JS `.click()` 不触发文件选择器 |
 * | LOGIN | 0（已登录） | ✅ |
 * | PASSWORD | 0（已登录） | ✅ |
 * | GENERATING | 0 | ❌ 无 stop 控件；**不得作为结束判定前提** |
 *
 * ## 复测 2026-10-28 23:00（AiProbeActivity，进程重启后）
 * `inputCount=1 inputTag=textarea inputEditable=true sendCount=1 fileInputCount=1
 * loginIndicatorCount=0 passwordCount=0 attachmentCount=0 editableCount=0 innerHeight=372`
 * → 登录态跨进程重启保持（Cookie + DOM Storage 生效，AI-007D 通过）。
 *
 * ## 合成 paste 实测 2026-10-28 23:07
 * `pasteImage` 返回 `{dispatched:true, accepted:false}`，但紧接的 `verifyAttachment`
 * 返回 `{attachmentCount:1, attached:true}` —— **预览真实出现**。
 * 结论：`dispatchEvent` 的返回值被 DeepSeek `preventDefault` 掉，**不能作为成败判据**；
 * 唯一权威判据是 [ATTACHMENT] 命中数。PASTE 路径在本机可行，保持为首选。
 *
 * ## 关键行为（实测）
 * 1. **发送按钮不是 `<button>`**，是 `div[role=button].ds-button`；启用/禁用靠
 *    `ds-button--disabled` 类，不看 `aria-disabled`/`disabled`。
 * 2. **主提交路径是 Enter keydown**，不是点发送按钮：点按钮只跳到新会话 URL，
 *    不发请求（SMSProxy 实测 `clickSend` 后无生成）。
 * 3. **读回答必须用 `textContent`**：虚拟列表离屏行 `innerText` 为空。
 * 4. **上传必须靠原生触摸**：JS 合成 `input[type=file].click()` 报
 *    `File chooser dialog can only be shown with a user activation.`，
 *    不触发 `onShowFileChooser`。驱动层需用原生坐标点击回形针图标。
 * 5. 合成 `ClipboardEvent` paste 会被接受（预览出现），但 `dispatchEvent`
 *    返回 false，**不能用返回值判成败**，必须以附件预览节点为准。
 */
object DeepSeekSelectors {

    /** 输入框。无 id、class 哈希，只能靠 placeholder / name。实测 1 命中。 */
    const val INPUT =
        "textarea[placeholder*='Message DeepSeek'], textarea[name='search'], div[contenteditable='true'][data-placeholder]"

    /**
     * 发送按钮。实测 1 命中。
     * 注意：仅用于诊断与兜底；主提交路径是 Enter。
     */
    const val SEND =
        "div.ds-button--circle.ds-button--primary, button[type='submit'], button[aria-label*='Send'], button[aria-label*='发送']"

    /**
     * 助手回答节点。实测：待回答页验证。
     * 只取助手消息，**不得**混入用户消息。
     */
    const val RESPONSE =
        "div.ds-assistant-message-main-content, div.ds-markdown"

    /** 登录页入口。必须与密码框同时存在才算未登录（M6）。 */
    const val LOGIN =
        "a[href*='/sign_in'], a[href*='/login'], form[action*='sign_in'], form[action*='login']"

    /** 密码输入框：只有登录页才有。实测已登录页 0 命中。 */
    const val PASSWORD = "input[type='password']"

    /** 文件输入框。实测 1 命中，但 JS click 无法触发文件选择器。 */
    const val FILE_INPUT = "input[type='file']"

    /**
     * 生成中/停止控件。实测 0 命中。
     * **不得**把“看到 stop 消失”作为结束判定前提（M4）。
     */
    const val GENERATING =
        "div[class*='stop'], button[aria-label*='Stop'], button[aria-label*='停止']"

    /**
     * 附件预览。上传成功判据。
     * DeepSeek 输入区用缩略图 + 删除按钮表示已附加，实测合成 paste 后会命中。
     */
    const val ATTACHMENT =
        "div[class*='file-preview'], div[class*='attachment'], img[src^='blob:'], img[src^='data:image']"

    /** 输入区的附件图标（回形针）。供原生触摸定位用，不用于 JS 点击。 */
    const val UPLOAD_ICON = "div.ds-button svg, div.ds-button"

    /** 上下文超限提示。 */
    const val CONTEXT_FULL = "div[class*='limit'], div[class*='context'], div[class*='error']"

    fun input(): String = INPUT
    fun send(): String = SEND
    fun response(): String = RESPONSE
    fun login(): String = LOGIN
    fun password(): String = PASSWORD
    fun fileInput(): String = FILE_INPUT
    fun generating(): String = GENERATING
    fun attachment(): String = ATTACHMENT
}
