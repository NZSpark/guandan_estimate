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

    /** AI-009D：`input[type=file]` 属性探测（只读，不触发选择器）。默认无此能力。 */
    fun fileInputProbeScript(): String = ""

    /** AI-011D：停止生成控件探测（只读，不点击）。默认无此能力。 */
    fun stopControlProbeScript(): String = ""

    /** 判定是否未登录。 */
    fun loginCheckScript(): String

    /**
     * AI-031：URL 是否直接就是登录页。
     *
     * DOM 判据会漏：实测 DeepSeek 未登录时被重定向到 `/sign_in`，页面上既没有
     * `input[type=password]` 也没有命中的登录锚点，`passwordCount` / `loginIndicatorCount`
     * 都是 0，于是被归成「输入框缺失 → 重载」，而重载会再次落回 `/sign_in`，
     * 形成无意义的重载循环。URL 是此时唯一可靠的信号。
     *
     * 默认按路径关键字判断，覆盖主流站点的 `/sign_in` `/login` `/auth`；
     * 站点有特殊路径时覆盖本方法。
     */
    fun isLoginUrl(url: String): Boolean {
        if (url.isBlank()) return false
        // 逐段精确匹配，不做 startsWith：`/authority`、`/author` 会误命中
        // 前缀判断，把正常页当成登录页从而停掉自动化。
        val path = url.substringAfter("://", url).substringAfter('/', "")
            .substringBefore('?').substringBefore('#')
        val segments = path.split('/').filter { it.isNotBlank() }.map { it.lowercase() }
        return segments.any { it in LOGIN_PATH_SEGMENTS }
    }

    private companion object {
        /** 登录页路径段白名单。只匹配整段，避免 /authority 这类误判。 */
        val LOGIN_PATH_SEGMENTS = setOf("sign_in", "signin", "login", "auth")
    }

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
