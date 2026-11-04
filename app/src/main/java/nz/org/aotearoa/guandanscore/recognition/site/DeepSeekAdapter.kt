package nz.org.aotearoa.guandanscore.recognition.site

import nz.org.aotearoa.guandanscore.recognition.source.AiSourceId
import nz.org.aotearoa.guandanscore.recognition.source.AiSourcePresets
import nz.org.aotearoa.guandanscore.recognition.webview.PasteCapableAdapter
import org.json.JSONObject

/**
 * DeepSeek 网页版适配器（首发源）。
 *
 * 已按 2026-10-28 真机实测回填（HUAWEI LIO-N29 / Android 10 / WebView 114.0.5.302）。
 *
 * ## 上传策略（实测结论）
 * - JS 合成 `input[type=file].click()` **不会**触发 `onShowFileChooser`：
 *   报 `File chooser dialog can only be shown with a user activation.`
 * - 真实触摸回形针图标 **会**触发 `onShowFileChooser`。
 * - 合成 `ClipboardEvent` paste 会被页面接受（预览出现），但 `dispatchEvent` 返回 false。
 *
 * 因此 [preferredUpload] 选 [UploadStrategy.PASTE]：它无需 user activation，
 * 且已实测能让预览出现；成败仍以附件预览节点为准（M11）。
 * FILE_CHOOSER 作为回退，需驱动层用 [uploadIconScript] 定位图标后原生触摸。
 *
 * ## 结束判定
 * 页面无 stop 控件（GENERATING 命中 0），`generating` 恒为 false，
 * 由 `GenerationWatcher` 的文本稳定性兜底（M4）。
 */
open class DeepSeekAdapter : SiteAdapter, PasteCapableAdapter {

    override val homeUrl: String =
        AiSourcePresets.homeUrlFor(AiSourceId.DEEPSEEK_WEBVIEW).orEmpty()

    override val siteKey: String = AiSourceId.DEEPSEEK_WEBVIEW.key

    /** 实测：paste 被接受且无需 user activation；file chooser 需要原生触摸。 */
    override val preferredUpload: UploadStrategy = UploadStrategy.PASTE

    /** AI-124：两条路径均已真机验证（paste 预览出现 / 原生触摸触发 onShowFileChooser）。 */
    override val supportedUploads: Set<UploadStrategy> =
        setOf(UploadStrategy.PASTE, UploadStrategy.FILE_CHOOSER)

    override fun fillAndSubmitScript(
        prompt: String,
        fileName: String,
        mimeType: String,
        base64: String
    ): String = DeepSeekScripts.fillAndSend(prompt)

    /** AI-173：点击前轮询发送按钮可用性。 */
    override fun sendStateScript(): String = DeepSeekScripts.sendState()

    override fun parseSendState(json: JSONObject): SendState = SendState(
        sendCount = json.optInt("sendCount", 0),
        sendDisabled = json.optBoolean("sendDisabled", false),
        sendClickable = json.optBoolean("sendClickable", false),
        valueEmpty = json.optBoolean("valueEmpty", false)
    )

    /** AI-174：DeepSeek 会话页形态为 `/a/chat/s/<id>`。 */
    override fun isSessionUrl(url: String): Boolean = url.contains("/a/chat/s/")


    /** 粘贴路径脚本。仅当驱动层决定尝试 PASTE 时调用。 */
    override fun pasteAttachmentScript(fileName: String, mimeType: String, base64: String): String =
        DeepSeekScripts.pasteImage(fileName, mimeType, base64)

    override fun snapshotScript(): String = DeepSeekScripts.snapshot()

    override fun diagnoseScript(): String = DeepSeekScripts.diagnose()

    /** AI-008D：输入框唯一性/最内层、发送按钮可点击判据、回答容器层级、Shadow DOM 探测。 */
    override fun deepProbeScript(): String = DeepSeekScripts.deepProbe()

    override fun fileInputProbeScript(): String = DeepSeekScripts.fileInputProbe()

