package nz.org.aotearoa.guandanscore.recognition.site

import nz.org.aotearoa.guandanscore.recognition.GeminiScripts
import nz.org.aotearoa.guandanscore.recognition.source.AiSourceId
import nz.org.aotearoa.guandanscore.recognition.source.AiSourcePresets
import nz.org.aotearoa.guandanscore.recognition.webview.PasteCapableAdapter
import org.json.JSONObject

/**
 * Gemini 网页版适配器。
 *
 * 这是对现有 [GeminiScripts] 的**包装**，不修改任何脚本内容（AI-109 要求行为与当前一致）。
 * 脚本仍集中在 [GeminiScripts]，选择器仍集中在 `GeminiSelectors`。
 *
 * 图片上传（AI-182 真机结论）：
 * - 离屏宿主的 WebView `document.hasFocus()` 为 false，Gemini 会 `preventDefault`
 *   合成 paste 但**不挂附件**；即使页面可见，未真实触摸时同样无效。
 * - 原生触摸 `Upload and tools` → `Files` 会真实触发 `onShowFileChooser`，
 *   回填后输入区出现 `uploader-file-preview` 缩略图。因此生产路径改用 FILE_CHOOSER。
 * - 顺序固定为：填文本 → 原生触摸上传并确认预览 → 点发送（`uploadsAfterFill`）。
 */
class GeminiAdapter : SiteAdapter, PasteCapableAdapter {

    override val homeUrl: String =
        AiSourcePresets.homeUrlFor(AiSourceId.GEMINI_WEBVIEW).orEmpty()

    override val siteKey: String = AiSourceId.GEMINI_WEBVIEW.key

    /** AI-182：原生触摸上传菜单可真实触发文件选择器，且不依赖页面焦点。 */
    override val preferredUpload: UploadStrategy = UploadStrategy.FILE_CHOOSER

    /**
     * AI-124 / AI-182：只启用真机验证过的原生文件选择路径。
     * 合成 paste 在离屏宿主与未真实触摸时会被页面丢弃，不作自动回退。
     */
    override val supportedUploads: Set<UploadStrategy> = setOf(UploadStrategy.FILE_CHOOSER)

    /** 填文本 → 上传并确认 → 点发送，三段必须分开（AI-182）。 */
    override val uploadsAfterFill: Boolean = true

    /**
     * 一次注入完成「填文本 → 注入图片 → 提交」。
     *
     * 顺序不可拆：Gemini 的 `contenteditable` 只有在本条消息文本已在框内、编辑器已聚焦时
     * 收到合成 paste，才会把图片挂到该消息上（GeminiBridge `doc/image_upload.md` §3.3）。
     * 图片派发失败不影响文本提交，只降级为纯文本。
     */
    override fun fillAndSubmitScript(
        prompt: String,
        fileName: String,
        mimeType: String,
        base64: String
    ): String = GeminiScripts.fillPasteAndSubmit(prompt, fileName, mimeType, base64)

    /**
     * 单独的粘贴注入脚本：仅用于探针 / 诊断。
     *
     * AI-182 真机结论：Gemini 只在 `document.hasFocus()` 为 true 时接受合成 paste，
     * 离屏宿主没有焦点，生产流程改走 FILE_CHOOSER，不再使用该脚本上传。
     */
    override fun pasteAttachmentScript(fileName: String, mimeType: String, base64: String): String =
        GeminiScripts.pasteImage(fileName, mimeType, base64)

    override fun snapshotScript(): String = GeminiScripts.snapshot()

    override fun diagnoseScript(): String = GeminiScripts.diagnose()

 /** AI-030：逐候选命中数探针，用于区分“哪条候选真的生效”。只读。 */
 override fun deepProbeScript(): String = GeminiScripts.selectorProbe()

    override fun loginCheckScript(): String = GeminiScripts.diagnose()

    override fun verifyAttachmentScript(): String = GeminiScripts.verifyAttachment()

