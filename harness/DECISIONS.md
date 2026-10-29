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
