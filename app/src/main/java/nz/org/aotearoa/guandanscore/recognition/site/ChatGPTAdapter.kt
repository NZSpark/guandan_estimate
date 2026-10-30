package nz.org.aotearoa.guandanscore.recognition.site

import nz.org.aotearoa.guandanscore.recognition.source.AiSourceId
import nz.org.aotearoa.guandanscore.recognition.source.AiSourcePresets
import nz.org.aotearoa.guandanscore.recognition.webview.PasteCapableAdapter
import org.json.JSONObject

/**

ChatGPT 网页版适配器。

验证状态：未真机验证（AI-164）

本适配器只完成「注册进五源体系 + 结构与其它适配器同构」这一步。

DOM 选择器与脚本尚未在目标设备上核对命中数，因此：

不得在 README.md / 配置页把 ChatGPT 写成「已验证可用」；

首次真机接入必须跑只读诊断（AiProbeActivity --es site chatgpt_web --es auto gemini_diag），

确认 inputCount/sendCount/responseCount/fileInputCount/passwordCount 后再回填

[ChatGPTSelectors]，并补一条与 [DeepSeekSelectors] 相同的实测记录表。

上传策略（推测，未验证）

与 DeepSeek 同属「contenteditable + 需要 user activation 才能开选择器」的一类，

故首选 [UploadStrategy.PASTE]；FILE_CHOOSER 作为回退，需驱动层原生触摸。

注意 Gemini 的实测已证明「点上传图标只是弹网页菜单」，ChatGPT 是否同样如此尚待确认。
*/
class ChatGPTAdapter : SiteAdapter, PasteCapableAdapter {

override val homeUrl: String =
AiSourcePresets.homeUrlFor(AiSourceId.CHATGPT_WEBVIEW).orEmpty()

override val siteKey: String = AiSourceId.CHATGPT_WEBVIEW.key

override val preferredUpload: UploadStrategy = UploadStrategy.PASTE

/** AI-124：ChatGPT 上传路径尚未真机验证，回退策略一律不启用。 */
override val supportedUploads: Set<UploadStrategy> = setOf(UploadStrategy.PASTE)

/**
 * AI-182：按 ChatGPTBridge 文档的不变量拆成三段：
 * 填文本 → 合成 paste 并确认附件 → 点发送。提交前必须看到真实附件预览。
 */
override val uploadsAfterFill: Boolean = true

override fun fillComposerScript(prompt: String): String = ChatGPTScripts.fillComposer(prompt)

override fun submitComposerScript(): String = ChatGPTScripts.submitComposer()

override fun parseFillResult(json: JSONObject): Boolean =
json.optBoolean("inputFilled", false) || json.optBoolean("ok", false)

override fun fillAndSubmitScript(
prompt: String,
fileName: String,
mimeType: String,
base64: String
): String = ChatGPTScripts.fillAndSubmit(prompt)

/** AI-176：点击前轮询发送按钮可用性。 */
override fun sendStateScript(): String = ChatGPTScripts.sendState()

override fun parseSendState(json: JSONObject): SendState = SendState(
sendCount = json.optInt("sendCount", 0),
sendDisabled = json.optBoolean("sendDisabled", false),
sendClickable = json.optBoolean("sendClickable", false),
valueEmpty = json.optBoolean("valueEmpty", false)
)

override fun pasteAttachmentScript(fileName: String, mimeType: String, base64: String): String =
ChatGPTScripts.pasteImage(fileName, mimeType, base64)

override fun snapshotScript(): String = ChatGPTScripts.snapshot()

    /** AI-158：站点会恢复未发送草稿，驱动层需要显式清空。 */
    override fun clearComposerScript(): String = ChatGPTScripts.clearComposer()

override fun diagnoseScript(): String = ChatGPTScripts.diagnose()

override fun loginCheckScript(): String = ChatGPTScripts.loginCheck()

override fun verifyAttachmentScript(): String = ChatGPTScripts.verifyAttachment()

/** 与其它站点一致：JS 点击不满足 user activation，只保留诊断价值。 */
override fun uploadTriggerScript(): String = ChatGPTScripts.uploadTrigger()

/**
 * AI-176：与 DeepSeek 同构的正面证据判据。未真机验证的源也不能先放宽：
 * 必须已填入文本且确实触发了 Enter 或点击发送，否则返回失败。
 */
override fun parseSubmitResult(json: JSONObject): SubmitOutcome {
val scriptOk = json.optBoolean("ok", false)
val filled = json.optBoolean("inputFilled", false)
val sent = json.optBoolean("sentEnter", false) || json.optBoolean("sendClicked", false)
if (!scriptOk) {
return SubmitOutcome.fail(JsonParse.why(json) ?: "submit-failed", inputFilled = filled)
}
if (!filled) return SubmitOutcome.fail("composer-not-filled", inputFilled = false)
if (!sent) {
return SubmitOutcome.fail(
if (json.optBoolean("sendDisabled", false)) "send-disabled" else "submit-not-dispatched",
inputFilled = true
)
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
passwordCount = json.optInt("passwordCount", 0),
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