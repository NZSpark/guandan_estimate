package nz.org.aotearoa.guandanscore.recognition.site

import org.json.JSONObject
import org.json.JSONTokener

/** 图片上传的首选策略。失败后驱动层会回退到另一条。 */
enum class UploadStrategy { PASTE, FILE_CHOOSER }

/**
 * `fillAndSubmit` 注入脚本的结构化结果。
 *
 * [ok] 为 false 时必须带 [why]；[attached] 为 false 不得被当成上传成功。
 *
 * AI-175：[ok] 是**正面证据**的结论，不只是脚本没报错。适配器必须先确认
 * 「文本已填入」且「确实触发了发送动作」，否则一律返回 [fail]。
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

/**
 * 发送按钮的只读状态（AI-173）。
 *
 * [sendClickable] 必须同时满足「存在按钮」「未 disabled」「有可见尺寸」；
 * 仅看 `disabled` 会在按钮被隐藏或被重渲染移除时误判为可点。
 */
data class SendState(
    val sendCount: Int,
    val sendDisabled: Boolean,
    val sendClickable: Boolean,
    val valueEmpty: Boolean
) {
    companion object {
        /** 适配器未实现 [SiteAdapter.sendStateScript] 时的保守值：不可点。 */
        val UNKNOWN = SendState(sendCount = 0, sendDisabled = true, sendClickable = false, valueEmpty = true)
    }
}

/**
 * 回答快照，`GenerationWatcher.Sample` 的数据来源。
 *
 * AI-042：除回答本身，还必须记录发送前的**输入框内容**、**待发送附件状态**和
 * **会话标识**，否则无法回答两个安全问题：
 * - 本轮提交的是不是空白输入区（上一轮残留文本会被一起发出去）；
 * - 本轮附件是不是只有本次图片（上一轮图片残留会让模型看错图）。
 *
 * 这三项都只是“页面现状”的读数，不参与成功判定；默认空值保持既有实现零改动。
 */
