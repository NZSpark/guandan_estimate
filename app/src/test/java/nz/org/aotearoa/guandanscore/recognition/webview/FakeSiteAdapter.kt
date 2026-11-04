package nz.org.aotearoa.guandanscore.recognition.webview

import nz.org.aotearoa.guandanscore.recognition.site.AnswerSnapshot
import nz.org.aotearoa.guandanscore.recognition.site.DiagnoseResult
import nz.org.aotearoa.guandanscore.recognition.site.SiteAdapter
import nz.org.aotearoa.guandanscore.recognition.site.SubmitOutcome
import nz.org.aotearoa.guandanscore.recognition.site.UploadStrategy
import org.json.JSONObject

/** 测试用站点适配器：不含任何真实选择器，也不含站点名。 */
class FakeSiteAdapter(
    override val preferredUpload: UploadStrategy = UploadStrategy.FILE_CHOOSER,
    override val homeUrl: String = "https://example.invalid/chat",
    /** 假适配器默认声明两条都可用，便于测试回退逻辑本身（AI-124）。 */
    override val supportedUploads: Set<UploadStrategy> =
        setOf(UploadStrategy.PASTE, UploadStrategy.FILE_CHOOSER),
    /** AI-182：模拟「填文本 → 上传并确认 → 提交」的站点。 */
    override val uploadsAfterFill: Boolean = false
) : SiteAdapter {

    override val siteKey: String = "fake_site"

    override fun fillAndSubmitScript(
        prompt: String,
        fileName: String,
        mimeType: String,
        base64: String
    ): String = "fake"

    override fun fillComposerScript(prompt: String): String = "fake"

    override fun submitComposerScript(): String = "fake"

    override fun parseFillResult(json: JSONObject): Boolean = true

    override fun snapshotScript(): String = "fake"

    override fun diagnoseScript(): String = "fake"

    override fun loginCheckScript(): String = "fake"

    override fun verifyAttachmentScript(): String = "fake"

    override fun parseSubmitResult(json: JSONObject) = SubmitOutcome.fail("fake")

    override fun parseSnapshot(json: JSONObject) = AnswerSnapshot.EMPTY

    override fun parseDiagnose(json: JSONObject) = DiagnoseResult("", "", 0, 0, 0, 0, 0, 0, 0)

    override fun parseLogin(json: JSONObject) = false

    override fun parseAttachment(json: JSONObject) = false
}
