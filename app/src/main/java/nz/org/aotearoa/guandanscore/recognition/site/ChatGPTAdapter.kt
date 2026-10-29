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

override fun fillAndSubmitScript(
prompt: String,
fileName: String,
mimeType: String,
base64: String
): String = ChatGPTScripts.fillAndSubmit(prompt)

override fun pasteAttachmentScript(fileName: String, mimeType: String, base64: String): String =
ChatGPTScripts.pasteImage(fileName, mimeType, base64)

override fun snapshotScript(): String = ChatGPTScripts.snapshot()

override fun diagnoseScript(): String = ChatGPTScripts.diagnose()

override fun loginCheckScript(): String = ChatGPTScripts.loginCheck()

override fun verifyAttachmentScript(): String = ChatGPTScripts.verifyAttachment()

/** 与其它站点一致：JS 点击不满足 user activation，只保留诊断价值。 */
override fun uploadTriggerScript(): String = ChatGPTScripts.uploadTrigger()

override fun parseSubmitResult(json: JSONObject): SubmitOutcome {
val ok = json.optBoolean("ok", false)
if (ok) {
return SubmitOutcome(
ok = true,
why = null,
inputFilled = json.optBoolean("inputFilled", false),
attached = true
)
}
return SubmitOutcome.fail(
why = JsonParse.why(json) ?: "submit-failed",
inputFilled = json.optBoolean("inputFilled", false)
)
}

override fun parseSnapshot(json: JSONObject): AnswerSnapshot = AnswerSnapshot(
responseCount = json.optInt("responseCount", 0),
lastAnswerText = json.optString("lastAnswerText", ""),
generating = json.optBoolean("generating", false),
loginFormPresent = json.optInt("passwordCount", 0) > 0 &&
json.optInt("loginIndicatorCount", 0) > 0,
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