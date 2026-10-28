# 多 AI 源支持：配置页可选 ChatGPT / DeepSeek / Gemini / 自定义 AI 源

> **依据：** `doc/ai_update.md`（Gemini WebView 单源设计）、`doc/ai_tasks.md`（阶段拆分）、
> `SMSProxy/doc/ai_support.md`（WebView 上游模块化做法）、
> `/Users/onetreehill/Github/ChatGPTBridge/doc/image_upload.md`（ChatGPT 网页图片附件上传、请求编排、提交顺序与失败降级设计）。
>
> **本文性质：** 设计 + 实施清单。**尚未执行**。所有“完成”必须以真机证据为准，
> 各网页源均须验证登录态、真实图片附件上传、提交、回答提取与识牌解析的完整闭环，不得仅凭脚本派发成功勾选。
>
> **一句话：** 把“识牌用哪个 AI”从硬编码 Gemini 改成**配置可选的源**，
> 每个网页源是一个独立适配器；业务层（`AiRecognitionService`）只认 `AiSource` 接口。

---

## 1. 现状与问题

### 1.1 现状盘点（2026-10-28，分支工作区）

| 文件 | 现状 | 问题 |
|---|---|---|
| `recognition/AiSettingsStore.kt` | 有 `AiTransport { GEMINI_WEBVIEW, API }`、`AiPresets.geminiWebView()`；Provider 是自由文本 | `GEMINI_WEBVIEW` 已定义但**没有任何地方消费**；没有 DeepSeek 预设；`provider` 靠字符串嗅探 |
| `recognition/AiRecognitionClient.kt` | 同步 HTTP，`isGemini()` / `isOpenAiChat()` 靠 provider/endpoint/model **字符串包含**推断协议 | 加第三个源必然继续堆 `isXxx()`；网页模式无法走这里 |
| `recognition/GeminiSelectors.kt` / `GeminiScripts.kt` | Gemini 专用选择器与注入脚本 | 站点逻辑没有隔离层，加站点只能复制文件 |
| `recognition/GeminiUpstream.kt` | 抽象接口（`checkReady` / `captureBaseline` / `attachImage` / `fillPrompt` / `submit` / `snapshot`） | **只有接口，没有任何实现**；名字绑死 Gemini，加第二个站点就得再写一个同构接口 |
| `recognition/AiRecognitionService.kt` | 依赖 `GeminiUpstream`，跑状态机 | 与站点名绑定；`Busy` 判据是进程内 `AtomicBoolean`，多源切换时语义不明 |
| `MainActivity.recognizeOnline()` | 只走 `aiClient.recognize()`（HTTP），`transport` 字段被忽略 | 选了“Gemini 网页版”照样要 API Key；**这是当前最直接的 bug** |
| `probe/GeminiProbeActivity.kt` | 硬编码 `https://gemini.google.com/app` | 探测入口只能测 Gemini |

**核心缺陷一句话：** 有 `transport` 字段，没有 transport 的分发点；
有上游接口，名字和选择器却是单站点的。

### 1.2 目标

1. 配置页能**显式选择 AI 源**：ChatGPT 网页版 / Gemini 网页版 / DeepSeek 网页版 / 自定义网页源 /（保留）HTTP API 源。
2. 每个网页源 = 一个 `SiteAdapter`（选择器 + 脚本 + 登录判据）；驱动层（WebView 生命周期、提交、轮询、结束判定）**只有一份**。
3. 业务层不认识任何站点名，只认 `AiSource`。
4. 新增一个站点：**只新增适配器 + 预设**，不改 `AiRecognitionService`、不改 `MainActivity` 主流程、不改驱动层。
5. 不引入官方 API Key 依赖（ChatGPT / Gemini / DeepSeek 网页版均复用网页登录态），HTTP API 源作为**可选**保留。

### 1.3 明确不做（本轮）

- 不做多源并发（同一时刻只有一个识牌任务，见 `C2` 单上游串行）。
- 不做“问同一个问题给两个源做投票”。
- 不把 HTTP API 路径迁移到新的抽象里（只加一个 `HttpApiSource` 适配壳，内部仍用现有 `AiRecognitionClient`）。
- 不改评分逻辑、不改 `Card` / `Rank` / `Suit`。

---

## 2. 目标架构

