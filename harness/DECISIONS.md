# Decisions —— 关键决策记录

> 追加式记录，每条含日期、决策、理由、状态。
> 旧决策不删除，被取代时标注 `Superseded by ADR-00xx`，由 Cleanup 归档。

## ADR-0001 · 2026-10-28 · 采用单一真源 + 轻量工具适配的 Harness 结构

**决策**：以 `AGENTS.md` 作为唯一入口与共享知识根，`CLAUDE.md` 只做一行引用；记忆分层放在 `harness/`（CRITICAL / state / DECISIONS / index / memory / archive），可复用工作流放在 `skills/`。

**理由**：仓库此前完全没有 AI 入口文件，无历史包袱；评分规则已有两份 Markdown 文档，Harness 不应复制公式表，只做指针与路由，避免双份规则漂移。

**状态**：Active

---

## ADR-0002 · 2026-10-28 · Harness 不复制评分公式，只引用原始文档

**决策**：`harness/memory/scoring-rules.md` 记录“公式在哪里、改动要同步哪些文件、有哪些易错点”，公式本体仍以 `doc/掼蛋牌型量化评分方案.md` / `doc/手牌综合牌力评估方法.md` 为唯一来源。

**理由**：公式表体量大、变更频繁，复制即产生漂移风险；单测已是数值行为的可执行规格。

**状态**：Active

---

## ADR-0003 · 2026-10-28 · Cleanup 采用「任务收尾轻量清理 + 手动深度清理」双档

**决策**：不引入定时调度依赖。日常在任务写回 `state.md` 时顺带做轻量清理（去重、压缩、修索引）；深度清理通过 `skills/harness-cleanup` 手动触发，触发条件为：`CRITICAL.md` > 60 行、`state.md` > 80 行、`memory/` 单文件 > 120 行、或每累计 10 次提交。

**理由**：项目为单人维护的 Android 仓库，无 CI 常驻 agent；过度自动化收益低、维护成本高。

**状态**：Active

---

## ADR-0004 · 2026-10-28 · 归档而非删除

**决策**：过期知识移入 `harness/archive/`，文件名保留原相对路径语义（如 `archive/memory-scoring-rules-2026-10.md`），并在文件头写明归档原因、替代文件与日期。

**理由**：评分规则历史版本对复盘与回归有价值；删除不可逆，且违反“关键决策可追溯”要求。

**状态**：Active

---

## ADR-0005 · 2026-10-28 · WebView 迁移采用「接口隔离 + 纯逻辑可测」分层

**决策**：把 Gemini 网页迁移拆成四层——`AiCardResponseParser`（解析）、`GenerationWatcher`（结束判定）、`GeminiUpstream`（页面能力接口）、`AiRecognitionService`（编排/互斥/错误映射）。前三层零 Android 依赖，可纯 JVM 单测；只有 `GeminiUpstream` 的真实实现依赖 WebView。

**理由**：任务清单的核心风险（上传不可验证、回答串线、DOM 变化）无法离线消除，但可以把「不可测的部分」压缩到最薄一层。这样真机调试时只需替换一个实现，状态机行为已有 60+ 测试保护。

**状态**：Active

---

## ADR-0006 · 2026-10-28 · 选择器集中管理且显式标注「未验证」

**决策**：`GeminiSelectors` 为唯一选择器来源，每个候选值必须真机验证后才能去掉注释中的警告；`GeminiScripts` 中所有动态参数强制经 `JSONObject.quote` 编码，所有脚本返回 `ok`/`why` 结构化结果。

**理由**：任务清单明确禁止把其他项目的选择器当既定事实；同时防止脚本注入与「派发成功即上传成功」的误判。

**状态**：Active

---

## ADR-0007 · 2026-10-29 · 解析器同时接受对象与顶层裸数组

**决策**：`AiCardResponseParser.parseStrict` 先尝试 `{"cards":[...]}` 对象，失败再尝试顶层 JSON 数组 `[...]`；两者共用 `collectCards` 做字段校验、条数上限与告警。

**理由**：`PROMPT` 要求对象格式，但探针/自定义 prompt 可能要求直接输出数组，DeepSeek 实测即返回裸数组。旧实现只认对象，导致必然退回正则兜底并产生“请重点核对”告警，削弱结果可信度。

**状态**：Active

---

## ADR-0008 · 2026-10-29 · `evaluateJavascript` 回传必须用 `JSONTokener` 解码

**决策**：`WebView.evaluateJavascript` 的字符串返回值是 JSON 字符串字面量，必须经 `org.json.JSONTokener` 反序列化还原 `\n`/`\"`/`\\`/`\uXXXX`；不得用 `removeSurrounding("\"")` + 手工 `replace`。

**理由**：手工替换无法还原换行与转义反斜杠，含引号的 JSON 回答（如识牌数组）会被解析成 `null`，表现为“快照为空”而非解析错误，极易误诊为 DOM/上传问题。

**状态**：Active

---

## ADR-0009 · 2026-10-29 · 上传路径保持 PASTE ⇄ FILE_CHOOSER 双策略，不引入第三种

**决策**：`WebViewAiSource.uploadWithFallback` 只按 `adapter.preferredUpload` 及其互补策略尝试两次；不把合成拖放或系统剪贴板纳入自动降级链。拖放与系统剪贴板作为探针手工排障入口保留（`AiProbeActivity` 的「拖放测试」「系统剪贴板」按钮与 `--es auto upload_alt`）。

**理由**：真机（LIO-N29 / Android 10 / webview 114，DeepSeek）实测两种替代路径都能让页面出现附件预览（基线 0 → 各自 1），但它们与 PASTE 一样依赖合成事件或原生注入，收益不显著，却会显著拉长失败链与排障面。现有双策略已在 DeepSeek（FILE_CHOOSER）与 Gemini（PASTE）两端各自闭环，足以覆盖页面改版时的互补失败。

**约束**：不得为此引入站点私有 API、隐藏网络接口或内部 RPC；附件是否被接受唯一判据是 `verifyAttachment()` 的预览计数，`dispatchEvent` 返回值不可作判据。

**状态**：Active

---

## ADR-0010 · 2026-10-29 · `WebViewHost.destroy()` 只销毁 WebView，不清注入配置

**决策**：`WebViewHost.destroy()` 负责释放 WebView 自身状态 —— 移除视图、`webView.destroy()`、置空 `webView`/`visibleParent`/`offscreenHost`、清 `currentUrl`/`pendingUploadUri`、释放挂起的 `filePathCallback`。它**不清除**由宿主注入的配置：`fileChooserHandler`、`onPageFinished`、`attachTargetProvider`。

**理由**：`PageDriver.ensureLoaded()` 在导航前会 `host.destroy()` 再 `host.attach()` 重建视图（华为 WebView 卡死恢复路径）。若 `destroy()` 清空注入配置，重建后的 WebView 将无人接管 `onShowFileChooser`，上传静默失败。真机实测（LIO-N29 / Android 10 / webview 114，DeepSeek）：首版清空后 `attachmentCount=0 attached=false`；保留后 `attachmentCount=1 attached=true`。

**约束**：注入配置的生命周期由宿主（`MainActivity` / `AiProbeActivity` / 调用方）负责；只有宿主主动 `setAttachTarget(null)` 或替换 handler 时才变更。新增「每视图」状态与「每宿主」配置时必须分清归属，写进对应一侧的清理路径。

**状态**：Active

---

## ADR-0011 · 2026-10-29 · WebView 安全开关收敛为契约对象；URL 进日志必须脱敏

**决策**：① WebView 的 JavaScript / DOM Storage / 文件访问 / 混合内容 / 表单保存 / 定位 / Cookie 开关不再散落硬编码在 `ensureWebView()`，统一由 `recognition/webview/WebViewSecurityConfig` 声明，默认值即契约，`violations()` 供启动自检与单测断言。② 任何打印页面 URL 的日志必须先经 `recognition/webview/UrlRedactor`：保留 scheme/host/path，query 与 fragment 的**值**替换为 `<redacted>`，键名保留。

**理由**：设置项只有"写对了"这一种保证，后来者顺手打开 `allowFileAccess` 或关闭混合内容拦截不会有任何阻力；SPA 登录后 URL 常带 `?code=` / `#access_token=`，原样进 logcat 等于把凭据写进系统日志，违反 CRITICAL §6/§8（API Key / 私有端点绝不入库、不入日志）。

