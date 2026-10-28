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

    override val preferredUpload: UploadStrategy = UploadStrategy.FILE_CHOOSER

    /**
     * Gemini 的 `fillPrompt` 与 `submit` 本是两步脚本，这里串联执行。
     *
     * 不合并成单个 JS 是为了不改变已验证脚本的行为；驱动层会在两次注入之间检查中间结果。
     * 第二次注入带 `submitOnly` 标记，由驱动层按 `;` 分隔执行（见 `PageDriver`）。
     */
    override fun fillAndSubmitScript(
        prompt: String,
        fileName: String,
        mimeType: String,
        base64: String
    ): String = GeminiScripts.fillPrompt(prompt)

    /** 附件注入脚本（粘贴路径）。只有 preferredUpload 为 PASTE 时才调用。 */
    override fun pasteAttachmentScript(fileName: String, mimeType: String, base64: String): String =
        GeminiScripts.pasteImage(fileName, mimeType, base64)

    override fun snapshotScript(): String = GeminiScripts.snapshot()

    override fun diagnoseScript(): String = GeminiScripts.diagnose()

    override fun loginCheckScript(): String = GeminiScripts.diagnose()

    override fun verifyAttachmentScript(): String = GeminiScripts.verifyAttachment()

    override fun pageFixScript(): String = ""

    override fun parseSubmitResult(json: JSONObject): SubmitOutcome {
        val ok = json.optBoolean("ok", false)
        if (ok) {
            return SubmitOutcome(ok = true, why = null, inputFilled = true, attached = true)
        }
        return SubmitOutcome.fail(
            why = JsonParse.why(json) ?: "fill-prompt-failed",
            inputFilled = json.optBoolean("inputFilled", false)
        )
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
        generatingCount = json.optInt("generatingCount", 0)
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