    /** AI-176：发送按钮由 Angular 异步渲染，点击前必须轮询可用性。 */
    override fun sendStateScript(): String = GeminiScripts.sendState()

    override fun parseSendState(json: JSONObject): SendState = SendState(
        sendCount = json.optInt("sendCount", 0),
        sendDisabled = json.optBoolean("sendDisabled", false),
        sendClickable = json.optBoolean("sendClickable", false),
        valueEmpty = json.optBoolean("valueEmpty", false)
    )

    /**
     * AI-015：FILE_CHOOSER 回退路径。JS `.click()` 预期因缺 user activation 失败，
     * 真实可行的是 [uploadIconScript] + 驱动层原生触摸。
     */
    override fun uploadTriggerScript(): String = GeminiScripts.uploadTrigger()

    /** AI-015：`Upload and tools` 按钮的视口坐标，供原生触摸打开上传菜单。 */
    override fun uploadIconScript(): String = GeminiScripts.locateUploadIcon()

    /** AI-182：菜单里的「Files」项，第二次原生触摸才真正触发 `onShowFileChooser`。 */
    override fun uploadMenuIconScript(): String = GeminiScripts.locateUploadMenuIcon()

    /** AI-182：只填文本，附件由驱动层的原生触摸路径另行挂上。 */
    override fun fillComposerScript(prompt: String): String = GeminiScripts.fillComposer(prompt)

    /** AI-182：附件确认后只点发送，不再改输入区。 */
    override fun submitComposerScript(): String = GeminiScripts.submit()

    /** 解析 fillComposerScript 的 `inputFilled`。 */
    override fun parseFillResult(json: JSONObject): Boolean =
        json.optBoolean("inputFilled", false) || json.optBoolean("ok", false)

    override fun pageFixScript(): String = ""

    /**
     * 解析 `fillPasteAndSubmit` 的返回。
     *
     * 注意：脚本返回 `ok:true` 只代表"文本已写入"，**不代表已提交**。
     * 必须同时确认 `submitted:true`；`pasted` 仅为事件派发信号，不作成功判据（M11）。
     */
    override fun parseSubmitResult(json: JSONObject): SubmitOutcome {
        val filled = json.optBoolean("filled", false) ||
            json.optBoolean("inputFilled", false) || json.optBoolean("ok", false)
        // `fillPasteAndSubmit` 用 submitted；只点发送的 `submit()` 用 sendClicked。
        val submitted = json.optBoolean("submitted", false) || json.optBoolean("sendClicked", false)
        if (!filled) {
            return SubmitOutcome.fail(
                why = JsonParse.why(json) ?: "fill-prompt-failed",
                inputFilled = false
            )
        }
        if (!submitted) {
            return SubmitOutcome.fail(
                why = JsonParse.why(json) ?: "submit-not-dispatched",
                inputFilled = true
            )
        }
        return SubmitOutcome(ok = true, why = null, inputFilled = true, attached = true)
    }

    override fun parseSnapshot(json: JSONObject): AnswerSnapshot = AnswerSnapshot(
        responseCount = json.optInt("responseCount", 0),
        lastAnswerText = json.optString("lastAnswerText", ""),
        generating = json.optBoolean("generating", false),
        loginFormPresent = json.optInt("loginIndicatorCount", 0) > 0,
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
        passwordCount = json.optInt("passwordCount", 0)
    )

    /**
     * 未登录判据：诊断里**必须**同时出现密码输入框（`input[type=password]`）与登录指示。
     * 只看登录选择器会把账户菜单误判为未登录（M6 / SMSProxy P25）。
     */
    override fun parseLogin(json: JSONObject): Boolean {
        val hasPassword = json.optInt("passwordCount", 0) > 0
        val hasLoginUi = json.optInt("loginIndicatorCount", 0) > 0
        return hasPassword && hasLoginUi
    }

    override fun parseAttachment(json: JSONObject): Boolean = json.optBoolean("attached", false)
}