**约束**：新增 WebView 能力开关时先改 `WebViewSecurityConfig` 并在 `violations()` 里加规则，不要在 `ensureWebView()` 里直接写 `settings.xxx = ...`。新增任何 URL 日志点必须套 `UrlRedactor.redact()`；脱敏保留结构是为了排障时能看出"被重定向到登录页"，不是为了让值可读。

**状态**：Active

---

## ADR-0012 · 2026-10-30 · `evaluateJavascript` 的二次 JSON 编码必须在解析层统一消解

**决策**：所有脚本返回值进入 Kotlin 前的统一入口是 `recognition/site/SiteAdapter.kt` 的 `JsonParse.parse(raw)`，由它负责消解 `evaluateJavascript` 对**字符串返回值**再做的 JSON 编码：若 `raw` 以 `{` 开头按对象文本直解；若以 `"` 包住则先用 `JSONTokener.nextValue()` 反转义成字符串，再按对象解析；其它形态返回 `null`。适配器与驱动层一律走这一个入口，禁止各自 `JSONObject(raw)` 或 `removeSurrounding("\"")`。

**理由**：`evaluateJavascript` 对字符串结果会再编码一次，脚本 `JSON.stringify(obj)` 到达 Kotlin 时是 `"{\"ok\":true,...}"`。此前 `JsonParse.parse` 直接 `JSONObject(raw)` 必然失败，`PageDriver.probeLiveness()` 恒返回 `NOT_READY`，表现为「页面明明健康却永远判未就绪」，并可能把健康页误推入 reload 判定链。这是与 AI-133（探针 `runScript` 漏解转义）同源的缺陷，说明「解码只做一半」是这类桥接的固定陷阱。

**约束**：新增任何从脚本取结构化数据的路径，必须经 `JsonParse.parse`；不得在调用点重复实现反转义。脚本侧仍以 `JSON.stringify` 返回对象（而非裸值），解析层只做「一层」消解，不做多轮循环解码。`JsonParseTest` 覆盖对象文本、二次编码字面量、空/null、垃圾输入四种形态，改动解析逻辑必须同步扩充。

**状态**：Active

---

## ADR-0013 · 2026-10-30 · 主界面不插可见 WebView，识牌走离屏宿主

**决策**：正式识牌不再把 WebView 放进可见布局（AI-165）。`WebViewHost` 新增 `WindowStatus.Offscreen` 与 `attachOffscreenViaProvider()`：宿主在已 attached 的视图树里放一个 1×1 锚点 `FrameLayout`，WebView 作为其子视图显式 measure/layout 到 `displayMetrics` 全屏像素。`PageDriver.ensureLoaded()` 可见挂接失败即回退离屏挂接，并把 `Offscreen` 与 `Attached` 视为对驱动层等价可用；登录与诊断仍由独立探针页承担可见 WebView。

**理由**：主工作台需要「输入图 + 结果卡」的完整版面，嵌入可见 WebView 会挤压布局并把页面 DOM 尺寸压到检测阈值之下。离屏锚点让 `innerHeight` 等于屏高（真机 1176×2328 / DPR 3 → 392×776 CSS px），页面正常出帧，`onShowFileChooser` 也照常触发。真机 LIO-N29 / Android 10 验证：`attachOffscreen: window=1x1 webviewLayout=1176x2328`、`OFFSCREEN_OK innerHeight=776`。

**约束**：`WindowStatus` 必须如实区分 Attached / Offscreen / NotAttached / NotCreated，不允许把「已创建」当「已挂接」；离屏挂接成功前不得缓存 `navigatedTarget`；`WindowStatusTest` 钉住「Offscreen 与 Attached 等价可用」。文件选择回填涉及的目录必须同时声明在 `res/xml/file_paths.xml`（`ai-upload/`、`probe/`），否则 `FileProvider.getUriForFile` 抛 `Failed to find configured root`。

**状态**：Active

---

## ADR-0014 · 2026-10-30 · 业务层只依赖 `AiSource`，提交前快照必须包含输入区状态

**决策**：

1. 删除 Gemini 专用的 `AiRecognitionService` / `GeminiUpstream` 及其测试。网页识牌的唯一生产编排是 `WebViewAiSource` + `PageOps`，HTTP 识牌是 `HttpApiSource`；`MainActivity` 只依赖 `recognition/source` 的接口与注册表。
2. `AnswerSnapshot` 除回答外同时记录 `inputText` / `attached` / `sessionId`。当 `inputText` 非空或已有附件预览时，驱动层不手动清空、也不直接提交，而是调用 `PageOps.startNewConversation()`：真实回到站点首页，再用 DOM 快照确认 `responseCount == 0 && !generating && 输入区干净`。确认不了就返回 `SubmitFailed`。

**理由**：`AiRecognitionService` 与 `GeminiUpstream` 是单源时代的骨架，生产路径早已改用 `AiSourceRegistry` + `WebViewAiSource`，保留它们只会让“业务层不感知站点”的约束看起来没被满足，并额外维护 15 个只测旧骨架的用例。另一个真机风险是输入区残留：上一轮的文本会拼进本轮 prompt，上一轮的图片附件会让模型看错图；仅靠“写新图前删缓存”只能清磁盘，清不掉页面里已经挂上的预览。把输入区状态纳入基线，才能在提交前做出可验证的判断。

**约束**：新增网页源只实现 `SiteAdapter` 并注册预设，不得再引入站点专用的业务服务或状态机。任何新的“提交前检查”都必须来自快照读数，不得靠假设或 URL 拼接；新会话确认失败必须显式失败，不得降级为“应该没问题”继续提交。

**状态**：Active

---

## ADR-0015 · 2026-10-30 · 登录页与识牌共用同一 WebView，提交证据必须限定在 composer

**决策**：

1. `AiProbeActivity` 不再自建 WebView，改为挂接进程级 `WebViewHost` 的同一实例；主界面与登录/诊断页共享登录态与页面状态。
2. 可见容器 ↔ 离屏锚点切换统一在 `WebViewHost.attach*` 内先解除旧父容器（`removeHostFromParent(dropWebView = false)`）再加新父容器；`MainActivity.onDestroy()` 不得 `destroy()` 共享实例，实例销毁只由 `WebViewHost` 自身负责。
3. 附件就绪判据只统计 composer 之上第 3 层内的预览节点；历史消息区域（第 6 层）的图片一律不算本轮附件。
4. 提交前必须存在可用发送按钮；按钮尚未渲染时先等 `awaitSendEnabled()`，再重填一次，禁止仅凭“Enter 已派发”返回 `submitted=true`。

**理由**：真机复现了三类“看起来正确、实际错误”的状态。其一，登录页与识牌页各建 WebView，导致主界面 `onDestroy` 销毁登录页正在使用的实例，返回后永远「未挂接」（UI-019 卡住的根因）。其二，全局统计附件会把上一轮已发送的图片当成本轮附件，连续两图时可能不再上传新图。其三，冷启动首轮发送按钮尚未渲染，旧脚本先派发 Enter 再找按钮，`parseSubmitResult` 却返回成功，形成假提交——后续等待只会超时。

**约束**：任何新页面/诊断入口都必须经 `WebViewHost.get(context)`，不得 `WebView(context)` 自建；父容器变更必须集中实现，页面代码不得直接 `addView` / `removeView`。附件与提交的判据只能来自 DOM 读数；新增站点适配器若 DOM 层级不同，须在 `verifyAttachmentScript()` 中显式限定作用域，不能退回全局计数。

**状态**：Active

---

## ADR-0016 · 2026-10-30 · 附件必须在填文本之后、提交之前确认；上传只走已验证路径

**决策**：

1. `SiteAdapter` 新增 `uploadsAfterFill`、`fillComposerScript()`、`submitComposerScript()`、`uploadMenuIconScript()`；生产流程固定为「填文本 → 上传并确认附件 → 点发送」。`fillAndSubmitScript()` 仅保留给探针与兼容路径，ChatGPT / Gemini 的生产识牌不再使用。
2. 上传成功后只重试点击发送，**禁止重填文本**；重填可能覆盖已挂附件，也违背「附件与文本属于同一条消息」。
3. 上传策略只允许适配器 `supportedUploads` 声明且真机验证过的路径。Gemini 使用 `FILE_CHOOSER`（原生触摸 `Upload and tools → Files`），ChatGPT 暂用 `PASTE`；合成 paste 在离屏宿主没有 `document` 焦点时会被站点丢弃，不能作为默认路径。
4. `FILE_CHOOSER` 必须先调用 `prepareFileChooserUpload()` 写缓存文件、设置 pending URI、原生触摸入口，再轮询附件预览；只验证预览而不准备上传视为缺陷。
5. 附件判据必须只匹配真实预览（如 `uploader-file-preview`、`gem-media-attachment`），禁止使用 `[data-test-id*="upload"]` 之类会命中工具栏按钮的宽选择器。
6. 页面给出的上传拒绝原因（额度用尽、未获得焦点）必须透传到 `UploadFailed`，不得统一报「未确认附件预览」；额度用尽时立即失败，不空等轮询超时。