```
MainActivity / 设置页
        │  选择 active source（AiSourceId）
        ▼
AiSettingsStore ──► AiSourceRegistry  （内置预设 + 用户自定义）
        │                  │
        │                  └─► SiteAdapter（ChatGPTAdapter / GeminiAdapter / DeepSeekAdapter / CustomAdapter）
        ▼
AiRecognitionService  ──►  AiSource 接口
        │                       │
        │                       ├─ WebViewAiSource   （网页模式：驱动层 + 适配器）
        │                       └─ HttpApiSource      （API 模式：包一层 AiRecognitionClient）
        ▼
WebViewHost（进程级单例 WebView + 窗口附着）
        │
        ├─ WebViewBridge（主线程投递 + evaluateJavascript）
        ├─ PageDriver（导航 / 就绪 / 提交 / 轮询 / 结束判定）  ← 站点无关
        └─ SiteAdapter（选择器 + 脚本 + 登录判据）            ← 每站点一份
```

**两条必须守住的线（继承 SMSProxy 的结论）：**

1. **驱动层不知道站点。** `PageDriver` 里不允许出现任何 CSS 选择器、URL 字面量或 `chatgpt` / `gemini` / `deepseek` 等站点标识。
2. **适配器不知道业务。** 适配器不碰 `Card`、不拼短信、不读设置；只回答“输入框在哪、怎么提交、回答节点在哪、是否已登录”。

---

## 3. 关键接口设计

### 3.1 AiSource（业务层唯一依赖）

```kotlin
/** 一次识牌请求的输入。 */
data class RecognizeRequest(
    val jpegBytes: ByteArray,
    val prompt: String,
    val mimeType: String = "image/jpeg",
    val fileName: String = "hand.jpg"
)

/** 统一的失败分类，直接映射成 UI 文案，不允许静默失败。 */
sealed class AiSourceError(message: String) : Exception(message) {
    class NotLoggedIn(val hint: String = "") : AiSourceError("AI 未登录")
    class NotReady(val reason: String) : AiSourceError("AI 页面未就绪：$reason")
    class UploadFailed(val detail: String) : AiSourceError("图片上传失败：$detail")
    class SubmitFailed(val detail: String) : AiSourceError("提交失败：$detail")
    class Timeout(val afterMillis: Long) : AiSourceError("等待回答超时")
    class ParseFailed(val detail: String) : AiSourceError("回答无法解析：$detail")
    class Busy : AiSourceError("已有识牌任务正在进行")
    class Cancelled : AiSourceError("识牌已取消")
    class Unknown(cause: Throwable?) : AiSourceError("未知错误：${cause?.message}")
}

sealed interface SourceHealth {
    data object Ready : SourceHealth
    data class NotLoggedIn(val hint: String) : SourceHealth
    data class Unavailable(val reason: String) : SourceHealth
}

interface AiSource {
    val id: String              // 与 AiSourceId 一致
    val displayName: String
    /** 是否需要 API Key / endpoint 等网络字段（配置页据此显隐）。 */
    val requiresNetworkConfig: Boolean
    /** 是否需要用户手动在 WebView 里登录（配置页据此显示“打开登录页”按钮）。 */
    val requiresWebLogin: Boolean

    fun healthCheck(): SourceHealth
    /** 同步阻塞调用，内部切主线程执行 WebView 操作；调用方在 worker 线程。 */
    fun recognize(request: RecognizeRequest): List<Card>
    fun cancel()
    fun currentSessionUrl(): String?
}
```

> 保留“同步 + worker 线程”的调用形状，是为了**不改 `MainActivity.recognizeOnline()` 的线程模型**，
> 让迁移可以逐源替换、可回滚。内部的 WebView 调用仍然必须主线程投递（`WebViewBridge`）。

### 3.2 SiteAdapter（站点适配层）

