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
 * 图片上传：首版保守选 FILE_CHOOSER。合成 paste 在华为 WebView 114 上是否被 Gemini 接受
 * 尚未实测（`doc/multi_ai_update.md` §11 待确认第 5 条），因此不作为首选。
 */
class GeminiAdapter : SiteAdapter, PasteCapableAdapter {

    override val homeUrl: String =
        AiSourcePresets.homeUrlFor(AiSourceId.GEMINI_WEBVIEW).orEmpty()

    override val siteKey: String = AiSourceId.GEMINI_WEBVIEW.key

    /** 附件在 [fillAndSubmitScript] 内随提交一起注入，因此不需要独立上传阶段。 */
    override val preferredUpload: UploadStrategy = UploadStrategy.PASTE

    /** 填文本 → 合成 paste → 点发送，必须在同一次注入内完成。 */
    override val uploadsInsideSubmit: Boolean = true

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
     * 单独的粘贴注入脚本：仅用于探针 / 诊断，主流程不走这里。
     * 主流程的粘贴在 [fillAndSubmitScript] 内部完成。
     */
    override fun pasteAttachmentScript(fileName: String, mimeType: String, base64: String): String =
        GeminiScripts.pasteImage(fileName, mimeType, base64)

    override fun snapshotScript(): String = GeminiScripts.snapshot()

    override fun diagnoseScript(): String = GeminiScripts.diagnose()

    override fun loginCheckScript(): String = GeminiScripts.diagnose()

    override fun verifyAttachmentScript(): String = GeminiScripts.verifyAttachment()

    override fun pageFixScript(): String = ""

    /**
     * 解析 `fillPasteAndSubmit` 的返回。
     *
     * 注意：脚本返回 `ok:true` 只代表"文本已写入"，**不代表已提交**。
     * 必须同时确认 `submitted:true`；`pasted` 仅为事件派发信号，不作成功判据（M11）。
     */
    override fun parseSubmitResult(json: JSONObject): SubmitOutcome {
        val filled = json.optBoolean("filled", false) || json.optBoolean("ok", false)
        val submitted = json.optBoolean("submitted", false)
        if (!filled) {
            return SubmitOutcome.fail(
                why = JsonParse.why(json) ?: "fill-prompt-failed",
                inputFilled = false
            )
        }
        if (!submitted) {
            return SubmitOutcome.fail(why = "submit-not-dispatched", inputFilled = true)
        }
        return SubmitOutcome(ok = true, why = null, inputFilled = true, attached = true)
    }

    override fun parseSnapshot(json: JSONObject): AnswerSnapshot = AnswerSnapshot(
        responseCount = json.optInt("responseCount", 0),
        lastAnswerText = json.optString("lastAnswerText", ""),
        generating = json.optBoolean("generating", false),
        loginFormPresent = json.optInt("loginIndicatorCount", 0) > 0,
        pageUrl = json.optString("href", "")
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