**理由**：两份网页图片直传文档的共同不变量是「文本先进入 composer，附件随后附加，最后提交」，且附件成功必须由页面预览独立确认。Android 离屏宿主的 `document.hasFocus()` 为 false，Gemini 会对合成 paste `preventDefault` 却不挂附件；同时旧附件选择器命中了 6 个上传工具栏按钮，令 `verifyAttachment` 恒为 true。两者叠加会让流水线在“没有图片”的情况下提交纯文本，模型输出无法对应输入图。

**约束**：新增网页源若上传入口是二级菜单，必须提供第二次点击坐标并等菜单动画完成；附件坐标会随输入/布局变化，取坐标前必须等布局稳定，菜单打不开要重新定位而不是盲点。附件确认、提交动作与失败分类的证据必须来自 DOM 读数，不得用脚本 `dispatchEvent` 返回值或“已点击”冒充成功。

**状态**：Active

---

## ADR-0017 · 2026-10-30 · AI 源配置使用独立 Activity，健康检查进程级串行

**决策**：

1. AI 源配置的编辑面从 `AlertDialog` 改为独立 `ui/AiSettingsActivity`（全屏、竖屏、`exported=false`）；`MainActivity` 只保留 `startActivity` 入口，不再持有配置表单。
2. 纯编辑逻辑抽到 `AiSettingsEditor` / `AiSettingsValidation`，Activity 只负责渲染、确认框、登录跳转与健康检查；不为 UI 复制 `AiSettingsStore` 的持久化或迁移规则。
3. 网页源的健康检查只针对**当前激活且已保存**的配置；非激活配置只显示「未选用」，不触发共享 WebView 导航。
4. 主界面与本页的 `healthCheck()` 统一经 `HealthCheckGate.run` 串行执行，避免冷启动时两边同时 `ensureLoaded()` 销毁/重挂同一个 WebView。

**理由**：弹窗在键盘与小屏下挤压字段，且源、配置档、激活配置三者关系不可见；独立页面让「编辑哪一份、是否已生效、能否登录」各自有位置。共享宿主是既有约束（ADR-0015），并发检查会让页面状态与激活源互相打脸，必须在入口处收敛。

**约束**：新增配置入口不得再建第二套弹窗或直接写 SharedPreferences；网页源不得出现 API 字段；删除最后一份配置由编辑器拦截，不依赖 store 的 defaults 兜底；`HealthCheckGate` 只允许后台线程调用，禁止在主线程包住可能等待十几秒的就绪流程。

**状态**：Active

---

## ADR-0018 · 2026-10-30 · 网页登录入口不随健康态隐藏

**决策**：AI 源管理页对当前激活的网页源始终显示登录入口；健康态 Ready 时文案为「网页登录 / 切换账号」，其余状态为「打开网页登录」。健康态只决定提示文案，不决定入口是否可见。

**理由**：健康检查的 Ready 只说明页面和输入框可操作，不能证明当前账号仍有效；全新安装没有 Cookie、页面把登录页误判为可操作、或用户需要切换账号时，隐藏入口会让用户无路可走。登录仍强制走 `AiProbeActivity` 的共享 WebView，不引入外部凭据或第二套 Cookie；正式入口使用 `EXTRA_LOGIN=true` 的登录模式，隐藏诊断按钮与日志。

**约束**：非激活配置的登录按钮仍禁用（共享 WebView 只有一个页面）；网页源之外不显示该入口；登录返回必须触发一次健康重查，不能假设已登录。

**状态**：Active

---

## ADR-0019 · 2026-10-30 · 主界面 singleTask：再次启动复用实例，不新建窗口

**决策**：`MainActivity` 声明 `android:launchMode="singleTask"`，并在 `onNewIntent` 里 `setIntent` 后补一次 AI 源状态刷新。

**理由**：manifest 原先未声明 launchMode（默认 standard）。桌面图标（MAIN/LAUNCHER）通常会复用已有任务，但快捷方式、服务卡片、深链等 Intent 形状不同的入口会各自新建 `MainActivity` 实例叠在同一任务里——真机连续两次自定义 action 启动让任务 `sz` 从 1 → 2 → 3，屏幕上出现第二个「主窗口」。主界面还是进程级 `WebViewHost` 的挂接方，多实例会互相抢同一个挂接目标。

**约束**：singleTask 下从图标再次进入会清掉主界面之上的页面（如登录页 `AiProbeActivity`），这符合「图标回到主界面」的预期；WebView 与登录态属于进程，不会因此丢失。子页面不要改成 singleTask。

**状态**：Active

---

## ADR-0020 · 2026-10-30 · 手工拆牌复用评分层，最优结果只读对照

**决策**：新增手工拆牌页（`doc/patterns_design.md`，原「拆牌结果调整」）。**修订（2026-10-31）**：UI 文案统一为「手工拆牌」；`Adjusted.base` 改为完整 `HandResult`，恢复最优不重跑搜索。分区用 `MeldGroup(indices, type)` 表示，分值由 `MeldEvaluator` 从 `HandScorer.candidates()` 抽出后统一生成，`PartitionEvaluator.summarize` 与优化器共用 `controlPercent`/`isLowWaste`/α/惩罚口径；`ScoreState.Success` 增加 `ScoreOrigin`（`Optimal` / `Adjusted(base)`）。页面固定两列：左侧「最优拆牌」只读（评级/SPI/NSPI/分子/轮数），右侧「手工拆牌」实时计算；应用后主界面来源行保留 `手工拆牌 · 最优 X → Y（差值）`，最优分值不被覆盖。

**理由**：同一手牌存在多种拆法，用户需要可对比的 SPI，而不是改手牌绕过。若在 UI 或新文件里重写牌型公式，两条路径必然漂移，且无法解释「为什么手工结果与重算结果不一致」。把评分集中在 `scoring` 层，UI 只提交分区、只渲染 `HandResult`。

**约束**：手牌或级牌变化时 `Adjusted` 与 `Optimal` 一样变为 `Stale`；手工结果不持久化（仅 `onSaveInstanceState` 抗重建）；不新增依赖（不引入 RecyclerView/Compose/拖拽库）；优化器默认 15 秒 / 15 万状态（ADR-0025 已取代本条原「3 秒上限」），未穷举时结果是近似最优，手工方案可能更高，UI 如实显示而不是钳制；最优摘要只由主界面投影，子页面不得重算或改写。`PartitionEvaluator.resolve` 对同一物理牌组的同类型多条解释必须取最高分（例如 5 张自然 A 必须解析为 5 炸，不得退化为 4 炸）；这是手工复算与最优结果等价的硬条件。

**修订（2026-10-31，真机）**：返回确认的「保留并返回」不是「关掉弹窗继续编辑」，而是应用当前分区后返回（`RESULT_OK` + `groups`）；架构没有跨页面草稿，否则该按钮会静默丢弃用户修改。手工页的勾选只由 `state.selection` 维护，组行 CheckBox 必须关闭 View 状态保存（`isSaveEnabled = false`），否则 Activity 重建时框架会在 `onCreate` 之后用旧 View 状态覆盖刚恢复的选择。`ScoreState.None/Running/Failed` 必须隐藏结果卡里的手工入口，不能沿用上一次成功状态的可见性。

**状态**：Active（2026-10-31 真机验收：MP-035 ①–⑥ 全通过，MP-036 ⑦⑧⑨⑩ 通过；MP-019/022 已补验；任务清单 37/37，见 `doc/patterns_tasks.md`）

---

## ADR-0021 · 2026-10-31 · 三带二自然级牌按 15，映射区间扩到 16–103

**决策**：三带二的候选分统一由 `HandScorer.fullHouseScore(triple, pair)` 计算：自然级牌（含用于级牌自身的逢人配）按 15，逢人配替代非级牌点数时按替代点数；原始分 `5×三张 + 2×对子` 从 `16..103` 线性映射到 `50..191`（原 `16..96`）。