```kotlin
interface SiteAdapter {
    val homeUrl: String
    /** 诊断用标识，出现在日志与配置页。 */
    val siteKey: String
    /** 图片上传首选策略。 */
    val preferredUpload: UploadStrategy

    /** 一次注入，完成“填 prompt + 挂附件 + 点发送”，返回结构化结果。 */
    fun fillAndSubmitScript(prompt: String, fileName: String, mimeType: String, base64: String): String
    /** 读回答快照。 */
    fun snapshotScript(): String
    /** 只读诊断。 */
    fun diagnoseScript(): String
    /** 判定是否未登录（必须要求 password 输入框存在，见 §6 M6）。 */
    fun loginCheckScript(): String
    /** 校验图片附件是否真的出现。 */
    fun verifyAttachmentScript(): String
    /** 站点特有修复（视口 / 弹窗）；可选，返回空串表示不需要。 */
    fun pageFixScript(): String = ""

    fun parseSubmitResult(json: JSONObject): SubmitOutcome
    fun parseSnapshot(json: JSONObject): AnswerSnapshot
    fun parseDiagnose(json: JSONObject): DiagnoseResult
    fun parseLogin(json: JSONObject): Boolean
    fun parseAttachment(json: JSONObject): Boolean
}

enum class UploadStrategy { PASTE, FILE_CHOOSER }
```

**为什么 fill 和 submit 要合成一个脚本：** DeepSeek 在 `input` 事件后会重渲染输入区并禁用发送按钮，
分两次往返会稳定命中禁用按钮（SMSProxy 记录为 `all_disabled`）。Gemini 也应验证是否存在同样问题，
所以统一按“一次注入完成”设计，成功与否由返回的 `SubmitOutcome` 表达。

```kotlin
data class SubmitOutcome(
    val ok: Boolean,
    val why: String?,          // not_found / disabled / selector_error / ...
    val inputFilled: Boolean,
    val attached: Boolean
)

data class AnswerSnapshot(
    val responseCount: Int,
    val lastAnswerText: String,
    val generating: Boolean,
    val loginFormPresent: Boolean,
    val pageUrl: String
)

data class DiagnoseResult(
    val href: String,
    val readyState: String,
    val innerHeight: Int,
    val inputCount: Int,
    val sendCount: Int,
    val responseCount: Int,
    val fileInputCount: Int,
    val loginIndicatorCount: Int,
    val generatingCount: Int
)
```

### 3.3 WebViewAiSource（驱动层）

```kotlin
class WebViewAiSource(
    private val adapter: SiteAdapter,
    private val host: WebViewHost,
    private val bridge: WebViewBridge,
    private val watcherFactory: () -> GenerationWatcher = { GenerationWatcher() },
    private val pollIntervalMillis: Long = 500,
    private val timeoutMillis: Long = 120_000
) : AiSource {
    // recognize() 状态机（复用现有 AiRecognitionService 的步骤，搬到驱动层）
    //  EnsurePageReady → EnsureLoggedIn → CaptureBaseline
    //  → FillAndSubmit（含附件） → VerifyAttachment → WaitForNewAnswer
}
```

**`AiRecognitionService` 之后只保留“编排 + 取消 + 结果解析”**，
或者更彻底：把状态机整个挪进 `WebViewAiSource`，`AiRecognitionService` 退化成
“给当前源跑一次 recognize + 解析卡片”的薄壳（见 §7 阶段 D）。

### 3.4 AiSourceId 与预设

```kotlin
enum class AiSourceId(val key: String, val label: String) {
    CHATGPT_WEBVIEW("chatgpt_web", "ChatGPT 网页版"),
    GEMINI_WEBVIEW("gemini_web", "Gemini 网页版"),
    DEEPSEEK_WEBVIEW("deepseek_web", "DeepSeek 网页版"),
    CUSTOM_WEBVIEW("custom_web", "自定义网页源"),
    HTTP_API("http_api", "HTTP API（API Key）")
}

object AiSourceRegistry {
    /** 内置预设：sourceKey / provider / endpoint / model / transport / homeUrl 的组合。 */
    fun presets(): List<AiSettings>
    /** 由配置 + 宿主构造实际可用的源。 */
    fun create(settings: AiSettings, host: WebViewHost, ctx: Context): AiSource
}
```

**预设表（初版）：**

| key | 显示名 | transport | homeUrl | 需要 Key | 需要网页登录 |
|---|---|---|---|---|---|
| `chatgpt_web` | ChatGPT 网页版 | `WEBVIEW` | `https://chatgpt.com/` | 否 | 是 |
| `gemini_web` | Gemini 网页版 | `WEBVIEW` | `https://gemini.google.com/app` | 否 | 是 |
| `deepseek_web` | DeepSeek 网页版 | `WEBVIEW` | `https://chat.deepseek.com` | 否 | 是 |
| `custom_web` | 自定义网页源 | `WEBVIEW` | 用户填写 | 否 | 是 |
| `http_api` | HTTP API | `API` | — | 是 | 否 |

