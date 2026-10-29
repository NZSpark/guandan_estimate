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