**理由**：旧实现把级牌按面值 2 计入，导致级牌 2 时 `222+33` 仅 50 分，反而低于 `AAA+33` 的 156，与「级牌按 15」及牌理直觉相反。最大原始分随级牌出现（三张级牌 + 对 A = 103）；若只改 15 不改映射，会得到 203，压过最小 4 炸 192，破坏层级。扩到 103 后最大值恰为 191。

**约束**：逢人配替代非级牌时不得按 15 计；三带二最大值必须保持 < 192；非级牌三带二因分母由 80 变 87 会整体略降（如 AAAKK 191→180），属预期代价，不得再加第二套映射。

**状态**：Active

---

## ADR-0022 · 2026-10-31 · 炸弹中的逢人配按替代点数计

**决策**：炸弹的 `Sum` 改为 `n × V`（`V = base(rank)`）：自然级牌仍按 15，逢人配只按它所替代的点数计。4～10 张炸弹统一适用，含配炸弹与同点数、同张数的自然炸弹完全同分。

**理由**：旧实现把逢人配按 15 计入 `Sum`，三张5+配得 220，反而高于四张6的 216；同样是「5 张 2 + 配」与「6 张 2」不同分。逢人配只是替代牌，牌力应由替代点数决定，否则低点数炸弹会靠配牌越过更大点数的炸弹。

**约束**：4 炸下限 192、5 炸下限 298、最小 6 炸 538 不变（自然炸弹未受影响）；不得再用 15 抬升含配炸弹。6 炸以上表值与公式的既有偏差仍按 [`doc/conflict.md`](../doc/conflict.md) 待裁决，本次未改公式常数。

**状态**：Active（部分约束由 ADR-0023 接续：6 炸以上偏差改由 V4.1 重标定处理，逢人配语义不变）

---

## ADR-0023 · 2026-10-31 · 炸弹层级重标定 V4.1（已落地）

**决策**：采用 [`doc/conflict.md`](../doc/conflict.md) §8 的方案 B，以公式为准重标定 6～9 张炸弹常数：6 炸 `523 + 55·log₂`、7 炸 `577 + 73·log₂`、8 炸 `672 + 75·log₂`、9 炸 `784 + 66·log₂`，10 炸不变；4/5 炸、同花顺、火箭不动。`doc/掼蛋牌型量化评分方案.md` 升为 **V4.1**，`doc/手牌综合牌力评估方法.md` 的 F_i 区间与 README 同步。文档先行，代码与测试同日落盘。

**理由**：V4.0 的表格值与公式长期不一致（6 炸表 531/595 vs 公式 538/650），且 6/7、7/8、8/9、9/10 四处张数层级倒挂。方案 B 保留对数平滑结构，让每级最小值严格大于上一级最大值，并与火箭 1000 留出余量（10 炸可实现上限 987）。

**落地（2026-10-31）**：`HandScorer.bombScore(count, value)` 集中实现 4～10 炸公式；6 炸 K **593**、7/8 炸 2 **601/700**、9 炸 8张2+1配 **810**、10 炸 8张2+2配 **912**，4/5 炸与火箭不变。`ScoringV4Test` 更新期望，并新增 4～10 炸区间/严格单调/火箭封顶属性测试；全量 39 类 / 392 项 0 失败。

**约束**：6 炸最小 538 > 同花顺最大 502，7 炸最小 601 > 6 炸最大 600，8 炸最小 700 > 7 炸最大 690，9 炸最小 810 > 8 炸最大 799，10 炸最小 912 > 9 炸可实现最大 895，10 炸可实现最大 987 < 火箭 1000。评级阈值仍按文档保留 V4.0 并加注；用历史 27 张样例重标定是后续独立任务，本轮未声称完成。

**状态**：Active

---

## ADR-0024 · 2026-10-31 · 级牌对子/三张的逢人配按 15

**决策**：`HandScorer` 新增 `pairScore(value)` / `tripleScore(value)`，统一走 `baseValue`：自然级牌与用于级牌自身的逢人配按 **15**，替代非级牌时按替代点数。候选生成的自然牌路径与逢人配路径共用同一公式，不再走 `3v/5v`。

**理由**：`assignments` 路径原先用 `3×v` / `5×v`，级牌 2 时「一张级牌 + 逢人配」的对子 raw=6（20 分），「两张级牌 + 逢人配」的三张 raw=10（30 分），与 §二「级牌独立或替代级牌按 15」及自然牌路径（45→168、75→191）不一致。

**约束**：对子上限仍为对大王 191；三张上限仍为三张级牌 191；逢人配替代非级牌时不得按 15 计；SPI 仍按拆牌方案选优，级牌单张 α=1.15，因此对子/三张候选分不代表最终一定采用该拆法。

**状态**：Active

---

## ADR-0025 · 2026-10-31 · 最优拆牌改为 anytime 搜索（15 秒 / 15 万状态）

**决策**：`HandScorer` 的拆牌优化器改为三层：5 种贪心策略保底 → 可中断的精确覆盖 DP（最多 15 万状态，完成即全局最优并提前返回）→ 未完成时按 512/2048/8192 逐级加宽束搜索。束搜索保留一半名额给炸弹/火箭覆盖牌数最多的分支，另一半按综合上界。总时间预算从 3 秒提高到 **15 秒**，`HandResult` 新增 `exhaustive`，UI 摘要标注「已穷举最优 / 近似最优」。任何情况下都返回完整合法拆牌，不再把剩余牌退化为单张。

**理由**：旧实现达到 3 秒 / 12 万状态后，把未穷举子树按单张收尾；基准显示单纯加时间几乎不改变结果（状态上限先触发），而结果会因退化明显变差。把状态上限提高到 15 万并保留 DP 的部分结果，再用「炸弹导向」束搜索补足，可在不引入新依赖的前提下显著改善密堆手牌；JVM 基准中 8/8/8/3 级牌 2 从 456.54 升到 678.21，6/6/6/6/3 从 355.23 升到 534.19，6 组同点密堆从 403.50 升到 507.38。

**约束**：`exhaustive=true` 时结果必须与精确 DP 的全局最优一致；`false` 时只能称 anytime 近似最优，UI 不得隐藏该标记。搜索时间上限 15 秒、DP 状态上限 15 万不得随意上调（内存/耗电）；牌型候选生成、α、P、SPI 公式不变。时间上限与状态上限同时存在，改任一参数都要重跑 `HandScorerSearchTest` 的预算单调与覆盖完整用例。

**状态**：Active

---

## ADR-0026 · 2026-10-31 · 评分方案升 V4.2：三带二允许王对，分母 93

**决策**：`doc/掼蛋牌型量化评分方案.md` 升为 **V4.2（文档先行）**。唯一改变分值的规则是三带二：允许对子为一对大王或一对小王，最大组合由 `222+AA`（$S_{max}=103$）变为「三张级牌 + 一对大王」（$Sum=79$，$S_{max}=109$），映射分母 **87 → 93**。其余牌型（单张/对子/三张/顺子/连对/钢板/4~10 炸/同花顺/火箭）公式与常数一律不变。同时补齐 §2 牌组与红心配定义、§2.3 A 高低位、连对/钢板最多 6 张、9/10 炸最小值口径（跨局全局最小值）。

**理由**：旧版认定三带二最大牌型是级牌 2 时的 `222+AA`，但掼蛋允许王对作对子，最大值被低估 6 分。若不改分母，「三张级牌 + 一对大王」会算到 50+93×141/87 ≈ 200，越过最小四炸 192，直接破坏「4 炸压制所有普通牌型」的核心公理。改成 93 后端点精确命中 191（因 191−50 = 141 = 109−16）。

**约束**：① 最大映射值必须仍为 191，最小四炸 192 不变；② 不得为三带二保留第二套映射；③ 分母扩大使非王对三带二整体回落（222+AA 191→**182**），属预期代价；④ A 本身是级牌且在 10JQKA 中的取值（14 或 15）未定，顺子 $S_{max}$ 暂按 74，裁决前测试不得断言该组合；⑤ 代码未落地前不得声称 App 按 V4.2 计分，落地清单见评分方案 §八。

**状态**：Accepted（文档已更新，代码/测试待落地）

---

## ADR-0027 · 2026-10-31 · 取整与边界：普通牌型四舍五入、炸弹对数项下取整