`chatgpt_web` 使用 ChatGPT 网页登录态，不需要 OpenAI API Key。它与 `http_api` 是两种不同的传输路径：前者通过 `WebViewAiSource` 操作网页，后者继续使用 `AiRecognitionClient`。

---

## 4. 配置数据模型改造

### 4.1 `AiSettings` 新形状

```kotlin
enum class AiTransport { WEBVIEW, API }   // 原 GEMINI_WEBVIEW 改名并泛化

data class AiSettings(
    val id: String,
    val sourceKey: String,        // AiSourceId.key；取代靠 provider 字符串嗅探
    val provider: String,         // 显示名（可用户改）
    val endpoint: String = "",    // API 模式必填
    val model: String = "",       // API 模式必填
    val apiKey: String = "",      // API 模式必填，加密存储
    val homeUrl: String = "",     // WEBVIEW 模式必填（自定义源用）
    val transport: AiTransport = AiTransport.API
) {
    val requiresApiKey: Boolean get() = transport == AiTransport.API
}
```

**迁移规则（`AiSettingsStore.loadAll()`）：**

| 旧数据 | 新解释 |
|---|---|
| 有 `transport` 且为 `GEMINI_WEBVIEW` | `sourceKey = gemini_web`，`transport = WEBVIEW` |
| 无 `transport` 字段 | `transport = API`，`sourceKey = http_api`（与现状一致，不改行为） |
| `provider` 含 deepseek 且 endpoint 为空 | 提示用户改选 `deepseek_web`，**不静默改写** |
| 其它 | `sourceKey = http_api` |

**不要**用 provider 字符串反向推断 `sourceKey`——这正是要消灭的耦合。
旧数据的 `provider` 原样保留为显示名。

### 4.2 存储

沿用现有 SharedPreferences + `AndroidKeyStore` AES-GCM 加密 `api_key`。
新增字段 `source_key`、`home_url` 写进同一个 profile JSON。
`active_profile` 语义不变（选中的 profile），**`sourceKey` 是 profile 的属性**，
所以“切源”= 切 profile，“同源不同账号/模型”= 同 `sourceKey` 的多个 profile。

---

## 5. DeepSeek 适配器设计

> 依据 `SMSProxy/doc/ai_support.md`。**以下选择器与脚本参数是待真机验证的候选值**，
> 必须在目标设备（LIO-N29 / EMUI 12 / WebView 114）上用 `diagnoseScript()` 实测后才算常量。

### 5.1 选择器（首版候选）

| 用途 | 候选 | 验证要求 |
|---|---|---|
| 输入框 | `textarea#chat-input`、`div[contenteditable="true"]`、`textarea[placeholder]` | 取最内层；实测 `querySelectorAll(sel).length ≥ 1` |
| 发送按钮 | `button[aria-label*="Send"]`、`div[role="button"][aria-disabled="false"]`、`#send-button` | 实测点击后 `responseCount` 增加 |
| 回答节点 | `div.ds-markdown`、`div[class*="markdown"]`、`div[class*="message"] div[class*="content"]` | 必须**排除用户消息**；实测 count 与页面可见回答数一致 |
| 登录判据 | `input[type="password"]`（**必须**）+ 登录按钮/表单 | 已登录页不得命中（SMSProxy P25 教训） |
| 生成中 | 无可靠 stop 控件 | 实测若为 0 命中，则**不得**作为结束判定的前提 |
| 文件上传 | `input[type="file"]`（优先）；否则 `button[aria-label*="Upload"]` | 必须能拿到 `WebChromeClient.onShowFileChooser` |

**纪律：** 不用哈希类名（`cb86951c` 这类每次构建都变）；选择器写成 CSS 列表但必须收窄；
每条在代码注释里记录实测日期、实测方法、命中数。

### 5.2 与 Gemini 的差异点