data class AnswerSnapshot(
    val responseCount: Int,
    val lastAnswerText: String,
    val generating: Boolean,
    val loginFormPresent: Boolean,
    val pageUrl: String,
    /** 发送前输入框里的文本（空串表示干净）。 */
    val inputText: String = "",
    /** 发送前是否已有待发送附件预览。 */
    val attached: Boolean = false,
    /** 可读取的会话标识（站点会话 URL 中的 id）；取不到时为空串。 */
    val sessionId: String = ""
) {
    /** 输入区是否有本轮不能继承的残留（文本或附件）。 */
    val composerDirty: Boolean get() = inputText.isNotBlank() || attached

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
     * AI-124：**已完成验证**、允许尝试的上传策略集合。
     *
     * 默认只有 [preferredUpload]——驱动层不得因为「另一条理论上存在」就自行回退。
     * 只有该路径在本项目的目标 WebView 上实际验证过，才可加入此集合；
     * 未验证的回退会伪装成「试过了」，把真因（页面结构变化）拖成超时。
     */
    val supportedUploads: Set<UploadStrategy> get() = setOf(preferredUpload)

    /**
     * 附件是否在 [fillAndSubmitScript] 内部完成（填文本 → 粘贴 → 提交一次注入）。
     *
     * Gemini 的 `contenteditable` 必须在本条消息文本已填入后收到合成 paste，
     * 才会把图片挂到该消息上，因此它的上传无法与提交拆开。为 true 时驱动层
     * 跳过独立的 upload 阶段，附件是否成功一律由 [verifyAttachmentScript] 判定。
     */
    val uploadsInsideSubmit: Boolean get() = false

    /**
     * 附件必须在文本填入之后、提交之前挂上（GeminiBridge / ChatGPTBridge
     * `doc/image_upload.md` 的共同不变量）。
     *
     * 为 true 时驱动层按 `fillComposer → 上传并确认 → submitComposer` 三段执行，
     * 不再调用 [fillAndSubmitScript]。这样附件确认发生在提交之前，
     * 提交后输入区已被清空也不会影响判定；同时避免填文本的 selectAll
     * 把刚挂上的附件一并覆盖。
     */
    val uploadsAfterFill: Boolean get() = false

    /** 只把 prompt 写进输入区，不提交；[uploadsAfterFill] 为 true 时使用。 */
    fun fillComposerScript(prompt: String): String = ""

    /** 只点击发送；[uploadsAfterFill] 为 true 时使用。 */
    fun submitComposerScript(): String = ""

    /** 解析 [fillComposerScript] 的结果；默认以 `ok` 为准。 */
    fun parseFillResult(json: JSONObject): Boolean = json.optBoolean("ok", false)

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

    /**
     * AI-173：只读发送按钮状态。返回空串表示该站点不支持，驱动层不得假设可点。
     */
    fun sendStateScript(): String = ""

    /** 解析 [sendStateScript] 的结果；默认返回不可点的 [SendState.UNKNOWN]。 */
    fun parseSendState(json: JSONObject): SendState = SendState.UNKNOWN

    /**
     * AI-174：URL 是否已进入会话页（提交开始的正面证据之一）。
     *
     * 只有站点自己知道会话 URL 形态（如 `/a/chat/s/<id>`），因此放在适配器里；
     * 默认 false 表示「不能靠 URL 判定」，驱动层会退回 DOM 判据。
     */
    fun isSessionUrl(url: String): Boolean = false

    /** 校验图片附件是否真的出现。 */
    fun verifyAttachmentScript(): String

    /** 站点特有布局修复；不需要时返回空串。 */
    fun pageFixScript(): String = ""

    /**
     * AI-158：清空 composer 里**未发送的文本草稿**（不碰附件、不点击发送）。
     *
     * 只用于「站点会把上次未发送文本恢复成草稿」的情况：此时新建会话也无法得到
     * 干净输入区，驱动层会在快照确认只剩文本残留时调用它，并重新读快照验收。
     * 返回空串表示站点不支持，驱动层不得假定清空成功。
     */
    fun clearComposerScript(): String = ""

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

    /**
     * 有些站点的上传入口是一个菜单：原生触摸 [uploadIconScript] 只是打开菜单，
     * 还要再点一次「Files / 相册」才会触发 `onShowFileChooser`。
     *
     * 返回第二次点击点的视口坐标（与 [uploadIconScript] 同格式）；
     * 返回空串表示一次点击即可（默认）。
     */
    fun uploadMenuIconScript(): String = ""

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

    /**
     * 安全解析：脚本返回空串或非 JSON 时给出可诊断的失败结果。
     *
     * AI-133 同源缺陷：`evaluateJavascript` 对**字符串**返回值会再做一次 JSON 编码，
     * 于是脚本 `JSON.stringify({...})` 的结果到这里变成带外层引号、内部反斜杠转义的
     * 字符串字面量：`"{\"ok\":true,...}"`。直接 `JSONObject(raw)` 必然失败，表现为
     * 页面明明健康却一律判成「未就绪」。
     *
     * 因此这里先尝试按 JSON 字符串字面量解码一次，再解析对象。
     */
    fun parse(raw: String): JSONObject? {
        if (raw.isBlank() || raw == "null") return null
        val text = raw.trim()
        // 形态一：已是被解码过的对象文本。
        if (text.startsWith("{")) {
            return try {
                JSONObject(text)
            } catch (_: Exception) {
                null
            }
        }
        // 形态二：JSON 字符串字面量，需先反转义。
        if (!text.startsWith("\"") || !text.endsWith("\"") || text.length < 2) return null
        return try {
            val unescaped = JSONTokener(text).nextValue()
            if (unescaped is String) JSONObject(unescaped) else null
        } catch (_: Exception) {
            null
        }
    }
}