**决策**：评分方案新增 §七「统一取整规则」并作为唯一口径——普通牌型线性映射用**四舍五入**（.5 向上）；4/5 炸与同花顺为纯整数；6~10 炸对 $\log_2$ 项取**下取整 $\lfloor\cdot\rfloor$**（offset 在取整之外）；火箭为常量。同时新增 §5.1 边界逐级复核，明确 4 炸 vs 普通（192 vs 191）与 7 炸 vs 6 炸（601 vs 600）两处余量仅 **1 分**。

**理由**：两种取整混用会让文档表值与公式可复现值对不上（旧文档 6 炸表 531/595 与公式 538/650 的冲突即源于此）。下取整保证各级最小值恰为公式值且单调；四舍五入保证普通牌型两端点精确命中 $F_{min}$ 与 191。边界复核把「1 分红线」显式写出来，避免后续随意上调普通牌型上限或 6 炸系数。

**约束**：① 浮点对数须加微小量再下取整（`floor(v + 1e-9)`）或用整数域比较；② 不得对加权项提前取整；③ 改取整规则必须同步评分方案 §四 的「最小值/最大值」列与 §5.1 余量表；④ 单测必须覆盖上述两处 1 分边界，且不得假设炸弹分值是满射（每个张数只有 13~14 个可达值）。

**状态**：Active

---

## ADR-0028 · 2026-10-31 · 综合评估方法以 V4.2 为基线重写，残局控制比取代 90% 必胜

**决策**：`doc/手牌综合牌力评估方法.md` 整篇重写（565 行，替换原 V4.0 改进版）。要点：① 牌型分值一律引用 V4.2，本文不重算；② 阶段边界统一为 **N≥10 用 SPI、N<10 用控制覆盖比优先**，消除原「10~12 张既属残局又属 SPI」的冲突；③ 定义 $N_{control}$（按控制型出牌单元计数，非牌张数）与 $R_{control}=N_{control}/(T_{plan}-1)$，**删除**原「$R_{control}\ge1$ 即 90%+ 必胜」的表述，改为「控制覆盖较充足」的数量型启发，并单列 $T_{plan}=1$ 的处理；④ SPI 评级阈值降级为「经验参考」，19~26 张与 10~12 张标记为未标定、不输出正式等级；⑤ 修正示例：示例 1 由「27 张」更正为实际 22 张（SPI 139.74），示例 2 补计散牌惩罚（SPI 149.30，$R_{control}=1.00$）。

**理由**：原文档存在三处硬伤——示例牌数虚报（22 张标成 27 张）、示例漏算散牌惩罚、$N_{control}$ 未定义却给出胜率结论；同时 SPI 阈值声称「符合正态分布」但无样本支撑。把不确定的东西标成不确定，比给出看似精确的数字更有利于后续标定。

**约束**：① SPI 只解释为结构强度指标，不得换算成胜率或必胜概率；② 未标定区间不得输出正式评级，也不得把相邻区间阈值外推；③ $\alpha=1.15$ 只用于规定的最终出牌单元，红心配替代普通牌型时不额外加成，单张小王按 1.00；④ $N_{waste}$ 按出牌单元计数，小对子计 1 个而非 2 个；⑤ 本文是目标口径，App 当前未实现残局控制比、王对三带二与 V4.2 分母，落地前须按 §9.3 的 T01~T16 补测试。

**状态**：Accepted（文档已重写，代码待落地）

---

## ADR-0029 · 2026-10-31 · 正式术语统一为「红心配」

**决策**：所有新增与维护中的文档、代码注释、测试注释统一使用「**红心配**」，不再新写「逢人配」。README、`harness/CRITICAL.md`、`harness/memory/scoring-rules.md`、`HandScorer`/`MeldEvaluator`/`MeldScoring` 注释与两个评分测试注释已同步；每处保留一处旧称对照说明。`harness/DECISIONS.md` 与 `doc/conflict.md` 中的历史条目**保持原文不改**（历史留痕）。

**理由**：用户明确要求全文统一「红心配」。混用两套术语会让新读者误以为是两种不同的牌，也会让 grep 检索漏项。

**约束**：① 历史 ADR 与冲突记录不回溯改写；② 代码逻辑不变，只改注释与文案；③ 若未来新增 UI 文案，必须用「红心配」。

**状态**：Active

---

## ADR-0030 · 2026-10-31 · V4.2 落地：三带二 93 分母与王对、残局控制比、待标定评级

**决策**：按 `doc/掼蛋牌型量化评分方案.md` V4.2 与 `doc/手牌综合牌力评估方法.md` 更新代码：① `fullHouseScore` 原始分区间 `16..103 → 16..109`（分母 87 → 93，`222+AA` 191→182，「三张级牌+一对大王」=191）；② 三带二候选允许一对小王/大王，王对只取实体牌（红心配不得替代王）；③ `HandResult` 新增 `control`（`N_control`）、`controlRatio`（`R_control`）、`stage` 与 `待标定` 评级；④ 散牌惩罚取消 `N≥10` 豁免，按出牌单元对全部张数计（综合评估 §4.3 与示例 2）；⑤ 主界面结果卡增加阶段与控制覆盖比。

**理由**：文档是评分唯一真源。V4.2 的分母变更使 `222+AA` 不再是最优分区（整手会改拆「三张级牌单张 α=1.15 + 对 A」），因此单测改为校验牌型候选分而非最优分区；评分表值（182/191）与公式可复现值逐位对齐。

**约束**：① 191/192 与 600/601 两处 1 分红线不变，单测必须覆盖；② 王对不得由红心配补齐；③ `R_control` 只在 `T_plan≥2` 时计算，`T_plan=1` 返回 `null` 并在 UI 标注单次出牌方案；④ 19~26 与 10~12 张不得输出正式评级、不得外推相邻区间；⑤ 9/10 炸最小值断言须写明所设级牌（级牌为 2 时 821/922）。

**状态**：Active（代码、测试、README、CRITICAL 已同步）

---

## ADR-0031 · 2026-11-02 · 删除 licenses.json 与 dataset-license 门禁

**决策**：按用户要求删除 `model_training/datasets/licenses.json` 与 `model_training/approvals/dataset-license.json`，并移除 `dataset-license` 在 T1.1/T1.4/T1.5/X1 与 `gate.py` 中的阻断逻辑。任何外部数据都可以加入本项目，许可字段只做登记。

**理由**：用户明确要求取消数据准入的许可门禁，保留登记是为了让来源可追溯，而不是作为准入条件。

**约束**：① `license_audit.py`（X1）仍记录来源与许可，缺字段只告警，不再 `BLOCKED`；② Ultralytics AGPL 权重仍不随 APK 分发（分发约束保留）；③ `golden-definition` 等其他签核不受影响。

**状态**：Active（脚本、run_all、文档已同步；未 commit）
---

## ADR-0032 · 2026-11-02 · 真机照片评估以 App 的 18-pass 配方为准

**决策**：离线评估真机照片统一使用 `model_training/draft_labels.py --pass-plan app`：原图 + 8 个整图旋转（-60/-40/-20/20/40/60/90/180）+ 3×3 个 55% 切片（小图 2.4×、大图 1.8×），分类 0°/180° TTA；阈值扫描用 `--conf-sweep`/`--gate-sweep`，检测只跑一次最低阈值。

**理由**：旧离线配方（0°/90°、单切片）与 App 实际 18 个 pass 不一致，系统性低估召回。同分类器、同门控（det 0.6 / cls 0.5）下 T2.2v2@960 从 11/242 升到 35/242，最优工作点 84/242。

**约束**：① golden 只用于评估，不得用于调参；阈值写回 manifest 必须在独立 calibration 集上标定；② App 仍使用 T2.2（640 训练、960 导出），换用 T2.2v2 属于发布变更，T4.7 真机验证仍 NOT_RUN。

**状态**：Active（工具、四组 sweep 与基线已落盘；App 资产未改）
## ADR-0033 · 2026-11-02 · 牌角 crop 以整牌几何遮挡过滤，不用牌角框 IoU

**决策**：`prepare_crops.py` 按绘制顺序计算后画整牌对当前牌角四边形的多边形覆盖比例，默认过滤覆盖 >0.2 的样本；`visible` 不再只依赖牌角框 IoU。

**理由**：T3.5 旧 946 组 test 的 7 个“错例”中，6 个（已部署模型）的牌角被后画整牌覆盖 49%–78%，crop 实际显示覆盖牌；标签继承正确但可见性错误。这是数据定义 bug，不是分类器回退，也不能靠调阈值修复。