| 差异 | Gemini | DeepSeek | 处理 |
|---|---|---|---|
| 输入框 | `rich-textarea [contenteditable]` | `textarea#chat-input` | 适配器各自实现填充脚本 |
| 发送按钮禁用逻辑 | 常规 | `input` 后重渲染并禁用 | 合成一个脚本，失败时补 `execCommand('insertText')` 再找 |
| 图片上传 | `paste` + `DataTransfer` 待验证 | 更可能走 `input[type=file]` / `onShowFileChooser` | 适配器声明 `preferredUpload` |
| 登录判据 | Google 账号跳转 | 站内登录页 | 都必须要求 `input[type=password]` |
| 会话 URL | `/app/<id>` | `/a/chat/s/<id>` 形态 | 驱动层只做“读当前 URL 并持久化”，不解析形态 |

### 5.3 上传路径的两条实现

- **PASTE**：合成 `ClipboardEvent` + `DataTransfer` + `File`。**派发成功 ≠ 附件已接受**，
  必须紧接着 `verifyAttachment` 确认预览节点出现，否则返回 `UploadFailed`。
- **FILE_CHOOSER**：`WebChromeClient.onShowFileChooser` 回调里，把 JPEG 写进 `cacheDir`，
  构造 `Uri`，`callback.onReceiveValue(arrayOf(uri))`。这条路径在华为 WebView 上通常更可靠。

**默认顺序：** 先试适配器声明的 `preferredUpload`；失败则回退另一条。
两条都失败 → `UploadFailed("both paste and file chooser rejected")`，**不静默提交纯文本**。

### 5.4 结束判定

沿用现有 `GenerationWatcher`（纯逻辑、已单测）：
基线 `(responseCount, lastAnswerText)` + 文本稳定 ≥ 3 次 + 静默 ≥ 1500ms + 无 stop 控件。
DeepSeek 实测无 stop 元素时，`generating` 传 `false`，**不得**改成“必须看到 stop 消失”。

---

### 5.5 ChatGPT 网页版适配器设计

> **参考：** `/Users/onetreehill/Github/ChatGPTBridge/doc/image_upload.md`。该文档记录的是 Python / Playwright 桥接实现；Android 端应复用其中的附件处理原则，而不是直接照搬 Python 代码。ChatGPT 页面选择器、登录判据和附件预览判据均须在目标 Android WebView 上实测，不能把候选值当成已验证结果。

#### 5.5.1 源注册与页面职责

- 新增 `ChatGPTAdapter`，实现通用 `SiteAdapter` 接口；`siteKey` 为 `chatgpt`，默认首页为 `https://chatgpt.com/`。
- 源 ID 使用 `chatgpt_web`，传输类型为 `WEBVIEW`，不需要 API Key，但需要用户在网页中登录。
- 输入框、发送按钮、回答节点、登录判据、附件预览节点与页面修复脚本全部封装在适配器中，禁止把 ChatGPT 选择器或 URL 写入 `PageDriver`。
- 选择器与脚本必须通过 `diagnoseScript()` 验证。登录状态不得只根据 URL、账户菜单或页脚推断；必须有足够证据区分登录页与已登录页。

#### 5.5.2 图片附件上传与提交顺序

ChatGPTBridge 的图片流程为“解析图片 → 获取图片字节 → 创建真实 `File` → 通过 `DataTransfer` 和 `ClipboardEvent('paste')` 附加 → 提交消息”。在本应用中，输入来自 `RecognizeRequest.jpegBytes`，适配器应将图片作为真实文件附件交给网页，而不是只把图片描述或 Base64 文本写进 prompt。

1. **准备图片：** 使用请求中的 JPEG 字节、文件名与 MIME 类型构造附件；不要把 Base64 正文拼进用户可见的提示词。
2. **填充文本：** 将识牌 prompt 写入 composer，并确认输入已成功。
3. **附加图片：** 首选 `UploadStrategy.PASTE`，按目标 WebView 实测结果构造 `File`、`DataTransfer` 与合成 `paste` 事件。不要假定事件派发必然意味着网页接受了附件。
4. **验证附件：** 随后执行 `verifyAttachmentScript()`，确认当前消息的附件预览确实出现。返回值必须表示网页已接受附件，而不能只表示找到输入框或派发了事件。
5. **最后提交：** 仅当文本填充和附件校验均成功，才执行提交脚本。填充、上传、校验与提交需要明确的结构化结果，至少包含 `ok` 和 `why`。

**失败策略：** 图片未能附加或无法确认附件预览时，返回 `AiSourceError.UploadFailed`，不得静默提交纯文本识牌请求，否则应用可能将没有看过图片的文字回答误认为识牌结果。若以后为 ChatGPT 增加 `FILE_CHOOSER` 回退，必须单独实现并通过真机验证，不能把 DeepSeek 的上传路径直接视为适用于 ChatGPT。

