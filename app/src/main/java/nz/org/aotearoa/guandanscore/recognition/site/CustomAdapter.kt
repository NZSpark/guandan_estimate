package nz.org.aotearoa.guandanscore.recognition.site

import nz.org.aotearoa.guandanscore.recognition.source.AiSourceId
import nz.org.aotearoa.guandanscore.recognition.webview.PasteCapableAdapter
import org.json.JSONObject

/**
 * 自定义网页源。
 *
 * 首版策略（`doc/multi_ai_update.md` §11 待确认第 2 条）：**只让用户填 URL，不让用户填选择器**。
 * 选择器与脚本复用 [DeepSeekAdapter]，因为这些选择器基于通用结构
 * （`textarea[placeholder]`、`div[contenteditable]`、`input[type=file]`），
 * 对同类聊天式页面有较大命中概率。
 *
 * 若用户填的站点结构差异过大，`diagnose()` 会给出 0 命中，
 * 驱动层返回 `NotReady` 而不是等到超时。
 */
class CustomAdapter(private val userHomeUrl: String) :
    SiteAdapter by DeepSeekAdapter(),
    PasteCapableAdapter {

    private val delegate = DeepSeekAdapter()

    override val homeUrl: String = userHomeUrl

    override val siteKey: String = AiSourceId.CUSTOM_WEBVIEW.key

    override val preferredUpload: UploadStrategy = UploadStrategy.FILE_CHOOSER

    /** AI-124：自定义站点没有验收记录，不得从 DeepSeek 的委托里继承“两条都可用”。 */
    override val supportedUploads: Set<UploadStrategy> = setOf(UploadStrategy.FILE_CHOOSER)

    override fun fillAndSubmitScript(
        prompt: String,
        fileName: String,
        mimeType: String,
        base64: String
    ): String = DeepSeekScripts.fillAndSend(prompt)

    override fun pasteAttachmentScript(fileName: String, mimeType: String, base64: String): String =
        DeepSeekScripts.pasteImage(fileName, mimeType, base64)

    override fun snapshotScript(): String = DeepSeekScripts.snapshot()

    override fun diagnoseScript(): String = DeepSeekScripts.diagnose()

    override fun loginCheckScript(): String = DeepSeekScripts.loginCheck()

    override fun verifyAttachmentScript(): String = DeepSeekScripts.verifyAttachment()

    override fun pageFixScript(): String = DeepSeekScripts.pageFix()

    override fun parseSubmitResult(json: JSONObject): SubmitOutcome = delegate.parseSubmitResult(json)

    override fun parseSnapshot(json: JSONObject): AnswerSnapshot = delegate.parseSnapshot(json)

    override fun parseDiagnose(json: JSONObject): DiagnoseResult = delegate.parseDiagnose(json)

    override fun parseLogin(json: JSONObject): Boolean = delegate.parseLogin(json)

    override fun parseAttachment(json: JSONObject): Boolean = delegate.parseAttachment(json)
}