**约束**：① 过滤阈值 0.2 是几何规则，未用 test 结果调参；② 旧 FAIL 报告保留，新 PASS 在 `T3.5c`；③ test 仍全部为合成 crop，真实照片逐牌角 test 仍缺，T5.3 不得因此算通过。

## ADR-0034 · 2026-11-02 · 公开整牌框只能做 T3.5r 真实域预检，不能替代 T3.5/T5.3

**决策**：新增 `model_training/real_pretest.py`，从 RF100 Poker Cards 与 TeogopK real 的整牌 bbox 为每张牌生成四个角候选 crop，`predict.py` 按 `group` 合并同一张牌的候选，分类器只按置信度选择；报告固定为 `T3.5r`，明确 `release_eligible=false`、`corner_truth=false`。

**理由**：公开数据可以自动替代“拍摄 + 整牌框标注”的一部分，但没有逐牌角/可见性真值；如果直接把 bbox 当牌角真值，会把裁剪错误伪装成模型错误，也可能被误用为发布证据。T3.5r 用来量化真实域差距，而不是替代发布 test。

**约束**：① 不得读取 expected label 选择候选；② 不得用 T3.5r test 调分类器阈值；③ 不得把 T3.5r 计入 X3 门禁或声称 T5.3 通过；④ 数据来源、许可、bbox 派生标记写入 manifest；⑤ 已部署资产不变。

**结果（2026-11-02）**：868 牌 / 6,944 crop，已部署分类器 exact 0.104839（RF100 0.125、TeogopK 0.071），ECE 0.097854；模型评估 FAIL，保留为真实域下限诊断。

**状态**：Active（脚本、文档、状态已落盘；未 commit）
## ADR-0035 · 2026-11-02 · testcases 8 张转训练、case13 唯一校验；伪框不等于真值

**决策**：按用户决定，`testcases/` 的 `case4/case10/case116/case12/case14/case18/case19/case21` 转为训练候选，`case13` 是唯一校验实例；切分写入 `model_training/datasets/testcases_split.json`，8 张在 `golden_truth.json` 标 `role=training`，`golden.py --cases all` 只校验 case13。训练用 `pseudo_from_testcases.py` 把检测器伪框与人工牌面清单匹配，只保留仍有剩余同类标签的框，并固定 `approval=pseudo`、`needs_review=true`。

**理由**：没有逐牌角人工四点，只有牌面清单；用户选择用检测器伪框补训练数据。这样能增加真实域样本，但伪框可能跨牌/重复，不能当作检测或发布真值。把 8 张移出 golden 是为了避免训练/验证泄漏。

**约束**：① `case13` 不得进入训练、校准或调参；② 伪框训练报告必须标 `NOT_RUN`/非发布，不得计入 X3 或关闭 T5.3；③ 不得用 `case13` 调阈值或选择模型；④ 55 个 crop / 29 类只是候选，真实域覆盖不足；⑤ 不替换 `app/src/main/assets/models`。

**结果（2026-11-02）**：344 候选 → 55 crop / 29 类；81 个框因无剩余人工标签丢弃。合成 5,104 + 55×10 训练，val exact 0.993492。`case13` 新模型最佳 F1 0.5000（14/27），旧 0.4643（13/27）；App 门控下均 5/27。T0.5 新口径 1 图/27 牌 PASS；T5.3 仍 NOT_RUN。

**状态**：Active（数据、训练、对照与 T0.5 已落盘；未 commit）

---

## ADR-0036 · 2026-11-02 · 三阶段单牌识别先用整牌几何，不用轴向牌角框

**决策**：新增 `model_training/card_engine.py`，把识别拆为：① 检测整牌旋转四边形；② 沿整牌短边/长边构造牌角平行四边形；③ 拉正后分类点数和花色。分类阶段默认尝试四个角候选并做 0°/90°/180°/270° TTA；几何阶段不依赖 TensorFlow。

**理由**：原流水线只输出牌角 OBB，牌角框与整牌角度没有显式几何约束；牌一大、旋转一多，框就可能切到花色或邻牌。先拿到整牌边/角，再用平行边生成框，旋转和裁切才有可验证的几何前提。

**约束**：① 当前第一阶段仅支持单张完整可见牌，白牌分割不适合多牌重叠，手牌必须另训整牌 OBB；② 不替换 Android 已验收的 `CardCornerDetector`/资产，也不构成 T4/T5 发布证据；③ 整牌检测器训练前，不用该模块生成训练真值；④ 合成单牌 CLI 与 9 项几何/分类单测只是链路验证，不是准确率验收。

**状态**：Active（模块、单测、README、状态已落盘；未 commit）

---

## ADR-0037 · 2026-11-02 · 手牌第一阶段改为局部 L 形牌角，不检测整牌

**决策**：`card_engine.py` 新增 `detect_card_corners()`：在一手牌中用 LSD 找任意方向的两条近似垂直短边，求交后枚举四个象限，按白牌象限确定顶边/左边，输出局部 `CardCorner(point, top, left)`；`build_corner_box_from_corner()` 用顶边和左边共同拟合正交方向（两条检测边的偏差各承担一半），生成真正的 96×128 长方形；`HandCornerEngine` 对候选逐个分类。**取代 ADR-0036 的“先检测完整整牌四边形”第一阶段**；`detect_single_card()`/`SingleCardEngine` 仅作为完整单牌 fallback。

**理由**：手牌中大多数牌只露出左上角，整牌轮廓被相邻牌遮挡；画框只需要知道牌角处顶边和左边的两个方向，不需要整条边或整张牌。局部 L 形几何可直接从短线段得到，避免把“整牌检测失败”误认为“牌角不存在”。

**约束**：① 牌边可任意方向，不按图像水平/垂直筛选；白牌象限和左手性 `cross(top,left)>0` 只定局部轴，同一张牌的其他可见角也会成为候选，需第三阶段排除；② `case10` 当前输出 80 个候选（达到默认上限），是候选集而非逐张计数或准确率，仍需第三阶段置信度、NMS/门控筛选；③ 尚未用真实分类器验证，不替换 Android 生产路径，也不构成 T4/T5 或发布证据；④ 15 项单测覆盖任意方向 L 角、两条检测边共同约束长方形朝向、四向 TTA 和旧单牌路径，不覆盖真实手牌全量召回。

**修订**：牌角框构造已由 ADR-0038 取代；本 ADR 的第一阶段局部 L 形检测仍然有效。

**状态**：Superseded by ADR-0038（牌角框构造部分；局部 L 形第一阶段仍 Active）

---

## ADR-0038 · 2026-11-02 · 手牌牌角框改为透视四边形 + 单应性拉正

**决策**：`build_corner_box_from_corner()` 不再拟合 90° 矩形，而是把检测到的顶边、左边作为两条**精确邻边**构造透视四边形：`P0=角点`，`P1=P0+top*width`，`P3=P0+left*height`，`P2=P0+(1-perspective)*((P1-P0)+(P3-P0))`。默认 `perspective=0.08`；`0` 退化为平行四边形，取值限制为 `[0,0.5)` 以保持凸性。`warp_corner()` 用 `cv2.getPerspectiveTransform` 将该四边形单应性拉正到 96×128。**取代 ADR-0037 的矩形拟合部分**；第一阶段局部 L 形检测不变。

**理由**：验收要求两条检测边严格重合。严格矩形的相邻边必须垂直，而非垂直的两条检测边无法同时成为矩形邻边；透视四边形保留两条原始边，由单应性负责把四边形拉正，分类器看到的仍是 96×128 长方形。

**约束**：① 只有两条边时第四个角不唯一，默认固定轻微透视先验只沿对角线收缩远端 `P2`，不影响两条检测边；`--perspective` 可调，不代表从图像估计出了真实消失点；② `perspective=0` 仍是平行四边形，不满足“透视”但仍保留两条原始边；③ `perspective` 必须 `<0.5`，否则四边形退化/非凸；④ 16 项单测覆盖两条边方向严格保持、非平行四边形、单应性映射、四向 TTA 和旧单牌路径，不覆盖真实手牌全量召回；⑤ 不替换 Android，不构成 T4/T5 或发布证据。

**状态**：Active（代码、测试、README、状态已落盘；未 commit）

---

## ADR-0040 · 2026-11-02 · T5.3p 公开真实图自动回归，不等于 T5.3 发布