#### 5.5.3 超时、资源与隐私

- 图片附件与文本必须属于同一条待提交消息；重新创建 WebView 后，应重新附加该次请求所需图片，不能假定旧页面的附件状态仍然存在。
- 对上传验证和回答等待设置明确超时，超时分类为 `UploadFailed` 或 `Timeout`，禁止无限等待；单元测试应覆盖脚本异常和网页拒绝附件的情况。
- 不记录图片 Base64、登录 Cookie、访问令牌或密码；诊断日志只记录必要状态、错误原因和耗时。
- 为输入字节数和 JavaScript 序列化开销设置合理边界。ChatGPTBridge 的 `IMAGE_PASTE_ENABLED`、`IMAGE_PASTE_MAX` 与 `IMAGE_DOWNLOAD_TIMEOUT_MS` 是其服务端配置，不应未经设计就原样复制为 Android 配置；本应用首先处理单张本地手牌照片，不需要为其引入远程图片下载逻辑。
- 当前仍需验证 ChatGPT 页面改版、登录状态、合成 paste 事件和附件预览选择器的实际行为。正式验收以前，`ChatGPTAdapter` 不得标记为已验证。

## 6. 必须遵守的硬规则（来自 SMSProxy 的回归教训）

| 编号 | 规则 | 原因 |
|---|---|---|
| M1 | 所有 WebView 调用必须主线程投递 | 否则 `A WebView method was called on thread ...` 或回调丢失 |
| M2 | 后台渲染必须有窗口（overlay 优先，decorView 回退） | 无 `ViewRootImpl` 则不出帧，流式 DOM 不更新 → 永远超时 |
| M3 | 视口 `innerHeight < 100` 时禁止写高度 | 会把页面压成 1px，之后不再建虚拟列表 |
| M4 | 结束判定稳定次数 ≥ 3 且静默 ≥ 1.5s | 2 次会在网络抖动时误判结束 |
| M5 | 回答判定必须有基线（`responseCount` + `lastAnswerText`） | 追问时最后一个节点仍是上一个答案，会串答 |
| M6 | 登录判据必须要求 `input[type=password]` | 否则账户菜单/页脚命中，已登录却报未登录 |
| M7 | 读文本确认虚拟列表已渲染 | 离屏行 `innerText` 为空 |
| M8 | 诊断/健康检查在生成中直接返回缓存，不碰 WebView | 探测会争抢主线程 JS 队列，历史上曾打断生成 |
| M9 | 每个脚本返回可解析 JSON，带 `ok` 与 `why`，不吞异常 | 否则“成功”是假的，排障无据 |
| M10 | 不记录 Cookie、令牌、密码、图片 Base64 | 安全边界 |
| M11 | 不把 `dispatched=true` 当作上传成功 | 事件派发与站点接受是两件事 |
| M12 | 一个 WebView 一个父容器；重挂前先 `removeHostFromParent(dropWebView=false)` | 否则 `IllegalStateException` 或僵尸态 |

---

## 7. 实施阶段（每步可编译、可回滚）

