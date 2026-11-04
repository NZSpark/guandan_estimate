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
 * ## AI-008D 深度探测 2026-10-29（`DeepSeekScripts.deepProbe()` 真机）
 * 设备同上，页面 https://chat.deepseek.com/ （已登录，readyState=complete）。
 * - 视口 `innerWidth×innerHeight = 392×601`，`dpr=3`；本机 DNS 不通时页面仍能完整渲染并命中选择器。
 * - **INPUT 唯一且最内层**：`inputCount=1 inputUnique=true inputTag=textarea inputInnerEditableCount=0
 *   inputIsInnermost=true`，无 id，class 为哈希（`_27c9245 ds-scroll-area ...`），
 *   确有 `ds-scroll-area` 类但元素仍是 `textarea`，故 `textarea[placeholder*='Message DeepSeek']` 可靠。
 * - **SEND 可点击判据**：`sendCount=1 sendTag=div sendDisabled=false sendBox=34×34 @(313,302) sendClickable=true`。
 *   即 `disabled` 与 `aria-disabled` 均不生效（`sendAriaLabel` 为空），必须用尺寸 + `ds-button--disabled` 类判定。
 * - **RESPONSE**：新会话首屏 `responseCount=0`（无历史回答），选择器待有回答后再核；容器标识仍取
 *   `.ds-assistant-message-main-content`。
 * - **无 Shadow DOM**：`shadowHostCount=0 hasShadowDom=false` → `querySelectorAll` 可覆盖全文档。
 *
 * ## AI-009D 文件选择器探测 2026-10-29（`DeepSeekScripts.fileInputProbe()` + `WebViewHost` 日志，真机）
 * - **`onShowFileChooser()` 真实触发**：原生触摸回形针（css (286,319) → px (857,955)）后立即回调，
 *   且回填 `cache/probe/probe_upload.png` 成功。JS `.click()` 路径不触发，与既有结论一致。
 * - **`input[type=file]` 只有 1 个**，`accept` 长达 **5868 字符**（约 900 个扩展名，含 png/jpg/webp 等），
 *   `multiple=true`，`capture=""`，`hidden=true`（尺寸为 0，只能靠原生触摸回形针，不能靠点它），
 *   `disabled=false`。
 * - **`FileChooserParams.mode = 1`（MODE_OPEN）**，即单选；站点虽给了 `multiple`，但宿主回调按单选处理。
 * - 注意：`accept` 超长会撑爆 logcat 单行并挤掉后续字段，探测脚本与宿主日志都只输出摘要。
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
     *
     * AI-011D 2026-10-29 真机复核（`DeepSeekScripts.stopControlProbe()`）：静止页
     * `presetCount=0`、`labelHits=[]`、`visibleButtonCount=8` → `note=no-stop-control`。
     * 已按行为特征（aria-label/title/文本含 stop/停止/中止/取消生成）扫描全部可见按钮，仍无候选。
     * 结论：DeepSeek 的 `generating` 恒为 false，结束判定只能靠回答文本增长 + 连续稳定。
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
