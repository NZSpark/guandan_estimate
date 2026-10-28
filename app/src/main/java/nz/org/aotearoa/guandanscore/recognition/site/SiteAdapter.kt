package nz.org.aotearoa.guandanscore.recognition.site

import org.json.JSONObject

/** 图片上传的首选策略。失败后驱动层会回退到另一条。 */
enum class UploadStrategy { PASTE, FILE_CHOOSER }

/**
 * `fillAndSubmit` 注入脚本的结构化结果。
 *
 * [ok] 为 false 时必须带 [why]；[attached] 为 false 不得被当成上传成功。
 */
data class SubmitOutcome(
    val ok: Boolean,
    val why: String?,
    val inputFilled: Boolean,
    val attached: Boolean
) {
    companion object {
        fun fail(why: String, inputFilled: Boolean = false, attached: Boolean = false) =
            SubmitOutcome(ok = false, why = why, inputFilled = inputFilled, attached = attached)
    }
}

/** 回答快照，`GenerationWatcher.Sample` 的数据来源。 */
data class AnswerSnapshot(
    val responseCount: Int,
    val lastAnswerText: String,
    val generating: Boolean,
    val loginFormPresent: Boolean,
    val pageUrl: String
) {
    companion object {
        val EMPTY = AnswerSnapshot(0, "", false, false, "")
    }
}

/** 只读诊断结果。用于真机回填选择器命中数。 */
data class DiagnoseResult(
    val href: String,
    val readyState: String,
    val innerHeight: Int,
    val inputCount: Int,
    val sendCount: Int,
    val responseCount: Int,
    val fileInputCount: Int,
    val loginIndicatorCount: Int,
    val generatingCount: Int,
    val passwordCount: Int = 0,
    val editableCount: Int = 0
) {
    fun summary(): String = buildString {
        append("href=${href.take(60)} ready=$readyState h=$innerHeight ")
        append("input=$inputCount send=$sendCount resp=$responseCount ")
        append("file=$fileInputCount login=$loginIndicatorCount gen=$generatingCount")
    }
}

/**
 * 站点适配层：每接入一个新站点只实现这个接口。
 *
 * 硬约束：
 * - 适配器不碰 `Card`、不读配置、不做业务决策；只回答“元素在哪、怎么操作、结果怎么解”。
 * - 所有脚本必须返回可解析 JSON，带 `ok` 与失败时的 `why`。
 * - 动态参数必须用 [nz.org.aotearoa.guandanscore.recognition.GeminiScripts.quote] 或等效方式 JSON 编码。
 * - [loginCheckScript] 必须同时要求密码输入框存在，避免账户菜单误判（M6）。
 */
interface SiteAdapter {

    /** 站点首页。必须来自 `AiSourcePresets`，不要在这里写字面量。 */
    val homeUrl: String

    /** 诊断标识，用于日志与配置页。 */
    val siteKey: String

    /** 首选上传策略。 */
    val preferredUpload: UploadStrategy

    /**
     * 附件是否在 [fillAndSubmitScript] 内部完成（填文本 → 粘贴 → 提交一次注入）。
     *
     * Gemini 的 `contenteditable` 必须在本条消息文本已填入后收到合成 paste，
     * 才会把图片挂到该消息上，因此它的上传无法与提交拆开。为 true 时驱动层
     * 跳过独立的 upload 阶段，附件是否成功一律由 [verifyAttachmentScript] 判定。
     */
    val uploadsInsideSubmit: Boolean get() = false

    /** 一次注入完成“填 prompt + 挂附件 + 点发送”。 */
    fun fillAndSubmitScript(prompt: String, fileName: String, mimeType: String, base64: String): String

    /** 读回答快照。 */
    fun snapshotScript(): String

    /** 只读诊断。 */
    fun diagnoseScript(): String

    /**
     * AI-008D：页面结构深度探测（只读）。默认无实现；需要精确结构事实的站点覆盖它。
     */
    fun deepProbeScript(): String = ""

    /** 判定是否未登录。 */
    fun loginCheckScript(): String

    /** 校验图片附件是否真的出现。 */
    fun verifyAttachmentScript(): String

    /** 站点特有布局修复；不需要时返回空串。 */
    fun pageFixScript(): String = ""

    /**
     * 点击页面自身的上传入口（打开文件选择器）。
     * 返回空串表示该站点不支持 FILE_CHOOSER 路径。
     *
     * 注意：部分站点（实测 DeepSeek）要求 user activation，JS 点击不会触发
     * `onShowFileChooser`；这类站点应实现 [uploadIconScript] 并由驱动层原生触摸。
     */
    fun uploadTriggerScript(): String = ""

    /**
     * 返回上传入口图标的视口坐标 JSON（`{ok,x,y,w,h,innerHeight,dpr}`），
     * 供驱动层用原生触摸点击以触发 `onShowFileChooser`。
     * 返回空串表示该站点不需要原生触摸路径。
     */
    fun uploadIconScript(): String = ""

    fun parseSubmitResult(json: JSONObject): SubmitOutcome

    fun parseSnapshot(json: JSONObject): AnswerSnapshot

    fun parseDiagnose(json: JSONObject): DiagnoseResult

    fun parseLogin(json: JSONObject): Boolean

    fun parseAttachment(json: JSONObject): Boolean
}

/** 供各适配器复用的宽松解析工具，避免重复 try/catch。 */
object JsonParse {
    fun optInt(o: JSONObject, key: String): Int = o.optInt(key, 0)

    fun optString(o: JSONObject, key: String): String = o.optString(key, "")

    fun why(o: JSONObject): String? = o.optString("why").takeIf { it.isNotBlank() }

    /** 安全解析：脚本返回空串或非 JSON 时给出可诊断的失败结果。 */
    fun parse(raw: String): JSONObject? = try {
        if (raw.isBlank() || raw == "null") null else JSONObject(raw)
    } catch (_: Exception) {
        null
    }
}