| 阶段 | 动作 | 出口条件 |
|---|---|---|
| **A 数据模型** | `AiTransport` 改名泛化；`AiSettings` 加 `sourceKey` / `homeUrl`；写迁移逻辑；`AiPresets` 加 ChatGPT / DeepSeek / custom；补 `AiSettingsStoreTest` | 旧数据加载后行为不变；单测全绿；配置页能显示五个源 |
| **B 配置页** | `showAiSettings()` 改成分组下拉：先选源，再按 `requiresApiKey` / `requiresWebLogin` 显隐字段；`http_api` 仍校验 HTTPS；网页源显示“打开登录页”按钮 | 选择 ChatGPT / Gemini 网页版时均不要求 API Key；ChatGPT 与 DeepSeek 显示正确首页和默认值 |
| **C 抽取适配层** | `SiteAdapter` 接口；`GeminiAdapter`（把 `GeminiSelectors` + `GeminiScripts` 收进去）；`WebViewBridge`；`WebViewHost`（进程级单例 + 窗口附着） | Gemini 现有行为不变；探测页可用新路径跑通诊断 |
| **D 驱动层** | `PageDriver`（导航/就绪/提交/轮询）+ `WebViewAiSource`；`AiRecognitionService` 退化为薄壳或删除状态机 | Fake 源 + Fake 驱动单测全绿；Gemini 真机识牌闭环不回退 |
| **E DeepSeek 适配** | `DeepSeekSelectors` / `DeepSeekScripts` / `DeepSeekAdapter`；两套上传策略 | 真机：登录态下上传手牌照片 → 回答被解析出 ≥ 1 张牌；`verifyAttachment` 通过 |
| **F ChatGPT 适配** | `ChatGPTSelectors` / `ChatGPTScripts` / `ChatGPTAdapter`；实现图片附件粘贴与附件预览校验 | 真机登录态下上传手牌照片 → 附件预览验证通过 → 回答解析出 ≥ 1 张牌；上传失败不得提交纯文本 |
| **G 接入 UI** | `recognizeOnline()` 走 `AiSourceRegistry.create(...).recognize()`；`probe` 页支持选源 | 所有已接入源均按各自验收步骤识牌；失败文案分类正确 |
| **H 清理** | 删除字符串嗅探 `isGemini()` / `isOpenAiChat()`（`http_api` 内部保留）；更新 `doc/ai_tasks.md` 勾选；补验收记录 | 驱动层中不存在 `chatgpt` / `gemini` / `deepseek` 等站点标识 |

**回滚点：** A/B 独立可回滚（只是配置层）；C 之后 Gemini 路径必须保持可跑，否则不进入 E。

---

## 8. 配置页 UX

```
AI Provider 设置
┌──────────────────────────────────────────┐
│ AI 源   [ ChatGPT 网页版        ▼ ]     │   ← 五个预设 + “＋ 新增…”
│ 名称    [ ChatGPT 网页版              ] │
│ 首页    [ https://chatgpt.com/          ] │   ← 仅网页源显示
│         [ 打开登录页 ]  ● 已登录 / ○ 未登录 │
├──────────────────────────────────────────┤
│ （仅 http_api 显示）                      │
│ 接口    [ https://…                    ] │
│ 模型    [ gemini-…                     ] │
│ API Key [ ••••••                       ] │
└──────────────────────────────────────────┘
        [ 取消 ]      [ 删除所选 ]   [ 保存并选用 ]
```

- 切源时**预填该源默认值**，但不覆盖用户已改过的同源 profile。
- “打开登录页”跳到 `probe` 页（或专门的登录 Activity），登录后返回刷新健康态。
- 健康态用 `SourceHealth` 映射成一句话，不做假绿：`Unavailable` / `NotLoggedIn` 都要说人话。

---

## 9. 测试计划

### 9.1 单元测试（不依赖真机，必须常绿）

| 对象 | 用例 |
|---|---|
| `AiSettingsStore` | 旧数据（无 `transport`）→ `http_api`；`GEMINI_WEBVIEW` → `gemini_web`；新增 ChatGPT / DeepSeek 预设后 `loadAll()` 不丢用户 profile；切 profile 后 `load()` 返回正确 `sourceKey` |
| `AiSourceRegistry` | 五个 `sourceKey` 各自映射到正确实现类型；未知 key 回退 `http_api` 并告警 |
| `ChatGPTAdapter` / `GeminiAdapter` / `DeepSeekAdapter` | 脚本 JSON 可解析；`why` 字段存在；prompt 含引号/换行/`$` 时不破坏脚本；图片参数正确转义；附件派发与附件验证分别测试 |
| `ChatGPTAdapter` 图片上传 | 上传成功必须有附件预览证据；paste 被拒、附件未出现、脚本报错或超时均返回 `UploadFailed`，不得继续提交纯文本 |
| `SubmitOutcome` 解析 | `ok=false` 必须带 `why`；`attached=false` 不得被当成成功 |
| `GenerationWatcher` | 现有用例保持；补一条“全站无 stop 元素也能完成” |
| 迁移 | 加密的 `api_key` 在改名后仍能解密 |