    /** AI-011D：停止生成控件探测（只读）。 */
    override fun stopControlProbeScript(): String = DeepSeekScripts.stopControlProbe()

    override fun loginCheckScript(): String = DeepSeekScripts.loginCheck()

    override fun verifyAttachmentScript(): String = DeepSeekScripts.verifyAttachment()

    override fun pageFixScript(): String = DeepSeekScripts.pageFix()

    /**
     * JS 点击无法触发文件选择器（user activation），保留用于诊断。
     * 真实的 FILE_CHOOSER 路径走 [uploadIconScript] + 原生触摸。
     */
    override fun uploadTriggerScript(): String = DeepSeekScripts.uploadTrigger()

    /** 附件图标视口坐标，供原生触摸点击以触发 onShowFileChooser。 */
    override fun uploadIconScript(): String = DeepSeekScripts.locateUploadIcon()

    /**
     * AI-175：不再只认 `ok`。
     *
     * 必须同时具备「文本已填入」与「确实触发了发送动作」（Enter 或点击发送按钮）；
     * 页面上只剩 disabled 按钮时即使脚本返回 `ok:true` 也判失败。
     *
     * `valueEmpty` 只作诊断：React 受控组件在同一个 turn 内通常尚未完成重渲染，
     * 同步读到非空值并不代表没发出去；因此不以它作硬判据（等价判据见下方 `sent`）。
     */
    override fun parseSubmitResult(json: JSONObject): SubmitOutcome {
        val scriptOk = json.optBoolean("ok", false)
        val filled = json.optBoolean("inputFilled", false)
        val sent = json.optBoolean("sentEnter", false) || json.optBoolean("sendClicked", false)
        if (!scriptOk) {
            return SubmitOutcome.fail(
                why = JsonParse.why(json) ?: "submit-failed",
                inputFilled = filled
            )
        }
        if (!filled) return SubmitOutcome.fail("composer-not-filled", inputFilled = false)
        if (!sent) {
            val why = when {
                json.optBoolean("sendDisabled", false) -> "send-disabled"
                json.optBoolean("valueEmpty", false) -> "submit-not-dispatched"
                else -> "submit-not-dispatched"
            }
            return SubmitOutcome.fail(why, inputFilled = true)
        }
        return SubmitOutcome(ok = true, why = null, inputFilled = true, attached = true)
    }

    override fun parseSnapshot(json: JSONObject): AnswerSnapshot = AnswerSnapshot(
        responseCount = json.optInt("responseCount", 0),
        lastAnswerText = json.optString("lastAnswerText", ""),
        generating = json.optBoolean("generating", false),
        loginFormPresent = json.optInt("passwordCount", 0) > 0 &&
            json.optInt("loginIndicatorCount", 0) > 0,
        pageUrl = json.optString("href", ""),
        inputText = json.optString("inputText", "").trim(),
        attached = json.optBoolean("attached", false),
        sessionId = json.optString("sessionId", "")
    )

    override fun parseDiagnose(json: JSONObject): DiagnoseResult = DiagnoseResult(
        href = json.optString("href", ""),
        readyState = json.optString("readyState", ""),
        innerHeight = json.optInt("innerHeight", 0),
        inputCount = json.optInt("inputCount", 0),
        sendCount = json.optInt("sendCount", 0),
        responseCount = json.optInt("responseCount", 0),
        fileInputCount = json.optInt("fileInputCount", 0),
        loginIndicatorCount = json.optInt("loginIndicatorCount", 0),
        generatingCount = json.optInt("generatingCount", 0),
        editableCount = json.optInt("editableCount", 0)
    )

    /** 未登录判据：必须同时有密码框与登录 UI（M6）。 */
    override fun parseLogin(json: JSONObject): Boolean {
        val hasPassword = json.optInt("passwordCount", 0) > 0
        val hasLoginUi = json.optInt("loginIndicatorCount", 0) > 0
        return hasPassword && hasLoginUi
    }

    override fun parseAttachment(json: JSONObject): Boolean = json.optBoolean("attached", false)
}