**决策**：新增 `public_benchmark.py` 与 `public_e2e.py`，用 RF100 Poker Cards + TeogopK Real 的公开整牌框构建确定性真实图 benchmark（RF100 train→train、valid→calibration、test→test；TeogopK 全 test），报告任务固定为 `T5.3p`，`formal_t5_3=false`、`corner_truth=false`、`release_eligible=false`。同时修正部署 detector 契约：LiteRT OBB 输出顺序为 `cx,cy,w,h,score,angle`，App 旧代码按 `angle,score` 解码；旧资产还是陈旧 640 训练版。

**理由**：公开集没有 Joker、双副牌重复牌、27 张完整手牌或逐牌角真值，不能替代正式 T5.3；但它能全自动覆盖“检测器牌角 → 分类器 → 牌面多重集”的真实图链路，不依赖人工画框、`needs_review` 或签核。若不先修 OBB 通道顺序，评测会把角度当置信度，产生数千个假框，任何基线都不可信。

**约束**：① T5.3p 只读公开 manifest，不得用 test 调参；② 不得把整牌框当牌角真值；③ 不纳入 X3，不改变 App 默认 `HEURISTIC`；④ `joker_exact` 在无 Joker 真值时必须为 `null`；⑤ detector 资产替换必须同时更新 manifest hash/size、`output_order`、Kotlin/Python 解码与回归测试。

**结果（2026-11-02）**：test 221 场景 / 868 牌；基线 per-card 0.192483、full-scene 0.00905、review 0.27765、confident error 0.451299（FAIL）。检测器修正后 TFLite 与 T2.2v2 `.pt` 的框数逐图一致；分类器真实域准确率仍是下一瓶颈。

**后续实验（同日，候选未发布）**：新增 `public_crops.py`，从 RF100 **train** 的 320 图按整牌 bbox 与距角距离匹配出 915 个伪角 crop；从 `T3.6-real-mix/best.keras` 以 `5e-5` 微调 12 epoch 后导出。默认门（0.5/0.02）下 calibration per-card **0.114078**、unknown 81.9%，旧分类器 **0.221698**、unknown 44.9%。新增 `public_gate.py` 只在 calibration 扫描门控，选中 **0.1/0.005**：calibration per-card **0.273553**；同一门应用到 test 得 **0.242358**（旧模型 calibration 选门 0.3/0.02 的 test **0.221239**）。但 full-scene、review **12.788%**、confident error **49.315%** 仍远未达 T5.3，故**不替换 App 分类器资产**；候选与工具保留，test 只用于最终复测，门控不得在 test 上回选。联合扫描 detector score 仍以 **0.6** 最优；`testcases/case13` 肉眼 sanity check 仅 **4 个检测 / 1 个 known**。进一步确认 T2.2v2 只训练于 **2,000 张合成图**；四向+翻转 TTA 在 0.6 仅 4→8，0.1 才 15 但伴随 4 个蓝底假框且顶部仍漏检。**停止在公开/合成域继续调阈值或 TTA**；下一步必须补真实密集手牌训练数据，否则不接 App。

**状态**：Active（脚本、manifest、资产修复、单测与文档已落盘；未 commit）

---

## ADR-0039 · 2026-11-02 · 手牌候选按可见左上角与牌面墨迹门控

**决策**：目视对比 `case10` 叠加图与 27 个手工牌角框后，在 `detect_card_corners()` 中增加可见左上角先验（`top.x>=0.8`、`left.y>=0.8`，并限制两条边的斜率），收紧白牌/亮度采样；在 `HandCornerEngine` 中增加裁框白牌比例 `>=0.60`、黑/红墨迹比例 `>=0.018` 的门控。**不改变 ADR-0038 的透视四边形和单应性拉正**。

**理由**：叠加图里的主要误检来自桌面/木纹、朝外或 45° 的线对，以及空白牌边；这些候选的白牌/墨迹证据明显弱于真实点数/花色区域。先用低成本门控收缩候选集，再交给分类器。

**约束**：① 当前只保留手牌中直立的左上角，极端旋转/倒置牌会被先验排除；② `case10` 从 80 个候选降到 54 个，但仍不是 27/27，目视真值 30px 内约 13/27，漏检和错配仍存在；③ 18 项单测覆盖透视四边形、单应性、左上角先验和墨迹门控，不覆盖真实手牌全量召回；④ 不替换 Android，不构成 T4/T5 或发布证据。

**状态**：Active（代码、测试、README、状态已落盘；未 commit）

---

## ADR-0041 · 2026-11-03 · 牌角候选排序按“双长边支持”条件启用

**决策**：`select_corner_predictions()` 默认继续按 `ink, white, score` 排序；仅当同一张图内至少有 **15** 个候选同时具备两条 `>=50px` 的检测边时，才使用 `ink + 0.25*white + 0.5*normalized_edge` 的质量分，否则完全退回默认排序。`HandCornerEngine` 仍保留 80 个候选、25px NMS、top-27 输出。**只改 Python 离线参考实现的候选排序（不参与 Kotlin/Android App 运行时），不改 ADR-0039 的几何、门控和 Android 路径。**

**理由**：`auto_case53~82` 的 30 张生成图显示候选排序是主要瓶颈；无条件加入边长质量把生成图 top-27 从 393/810 提到约 488/810，但在真实已签核 case18/19/21 上从 32/82 掉到 26/82。真实图的真实牌角边通常更短、碎片更多，不能把生成长边当成通用证据。按整图双长边支持门控后，真实图保持 32/82，生成图仍提升到 471/810（+78）。

**约束**：① 门控阈值是生成域/真实护栏联合结果，不是发布证据；② 单候选 `min>=50` 在 80 上限下真实图降到 29/82，已拒绝；③ 只测几何候选，不包含分类准确率、Android/T5.3；④ `case13` 未用于调参，`testcases` 生成图不进入真实 golden。

**结果（2026-11-03）**：生成域 **393/810 → 471/810**；真实签核 **32/82（11/8/13）**；`test_card_engine.py` **28/28 PASS**；证据 `output/runs/20261103/corner-ranking/report.json` 与 `auto_case53-overlay.jpg`。

**状态**：Active（代码、单测、README、状态已落盘；未 commit）

---

## ADR-0042 · 2026-11-03 · learned 牌角最终策略：基础 0.6 + 短场景 0.3 known-only 补漏

**决策**：冻结 learned 牌角后处理为“基础阈值 0.6 + 短场景补漏”。`model_manifest.json` 新增 `fallback_score_threshold=0.3`；Kotlin `AdaptiveCornerPolicy` 仅在基础检测数少于 27 时运行 0.3 检测，低阈值候选与现有框按 IoU 0.5 去重，先分类且只有 known 才接纳，最终候选上限 27。`CardCornerDetector` 增加显式阈值入口，`LearnedCardRecognizer` 负责编排；默认引擎仍为 `HEURISTIC`。

**理由**：`auto_case23~82` 的 60 张生成图共 1,620 个牌角，固定 0.6 为 **1609/1620**；短场景 0.3 补漏并过滤 unknown 后为 **1615/1620、0 误检**，留出 **807/810**、调参 **808/810**，分类 exact **1.0**。继续降低阈值只会引入误检；生成域已接近饱和。真实 case18/19/21 没有逐牌角 quad，公开真实图也没有 27 张逐角真值，因此无法再提供有效的同口径评测，不能把继续扫参当成优化。

**约束**：① 该结果是生成域证据，不是 T5.3、真实 test 或发布证据；② `MainActivity`/默认引擎未切换到 learned，T5.3 仍 `NOT_RUN`；③ manifest 版本升到 `20261102.4`，模型文件 hash 不变；④ 若未来补到真实逐牌角/27 张签核数据，应重新评估 fallback 是否保留，而不是直接在旧数据上继续调参。

**状态**：Active（Kotlin、manifest、JVM 测试、状态与任务文档已落盘；未 commit）

---

## ADR-0043 · 2026-11-03 · 停止本地识别引擎自动化优化并冻结最终策略

**决策**：本地识别引擎优化到此为止。最终策略固定为基础 detector threshold `0.6`；仅当检测数少于 27 时用 `0.3` 补漏，低阈值候选按 IoU `0.5` 去重、仅接纳 known 分类，最终上限 27。该策略已落到 Kotlin `AdaptiveCornerPolicy` 与 `model_manifest.json`。不再进行阈值扫描、TTA、候选排序或合成域微调。

**理由**：生成域已饱和，固定 0.6 为 **1609/1620**，最终策略 **1615/1620、0 误检**；留出 **807/810**、调参 **808/810**。剩余漏检只有 5 个，继续降阈值开始引入误检。真实 `case18/19/21` 没有逐牌角 quad，公开真实图也没有 27 张逐角真值；在缺少真实密集手牌/逐牌角签核的情况下，继续自动化搜索没有可验证收益，只会过拟合生成域。