### 9.2 真机验收（按顺序）
1. 选 “Gemini 网页版”，**不填 API Key**，点联网识图 → 不再弹“请先填写 API Key”。
2. 选 “ChatGPT 网页版”，**不填 API Key**，登录后上传手牌照片 → 附件预览验证通过，解析出 ≥ 1 张牌。
3. ChatGPT 未登录时 → `NotLoggedIn` 文案，而不是超时。
4. ChatGPT paste 被拒、附件预览未出现或附件脚本失败 → 返回 `UploadFailed`，**不得**提交纯文本。
5. 选 “DeepSeek 网页版”，同一张照片 → 附件验证通过并解析出 ≥ 1 张牌（网页多源核心验收）。
6. Gemini、ChatGPT、DeepSeek 来回切换 → 不串会话、不覆盖 profile。
7. 后台（Launcher 在前台）分别跑各网页源 → 不因无窗口而超时。
8. 断网 → 明确失败文案，不挂死。
9. 上传被拒（支持的上传策略都失败）→ `UploadFailed`，**不得**提交纯文本。
10. 回答节点数不增加但文本变化（追问）→ 得到新答案，不返回上一轮。
11. 自定义网页源填错误 URL → `NotReady`，给出具体原因。

### 9.3 每次改选择器后

- 真机 CDP 或 probe 页跑 `document.querySelectorAll(sel).length`，非零且语义正确。
- `./gradlew testDebugUnitTest` 全绿。
- 在适配器注释里更新实测日期与命中数。

---

## 10. 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| ChatGPT / DeepSeek / Gemini 前端改版 | 选择器失效，整源挂 | 选择器集中 + `diagnose()` + 明确报错（M9）；ChatGPT 附件必须独立验证，不用脚本派发结果冒充上传成功 |
| 华为 WebView 对合成 paste 不友好 | 图片上不去 | 双策略 + `verifyAttachment` 硬校验 + `FILE_CHOOSER` 回退 |
| 网页源风控/验证码 | 登录态失效 | 手动登录入口 + `NotLoggedIn` 显式上报，不重试到封号 |
| 后台渲染不出帧 | 永远超时 | `WebViewHost` overlay 优先（M2）+ 健康检查暴露窗口状态 |
| 配置迁移写坏用户 profile | 丢 Key / 丢源 | 迁移只新增字段、不改既有语义；单测覆盖旧数据 |
| 抽象过度 | 加一个源要改五个文件 | 接口只留 §3 那些方法；驱动层禁止站点字样（阶段 H 的验收项） |
| 网页自动化合规 | 账户限制 | 上线前产品侧评估，遵守站点条款；不用 API Key ≠ 无风险 |

---

## 11. 待确认问题（需用户决策）

1. **ChatGPT 与 DeepSeek 的首发顺序？** 建议先完成 A/B（数据模型与配置 bug），再分别完成 DeepSeek 和 ChatGPT 的真机图片上传闭环；没有真实附件验收证据的源不得标记完成。
2. **自定义网页源的编辑粒度：** 只允许填 `homeUrl`（选择器用内置默认），还是允许用户填选择器？
   建议先只填 URL + 选“站点类型”，避免把选择器暴露成用户字段。
3. **HTTP API 是否保留在同一配置页？** 建议保留为第五个选项，但默认折叠。
4. **`AiRecognitionService` 是否删除？** 建议保留类名与 `Result` 形状（UI 依赖），只把状态机下沉到 `WebViewAiSource`。
5. **上传成功判据：** Gemini 与 ChatGPT 的附件预览选择器尚待真机验证；需登录态设备数据定稿，在此之前不得勾选任何“上传已验证”。
   需要登录态真机数据后定稿，在此之前不得勾选任何“上传已验证”。

---

## 12. 与现有文档的关系

| 文档 | 关系 |
|---|---|
| `doc/ai_update.md` | 单源（Gemini）WebView 设计；本文把其“上游接口”泛化为多源 |
| `doc/ai_tasks.md` | 阶段 0–5 任务清单；本文新增“多源”子项，**不替换**其验收标准 |
| `SMSProxy/doc/ai_support.md` | 驱动层、窗口宿主、结束判定、排障方法论的来源；本文只取站点无关部分 |
| `/Users/onetreehill/Github/ChatGPTBridge/doc/image_upload.md` | ChatGPTBridge 的图片解析、真实文件附件粘贴、提交顺序、失败降级与安全边界；Android 实现须针对 WebView 重新验证 |
| `skills/web-to-openai-api` | 网页转 API 的通用方法论；适配器设计与其“选择器集中 + 结构化验签”一致 |