**清理**：`model_training/annotations.json` 删除 15 个无对应图片的 `IMG_20260809_*` key / 310 框，保留 9 图/242 框；删除 `model_training/output/obb_dataset_v2`、缓存和误建文件；清理 `output/` 下旧预览、临时预测、无日期 run、重复 sweep/check/crop 集及已放弃的 T5.3p 候选中间产物。保留最终 benchmark、模型/打包证据和当前训练数据。共释放约 **7.1 GB**。

**约束**：① 默认引擎仍为 `HEURISTIC`，`MainActivity` 未切换，T5.3 仍 `NOT_RUN`；② 本次清理不改变评分 V4.2、已部署模型 hash 或最终策略；③ 只有新增真实密集手牌、逐牌角可见性签核和 `release-qa` 后，才重新评估 learned 默认切换或恢复优化。

**状态**：Active（清理与冻结已落盘；未 commit）

**后续（ADR-0044）**：本条中“默认引擎仍为 `HEURISTIC`、`MainActivity` 未切换”的约束已被取消；learned 现在默认启用，模型失败仍回退启发式。优化冻结和 T5.3 未完成事实不变。

---

## ADR-0044 · 2026-11-03 · 取消 T5.3 默认启用门禁，learned 成为默认引擎

**决策**：① 取消“未过 T5.3 不得默认启用 learned”的约束；`RecognitionEngine.DEFAULT` 和 `CardRecognizer` 默认值改为 `LEARNED`。② `MainActivity` 通过 `ModelAssets.load()` 加载 APK 内模型，成功时把 `LearnedCardRecognizer` 与模型版本注入 `CardRecognizer`；加载失败则把原因带入 `learnedUnavailableReason`。③ 任何加载失败、manifest/hash 校验失败、推理异常、超时、牌角不足或可用牌少于 5 张仍回退启发式，并在本地识牌状态显示警告。④ T5.3 继续作为未完成的质量证据，不因本次启用而改写为 PASS，也不再把 T5.3 当作默认启用前置门禁。

**理由**：用户明确要求 learned 路径真正参与识别，并取消 T5.3 默认门禁。此前 learned 只被编译、未被生产入口调用；`MainActivity` 构造默认 `HEURISTIC` 且 `learned=null`，`ModelAssets.load()` 没有调用者，导致 `AdaptiveCornerPolicy` 只在 JVM 测试中执行。

**约束**：① 模型资产仍必须通过 `ModelManifest`/size/SHA-256 校验；不得绕过加载失败回退。② `VisualCardCodec` 条码快速路径仍优先于模型。③ `SHADOW` 语义不变：跑模型但不采用结果。④ T5.3、真实密集手牌、逐牌角可见性和 `release-qa` 仍未完成；不得把生成域 benchmark 或本次默认切换写成发布验收。⑤ 本次未在真机验证模型加载耗时、内存和识别效果；这些仍是未验证风险。

**取代关系**：只取代 ADR-0042/0043 中“默认引擎保持 `HEURISTIC` / `MainActivity` 未切换”的部分；最终牌角策略、优化冻结和 T5.3 未完成事实不变。

**状态**：Active（Kotlin 接线、默认值、测试与文档已落盘；未 commit）

---

## ADR-0045 · 2026-11-03 · 方案 A 补缺只在严格 A/B 证明收益后接入 HYBRID

**决策**：新增 `model_training/hybrid_eval.py`，比较 learned 单独、heuristic 单独、`learned + heuristic 只补缺`。补缺只添加 learned 尚未产出的牌面副本，不替换 learned，不使用真值选择补哪张。严格 A/B 必须输入真实 Android heuristic 预测 JSONL；桌面 `card_engine.py` 只能作为明确标记的 proxy，不能授权 HYBRID。

**理由**：用户希望通过 heuristic 补 learned 的漏牌，但必须先用同一批真实签核图验证 per-card、full-scene 和 confident error。9 张签核图的 proxy 诊断中，补缺把 per-card 从 **0.0992** 提到 **0.1322**，但 full-scene 仍为 **0**，confident error 从 **0.1724** 升到 **0.2558**；不满足接入条件。

**约束**：① 报告固定 `formal_t5_3=false`、`release_eligible=false`；② proxy 不得写成 Android heuristic 或 A/B；③ 严格 A/B 缺失时状态为 `BLOCKED`，不得用 proxy 伪造通过；④ 补缺不得替换 learned 牌、不得用真值挑选候选；⑤ 只有 per-card 与 full-scene 同时提升且 confident error 未明显增加，才考虑实现 `HYBRID` 运行时。

**状态**：Active（脚本、proxy 报告、单测与状态文档已落盘；严格 A/B 未执行；未 commit）

**后续（ADR-0046）**：按用户指令，App 已落地“只补缺”的方案 A；这不是本 ADR 所述需要严格 A/B 才启用的 HYBRID/冲突仲裁。

---

## ADR-0046 · 2026-11-03 · App 采用方案 A 的补缺实现，不启用冲突仲裁

**决策**：① `CardRecognizer` 在 learned 成功且牌数少于 27 时，再调用原 heuristic 路径；② 新增 `fillMissingCards()`，按 `(rank, suit)` 计数，只追加 learned 尚未产出的副本，最多两副（同牌面 2 张），不替换 learned 牌，不按真值挑选；③ 所有补入牌设置 `Card.needsReview=true`，手牌 chip 显示“需核对”，人工校正后清除；④ 补入数量计入 `RecognitionStats.unknown` 并产生警告；⑤ 保留 learned 失败回退 heuristic。

**理由**：用户明确要求实际采用方案 A，仅补缺，不做冲突仲裁；learned 仍是主结果，heuristic 只在不足 27 张时补漏。

**约束**：① 这不是 `HYBRID`，也不实现冲突仲裁或替换 learned 牌；② 两副上限和 27 张上限必须在补缺函数内保证；③ 本实现不构成 T5.3、真实 test 或发布验收；④ 本次只完成 JVM 测试、debug 构建和真机安装，未执行真机识图效果/耗时/内存验收；⑤ 未提交、未推送。

**验证**：`./gradlew :app:testDebugUnitTest :app:assembleDebug` 成功；**568 tests / 0 failures / 0 errors**；`adb install -r` 成功，设备 **LIO-N29（Android 10 / API 29）**。新增 `MissingCardFillTest` 5 项。

**状态**：Active（代码、测试、构建、真机安装已落盘；未 commit）

---

## ADR-0047 · 2026-11-03 · 识别调度以“已回收数 == 计划数”为完成判据，`pump()` 为唯一派发点

**决策**：① `RecognitionWorker` 增加 `isDrained`（队列空与在飞为 0 同一临界区判定），不再从外部组合读取 `inFlight`/`isExhausted`；② `CardRecognizer.heuristic()` 的 `pump()` 成为唯一派发点，`next()` 返回 null 即 `return`，不再落到循环后的完成判断；③ OCR 成功/失败回调只做 `release` + `settle()`，再在 `finished` 保护下调用一次 `pump()`；④ 完成判据由“在飞为 0”改为“已回收数 ≥ 计划数”，`settled` 只在 `release` 内自增。

**理由**：原实现中 `inFlight`/`isExhausted` 为两次独立加锁读取，回调线程与主循环交错时同一 pass 会被重复派发，`release` 次数大于 `next` 次数导致计数失衡；完成条件依赖两个变量同时成立的时序，任何一方先读到不满足就递归重派，`finish()` 永不执行。改为回收计数判据后，完成状态只依赖单调递增的计数器，与派发时序解耦。

**约束**：① 不得在回调中直接判定完成或重复派发；② `settled` 只能由 `release` 自增，禁止外部写入；③ 派发与回收必须在 `RecognitionWorker` 的锁内完成计数变更；④ 本次仅为时序缺陷修复，不改变识别算法、阈值或方案 A 补缺逻辑；⑤ 时序问题单测覆盖不到，真机验证为一次性回归证据，不构成 T5.3 或发布验收。

**验证**：`./gradlew testDebugUnitTest` 通过（24 项）；`assembleDebug` + `adb install -r` 到 **LIO-N29（Android 10 / API 29）** 成功；真机导入图片识别完成 **27 张 + 2 条警告**并正常结束。用户确认人工验证通过。

**状态**：Active（已提交并推送；未发布）
