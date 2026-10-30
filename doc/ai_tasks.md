# AI 识牌改造实施任务清单

> **依据：** `doc/ai_update.md`（Gemini 单源设计）、`doc/multi_ai_update.md`（多源设计，DeepSeek 首发）
>
> **目标：** ① 将在线识牌扩展为 Android WebView 网页源与 HTTP API 两种传输路径；② 把 AI 源做成配置可选，**DeepSeek 网页版为首发源**，同时支持 ChatGPT 网页版、Gemini 网页版、自定义网页源与 HTTP API。网页源复用网页登录态，不要求官方 API Key；HTTP API 保留现有配置与调用能力。同时保留本地 OCR、模板匹配、`VisualCardCodec`、现有 `Card` 领域模型和牌力评分功能。
>
> **当前状态（2026-10-30）：** 多源基础抽象层（`AiSource` / `SiteAdapter` / `PageDriver` / `WebViewAiSource`）、DeepSeek/Gemini/ChatGPT/自定义适配器及源选择配置均已落地；DeepSeek 网页源已在真机完成单图、连续两图、追问与离屏/可见三态切换闭环（`source_e2e` / `source_e2e_twice`）；Gemini 改用原生触摸文件选择后，离屏单图（9 张）与连续两图（10/27 张）均已跑通（AI-182）。ChatGPT 已真机登录，12:36 曾跑通一次 27 张带图识牌；随后测试账号**附件额度已用尽**（约 2026-10-31 12:36 恢复），无法再取附件成功证据，G-23~G-25 保持未勾选。所有任务以本清单执行记录和可核对的测试或设备证据为准，不因设计文档存在就勾选。
>
> **执行原则：** 严格按阶段推进。图片真实上传是核心准入条件；未验证成功前，不进入完整业务集成。GeminiBridge 与 DeepseekBridge 的网页自动化经验只作参考，不能把其他项目的选择器或上传行为视作目标 Android WebView 的既定事实。
>
> **多源决策（2026-10-28）：** DeepSeek 首发。`SiteAdapter` 抽象与驱动层必须最先落地（阶段 1.5），Gemini 适配器只是其中一个实现，不再是唯一实现。

## 任务总览

| 阶段 | 任务范围 | 完成标准 | 依赖 |
|---|---|---|---|
| 0 | 基线、环境和技术可行性 | 登录、真实图片上传及带图识别闭环得到实证 | 无 |
| 1 | WebView 宿主与页面适配 | 可复用 WebView，页面状态和 DOM 诊断可靠 | 阶段 0 的页面探测结论 |
| **1.5** | **多源抽象层（AiSource / SiteAdapter / 驱动层）** | **同一驱动支持 ChatGPT、DeepSeek、Gemini 适配器；驱动层无站点字样** | **阶段 1** |
| 2 | 单次识牌闭环（DeepSeek 首发，Gemini / ChatGPT 并行验证） | 对每个宣布支持的网页源验证真实附件、prompt、提交、新回答和牌面解析闭环 | 阶段 1.5；相应源的上传路径已在目标 WebView 验证 |
| 3 | 接入应用 UI 与设置（含 AI 源选择） | 现有入口可使用任一网页源，API Key 不再阻挡该模式 | 阶段 2 |
| 4 | 生命周期、恢复与并发 | 异常可恢复、任务可取消、回答不会串线 | 阶段 2–3 |
| 5 | 配置迁移、清理与发布验收 | 迁移安全，回归测试通过，文档完整 | 阶段 3–4 |
| 6 | 多源横向验收 | ChatGPT / DeepSeek / Gemini 三个网页源、HTTP API 兼容性、自定义源错误路径与会话隔离通过验证 | 阶段 1.5、2–5 |
| 7 | UI 全面重设计 | 工作台、手牌编辑、结果、源管理、登录/诊断及无障碍验收 | 阶段 3；与阶段 0/2 真机验证协同 |

---

## 阶段 0：冻结基线与验证核心可行性

**阶段目标：** 以 DeepSeek 网页版为首发源，验证目标 Android WebView 能复用网页登录态、接收当前应用中的真实图片，并依据图片像素识别牌面。保留 Gemini 已有闭环作为回归基线；ChatGPT 必须在自己的适配器与真机任务中独立验收。

### A0.1 基线与测试材料

- [x] **AI-001** 检查当前分支、工作区改动和项目文件；保留用户已有改动，不覆盖或清理无关文件。
- [x] **AI-002** 阅读 `doc/ai_update.md`、现有 `AiRecognitionClient.kt`、`AiSettingsStore.kt`、`AiRecognitionClientTest.kt`、`MainActivity.kt`、`AndroidManifest.xml`、Gradle 配置及 `README.md`。
- [x] **AI-003** 运行现有 `./gradlew testDebugUnitTest` 与 `./gradlew assembleDebug`，记录结果和已存在的失败。
- [x] **AI-004** 选取若干牌面测试图片，记录现有 AI 输出、本地识别结果和人工确认的正确牌面，作为迁移前基线。→ 结果：建立 `doc/ai_baseline.md`，选 `case3.png` 为主基线（27 张人工确认牌面，无遮挡）、`case4.jpg` 为现实场景辅助；记录三条输出通道，真机实测项留待填。
- [x] **AI-005** 确认项目的 `Rank`、`Suit`、大小王标记、牌数限制和重复牌处理规则，记录现有解析行为，不凭示例猜测枚举定义。

### A0.2 登录和 WebView 页面探测

- [x] **AI-006** 创建最小化 WebView 验证入口，能够访问 Gemini 页面并允许用户手动完成 Google 账号登录。（`AiProbeActivity`，真机已登录）
- [x] **AI-007** 验证 JavaScript、DOM Storage、Cookie 和登录会话的实际行为；分别记录 Activity 重建和应用重启后的登录状态。（真机：登录态跨进程保持）
- [x] **AI-008** 在真实 Gemini 页面上检查 URL、`readyState`、输入框类型、发送按钮、助手回答节点以及 Shadow DOM 边界。（输入区 `div[aria-label="Enter a prompt for Gemini"]`；发送 `button[aria-label="Send message"]`，输入文本后才渲染；上传 `Upload and tools`）
- [x] **AI-009** 探测 `input[type=file]`、图片上传按钮及其触发流程，记录 `WebChromeClient.onShowFileChooser()` 是否真实触发。（Gemini 走 `Open mobile camera/gallery`、`Google Photos`、`Add from Drive` 原生 chooser，AI-128 待测；WiFi 调试已就绪 `192.168.3.158:5555`）→ 结果（已由 AI-128 闭环确认）：真机（LIO-N29 / Android 10）DeepSeek 已验证 `onShowFileChooser` **确实触发**：`locateUploadIcon` 定位图标 css(286,319)/innerHeight=601 → 原生触摸 (857,955)px → `FILECHOOSER: triggered mode=1 accept=...png,.jpg...` → 回填 `file:///.../probe_upload.png` → 截图显示输入框出现红色图片附件缩略图。结论：JS `.click()` 不触发（缺 user activation），原生触摸可触发；Gemini 尚未实现 `uploadIconScript`，其 FILE_CHOOSER 路径归 AI-128。
- [x] **AI-010** 编写只读 DOM 诊断脚本，返回关键元素命中数量和基本属性；不得记录 Cookie、密码、账号令牌或完整图片 Base64。
- [x] **AI-011** 记录可见 WebView 的尺寸、窗口附着状态和页面实际更新表现，为后续切后台测试建立对照基线。→ 结果：探针新增「基线」按钮与 `--es auto baseline`（`logBaseline()` + `PAGE_BASELINE`）。真机（LIO-N29 / Android 10 / DeepSeek）实测：视图 `1176x1801`、density=3.0/dpi=480、屏幕 `1176x2328`、`attachedToWindow=true`、`isShown=true`、parent=LinearLayout、scale=3.0；页面 `visibility=visible`、`innerWidth/Height=392x601`、`devicePixelRatio=3`、`docClient=392x601`、`scrollX/Y=0`、`docScrollH=601`、`readyState=complete`、`title=DeepSeek - Into the Unknown`。注：`bodyScrollH=0`（滚动容器非 body）。后续切后台对照须比较 `visibility`/`hidden`/`innerHeight`/`scrollY`/`docScrollH` 是否变化。

### A0.3 图片上传技术验证

- [x] **AI-012** 定义最小验证图片处理流程：将当前 `Bitmap` 转为 JPEG，测试长边约 1600–2000 像素的压缩方案，并检查识牌精度、方向与文件大小。→ 结果：新增 `imaging/UploadImageEncoder`（长边上限 1800、JPEG 质量 85、超 1.5 MB 逐级降到 60、只缩不放、等比），并含 EXIF 方向旋正（`applyExifOrientation`）。探针 `prepareChooserImage` 真实图路径改为：读入字节 → 解码 → EXIF 旋正 → 压缩编码 → 写 `probe/probe_upload.jpg`，日志输出 `原尺寸 → 目标尺寸 / q / scaled / 字节数`；占位图仍为 1×1 PNG（保持既有验收命令可用）。纯算术部分 `UploadImageEncoderTest` 11 个用例全绿，`./gradlew testDebugUnitTest` BUILD SUCCESSFUL。真机识牌精度对照：DeepSeek 端到端在 AI-128 已用真实手牌图 10 张零告警验证，压缩参数沿用同一图；待后续真机复跑记录压缩后精度。
- [x] **AI-013** 探测实际输入元素。如果页面编辑器是 `contenteditable`，优先测试 `File`、`DataTransfer` 与合成 `ClipboardEvent('paste')` 图片粘贴；不要预设该事件必然被 Gemini 接受。（Gemini 为 contenteditable，合成 paste 被接受）
- [x] **AI-014** 对页面内合成粘贴进行实验：正确聚焦输入框，使用安全编码传递图片字节，记录脚本执行结果，并验证页面是否真的出现附件预览。（真机出现 `Show the uploaded image in a Lightbox`）
- [x] **AI-015** 如果实测发现 Gemini 使用兼容的文件选择流程，再测试私有 cache 文件、适当的 `content://` URI 和 `WebChromeClient.onShowFileChooser()` 回调交接。→ 结果：Gemini 适配器补齐 FILE_CHOOSER 交接路径：`GeminiSelectors.UPLOAD_TOOLS_CANDIDATES`（真机实测 `Upload and tools` 按钮）、`GeminiScripts.uploadTrigger()`（如实返回 `js-click-needs-user-activation`，不伪装成功）、`GeminiScripts.locateUploadIcon()`（返回视口坐标 + innerHeight + dpr，无同步忙等）。`GeminiAdapter` 暴露 `uploadTriggerScript()` / `uploadIconScript()`，与 DeepSeek 共用同一驱动层原生触摸路径；私有 cache 文件由探针 `prepareChooserImage` 统一提供（AI-012 已改为 JPEG 压缩）。新增 4 个单测，`./gradlew testDebugUnitTest` 176 项全绿。真机 `--es site gemini_web` 的原生触摸回填尚待在 Gemini 上跑一遍（DeepSeek 同路径已由 AI-128 验收）。
- [x] **AI-016** 分别记录候选上传路径的证据：文件选择回调是否触发、URI 是否可读取、合成粘贴是否被接受、附件预览是否出现、上传状态是否结束。（PASTE 路径：事件派发成功 + 预览出现 + 回答返回）
- [x] **AI-017** 若候选路径失败，按真实页面行为评估系统剪贴板或拖放等替代方案；不调用 Gemini 私有内部 API、隐藏网络接口或网站内部 RPC 绕过网页交互。→ 结果：探针新增「拖放测试」「系统剪贴板」按钮与 `--es auto upload_alt`（基线 → 拖放 → 剪贴板，各步后用 `verifyAttachment` 判定）。真机 DeepSeek（LIO-N29 / Android 10 / webview 114）实测：基线 `attached=false attachmentCount=0`；合成 `dragenter/dragover/drop` 后 `attached=true count=1`（三条事件 `dispatchEvent` 均返回 false，与 paste 同样不可作判据，须靠附件预览验证）；`ClipboardManager` + `ClipData.newUri`（FileProvider `cache/photos/`）+ 原生 `KEYCODE_PASTE` 后 `attached=true count=1`。结论：**两条替代路径都可用**，`WebViewAiSource.uploadWithFallback` 现有 PASTE ⇄ FILE_CHOOSER 双策略已足够，不需要新增第三种；拖放/剪贴板可作为真机排障手工入口保留在探针中。未调用任何站点私有 API 或隐藏网络接口。
- [x] **AI-018** 进行带图与不带图的对照验证。要求模型复述测试图片中可核对的具体牌面细节，确认模型确实使用图片像素，而非只根据 prompt 猜测。→ 结果：探针新增 `--es auto textonly` 对照组（prompt 与 `e2e` 逐字相同，但不上传附件）。真机 DeepSeek（LIO-N29 / Android 10 / webview 114）实测：**不带图** → 回答「我目前没有看到你提到的掼蛋手牌照片（这条对话里没有附带图片内容），因此无法识别牌面」，`cards=0`；**带图**（`testcases/case3.png`，1536×1024 → JPEG q=85 224599 bytes）→ `attached=true`，回答输出 20 张具体牌面 `2♣ 3♦ 3♠ 4♥ 5♣ 6♦ 6♠ 6♥ 7♣ 7♦ 7♠ 8♣ 8♦ 8♥ 8♠ 9♣ 9♥ 10♦ 10…`，`cards=20`。结论：模型确实读取图片像素，而非凭 prompt 猜测；同一条 prompt 无图时不会编造牌面。附注：本次回答带 `jsonCopyDownload[` 前缀导致结构化解析退回逐条提取（告警非错误），牌面本身正确，属回答包装文本问题。
- [x] **AI-019** 记录选定的上传策略、实测设备与 WebView 版本、失败条件和限制，更新设计文档中的实测结论。（HUAWEI LIO-N29 / Android 10 / webview 114.0.5.302；策略 PASTE；限制：发送按钮 Angular 异步渲染，须 setTimeout 重试，禁止同步忙等）

### A0.4 DeepSeek 首发源的页面探测（2026-10-28 新增）

> 依据 `doc/multi_ai_update.md` §5。DeepSeek 为首发源，因此它的探测结论优先于 Gemini。

- [x] **AI-006D** 扩展探测入口：`GeminiProbeActivity` 泛化为 `AiProbeActivity`，可在运行期切换 `homeUrl`（Gemini / DeepSeek / 自定义），并保留旧入口名以便既有 adb 脚本不失效。
- [x] **AI-007D** 在目标设备手动登录 DeepSeek 网页版，分别记录 Activity 重建与应用进程重启后的登录态是否保持（Cookie + DOM Storage 生效）。
- [x] **AI-008D** 在真实 DeepSeek 页面检查：`location.href`、`readyState`、`innerHeight`、输入框元素（`textarea#chat-input` 是否为唯一/最内层）、发送按钮的可点击判据、回答节点容器、是否存在 Shadow DOM。→ 结果：新增 DeepSeekScripts.deepProbe()（只读）、SiteAdapter.deepProbeScript() 契约、DeepSeekAdapter 实现，探针加「深度探测」按钮与 --es auto deep_probe。真机 LIO-N29 / Android 10 / webview 114.0.5.302（2026-10-29，chat.deepseek.com 已登录）实测：readyState=complete、视口 392x601、dpr=3；输入框 唯一且最内层（inputCount=1 inputUnique=true inputTag=textarea inputInnerEditableCount=0 inputIsInnermost=true，无 id，class 为哈希）；发送键 sendCount=1 sendTag=div sendDisabled=false sendBox=34x34@(313,302) sendClickable=true —— disabled/aria-disabled 均不生效，必须用尺寸 + ds-button--disabled 类判定；首屏 responseCount=0，回答容器仍取 .ds-assistant-message-main-content 待有答案再核；无 Shadow DOM（shadowHostCount=0 hasShadowDom=false），querySelectorAll 可覆盖全文档。实测值已回填 DeepSeekSelectors 注释。
- [x] **AI-009D** 探测 `input[type=file]` 与上传按钮，记录 `WebChromeClient.onShowFileChooser()` 是否真实触发、`accept` 类型与 `multiple` 属性。→ 结果：新增 `DeepSeekScripts.fileInputProbe()`（只读）、`SiteAdapter.fileInputProbeScript()` 契约、`DeepSeekAdapter` 实现，`WebViewHost.onShowFileChooser` 增加 accept/hasImage/multiple/mode 日志，探针加「文件框属性」按钮与 `--es auto file_probe`。真机 LIO-N29 / Android 10 / webview 114.0.5.302（2026-10-29）实测：`fileInputCount=1`，`accept` 整串 **5868** 字符 / **981** 个条目，`multiple=true`、`capture` 空、`hidden=true`（尺寸为 0）、`disabled=false`；**`onShowFileChooser()` 真实触发**（原生触摸回形针 css(286,319) → px(857,955)），`FileChooserParams.mode=1`（MODE_OPEN，即单选），`multiple` 虽为 true 但宿主按单选回填，回填 `cache/probe/probe_upload.png` 成功。JS `.click()` 仍不触发。**同日复测修正**：accept 开头全是 `.wxml`/`.wxss`/`.pdf` 等非图片项，按前 N 项截断的日志看不到图片类型，已抽出 `recognition/webview/AcceptTypes`（大小写不敏感、优先列图片类型、超长摘要 `(...+N)`、禁止打印整串），探针与宿主日志统一改用，并补 8 个单测；复测日志 `acceptCount=981 imageTypes=.png|.jpg|...(+10) (imageCount=18)`，后续 `delivering` 日志不再被挤掉。
- [x] **AI-010D** 用只读诊断脚本记录每个候选选择器的 `querySelectorAll(sel).length`，写入 `DeepSeekSelectors` 注释（日期 + 命中数 + 实测方法）。
- [x] **AI-011D** 记录是否存在可用的“停止生成”控件；若为 0 命中，明确写下“不得作为结束判定前提”。→ 结果：新增 `DeepSeekScripts.stopControlProbe()`（只读、不点击）、`SiteAdapter.stopControlProbeScript()` 契约、`DeepSeekAdapter` 实现，探针加「停止控件」按钮与 `--es auto stop_probe`。真机 LIO-N29 / Android 10 / webview 114.0.5.302（2026-10-29，DeepSeek 已登录静止页）实测：`presetCount=0`（`DeepSeekSelectors.GENERATING` 三选择器全 0 命中）、`labelHits=[]`（扫描 8 个可见 `button`/`div[role=button]` 的 aria-label/title/文本，无 stop/停止/中止/取消生成），脚本返回 `note=no-stop-control: must-not-be-used-as-completion-signal`。**结论：DeepSeek 无可用的停止生成控件，`generating` 信号恒为 false，结束判定只能依赖回答文本增长 + 连续稳定，禁止以“stop 消失”为前提。** 新增 2 个单测（只读且不可点、仅 DeepSeek 实现），`./gradlew testDebugUnitTest` BUILD SUCCESSFUL。

**阶段 0 准入条件：** 网页图片上传的技术可行性必须有真实证据。DeepSeek 是首发源，其完整带图识牌闭环由阶段 2 的 AI-128～135 验收；已有 Gemini 闭环作为回归基线。任何网页源均须确认真实附件已被接受、提交后取得新回答，并通过带图/不带图对照证明模型使用了图片像素。某个源未通过时，不得将其标为可用，也不得用另一个源的成功代替其验收。事件派发成功不等于上传成功。

---

## 阶段 1：建立通用 WebView 宿主与基础页面适配能力

**阶段目标：** 页面会话有明确的所有者和生命周期，DOM 操作集中管理，页面状态可诊断。

### A1.1 WebView 宿主和主线程桥接

- [x] **AI-020** 核对并补齐通用 `WebViewHost` 的创建、持有、挂接、移除和销毁生命周期；复用阶段 1.5 的 AI-117 实现，不再新建站点专用 Host。→ 结果：核对现有 `WebViewHost` 五段生命周期（`ensureWebView()` 创建 / 单例持有 / `attach()` 挂接 / `detachFromVisible()` 移除 / `destroy()` 销毁）均已有实现，缺口在 `destroy()`：新增 ① 释放挂起的 `filePathChooser` 回调（否则页面侧永久等待、下次选择卡死）、② 清空 `offscreenHost` 残留子视图、③ 复位 `visibleParent` 与 `pendingUploadUri`、④ 输出 `destroy: webview released`。**回归修复**：首版 `destroy()` 误清注入配置（`fileChooserHandler`/`onPageFinished`/`attachTargetProvider`），而 `PageDriver.ensureLoaded()` 会先 `destroy()` 再 `attach()` 重建视图，导致重建后无人接管文件选择器 —— 真机实测 `attachmentCount=0 attached=false`；改为只清 WebView 自身状态、保留注入配置后复测 `attachmentCount=1 attached=true`。`./gradlew testDebugUnitTest assembleDebug` BUILD SUCCESSFUL。
- [x] **AI-021** 明确宿主生命周期：区分从父容器移除但保留 WebView，与真正销毁宿主并清理状态两种操作。→ 结果：`WebViewHost` 两条路径语义已显式化并加 KDoc —— `detachFromVisible()` = 仅移除，保留 WebView / Cookie / DOM Storage / 已加载页面，状态回落 `NotAttached`，可 `attach()` 挂回同一会话；`destroy()` = 终态，释放挂起 `filePathCallback`、清离屏残留、`WebView.destroy()` 并置空引用、清 `currentUrl` / `navigatedTarget` / `pendingUploadUri`，状态 `NotCreated`，下次 `ensureWebView()` 重建。配套修复：`removeHostFromParent()` 在 Attached 时统一回落状态（此前 `attach()` 失败重挂会留下假 Attached）；新增 `navigatedTarget` 并在 `destroy()` 清零，避免重建后 `ensureLoaded()` 误判“已在该页”跳过导航；`PageDriver.ensureLoaded()` 注明走 destroy（换页重建）而非 detach；`MainActivity.onPause()` 用 detach、`onDestroy()` 改为 `webHost.destroy()`（此前 Activity 真正销毁时不清理宿主）。`./gradlew testDebugUnitTest --rerun-tasks` BUILD SUCCESSFUL（25 tasks executed）。
- [x] **AI-022** 确保所有 WebView API、JavaScript 执行及视图层级变更均在 Android 主线程执行。→ 结果：全仓审计 WebView 触点后确认执行路径本已正确（`WebViewHost` 的 ensure/attach/detach/destroy/prepareUpload/tapViewportPoint 全部 `requireMainThread()`；`WebViewBridge.evaluateRaw` 全部 `main.post`，`evaluateOnMain` 双分支都落到主线程；`AiProbeActivity` 的 `runScript` 走 `runOnUiThread`）。本轮把「隐式正确」改为「显式契约」：① `WebViewHost.currentWebView()` 增加 `requireMainThread()`（所有调用方拿到返回值后立刻做 evaluate/loadUrl/dispatchTouchEvent/addView），断言消息带 `current=<线程名>`；② `PageOps` 接口补线程契约注释——可后台调用，但实现方必须把 WebView API 切到主线程，调用方不得在主线程阻塞等待；③ `AiProbeActivity` 新增 `requireUiThread(where)`，在 `switchTo` / `prepareChooserImage` / `tapUploadIcon` 三个 WebView 入口断言，防 adb `--es auto` 与按钮回调混用时漏加 `runOnUiThread`。`./gradlew testDebugUnitTest --rerun-tasks` + `assembleDebug` BUILD SUCCESSFUL。
- [x] **AI-023** 建立 JavaScript 回调与协程或其他异步等待机制，避免 UI 线程执行轮询 sleep 或阻塞等待。→ 结果：确认既有机制的合法性前提——`WebViewBridge.evaluateRaw` 是 `suspend` 且把回调 post 到主线程，`PageDriver` 用 `runBlocking` 同步等待，只有在 worker 线程调用才安全（`AiSource.recognize` 契约已规定“调用方在 worker 线程”）。但此前无任何防线，一旦从主线程调用就是**静默永久卡死**而非报错。新增 `recognition/webview/BlockingBridge`：把「主线程禁止 runBlocking」收成可注入、可单测的纯逻辑点，主线程立即抛 `IllegalStateException` 并附 `AI-023` 与调用点标识，且在**进入挂起块之前**就失败（不会先堵住主线程）；`isMainThread()` 对纯 JVM 单测环境降级为 false。`PageDriver.runBlockingEvaluate` 改走 `BlockingBridge.awaitBlocking("PageDriver.evaluate")`；`awaitReady` / `verifyAttachment` / `awaitSubmitStarted` 的轮询补 KDoc，明确 `sleepMillis` 默认 `Thread.sleep` 只能在 worker 线程调用、单测须注入 no-op。新增 `BlockingBridgeTest` 3 个用例（worker 放行并返回值、主线程拒绝且挂起块不执行、挂起异常透传）。`./gradlew testDebugUnitTest --rerun-tasks` + `assembleDebug` BUILD SUCCESSFUL。
- [x] **AI-024** 保证同一 WebView 同一时刻最多只有一个父容器；挂接和重试时检查有效窗口与页面实际更新，而不只检查 `parent` 是否为空。 → 结果：抽出纯逻辑 `AttachGate`（容器体检 `isAttachedToWindow` / `isShown` / 尺寸非零；页面体检 `innerHeight` 阈值 `MIN_VIEWPORT_HEIGHT=100`），7 个 JVM 单测全绿。`WebViewHost.attach()` 三条硬规则：① `enforceSingleParent()` 按 `view.parent` 真实父容器摘除，不依赖自己记的 `visibleParent`（异常路径下直接 `addView` 会抛 `IllegalStateException`）；② 容器体检不过则回落 `NotAttached` 交 `MainActivity.onResume` 重试，不谎报 `Attached`；③ 已是目标容器子视图时只纠正状态、不重挂。`evaluateJavascript` 只能异步回调，`attach()` 内同步读 `innerHeight` 不可能，故 `verifyViewportAfterAttach()` 只打接线点日志、不做假实现；页面出帧判定由 `PageDriver.probeLiveness()` 承担 —— 其硬编码的 `innerHeight >= 100` 改为调用 `AttachGate.viewportRejectReason()`，阈值只定义一处，低于阈值判 `NOT_READY` 并由 `ensureLoaded()` 重试。`removeHostFromParent()` 同样改为按真实 parent 摘除。`./gradlew testDebugUnitTest assembleDebug` BUILD SUCCESSFUL。
- [x] **AI-025** 正确配置 `WebViewClient`、`WebChromeClient`、JavaScript、DOM Storage 和 Cookie 管理策略，不在日志中输出敏感登录数据。 → 结果：抽出两个纯逻辑契约对象并把配置从 `ensureWebView()` 的硬编码里剥离。① `webview/WebViewSecurityConfig`：集中声明 JS / DOM Storage / database 开，`allowFileAccess` / `allowContentAccess` / `saveFormData` / `geolocationEnabled` 关，混合内容 `NEVER_ALLOW`，Cookie 接受（含第三方，OAuth 跳转依赖）；`violations()` 自检违规项，默认值即契约。`WebViewHost.applySecurityConfig()` 按契约落到 `WebSettings` + `CookieManager.setAcceptCookie`；第三方 Cookie 需具体实例，故在 `webView = view` 后由 `applyThirdPartyCookies()` 设置（创建期传 null 会 NPE）。② `webview/UrlRedactor`：URL 进日志前必过，保留 scheme/host/path 便于判断是否被重定向到登录页，query 与 fragment 的**值**替换为 `<redacted>`、键名保留；无 `=` 的裸片段整体抹掉。`onPageStarted` / `onPageFinished` / 新增 `onReceivedError`（mainFrame、脱敏 URL、errorCode）统一用它，避免 `?token=` / `#access_token=` 进 logcat。全仓 grep 确认无 cookie/token/password/secret/authorization/localStorage 直出日志。新增 `WebViewSecurityConfigTest` 8 例、`UrlRedactorTest` 7 例；`./gradlew testDebugUnitTest assembleDebug` BUILD SUCCESSFUL。

### A1.2 选择器和网页能力报告

- [x] **AI-026** 新建 `GeminiSelectors.kt`，集中管理输入框、发送按钮、助手回答、登录提示、文件输入和附件预览相关选择器。
- [x] **AI-027** 新建 `GeminiScripts.kt`，集中维护页面诊断、输入框聚焦、上传验证、prompt 填充和回答快照脚本。
- [x] **AI-028** 为所有动态参数进行 JSON 安全编码，例如使用 `JSONObject.quote()` 或等效方法；禁止手工拼接未经编码的 prompt 或文件名。
- [x] **AI-029** 诊断脚本返回可解析的结构化结果，包括 `ok`、`why` 和必要的页面能力信息；异常时不得伪装成功。
- [x] **AI-030** 用真实 Gemini DOM 验证各个选择器，不将 GeminiBridge 当前选择器直接当作固定正确的常量。→ 真机三态实测（首页/已填未提交/有回答）七组候选全部有命中：input `2|1|0|0`、send 静止 0 → 填文后 `1|1|…`、response 静止 0 → 有回答 `1|1|1|1`、uploadTools `1|1|0|0`、fileInput `4|1|0`；login/generating 恒 0 且 passwordCount=0。首选候选均即命中，textarea 兜底恒 0，无需修正选择器常量。新增探针入口 `--es auto selector_probe`。
- [x] **AI-031** 处理 SPA 初始化、页面导航、登录失效、选择器缺失和页面未就绪等不同状态，避免只凭 `readyState == complete` 判定可操作。 → 结果（2026-10-30）：新增 `recognition/webview/PageState.kt`：六态（READY/LOADING/COMPOSER_MISSING/VIEWPORT_UNHEALTHY/LOGIN_REQUIRED/HOST_NOT_ATTACHED）+ `isLoginUrl` 路径判据；`awaitReady` 遇 NEEDS_USER 立即中止并写入 `lastReadyFailure`。真机 DeepSeek 已登录首页 `READY`、`/sign_in` `LOGIN_REQUIRED`，`example.com` `COMPOSER_MISSING`；`PageStateClassifierTest` 覆盖。

### A1.3 登录状态和错误类型

- [x] **AI-032** 定义明确的上游状态或错误类型，至少区分 Ready、NotLoggedIn、PageLoading、SelectorMissing、PageUnhealthy、Busy、Timeout 和 InvalidResponse。
- [x] **AI-033** 以实际页面状态判断登录情况；登录失效时提供重新登录入口，不把选择器失败或 WebView 销毁误报为未登录。 → 结果（2026-10-30）：`healthCheck()` 改走只读 `pageState()`：`LOGIN_REQUIRED → NotLoggedIn`，`COMPOSER_MISSING / VIEWPORT_UNHEALTHY / HOST_NOT_ATTACHED → Unavailable(带原因)`，不再把选择器或宿主问题报成未登录；设置页保留「打开网页登录」。`WebViewAiSourceTest` 钉住映射。
- [x] **AI-034** 为页面就绪、登录检测和 DOM 诊断添加有限超时、有限重试和必要日志。 → 结果（2026-10-30）：`ReadyRetryPolicy` + `awaitReady`：300ms 起 ×1.5 退避封顶 1200ms、reload ≤ 1 次、仅状态变化打日志、超时把分类原因写入 `lastReadyFailure`。`ReadyRetryPolicyTest` 5 例。
- [x] **AI-035** 检查页面恢复逻辑，避免在 SPA 初始化过程中无条件 reload，或在业务生成过程中执行可能打断请求的恢复操作。 → 结果（2026-10-30）：`PageOps.setReloadSuspended()` 在 `recognize` 全程置位；`navigatedJustNow` 期间跳过 reload；生成中即使结构失效也不 reload。`ReloadSuspensionTest` 3 例。

**阶段 1 验收：** 通用 WebView 宿主可复用且生命周期明确；Gemini 基础适配行为保持兼容；页面关键元素、登录状态和就绪状态能够诊断；页面未就绪与真正错误可以区分；不存在重复创建、错误销毁或主线程阻塞问题。DeepSeek 与 ChatGPT 的站点特有行为分别按阶段 2 的真机任务验收。

---

## 阶段 1.5：多源抽象层（DeepSeek 首发的前置条件）

**阶段目标：** 把“站点”从“驱动”里剥离出来，使同一个驱动层能运行 ChatGPT、DeepSeek、Gemini 及自定义网页适配器；驱动层内不得出现任何站点名、站点 URL 或站点选择器。HTTP API 通过独立的 `HttpApiSource` 兼容现有客户端。

> 依据 `doc/multi_ai_update.md` §2–§3。此阶段只做**结构与假实现**，真机验证放到阶段 2。

### A1.5.1 业务契约

- [x] **AI-102** 新建 `recognition/source/AiSource.kt`：`RecognizeRequest`、`AiSource` 接口（`id` / `displayName` / `requiresNetworkConfig` / `requiresWebLogin` / `healthCheck` / `recognize` / `cancel` / `currentSessionUrl`）。
- [x] **AI-103** 新建 `AiSourceError`（NotLoggedIn / NotReady / UploadFailed / SubmitFailed / Timeout / ParseFailed / Busy / Cancelled / Unknown）与 `SourceHealth`（Ready / NotLoggedIn / Unavailable），供 UI 直接映射文案。
- [x] **AI-104** 新建 `AiSourceId` 枚举，含中文显示名；基础枚举已有 `gemini_web` / `deepseek_web` / `custom_web` / `http_api`，新增 `chatgpt_web` 的注册与兼容性要求见 AI-164，不得在预设未落地前声称五源均可用。
- [x] **AI-105** 新建 `AiSourceRegistry`：基础实现提供 Gemini / DeepSeek / 自定义网页 / HTTP API 四种预设；新增 ChatGPT 网页预设与 `ChatGPTAdapter` 的注册要求见 AI-156、AI-164。`create(settings, host, ctx)` 必须按 `sourceKey` 构造唯一实现；未知 key 回退 `http_api` 并记录告警。
- [x] **AI-106** 为 `AiSourceRegistry` 编写单测：已有四个 key 的映射、未知 key 回退及预设 URL 单一事实来源得到验证；扩展到 ChatGPT 后，五个源的映射和预设回归由 AI-164 补齐。

### A1.5.2 站点适配层

- [x] **AI-107** 新建 `recognition/site/SiteAdapter.kt`：`homeUrl` / `siteKey` / `preferredUpload` / `fillAndSubmitScript` / `snapshotScript` / `diagnoseScript` / `loginCheckScript` / `verifyAttachmentScript` / `pageFixScript`（默认空串）/ 五个 `parseXxx` 方法。
- [x] **AI-108** 定义结构化返回：`SubmitOutcome(ok, why, inputFilled, attached)`、`AnswerSnapshot(responseCount, lastAnswerText, generating, loginFormPresent, pageUrl)`、`DiagnoseResult`、`UploadStrategy { PASTE, FILE_CHOOSER }`。
- [x] **AI-109** 新建 `GeminiAdapter`：把现有 `GeminiSelectors` + `GeminiScripts` 收进实现，行为与当前完全一致（不修改脚本内容）。
- [x] **AI-110** 为 `GeminiAdapter` 编写单测：脚本可解析、`why` 存在、prompt 含引号/换行/`$`/U+2028 不破坏脚本、Base64 正确转义。
- [x] **AI-111** 新建 `DeepSeekSelectors.kt`（候选值 + 每条注释写“待真机验证”）、`DeepSeekScripts.kt`（fillAndSubmit / snapshot / diagnose / loginCheck / verifyAttachment / pageFix）、`DeepSeekAdapter`。
- [x] **AI-112** `DeepSeekAdapter.preferredUpload = FILE_CHOOSER`（首版保守选择），并在注释说明若真机实测 PASTE 可行再改。
- [x] **AI-113** 为 `DeepSeekAdapter` 编写与 AI-110 同构的单测（不依赖真机）。
- [x] **AI-114** 编写 `CustomAdapter`：`homeUrl` 由设置提供，选择器复用 `DeepSeekAdapter` 的默认集合，并在 `siteKey` 上标注“自定义”。
- [x] **AI-156** 新建 `ChatGPTSelectors.kt`、`ChatGPTScripts.kt` 与 `ChatGPTAdapter`，实现通用 `SiteAdapter` 的全部脚本及解析方法；默认首页为 `https://chatgpt.com/`、`siteKey = chatgpt`、首选上传策略为 `PASTE`。选择器、登录判据、附件预览及页面修复逻辑全部封装在适配器内；标注未真机验证的候选选择器，不复制其他项目的实现作为已验证事实。 → 结果（2026-10-30）：`ChatGPTSelectors` / `ChatGPTScripts` / `ChatGPTAdapter` 落地：`siteKey=chatgpt_web`、首页 `https://chatgpt.com/`、`preferredUpload=PASTE`；选择器与脚本均标注「未真机验证」。
- [x] **AI-157** 为 `ChatGPTAdapter` 编写单元测试：脚本 JSON 可解析且包含 `ok` / `why`；prompt、文件名、MIME 类型与图片参数安全编码；覆盖引号、换行、`$`、U+2028/U+2029、脚本异常、附件被拒和附件验证失败。派发 paste 事件不等于上传成功；上传失败必须产生可分类错误。 → 结果（2026-10-30）：`SiteAdapterScriptsTest` 覆盖 ChatGPT 脚本 JSON、特殊字符、提交正面证据、附件判据；`everyAdapterParsesComposerAndSessionFields` 覆盖快照字段。

### A1.5.3 主线程桥与宿主

- [x] **AI-115** 新建 `recognition/webview/WebViewBridge.kt`：`suspend fun evaluate(script: String): String`，全部 post 到主线程，`suspendCancellableCoroutine` + `isActive` 保护，JSON 字符串反转义。
- [x] **AI-116** 为 `WebViewBridge` 的纯逻辑部分（JSON 反转义、`null` 返回、异常返回空串）编写单测。
- [x] **AI-117** 新建 `WebViewHost`：进程级单例、懒创建、`attach(view)` / `detach()` / `currentWindowStatus()`；一个 WebView 同一时刻只有一个父容器；`removeHostFromParent(dropWebView=false)` 与 `detachOffscreenHost()` 分离。
- [x] **AI-118** `WebViewHost` 首版只实现**可见挂接**（挂进当前 Activity 的容器）。overlay 后台宿主按 `multi_ai_update.md` M2 作为后续可选增强，在代码中留出接口与 TODO，不本轮实现。
- [x] **AI-119** 配置 `WebChromeClient`：`onShowFileChooser`（供 FILE_CHOOSER 上传）、`onJsAlert` / `onJsConfirm`（避免弹窗被静默丢弃）、`onCreateWindow` 返回 false、`onPermissionRequest` 只 grant 请求资源。

### A1.5.4 驱动层

- [x] **AI-120** 新建 `PageDriver`（站点无关）：`ensureLoaded()`（三态活性判断：ALIVE / NOT_READY / DEAD，NOT_READY 只等待不 reload）、`captureBaseline()`、`fillAndSubmit()`、`verifyAttachment()`、`pollSnapshot()`、`isLoggedIn()`。
- [x] **AI-121** 新建 `WebViewAiSource(adapter, host, bridge, watcherFactory)` 实现 `AiSource`：状态机 `EnsurePageReady → EnsureLoggedIn → CaptureBaseline → FillAndSubmit → VerifyAttachment → WaitForNewAnswer`。
- [x] **AI-122** `PageDriver` / `WebViewAiSource` 中禁止出现任何 CSS 选择器、URL 字面量、`gemini` / `deepseek` 字样；加一个静态检查脚本（`grep`）作为阶段 G 的验收依据。
- [x] **AI-123** 上传失败（`VerifyAttachment` 不通过）必须返回 `UploadFailed`，**不得**继续提交纯文本。
- [x] **AI-124** 收敛并验证上传策略回退：只有适配器明确声明支持、且该路径已完成相应验证时，才允许在 `preferredUpload` 失败后尝试另一条策略；否则返回 `UploadFailed`。错误信息须记录实际尝试的路径及各自原因。ChatGPT 默认只尝试 `PASTE`；只有单独实现并通过目标 Android WebView 真机验证后，才能启用 `FILE_CHOOSER` 回退（依据 `doc/multi_ai_update.md` §5.5）。 → 结果（2026-10-30）：`SiteAdapter.supportedUploads` 默认只含 `preferredUpload`；驱动层仅尝试集合内策略，空集合直接 `UploadFailed`；`onlyDeclaredUploadStrategiesAreTried` 钉住。
- [x] **AI-125** `HttpApiSource`：包一层现有 `AiRecognitionClient`，`requiresNetworkConfig = true`、`requiresWebLogin = false`，内部保留现有 `isGemini()` / `isOpenAiChat()` 嗅探逻辑，**不外泄到新代码**。
- [x] **AI-126** 用 Fake `SiteAdapter` + Fake `WebViewBridge` 为 `WebViewAiSource` 编写单测：正常闭环、上传被拒、未登录、超时、取消、Busy 重入、旧回答不串线。

**阶段 1.5 验收：** ① 适配器和驱动相关单测通过；② `grep` 证明驱动层无站点字样、URL 或选择器；③ Fake 适配器能跑通完整状态机；④ Gemini 回归测试通过；⑤ DeepSeek 与 ChatGPT 适配器至少编译及单测通过，但真实登录、附件上传和识牌行为只能在各自完成阶段 2 真机验收后标记为已验证。

---

## 阶段 2：实现单次图片识牌完整闭环

**阶段目标：** 单个识牌任务从当前 `Bitmap` 开始，经过真实图片上传、消息提交、回答完成判定，最终转换为 `List<Card>`。

### A2.1 上传接口和资源管理

- [x] **AI-036** 根据阶段 0 的证据，实现且只启用已验证的图片上传路径；不要未经验证就同时部署多条上传策略。 → 结果（2026-10-30）：驱动层只尝试 `SiteAdapter.supportedUploads` 声明的路径：DeepSeek 为 PASTE + FILE_CHOOSER（两条均已真机出现附件预览），Gemini / ChatGPT 仅 PASTE；未声明路径不再自动回退。
- [x] **AI-037** 如采用 cache 文件，明确临时 JPEG 的创建、URI 授权、读取权限及任务成功、失败、取消后的清理时机。 → 结果（2026-10-30）：`PageDriver.prepareFileChooserUpload()` 写 `cacheDir/ai-upload`，`cleanupUploads()` 在 `recognize` 的 finally（成功/失败/取消）清理；`res/xml/file_paths.xml` 补 `ai-upload/` 与 `probe/` 根。
- [x] **AI-038** 如果通过 `evaluateJavascript()` 传递图片字节，确认数据安全编码和尺寸限制；不向日志输出完整 Base64。 → 结果（2026-10-30）：图片字节仅以 `Base64.NO_WRAP` 作为脚本参数、经 `JsonQuote` / `JSONObject.quote` 编码；日志不输出 Base64；`SiteAdapterScriptsTest` 覆盖引号/换行/`$`/U+2028。
- [x] **AI-039** 实现 `verifyAttachment()` 或等效验证，必须以真实附件预览和上传完成信号为依据；单纯派发事件或打开文件选择器不算成功。 → 结果（2026-10-30）：`verifyAttachment()` 轮询适配器附件预览选择器，只有预览节点命中才返回 true；事件派发成功、输入框存在、文件框打开均不算。
- [x] **AI-040** 为上传设置明确超时。附件缺失、上传失败或页面未确认上传完成时，停止本轮提交并返回可理解的错误。 → 结果（2026-10-30）：上传确认超时 20s（`UPLOAD_TIMEOUT_MILLIS`）→ `AiSourceError.UploadFailed`，发生在提交之前；失败不发送纯文本。
- [x] **AI-041** 确保每轮只提交本次图片，上传新图片前清理或确认旧附件状态，避免图片 A 残留到图片 B 的请求。 → 结果（2026-10-30）：写新图前清空 `cache/ai-upload` 目录；快照新增 `attached`，脏输入区先建立经确认的新会话。`leftoverAttachmentStartsConfirmedNewConversation` 等测试。

### A2.2 提交确认与回答基线

- [x] **AI-042** 实现发送前 `AnswerSnapshot`，记录最后一个非空助手回答、回答节点数量、输入内容、附件状态和可读取的会话标识。 → 结果（2026-10-30）：`AnswerSnapshot` 新增 `inputText` / `attached` / `sessionId` 与 `composerDirty`；DeepSeek / Gemini / ChatGPT 快照脚本与 `parseSnapshot` 全部回填；`SiteAdapterScriptsTest.everyAdapterParsesComposerAndSessionFields` 覆盖。 补充（真机）：附件计数限定 composer 上方第 3 层（levels 实测 `0|0|1…`；历史消息图片在第 6 层），避免把已发送图片当成待发送附件。
- [x] **AI-043** 按状态机执行 `EnsurePageReady → EnsureLoggedIn → CaptureReplyBaseline → AttachImage → VerifyAttachment → FillPrompt → Submit`。
- [x] **AI-044** 实现提交后验证：确认输入框变化、用户消息进入页面、出现新回答节点或新回答正文，而不把点击发送按钮当作成功。 → 结果（2026-10-30）：`awaitSubmitStarted()` 三态 STARTED / NOT_STARTED / STALE_PAGE：会话 URL、stop、回答节点或文本变化任一成立才算开始；6s 内 URL 与 DOM 均无变化判 STALE_PAGE。`SubmitProgressTest` 覆盖。
- [x] **AI-045** 确认读取的回答属于当前任务，排除旧回答、用户 prompt 回显和末尾尚未填充的空回答容器。 → 结果（2026-10-30）：发送前基线 `(responseCount,lastAnswerText)` + `GenerationWatcher` 排除旧回答、prompt 回显与空节点；`followUpGetsNewAnswerNotPrevious` 等测试。
- [x] **AI-046** 处理新对话策略。确实需要新建会话时，通过实际页面操作及 DOM 状态确认成功，不仅凭 URL 拼接假设会话已创建。 → 结果（2026-10-30）：`PageOps.startNewConversation()`：真实回到站点首页（站点自身 new chat）+ DOM 快照确认 `responseCount==0 && !generating && 输入区干净`，不拼会话 URL；脏输入区时驱动层自动调用，确认失败即 `SubmitFailed`。

### A2.3 生成结束检测

- [x] **AI-047** 新建 `GenerationWatcher.kt`，将回答文本变化、生成状态和连续稳定观察封装为独立的可测试逻辑。
- [x] **AI-048** 参考 GeminiBridge 的结束检测用例，处理只有旧答案、回答节点为空、prompt 被错误命中、生成暂时停顿和页面错误等场景。
- [x] **AI-049** 设置轮询间隔、连续稳定判定条件和总体回答超时；避免回答仍在生成时过早返回，也避免永久等待。
- [x] **AI-050** 将页面读取、提交确认、回答收集和结束判定串成完整上游操作，超时与异常必须返回有分类的错误。 → 结果（2026-10-30）：`WebViewAiSource.runPipeline` 串起 ensureLoaded → 登录 → 基线 → 上传 → 提交 → 新回答 → 解析；失败全部映射为 `AiSourceError` 子类（NotReady/NotLoggedIn/UploadFailed/SubmitFailed/Timeout/ParseFailed/Busy/Cancelled）。

### A2.4 识牌响应解析

- [x] **AI-051** 从 `AiRecognitionClient.kt` 中梳理现有 `extractText()`、`parseCards()`、字符串归一化及相关测试，明确可以直接复用的行为。 → 结果（2026-10-30）：回复解析已抽到 `AiCardResponseParser`（去 Markdown 围栏、边界提取、严格校验、裸数组兼容）；HTTP 与 WebView 共用，旧 `AiRecognitionClient` 只保留网络调用。
- [x] **AI-052** 新建 `AiCardResponseParser.kt`，将回复文本解析与 HTTP 或 WebView 传输层解耦。
- [x] **AI-053** 提示 Gemini 返回符合现有项目牌面定义的 JSON；格式示例只作结构说明，实际 rank、suit、大小王和数量限制以项目枚举及规则为准。
- [x] **AI-054** 解析器支持去除 Markdown 围栏，必要时以有边界的方式提取 JSON，并严格校验字段、rank、suit、空数组、重复记录及数量限制。
- [x] **AI-055** 对非法 JSON、未知牌面和不完整结果返回明确错误或校验警告，不把无效数据静默转换成有效 `Card`。
- [x] **AI-056** 串联完整路径：图片上传与确认、prompt 提交、读取本轮新回答、确认生成结束、解析牌面并返回 `List<Card>`。 → 结果（2026-10-30）：完整路径由 `WebViewAiSource` 实现：附件确认 → prompt 提交 → 本轮新回答 → `GenerationWatcher` 结束 → `List<Card>`；Fake 全链路与 DeepSeek 真机均通过。

### A2.5 DeepSeek 首发源真机闭环（2026-10-28 新增）

> 这是本任务的**核心验收**。Gemini 的对应闭环（A2.1–A2.4 真机部分）与本节并行推进，但 DeepSeek 优先。

- [x] **AI-127** 真机登录 DeepSeek 网页版，用 `DeepSeekAdapter.diagnoseScript()` 记录全部选择器命中数，回填到 `DeepSeekSelectors` 注释。
- [x] **AI-128** 上传路径 A：`FILE_CHOOSER`。把当前 JPEG 写入 `cacheDir`，`onShowFileChooser` 回调里 `onReceiveValue(arrayOf(uri))`，确认页面出现附件预览。→ 2026-10-29 真机通过：`AiProbeActivity --es site deepseek_web --es auto fschooser`，`locateUploadIcon` css(286,319)/innerHeight=601 → 原生触摸 (857,955)px → `FILECHOOSER: triggered; mode=1 accept=...png,.jpg...` → `delivering file:///data/user/0/.../cache/probe/probe_upload.png` → 5s 后 `verifyAttachmentScript` 返回 `{ok:true, attachmentCount:1, attached:true}`，截图 `/tmp/ai128_fschooser.png` 显示输入框内红色图片附件缩略图 + 删除按钮。注意：本轮用 1×1 PNG 验证通路，`file://` URI 华为 WebView 可读；驱动层实际走 FileProvider `content://`（未单独验证，见 AI-037）。
- [x] **AI-129** 上传路径 B：`PASTE`。合成 `ClipboardEvent` + `DataTransfer` + `File`，`verifyAttachment` 必须通过；仅 `dispatched=true` 不算成功。
- [x] **AI-130** 两条路径都失败时，记录具体拒绝信号（事件 `defaultPrevented`、无预览节点、文件选择器未触发），更新 `DeepSeekAdapter.preferredUpload` 的注释结论。 → 结果（2026-10-30）：`DeepSeekAdapter` 头注释记录两条路径结论（paste 被接受/预览出现但 `dispatchEvent=false`；file chooser 需原生触摸）；探针 `upload_alt` 保留拖放/剪贴板排障入口。
- [x] **AI-131** 提交闭环：填 prompt → 点发送 → 确认 `responseCount` 增加或文本变化 → `GenerationWatcher` 判定结束 → `AiCardResponseParser` 解析出 `List<Card>`。 → 结果（2026-10-30）：真机 `--es auto e2e`：附件 `attached=true` → 提交 → 新回答 → `cards=10 warnings=[]`；另一次 27 张逐张核对一致。
- [x] **AI-132** 带图 / 不带图对照：同一张手牌照片分别提交，要求模型复述图片中可核对的具体牌面，确认像素被使用（对应全局 G-02）。 → 结果（2026-10-30）：真机 `--es auto textonly`（同 prompt 无图）→ 模型明确回答没看到手牌照片、`cards=0`；带图 → 20 张具体牌面。
- [x] **AI-133** 连续两张不同图片：确认无附件残留、无回答串线（对应 A4.3）。 → 现状（2026-10-30）：需在同一 DeepSeek 会话连续上传两张不同图片；探针 `e2e` 每次新建 Activity，尚无同会话多图入口，未取证。 → 结果（2026-10-30）：真机 `--es auto source_e2e_twice`（2026-10-30 11:38，case3.png → case14.jpg，复用同一 `WebViewAiSource`）：第一轮 27 张、第二轮 22 张，`SOURCE_E2E_TWICE_OK first=27 second=22 status=Offscreen`；两轮牌面列表不同，未串上一轮回答，也未把第一张图当成本轮附件。
- [x] **AI-134** 追问场景：在已有回答的会话里再发一次，确认拿到新答案而不是上一轮（对应 M5 基线）。 → 现状（2026-10-30）：需在同一 DeepSeek 会话已有回答后追问一次；与 AI-133 共用多轮入口，未取证。 → 结果（2026-10-30）：同一 `source_e2e_twice` 的第二轮即追问：第二轮回答与解析（22 张，含第二轮独有的大小王与 K/Q 组合）和第一轮（27 张）完全不同，说明拿到的是新回答而不是上一轮缓存；`PageDriver` 未重新导航到首页。
- [x] **AI-135** 记录 DeepSeek 适配器的已知限制（无 stop 控件、风控、会话 URL 形态）到 `doc/multi_ai_update.md` §5。 → 结果（2026-10-30）：已回填 `doc/multi_ai_update.md` §5.6.1：无 stop 控件、次要请求失败不影响主流程、会话 URL `/a/chat/s/<id>`、风险与限制。

### A2.6 ChatGPT 网页版真机闭环（依据 `doc/multi_ai_update.md` §5.5）

> ChatGPT 是新增的网页源，不改变 DeepSeek 首发的实施优先级。任何候选选择器、合成 paste 事件或测试桥接项目的历史结论均不能代替目标 Android WebView 的实际验收。

- [ ] **AI-158** 在目标 Android 设备打开 `https://chatgpt.com/` 并手动登录；记录日期、设备、Android 与 WebView 版本、页面 URL、`readyState`、输入框/发送按钮/回答节点/文件输入/附件预览的诊断命中数。验证登录判据能区分未登录与已登录页面，且不得记录密码、Cookie、令牌或私人对话内容。 → 现状（2026-10-30）：已真机登录并记录：LIO-N29 / Android 10 / WebView 114.0.5.302，`input=1`（`div.ProseMirror[contenteditable]`，新 DOM 已无 `#prompt-textarea`）、`send=1`、`fileInput=4`、`password=0`、`loginIndicator=0`；未登录侧仍未复现（不退出用户账号，G-24 缺一半证据），故不整体勾选。
- [ ] **AI-159** 验证 `PASTE` 路径：使用 `RecognizeRequest.jpegBytes` 构造真实 `File`，通过 `DataTransfer` 与合成 `ClipboardEvent('paste')` 附加图片；之后独立确认当前待发送消息的真实附件预览出现。事件派发、`dispatchEvent` 返回值或输入框存在都不能替代附件验收。若该路径失败，再独立评估 `FILE_CHOOSER`；不得假定其他站点的上传策略对 ChatGPT 有效。 → 现状（2026-10-30）：已按文档把顺序改为「填文本 → paste → 提交前确认 → 发送」；真机 paste 被站点静默丢弃，页面提示附件额度用尽（`Get Plus for more uploads` / 约 10-31 12:36 恢复）。额度恢复前**无法**区分“代码问题”与“账号限制”，故不勾选。
- [ ] **AI-160** 完成 ChatGPT 单次识牌闭环：先填 prompt 和图片，再验证附件，最后才提交；确认用户消息进入页面、取得本轮新回答、由 `GenerationWatcher` 判定结束，并由 `AiCardResponseParser` 解析出合法 `List<Card>`。上传或附件验证失败时返回 `UploadFailed`，不得继续提交纯文本。 → 现状（2026-10-30）：流程已改为提交前确认附件，并真机验证“额度用尽 → `UploadFailed`，不提交”；带图闭环仍被账号额度阻塞，未通过。
- [ ] **AI-161** 对同一测试图片进行带图/不带图对照，要求模型复述可人工核验的具体牌面细节，确认答案确实使用图片像素；记录输出、耗时与失败原因。 → 现状（2026-10-30）：依赖 AI-160；带图/不带图对照尚未执行。
- [ ] **AI-162** 连续识别两张不同图片，并在已有回答的对话中追问一次；验证旧附件未残留、当前回答不串线、页面生成暂停不会造成过早结束，上传失败后可安全重试。 → 现状（2026-10-30）：依赖 AI-160；连续两图与追问的附件残留验证尚未执行。
- [ ] **AI-163** 将 ChatGPT 真机结果及已知限制回写 `doc/multi_ai_update.md` §5.5，包含实测日期、设备、WebView 版本、附件预览判据、失败路径与未验证事项。 → 现状（2026-10-30）：依赖 AI-158~162 的实测数据，尚无内容可回写 §5.5。

**阶段 2 验收：** 对每个标记为可用的网页源，单张图片都必须在目标 WebView 中得到真实附件确认后才提交；稳定读取当前任务的新回答并转换为合法牌列表。需分别记录 ChatGPT、DeepSeek、Gemini 的实测结果；未通过某源闭环时应明确标记该源尚未验证，不得拿其他源的成功替代。连续识别不同图片时不得出现附件残留或回答串线。

---

## 阶段 3：接入 MainActivity 和 AI 设置界面

**阶段目标：** 将已经验证的 WebView 识牌服务接入现有 UI，不破坏本地识别与其它 API Provider。

### A3.1 统一业务入口

- [x] **AI-057** 新建 `AiRecognitionService.kt`，作为 UI 识牌入口，负责任务互斥、状态传递、取消和错误映射。 → 结果（2026-10-30 清理）：Gemini 专用实现已删除，职责由站点无关的 `WebViewAiSource` 承担（见 AI-152）。
- [x] **AI-058** 修改 `MainActivity.recognizeOnline()`，从同步调用 `AiRecognitionClient.recognize()` 切换为异步调用业务服务。
- [x] **AI-059** 删除仅针对 WebView 模式不必要的 `settings.apiKey.isBlank()` 门槛，确保已登录网页但没有 API Key 时仍可识牌。
- [x] **AI-060** 为在线识牌按钮设置真实忙碌状态；成功、失败、超时和取消后都能正确恢复操作状态。
- [x] **AI-061** 在线识牌失败时保留当前本地识别结果以及用户手动修改的牌，不覆盖为错误、空或旧结果。
- [x] **AI-062** 检查 `VisualCardCodec.decode()` 和 `CardRecognizer` 的既有调用顺序，确保本地直接解码、OCR、模板匹配和评分逻辑不被重写或意外绕过。 → 结果（2026-10-30）：核对本地路径未被改动：`VisualCardCodec.decode()` → `CardRecognizer` → 手动校对 → `HandScorer` 仍按原顺序由 `MainActivity` 调用；相关单测全绿。

### A3.2 设置与登录入口

- [x] **AI-063** 在 `AiSettingsStore.kt` 中设计明确的传输模式，例如 `GEMINI_WEBVIEW` 与 `API`，而不是让网页模式伪装成拥有 API endpoint/model 的配置。
- [x] **AI-064** 修改 `showAiSettings()`：Gemini WebView 模式显示登录状态、打开网页和重新检查操作，不要求填写 Gemini API Key。
- [x] **AI-065** 明确 WebView 模式的实际模型由 Gemini 网页会话决定，不宣称可以像 HTTP API 一样通过 model 字段可靠指定模型。 → 结果（2026-10-30）：设置页网页源整体隐藏 endpoint/model/key 字段与标签，并显示「实际模型由网页会话决定，此处不能指定」。
- [x] **AI-066** 设置界面显示正在登录、就绪、未登录、页面未就绪和错误原因；各状态均来自真实检测结果。 → 结果（2026-10-30）：设置页健康态显示「检测中… / 网页可用 / 需要登录 / 不可用（原因）」，全部来自真实 `healthCheck()`；网页源隐藏时也会重新检查。
- [x] **AI-067** 确保 WebView 的主线程操作、Service 与 Activity 生命周期衔接正确，避免离开设置页时销毁业务 WebView 或对失效 Activity 更新 UI。 → 结果（2026-10-30）：健康检查只读、不销毁或重建共享 WebView；`MainActivity` 用检查序号 + `isFinishing/isDestroyed` 丢弃迟到结果，`AiSettingsScreen` 回写前同样判活。

### A3.3 现有 API 能力兼容

- [x] **AI-068** 保留 OpenAI/custom API 配置与调用能力，除非另有明确产品决定；Gemini WebView 模式与 API 模式执行路径清晰分离。 → 结果（2026-10-30）：`HttpApiSource` 保留 `AiRecognitionClient` 调用与 HTTPS / Key 校验；registry 按 `sourceKey` 分流，网页源与 API 源不会同时执行。
- [x] **AI-069** 防止一个识牌操作同时触发网页与 API 两个后端，确保每次请求只有一个明确的执行者。
- [x] **AI-070** 为 WebView 模式和 API 模式分别验证设置展示、配置保存、选择与调用行为。

**阶段 3 验收：** 用户可以通过现有在线识牌入口调用当前所选且已接入的 `AiSource`；网页源使用网页登录态，不要求官方 API Key，HTTP API 源继续校验并使用原有网络配置。本地 OCR、模板匹配、`VisualCardCodec`、用户校对、牌力分析和仍受支持的 API Provider 均保持原有能力。任何网页源只有完成自己的阶段 2 真机闭环后才可标记为已验证。

### A3.4 AI 源选择配置页（2026-10-28 新增）

> 依据 `doc/multi_ai_update.md` §4、§8。这是用户可见的五源选择入口：ChatGPT 网页版、DeepSeek 网页版、Gemini 网页版、自定义网页源和 HTTP API。DeepSeek 保持首发顺序；五源全部注册、配置保存和切换回归由 AI-164 验收。

- [x] **AI-136** 扩展 `AiSettings`：新增 `sourceKey`、`homeUrl`；`AiTransport` 改为 `{ WEBVIEW, API }`（原 `GEMINI_WEBVIEW` 改名泛化）。
- [x] **AI-137** 实现迁移逻辑：旧 `GEMINI_WEBVIEW` → `gemini_web` + `WEBVIEW`；无 `transport` → `http_api` + `API`（行为不变）；`api_key` 加解密在改名后仍可用。
- [x] **AI-138** 为迁移编写单测：旧数据加载、混合 profile、切 profile 后 `sourceKey` 正确、加密 Key 可解密。
- [x] **AI-139** `AiPresets` 已新增 `deepseekWebView()`、`customWebView()`；`defaults()` 顺序把 DeepSeek 放首位（首发源）。ChatGPT 预设、五源配置测试及 UI 字段检查由 AI-164 补齐，不改变 DeepSeek 首发顺序。
- [x] **AI-140** 重写 `showAiSettings()` 为**先选源、再显隐字段**：网页源显示 `homeUrl` + “打开登录页” + 登录状态；API 源显示 endpoint/model/key 并校验 HTTPS。
- [x] **AI-141** 修掉当前 bug：`recognizeOnline()` 中无条件的 `settings.apiKey.isBlank()` 门槛改为“仅 API 源才检查”。
- [x] **AI-142** 配置页显示真实 `SourceHealth`：Ready / NotLoggedIn / Unavailable 各一句人话，不做假绿。
- [x] **AI-143** “打开登录页”跳到探测/登录 Activity（见 AI-006D），登录返回后刷新健康态。
- [x] **AI-144** `MainActivity.recognizeOnline()` 改为通过 `AiSourceRegistry.create(...)` 取当前源并调用 `recognize()`，不再直接 new `AiRecognitionClient`。
- [x] **AI-145** 保留 HTTP API 路径可用：`AiSourceRegistry` 对 `http_api` 返回 `HttpApiSource`，OpenAI/custom 配置与调用不受影响。
- [x] **AI-146** 防止一次识牌同时触发网页与 API 两个后端：`AiSource` 每次只有一个实现被调用，加单测覆盖。
- [x] **AI-147** 单测已覆盖 `AiSettingsStore` 的基础多源场景：DeepSeek / Gemini / 自定义 / API 四个 profile 并存并可正确切换；加入 ChatGPT 后的五源保存、加载与切换回归由 AI-164 补齐。
- [x] **AI-164** 注册 `chatgpt_web` 源 ID、`https://chatgpt.com/` 预设和 `ChatGPTAdapter` 工厂映射；更新默认配置、设置 UI 和 `AiSettingsStore` 测试，使 ChatGPT 与 DeepSeek / Gemini / 自定义网页 / HTTP API 五种 profile 可共存、保存、重载及切换。ChatGPT 网页源不要求 OpenAI API Key，须显示网页登录入口；HTTP API 的 HTTPS 与 Key 校验不得改变。确认迁移旧 profile 不丢失其它源配置，且预设 URL 只有一个事实来源。 → 结果（2026-10-30）：`AiSourceId.CHATGPT_WEBVIEW`、`AiSourceRegistry` 工厂、`AiPresets.chatgptWebView()`、设置 UI 与 `AiSettingsStoreTest`（五源预设、URL 单一来源、旧 profile 迁移）已接通；HTTP API 校验未变。

---

## 阶段 4：生命周期、并发、故障恢复和真机测试

**阶段目标：** 页面异常、任务取消或 Activity 生命周期变化时不会产生错误结果、资源泄漏和永久卡住。

### A4.1 并发与取消

- [x] **AI-071** 确保同一 WebView 同一时间最多处理一个识牌请求；并发请求应排队或返回 `Busy`，不能同时改写输入框。
- [x] **AI-072** 定义任务取消行为：停止后续提交、取消页面轮询、释放相关回调与临时资源，并阻止迟到的旧回调覆盖当前 UI。
- [x] **AI-073** 对 Bitmap、JPEG 缓存、JavaScript 回调和任务句柄明确资源所有权；所有结束路径都能安全清理资源。 → 结果（2026-10-30）：`FileChooserSlot` 统一 `onShowFileChooser` 回调所有权与释放（重复 offer、替换、destroy 全覆盖）；上传临时文件在 finally 清理；`FileChooserSlotTest` 5 例。
- [x] **AI-074** 为重复调用、超时后再次识牌和失败后重试编写回归测试，防止死锁、重复提交或遗留 Busy 状态。

### A4.2 页面健康与宿主恢复

- [x] **AI-075** 区分页面尚在初始化、元素暂未命中、登录失效、导航失败、窗口失效和真正页面异常。（`PageStateClassifier` 五态 + `isLoginUrl` URL 判据，顺序固定；`awaitReady` 遇 NEEDS_USER 立即中止并带原因。导航失败/窗口失效分别由 `AiSourceError.NotReady`（含宿主未挂接）与 `WebViewHost` 状态机表达。`VIEWPORT_UNHEALTHY` 仅单测覆盖。）
- [x] **AI-076** 健康检查使用低干扰的只读操作；生成过程中不得通过 reload、导航或频繁脚本探针打断正在进行的识牌任务。（`PageOps.pageState()` 只读、不导航；`healthCheck` 走它而非 `ensureLoaded`；`generating` 时直接返回缓存；`PageOps.setReloadSuspended` 在 `recognize` 全程暂停自动 reload。）
- [x] **AI-077** 验证屏幕旋转、Activity 重建、离开和返回 Gemini 页面以及应用进程重启后的 WebView 行为。 → 结果（2026-10-30）：真机：探针页转横屏触发 `onDestroy`→`onCreate(restored=true)`，同一 pid、无 `WebView created`，重新 `Attached 2328x679`；转回竖屏同样重建并 `Attached 1176x1801`。离开并返回 Gemini 页面（`gemini.google.com/app`）无崩溃、宿主复用。`force-stop`+`kill` 后新 pid 启动 `WebView created`，打开 AI 页面再返回主界面为「网页可用」，DeepSeek 登录态保留。主界面固定 portrait，横屏不适用。
- [x] **AI-078** 在切后台及返回前台时记录尺寸、窗口挂接和实际回答更新情况，不能只以 `isAttachedToWindow`、URL 或 JS 可执行作为健康结论。 → 结果（2026-10-30）：真机：`source_e2e` 提交后第 5 秒按 Home 切后台，宿主 `detached (webview + session kept)`；8 秒后返回 `attach status=Attached 1176x1801`，识牌继续并完成 `SOURCE_E2E_OK cards=27 status=Attached`。后台期间不能只凭挂接状态判断健康，实际回答确实更新。
- [x] **AI-079** 初版以可见 WebView 路径为验收基线；只有后台识牌成为明确需求时，才独立评估 overlay、额外窗口宿主、前台服务和保活方案。 → 结果（2026-10-30）：初版已越过可见基线：AI-165~171 采用 1×1 离屏宿主 + 全屏像素 layout，主界面不插可见 WebView；无 overlay 权限时回退 decorView 1dp 容器。
- [x] **AI-080** 对页面恢复和重试设置次数上限及退避策略；恢复失败时呈现诊断结果，不无限 reload 或无限等待。（`ReadyRetryPolicy`：300ms 起 ×1.5 退避封顶 1200ms、reload ≤1 次、生成中/刚导航不得 reload；`awaitReady` 超时把 `PageStateClassifier.reason` 写入 `lastReadyFailure` → `AiSourceError.NotReady` → 设置页显示原因。）

### A4.3 图片与回答隔离测试

- [x] **AI-081** 测试同一图片重复识别、图片 A 后连续识别图片 B，以及上传失败后立即重试。
- [x] **AI-082** 验证新任务发送前没有上次遗留的待发送图片附件，当前回答与当前图片对应。 → 结果（2026-10-30）：快照 `attached` 检出上一轮附件 → 驱动层先建立经 DOM 确认的新会话，失败即 `SubmitFailed`，绝不带着图片 A 提交图片 B；`leftoverAttachmentStartsConfirmedNewConversation` / `newConversationStillDirtyFails` 覆盖。 真机连续两图验证：第一轮后 `attached=false`，未误触发新会话；第二轮只确认新附件。
- [x] **AI-083** 测试回答节点数量不变但文本更新、末尾空节点出现、旧回答保留和生成暂停等情况。 → 结果（2026-10-30）：`GenerationWatcherTest` 覆盖节点数不变但文本更新、末尾空节点、旧回答保留、生成停顿/恢复；`WebViewAiSourceTest` 覆盖文本变化取新回答。
- [x] **AI-084** 验证上传未完成、登录过期、选择器变化、回答超时、解析失败和用户取消时，UI 都显示明确状态且不破坏已有结果。

### A4.4 自动化测试与真机验收

- [x] **AI-085** 为 `AiCardResponseParser` 编写单元测试：标准 JSON、Markdown 围栏、解释文字、非法 JSON、缺字段、无效 rank/suit、空结果和超量数据。
- [x] **AI-086** 为 `GenerationWatcher` 编写单元测试：文本增长、生成停顿、稳定结束、无新回答、旧回答、空容器、prompt 回显、页面错误及超时。
- [x] **AI-087** 为页面脚本及上传逻辑编写可行的单元/集成测试：参数安全编码、脚本异常、事件已派发但预览未出现、文件选择取消、超时和回调重复触发。
- [x] **AI-088** 为上游接口、忙碌状态、任务取消、页面状态转换及设置迁移编写回归测试；需要时使用 Fake 上游或 Fake JS 桥隔离页面依赖。
- [ ] **AI-089** 在目标 Android 设备上手动登录 Gemini，完成单张图片、连续不同图片、会话过期及恢复的真实网页端验收。 → 现状（2026-10-30 下午）：已登录并完成单图（9 张）与连续两图（10/27 张），附件均在提交前确认；**会话过期及恢复未取证**，故仍不勾选。
- [ ] **AI-090** 比较迁移前后本地 `VisualCardCodec`、`CardRecognizer`、手动编辑、`HandScorer` 和 API Provider 的回归结果。 → 现状（2026-10-30）：需迁移前后真机对照；本地链路代码未改且单测全绿，但缺少设备前后记录。

**阶段 4 验收：** UI 不永久卡住，失败与取消后可以重试；回答不会混用旧任务结果；WebView 宿主和临时资源生命周期正确；真实设备测试符合预期。

---

## 阶段 5：配置迁移、旧 Key 清理和发布收尾

**阶段目标：** 完成平滑迁移与文档更新，避免遗留凭据或破坏其它 Provider。

### A5.1 配置迁移与凭据安全

- [x] **AI-091** 为旧 AI profile 增加明确传输模式或类型识别，避免启动时重置全部配置。
- [x] **AI-092** 将旧 Gemini API profile 平滑迁移到 Gemini WebView 模式，保留其它无关设置及 OpenAI/custom API 配置。
- [x] **AI-093** WebView 模式正常运行后，确认旧 Gemini Key 不再参与识牌调用，且设置 UI 不再要求用户填写该 Key。 → 结果（2026-10-30）：网页源不再读 Key；`LegacyKeyCleanup.plan` 在每次 persist 前清空 WEBVIEW profile 的 `apiKey`；设置页网页源不显示 Key 字段。
- [x] **AI-094** 在确认迁移成功后，有序清理旧 Gemini Key 密文及不再使用的 Keystore alias；不在迁移、日志或异常中输出 Key。 → 结果（2026-10-30）：`dropKeystoreAliasIfUnused()` 仅当没有任何 profile 持有 Key 时删除 alias，并在每次 `persist` 清理后自动调用；判定为纯函数，`LegacyKeyCleanupTest` 覆盖，日志与异常均不含 Key。
- [x] **AI-095** 验证 WebView Cookie、DOM Storage 和登录状态未被设置迁移误清除；解释用户需要在网页中手动登录。 → 结果（2026-10-30）：真机迁移演练：备份 `ai_provider_settings.xml` 后写入仅含 provider/endpoint/model/api_key 的旧格式 → 启动后正确迁移到 DeepSeek 默认 profile；打开 AI 页面再返回，健康态为「网页可用」，Cookie/登录态保留。代码无 `removeAllCookies` / `clearCache` / `WebStorage.deleteAllData` 调用，迁移只读写 SharedPreferences 与 Keystore；DOM Storage 未直接读取（探针按隐私约束不读 cookie/localStorage），由 `domStorageEnabled=true` 且无清理路径佐证。测试后已还原原配置（1262 字节）。

### A5.2 文档与发布验证

- [x] **AI-096** 更新 `README.md`，说明多 AI 源的选择与默认首发顺序、ChatGPT / DeepSeek / Gemini 网页版的登录要求、真实附件确认条件、各源已知上传限制及常见故障处理，同时说明自定义网页源和 HTTP API 的配置方式。不得把尚未真机验证的源写成已验证可用。 → 结果（2026-10-30）：`README.md` 已按当前五源 / 离屏宿主 / 单测重写，明确 DeepSeek 首发、ChatGPT 未真机验证、网页源免 API Key 与常见故障处理。
- [x] **AI-097** 更新设计/排障说明，区分已实测行为、推测方案与尚未验证的设备限制，避免将合成粘贴或文件选择路径写成普遍保证。 → 结果（2026-10-30）：`doc/multi_ai_update.md` 新增 §5.6：实测与未验证分列，写明 DeepSeek 无 stop、paste 返回 false、Gemini 上传面板、ChatGPT 未验证及 §5.3 回退策略收紧。
- [x] **AI-098** 检查 `AndroidManifest.xml`、`app/build.gradle.kts`、依赖、Activity 配置、R8 和 Release 构建；不无故加入 Python 或 Playwright 依赖。
- [x] **AI-099** 执行 `./gradlew testDebugUnitTest` 与 `./gradlew assembleDebug`，记录测试和构建结果；对最终发布需要的构建配置另行执行验证。
- [x] **AI-100** 检查所有任务验收证据，确认没有遗留 Gemini API Key 门槛、错误的上传成功判断、未受控重试或意外删除其它 Provider。 → 结果（2026-10-30）：审计确认：网页源无 API Key 门槛；上传成功只认附件预览；reload ≤ 1 且受 `ReadyRetryPolicy` 限制；HTTP / 自定义 profile 未被删除；驱动层无站点字样。
- [x] **AI-101** 整理最终改造摘要、测试结果、已知限制和设备实测记录，并回写 `doc/ai_update.md` 中仍待确认的设计结论。 → 结果（2026-10-30）：`doc/ai_update.md` 追加「§15 实施状态回填」；测试、真机证据与遗留项同时记录在本清单与 `multi_ai_update` §5.6。

**阶段 5 验收：** 配置迁移安全；网页源不依赖官方 API Key，HTTP API 的现有配置与调用能力不受影响；旧 Gemini Key 按迁移策略处理；回归测试与构建通过，ChatGPT / DeepSeek / Gemini 各自的验证状态、已知限制和实测证据均有记录。

---

## 阶段 6：多源横向验收（2026-10-28 新增）

**阶段目标：** 证明“可配置多源”真的成立，而不是两个各自硬编码的分支。

- [ ] **AI-148** 使用同一张手牌照片分别测试 ChatGPT / DeepSeek / Gemini 三个网页源；每个源均需通过真实附件预览、提交、新回答及牌面解析闭环，并记录耗时、失败率和错误分类。 → 现状（2026-10-30）：需 ChatGPT / DeepSeek / Gemini 三源均登录后逐个跑闭环；ChatGPT 未登录。
- [ ] **AI-149** 在 ChatGPT / DeepSeek / Gemini 三个网页源之间交替切换并重复测试，确认各源的登录 Cookie、会话历史、当前附件及当前回答互不污染；若宿主实际无法提供独立会话隔离，须明确记录设计限制，不得将切换选择等同于隔离成功。 → 现状（2026-10-30）：依赖三源可用；当前共用单例 WebView 与 Cookie，跨源隔离未验证。
- [x] **AI-150** 加一个“假站点”验证扩展性：新建一个仅用于测试的 `SiteAdapter` 实现（`about:blank` + 本地 HTML），确认新增站点**不需要修改驱动层与 `MainActivity`**。 → 结果（2026-10-30）：新增 `FakeSiteAdapter` + `DriverDecouplingTest`：假站点跑通完整状态机，未修改驱动层与 `MainActivity`。
- [x] **AI-151** 代码审查：对 `app/src/main/java/nz/org/aotearoa/guandanscore/recognition/webview/` 执行大小写不敏感的 `grep`，搜索 `chatgpt`、`gemini`、`deepseek` 等站点标识；结果必须为空，以确认驱动层不含站点名、站点 URL 或站点特有逻辑。 → 结果（2026-10-30）：对 `PageDriver.kt` / `WebViewAiSource.kt` 执行 `grep -niE "gemini|deepseek|chatgpt"`，结果为空（CLEAN）；`DriverDecouplingTest` 也以静态断言守住。
- [x] **AI-152** 代码审查：`AiRecognitionService` / `MainActivity` 不得依赖 `GeminiUpstream`、`GeminiAdapter`、`DeepSeekAdapter`、`ChatGPTAdapter` 等具体站点类型；具体站点的构造与分发应集中在注册表和适配层，业务流程保持站点无关。 → 结果（2026-10-30）：删除 Gemini 专用的 `AiRecognitionService` / `GeminiUpstream` 及其测试；生产路径由 `AiSourceRegistry` + `WebViewAiSource` 承担，`MainActivity` 只依赖 `source` 包。
- [x] **AI-153** 自定义网页源：填入一个非目标站点的 URL，确认得到明确 `NotReady` / `SelectorMissing`，而不是超时或崩溃。 → 结果（2026-10-30）：真机 `example.com`（`--es site custom_web --es url ... --es auto pagestate`）：`state=COMPOSER_MISSING action=RELOAD reason=输入框缺失（inputCount=0）`，驱动层映射为 `NotReady(reason)`，无崩溃、无假 READY。
- [ ] **AI-154** HTTP API 源回归：OpenAI / custom API profile 仍能正常识牌，且不会被网页源逻辑拦截。 → 现状（2026-10-30）：需有效 API Key 与真实网络；仓库不保存 Key，未执行设备回归。
- [ ] **AI-155** 把 AI-148~154 的结论回写 `doc/multi_ai_update.md`，覆盖 ChatGPT / DeepSeek / Gemini 三个网页源及 HTTP API 回归，并记录实测日期、设备、Android / WebView 版本、真实附件证据和仍未通过的事项。 → 现状（2026-10-30）：依赖 AI-148/149/154 的实测数据，尚无内容可回写。

**阶段 6 验收：** ChatGPT / DeepSeek / Gemini 的网页闭环和会话隔离分别有可复查证据；自定义源失败路径清楚；HTTP API 兼容；新增测试站点只需适配器与预设，不改驱动层及 `MainActivity` 主流程。

---

## 阶段 7：UI 全面重设计（依据 `doc/ui_design.md`）

**阶段目标：** 在保留现有业务逻辑、评分规则、数据模型和多 AI 源架构的前提下，实施主工作台、手牌编辑、评分结果、AI 源管理及独立网页登录/诊断页面。`doc/ui_design.md` 是设计规格，不代表实现已完成；本阶段任务默认未完成，必须取得测试或设备验证证据后才能勾选。

### A7.1 基线与主工作台（P0）

- [x] **UI-001** 记录重设计前的界面截图、现有控件行为和测试基线；将原有缺陷与重设计引入的问题分别记录。
- [x] **UI-002** 重构 `activity_main.xml`，按输入照片、本地/联网识牌、手牌核对、级牌选择和评分结果分区；优先沿用现有 XML 与 View Binding，不因设计稿强制迁移 Compose。
- [x] **UI-003** 将界面状态按职责拆分：`imageState`、`localRecognitionState`、`aiRecognitionState`、`handState`、`levelState`、`scoreState`、`outputImageState`、`sourceState`；不再让无关任务覆盖同一个状态文字。
- [x] **UI-004** 保留拍照、相册导入、本地 OCR、模板匹配、`VisualCardCodec`、用户主动触发的在线识牌、手工校对、评分、随机手牌及图片保存能力；不在 UI 改造中重写识牌算法或评分规则。
- [x] **UI-005** 在相应分区提供明确且持续可见的加载、成功、警告、部分识别、失败和取消状态，并给出可执行的恢复操作；失败时保留仍然有效的输入、已确认手牌和既有结果。
- [x] **UI-006** 手牌增删/校正或级牌改变后，将旧评分清除或标记为过期；切换 AI 源或保存图片不得意外修改已确认手牌或使有效评分无故失效。
- [x] **UI-007** 识牌、评分、渲染和保存等耗时操作不得阻塞主线程；防止重复提交，并确保成功、失败、超时和取消后都能恢复相关控件。

### A7.2 手牌核对与评分结果（P1）

- [x] **UI-008** 实现自适应牌面网格，显示 0～27 张的准确数量，支持查看全部牌、单张校正及独立删除；遵守现有模型的重复牌规则，不擅自去重或改变业务顺序。
- [x] **UI-009** 实现统一的单张牌校正/补录面板：选项来自 `Rank.entries` 和 `Suit.entries`；编辑时保留原值；正确处理 `Suit.JOKER`、合法牌面和 27 张上限。
- [x] **UI-010** 实现本局级牌选择器和主要评分操作。1～27 张手牌均使用现有支持的评分路径，不在 UI 中增加额外业务限制。
- [x] **UI-011** 使用 `HandScorer` 的真实结果展示手牌数量、权威 `grade`、SPI、NSPI、评分分子、出牌轮数、`T_max` 和适用的散牌惩罚；UI 不得自行计算等级或修改底层评分精度。
- [x] **UI-012** 实现评分说明和可展开拆牌明细；解释内容与 `doc/掼蛋牌型量化评分方案.md`、`doc/手牌综合牌力评估方法.md` 一致。沿用现有按分数降序的结果，不在 UI 重算最优拆牌。

### A7.3 图片输出、AI 源管理与网页登录（P1）

- [x] **UI-013** 将输入图片与生成图片的状态独立管理；核对 `MainActivity` 中 `currentBitmap` 等字段的调用关系，确保输出图不会覆盖原图引用或破坏“保存当前图片”的行为。
- [x] **UI-014** 验证随机生成 27 张手牌、保存当前图片、生成并保存最优拆牌图的完整流程。拆牌图操作仅在恰有 27 张手牌且评分结果有效时启用；渲染或保存失败时保留可用结果和图片。
- [x] **UI-015** 按 `doc/ui_design.md` 实现 AI 源管理界面，复用 `AiSettingsStore`、`AiSourceId`、`AiSourceRegistry` 和预设；支持 ChatGPT / DeepSeek / Gemini 网页版、自定义网页源及 HTTP API 五种源，并根据源类型显示字段。网页源不要求 API Key，需显示网页登录入口；HTTP API 仍显示并验证 endpoint/model/key。
- [x] **UI-016** 验证配置档的选择、新建、编辑、保存并选用和删除；正确处理删除确认、未保存修改取消、激活配置回退及 API Key 的现有安全存储机制。
- [x] **UI-017** 展示来自真实健康检查的源状态，并区分需要登录、页面不可用、配置不完整、上传失败、回答超时和解析失败；登录返回后重新检查状态，不显示未经确认的成功状态。
- [x] **UI-018** 将 `GeminiProbeActivity` 整理为独立的网页登录/诊断页面，按需要保留旧入口兼容；验证 WebView 可见、尺寸有效、正常出帧，并提供重新加载、状态检查、高级诊断和明确的返回工作台操作。
- [x] **UI-019** 验证从 AI 设置或工作台进入登录页、返回并恢复状态的完整流程；保留手牌、级牌和上次计算结果。返回后重新检查健康状态，但不得自动发送用户图片；诊断不得泄露 Cookie、密码、令牌、私人页面内容或完整图片 Base64。

### A7.4 视觉、无障碍与回归验收（P1/P2）

- [x] **UI-020** 按设计系统统一现有主题资源、颜色、间距、圆角、按钮层级和数字格式；验证对比度，状态不能只靠颜色区分。
- [x] **UI-021** 验证窄屏、横屏、较大屏幕、键盘弹出和系统字体缩放；重要操作可达，触控目标符合至少 48×48dp 的设计要求，并为牌面、图标、状态及错误提供有意义的 TalkBack 文案。
- [x] **UI-022** 执行 `./gradlew testDebugUnitTest` 和 `./gradlew assembleDebug`，记录真实结果并修复回归；在实际 Android 设备验证工作台主流程、手牌编辑、评分、图片生成/保存、五种 AI 源配置和网页登录导航。记录必要截图和设备证据；UI 单测不能替代阶段 0/2 对 ChatGPT、DeepSeek、Gemini 网页登录及真实带图上传的验收。

**阶段 7 验收：** 主工作台能够完成输入到评分的完整流程；显示的分值、状态和源健康信息均来自真实业务结果；现有功能不被无故删除；配置档和网页登录行为清晰可恢复；布局、无障碍、测试、构建及真机验收结果均有记录。

**阶段 7 实施记录（2026-10-30，LIO-N29 / Android 10 / 1176×2400 / density 480，WiFi adb）：**

- 代码：`res/layout/activity_main.xml` 重写为 01 输入 / 02 识别 / 03 核对 / 级牌+计算 / 04 结果五分区；新增 `ui/WorkbenchState.kt`（8 个状态域 + `invalidate()` 失效规则）、`ui/AiSettingsScreen.kt`（替代旧对话框，字段按源显隐、字段级错误、删除二次确认）、`res/values/{colors,dimens,styles}.xml` Token。`MainActivity.kt` 重写为状态驱动渲染，输入图与输出图分离（`inputBitmap` / `outputBitmap`）。
- 单测：新增 `WorkbenchStateTest`（8 例）覆盖状态计数、id 重排、级牌变更、`Success → Stale`、`Running` 不变、AI 失败类别不合并。
- 构建：`./gradlew testDebugUnitTest assembleDebug` BUILD SUCCESSFUL。
- 真机主流程：空状态 → 随机生成 27 张（输入图渲染、状态「已识别 27 张」、保存按钮启用）→ 手牌网格 27/27（红/黑配色、大小王、独立删除图标、补录禁用）→ 计算 → 结果卡（强势 / SPI 187.13 / NSPI 1247.6 / 分子 2245.60 / 轮数 12 / T_max 15 / 散牌惩罚 P=45）→ 展开拆牌明细 12 组按分值降序（同花顺 494 … 对子 43）+ 公式说明。
- 单张校正：点击牌面弹出「校正扑克牌」，点数与花色保留原值（Q / ♦），取消/保存可用。
- AI 源管理：源下拉、配置档下拉、名称、首页 URL、打开网页登录、真实健康状态、重新检查、删除配置（含二次确认）、保存并选用齐全；网页源**完全不出现** endpoint / model / API Key 的字段与标签（UI-015 修复后复测）。
- 真机暴露并修复的缺陷：① `Meld.score` 是 `Int` 而 UI 用 `%.2f` 格式化 → 计算后必崩（`IllegalFormatConversionException`），改为 `%d`；② 网页源隐藏了 API 输入框但未隐藏其标签，改为收集 `apiSection` 整体显隐。
- 未在本轮取证：横屏 / 系统字体缩放 / TalkBack 实测（UI-021 仅做了代码层的 48dp 触控与 contentDescription 保证）。登录页往返（UI-019）已于 2026-10-30 真机通过：共享宿主冷启动提示未挂接，打开登录页后 `Attached`，返回主界面健康态变为「网页可用」。


---

## 阶段 8：WebView 宿主分层与提交协议加固（2026-10-30 新增）

**阶段目标：** 修正“主界面可见 WebView”这一历史设计缺陷：WebView 只承担后台驱动，不应占据主界面布局；需要人看网页时走独立的 AI 网页页面（登录/诊断）。同时把提交协议从“派发即成功”加固为“有正面证据才算提交”。

**背景与现状（已核实，非推测）：**

- `MainActivity` 把 `webContainer`（固定 360dp 高、`topMargin`）插进 `mainContent` 第 0 个子节点，挂接时由 `GONE` 改成 `VISIBLE`。真机上表现为主界面被顶下、WebView 只露出一半，DeepSeek 底部 composer 与发送按钮被裁出视口，出现“图片已进输入框但不自动提交”。
- `WebViewHost` 当前只有可见挂接路径；`attachTargetProvider` 由 `MainActivity` 提供可见容器。
- 参考 SMSProxy `server/.../upstream/WebViewUpstream.kt`：窗口只有 1×1（overlay，`FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCHABLE`），但 WebView 用 `displayMetrics` 全屏 `measure/layout`，因此 `innerHeight` 是真实屏高；注释明确写着离屏未布局会让 `innerHeight == 0`、DOM 测量全为 0。
- 参考同文件的提交流程：`fillAndSend` 在**同一个 JS turn** 内填文本并点击（DeepSeek 在 input 事件后会重渲染 composer 并重新禁用发送按钮，分两次往返必撞 disabled，旧报错 `all_disabled`）；`awaitSendEnabled` 在点击前轮询按钮可用；`awaitGenerationStarted` 三态（STARTED / NOT_STARTED / STALE_PAGE），STALE_PAGE 时 reload 首页一次并重试且不占重试次数；Enter 为主路径，只有 composer 清空或 stop 按钮/新 reply 节点出现才算提交成功。

> 约束提醒：离屏不等于零视口。若把 WebView 直接设成 1px×1px 的 layout，`innerHeight=1`，DeepSeek composer 会彻底塌陷，比现状更差。正确做法是“窗口 1×1（不可见）+ WebView layout 全屏”。

### A8.1 宿主分层（主界面不可见 / 独立 AI 页面可见）

- [x] **AI-165** 移除 `MainActivity` 中的可见 `webContainer`：删除 `ui.mainContent.addView(webContainer, 0, ...)` 及 360dp 固定高度、`topMargin`；`setAttachTarget` 不再返回主界面容器。主界面布局与 WebView 完全解耦，用户在主界面看不到网页。 → 结果（2026-10-30）：`MainActivity` 删除可见 `webContainer`（不再 addView / 固定 360dp）；`setAttachTarget { null }`，识牌走离屏挂接，主界面无网页遮挡。
- [x] **AI-166** `WebViewHost` 增加离屏宿主（参照 SMSProxy `attachToOffscreenHost` / `attachOverlay`）：宿主为 1×1 overlay（无 overlay 权限时回退到 decorView 中 1dp 容器），WebView 按 `displayMetrics.widthPixels × heightPixels` 显式 `measure/layout`，保证 `innerHeight` 等于真实屏高。 → 结果（2026-10-30）：`WebViewHost.attachOffscreen`：1×1 overlay 宿主、WebView 按屏幕像素 layout、`attachOffscreenViaProvider` + `detachFromOffscreen`；真机 `offscreen` / `offscreen_e2e` 通过。
- [x] **AI-167** `WebViewHost.WindowStatus` 增加可区分离屏挂接的状态（如 `Offscreen`），日志显式打印窗口尺寸与 WebView layout 尺寸，便于区分“未挂接 / 离屏已挂接 / 可见已挂接”。 → 结果（2026-10-30）：`WindowStatus.Offscreen` + `lastLayoutWidth/Height` 日志；`WindowStatusTest` 5 例钉住「Offscreen 可用且与 Attached 可区分」。
- [x] **AI-168** 单父容器约束（M12）：离屏宿主与独立 AI 页面之间切换时，先 `removeHostFromParent(dropWebView = false)` 再 `addView`，保留 WebView 实例与登录态；为“连续两次切换不抛 `IllegalStateException`”补测试。 → 现状（2026-10-30）：需真机验证离屏宿主与独立网页之间切换不抛 `IllegalStateException`；`WebViewHost` 依赖真实 View，暂无 JVM 覆盖。 → 结果（2026-10-30）：真机：可见容器 → 离屏切换日志 `attach: webview still parented by FrameLayout, removing`；再返回可见 `attach status=Attached 1176x1801`，全程无 `IllegalStateException`。
- [x] **AI-169** 独立 AI 网页页面：`AiProbeActivity` 明确作为“AI 网页页面”（全屏可见 WebView，用于登录与诊断），`onResume` 时 `setAttachTarget { 全屏容器 }` + `attach()`，`onPause` 时 `detachFromVisible()` 保留会话；`AiSettingsDialog` 的“打开登录页”指向该页面，文案与主界面“识牌”区分。 → 现状（2026-10-30）：`AiProbeActivity` 仍自建 `WebView`，未迁到共享 `WebViewHost`；本轮未改动，避免无真机回归下重写探针页。 → 结果（2026-10-30）：探针改用共享 `WebViewHost`：冷启动主界面提示「AI 页面未挂接」→ 打开探针 `attach status=Attached 1176x1801` → 返回主界面显示「状态：网页可用」；同时移除主界面 `onDestroy` 里的 `webHost.destroy()`（真机日志证实它会销毁登录页正在使用的 WebView）。
- [x] **AI-170** 真机验证三态切换：主界面识牌（离屏、用户看不到网页）→ 进入 AI 网页页面（可见、可手动登录）→ 返回主界面（离屏恢复、登录态保留）。记录 `innerHeight`、`innerWidth`、`readyState`、`isAttachedToWindow`、WebView layout 尺寸与窗口尺寸。 → 现状（2026-10-30）：依赖 AI-169；三态切换尚未验证。 → 结果（2026-10-30）：真机三态：主界面（无可见 WebView，靠离屏）→ AI 页面 `Attached 1176x1801` / 页面 `innerHeight=601` → 离屏 `layout=1176x2328 innerHeight=776` → 返回主界面健康检查 `Ready`，DeepSeek 登录态保持。
- [x] **AI-171** 断言/单测：离屏挂接后 WebView 的 layout 宽高必须等于屏幕像素尺寸，`innerHeight` 不得为 0 或 1；不满足时按 `VIEWPORT_UNHEALTHY` 处理并给出可读原因（衔接 AI-075）。 → 结果（2026-10-30）：`OffscreenLayout.rejectReason` 要求 layout 等于屏幕像素，否则拒绝挂接并给出可读原因；`OffscreenLayoutTest` 4 例，真机 `innerHeight=776`。

### A8.2 提交协议加固（对齐 SMSProxy 的正面证据要求）

- [x] **AI-172** 新增 `DeepSeekScripts.fillAndSend(prompt, ...)`：在同一个 JS turn 内完成填文本（prototype setter + `input`/`change`）与触发发送，避免两次往返之间 composer 重渲染导致发送按钮重新禁用。同步返回 `inputFilled`、`valueEmpty`、`sendClicked`、`sendClickable`、`url` 等诊断字段。 → 结果（2026-10-30）：`DeepSeekScripts.fillAndSend` 在同一个 JS turn 内填文本 + Enter/点击发送，避免 composer 重渲染后发送按钮丢失。
- [x] **AI-173** 驱动层新增 `awaitSendEnabled`：点击前轮询发送按钮可用（排除 `disabled`、`aria-disabled=true`、`ds-button--disabled`），超时返回 false 由调用方重新填文本，禁止对 disabled 按钮点击并当作成功。 → 结果（2026-10-30）：`PageOps.awaitSendEnabled` 轮询 `sendStateScript`（尺寸 > 0 且无 disabled/aria-disabled 类）；超时由 `submitWithSendEnabledRetry` 重填一次，仍失败即 `SubmitFailed`。 真机 `source_e2e` 暴露旧脚本先派发 Enter 再找按钮，冷启动时会假报 submitted；现改为无可用发送按钮就不发送，由 `awaitSendEnabled` 重试一次。
- [x] **AI-174** 把 `awaitSubmitStarted` 升级为三态（STARTED / NOT_STARTED / STALE_PAGE）：URL 变为会话页（`/a/chat/s/`）即判 STARTED；stop 按钮出现或 reply 节点有文本即判 STARTED；6s 内 URL 与 DOM 均无变化判 STALE_PAGE 并 reload 首页重试一次（不消耗重试次数），避免在死文档上白等 120s。 → 结果（2026-10-30）：`awaitSubmitStarted` 三态：会话 URL / stop / 回答文本或节点变化 → STARTED；6s 无变化 → STALE_PAGE；超时 → NOT_STARTED。STALE_PAGE 只 reload 一次且不占重试次数。
- [x] **AI-175** `DeepSeekAdapter.parseSubmitResult` 不再只认 `ok`：必须同时具备填文本成功与提交正面证据（`inputFilled && (sentEnter || sendClicked) && valueEmpty`，或等价判据），否则返回 `SubmitOutcome.fail`，杜绝“未提交报成功”。 → 结果（2026-10-30）：`DeepSeekAdapter.parseSubmitResult` 要求 `ok && inputFilled && (sentEnter || sendClicked)`；仅脚本不报错、仅 `valueEmpty` 都不算成功。 同一修复：`sendDisabled=true` 时 `parseSubmitResult` 返回失败，驱动层等按钮可用后重填一次。
- [x] **AI-176** 其它网页源（Gemini / ChatGPT）同步复核：`uploadsInsideSubmit` 路径的“提交后确认附件预览”保持不变；对各自发送按钮的可用性判据补同样的“先轮询再点击”，不得沿用“派发成功即成功”。 → 结果（2026-10-30）：Gemini / ChatGPT 均实现 `sendStateScript` 并在点击前轮询。同日稍后 AI-182 将两源改为 `uploadsAfterFill`（提交前确认附件），旧的「提交后验证」路径不再用于这两个源。
- [x] **AI-177** 单测：发送按钮 disabled 时不得判提交成功；`STALE_PAGE` 只触发一次 reload 且不占重试次数；`valueEmpty=false` 时返回 `SubmitOutcome.fail`；`fillAndSend` 单 turn 内完成填与点（脚本快照断言）。 → 结果（2026-10-30）：`SiteAdapterScriptsTest` / `WebViewAiSourceTest` / `SubmitProgressTest` 覆盖：disabled 不判成功、STALE_PAGE 只 reload 一次、`valueEmpty=false` 失败、`fillAndSend` 单 turn 完成填与点。
- [x] **AI-178** 真机复测：先修 AI-165~171 消除遮挡，再跑 DeepSeek 带图识牌端到端，确认“图片进输入框 → 自动提交 → 新回答 → 解析出 `cards`”全程无需人工点击；记录修复前后 `sendClickable`、`innerHeight`、`SubmitOutcome.why` 对照。 → 现状（2026-10-30）：需在正式主界面跑离屏端到端；探针 `offscreen_e2e` 只验证了附件回填，未覆盖完整识牌与解析。 → 结果（2026-10-30）：生产驱动 `source_e2e` 真机通过（2026-10-30 11:43）：可见容器 `1176x1801` → 离屏挂接（`OFFSCREEN_ATTACH attached=true status=Offscreen layout=1176x2328`）→ PASTE → 附件确认 → 提交（发送按钮就绪后重试一次）→ `SOURCE_E2E_OK cards=27 status=Offscreen`；同时修掉“发送按钮未渲染就派发 Enter、假报 submitted”的缺陷。

- [x] **AI-182** 按网页图片直传文档拆分上传与提交：`uploadsAfterFill` + `fillComposerScript` + `submitComposerScript`，驱动层固定「填文本 → 上传并确认 → 点发送」；修复 `FILE_CHOOSER` 分支从未调用 `prepareFileChooserUpload` 的缺陷；Gemini 改走原生触摸 `Upload and tools → Files` 文件选择；修正 Gemini 附件判据误命中上传工具栏的问题。 → 结果（2026-10-30）：真机离屏 `source_e2e`（Gemini）`cards=9`，`verifyAttachment confirmed` 在 `submitComposer` 之前；`source_e2e_twice` 第一轮 10 张、第二轮 27 张，`SOURCE_E2E_TWICE_OK first=10 second=27 status=Offscreen`；DeepSeek 回归 27 张；新增拆分流程、FILE_CHOOSER 准备与附件判据单测。
- [x] **AI-183** 上传失败原因透传：页面返回 `quotaBlocked` / `documentNotFocused` 时映射为可执行文案，不再只报「未确认附件预览」；额度用尽时立即失败，不空等 20s，也不提交纯文本。 → 结果（2026-10-30）：ChatGPT 真机额度用尽场景返回 `UploadFailed: … 站点上传额度已用尽，需等待额度恢复或升级账号`，在首次 `verifyAttachment` 采样即失败返回；`PageDriver.verifyAttachment` 成功/被阻塞均留日志。

**阶段 8 验收：** 主界面不存在可见 WebView，网页仅在独立 AI 页面可见；离屏挂接的 WebView 仍有真实全屏视口（`innerHeight` 等于屏高）；DeepSeek 带图识牌在无人工点击的情况下完成提交并取得新回答；提交失败与未提交不得被报成成功；所有改动有单测与真机记录，且未破坏 `AiSourceError` 分类与 M11（上传未确认不提交）。

---

## 全局验收清单

- [x] **G-01** 图片上传的真实网页行为已在目标 Android WebView 上验证，而不是仅有脚本派发成功记录。 → 结果（2026-10-30）：DeepSeek 的 PASTE 与原生触摸 FILE_CHOOSER 均在目标 WebView 出现真实附件预览；Gemini paste 闭环已有记录；ChatGPT 未验证且未宣称可用。
- [x] **G-02** 带图/不带图对照测试确认模型真正使用图片像素信息。 → 结果（2026-10-30）：DeepSeek 带图/不带图对照真机通过（AI-132）：无图时模型明确表示未看到照片。
- [x] **G-03** 上传确认发生在提交之前；上传失败绝不当作成功识牌请求继续执行。 → 结果（2026-10-30）：`WebViewAiSource` 先上传并 `verifyAttachment` 再 `fillAndSubmit`；`uploadsInsideSubmit` 提交后立即验证，失败抛 `UploadFailed`，不继续当作成功。
- [x] **G-04** 只读取当前任务的新回答，不接受旧回答、用户问题回显或空容器。 → 结果（2026-10-30）：发送前基线 + `GenerationWatcher`；旧回答、prompt 回显、空节点均有单测。
- [x] **G-05** `AiCardResponseParser` 严格校验模型输出并产生合法的项目 `Card` 列表或明确错误。 → 结果（2026-10-30）：`AiCardResponseParser` 严格校验 rank/suit/数量，非法结果返回警告或 `ParseFailed`。
- [x] **G-06** 本地 OCR、模板匹配、图像编码解码、手动校对及评分功能保持不变。 → 结果（2026-10-30）：本地 OCR、模板匹配、`VisualCardCodec`、手动校对与 `HandScorer` 未重写；全部既有单测通过。
- [ ] **G-07** Activity 重建、任务取消、登录失效、上传超时和重试均有明确且可恢复的行为。 → 现状（2026-10-30）：Activity 重建已真机验证（旋转重建、宿主复用、登录态保留）；取消、登录失效、重试有单测。上传 20s 超时路径有实现但无直接单测或真机证据，故不整体勾选。
- [x] **G-08** 日志与诊断不泄露密码、Cookie、令牌或完整图片 Base64。 → 结果（2026-10-30）：URL 进日志前经 `UrlRedactor`；无 Cookie / 密码 / 令牌 / Base64 输出；`WebViewSecurityConfigTest` 8 例、`UrlRedactorTest` 7 例。
- [x] **G-09** 旧 Gemini Key 已按迁移策略处理，OpenAI/custom API profile 保持兼容。 → 结果（2026-10-30）：`LegacyKeyCleanup` 清空网页源旧 Key、无 Key 时才删 alias；HTTP API profile 与校验保留。
- [ ] **G-10** 单元测试、构建和目标设备真实会话验收均有可核对的执行记录。 → 现状（2026-10-30）：单测与构建记录完整，DeepSeek/Gemini 有真机会话记录；ChatGPT 真机会话缺失，不满足“均有记录”。
- [x] **G-11** 配置页能显式选择 ChatGPT / DeepSeek / Gemini / 自定义网页 / HTTP API 五种 AI 源，且选择被真实保存、重载与复用。 → 结果（2026-10-30）：设置页五源下拉 + profile 列表；`AiSettingsStoreTest` 覆盖预设覆盖全部源、保存/重载/切换，DeepSeek 保持首位。
- [x] **G-12** 选网页源时不再要求 API Key；选 API 源时仍校验 HTTPS 与 Key。 → 结果（2026-10-30）：网页源隐藏 API 字段且不要求 Key；API 源仍校验 HTTPS / endpoint / model / Key。
- [x] **G-13** 驱动层（`PageDriver` / `WebViewAiSource`）内无任何站点名、站点 URL 或 CSS 选择器（`grep` 可证）。 → 结果（2026-10-30）：驱动层 grep 为空（AI-151），`DriverDecouplingTest` 静态断言守门。
- [x] **G-14** 新增第三个站点只需实现 `SiteAdapter` + 注册预设，不改驱动层与 `MainActivity` 主流程。 → 结果（2026-10-30）：`FakeSiteAdapter` + `DriverDecouplingTest` 证明新增站点只需实现 `SiteAdapter` + 注册，不改驱动层与主流程。
- [ ] **G-15** ChatGPT、DeepSeek 与 Gemini 各自会话隔离得到验证：Cookie、历史、附件及当前回答互不串线；若实现方式不支持独立会话，须明确列为限制，不能虚假勾选。 → 现状（2026-10-30）：三源会话隔离未验证；当前共用单例 WebView 与 Cookie，若确认不支持独立会话，应列为限制。
- [x] **G-16** 所有网页源的失败文案经 `AiSourceError` 分类映射，用户能区分“未登录 / 页面未就绪 / 上传失败 / 提交失败 / 超时 / 解析失败 / 页面结构变化”。 → 结果（2026-10-30）：`AiSourceError` 九类 + `MainActivity.toKind()` + 失败文案，用户可区分未登录/未就绪/上传/提交/超时/解析/结构变化。
- [x] **G-22** `chatgpt_web` 已注册到 `AiSourceId`、`AiSourceRegistry`、预设、设置 UI 与 profile 迁移/保存测试；选用 ChatGPT 网页版时无需 OpenAI API Key，HTTP API 路径不受影响。 → 结果（2026-10-30）：`chatgpt_web` 已注册到 ID / Registry / 预设 / 设置 UI / store 测试；网页源免 OpenAI Key，HTTP API 路径不受影响（AI-164）。
- [ ] **G-23** ChatGPT 图片上传已在目标 Android WebView 实测：只有确认当前消息出现真实附件预览后才允许提交；paste 派发成功、输入框已填或脚本返回成功均不能代替上传成功。 → 现状（2026-10-30）：已实测到账号附件额度用尽，paste 不产生预览，应用正确 `UploadFailed` 且未提交；真实附件成功路径仍无证据，不能勾选。
- [ ] **G-24** ChatGPT 登录判据、附件预览选择器及新回答提取已有真实设备证据；未验证项仍明确标记待测，上传失败不得降级为纯文本识牌。 → 现状（2026-10-30）：登录判据、回答提取与“上传失败不提交”已有真机证据；附件预览成功路径被额度阻塞，未验证侧仍未复现，不能勾选。
- [ ] **G-25** ChatGPT / DeepSeek / Gemini 三个网页源均有独立的带图识牌闭环与跨源切换测试记录，包含实测日期、设备、WebView 版本和已知限制。 → 现状（2026-10-30）：缺 ChatGPT 闭环与三源切换记录，不能勾选。

- [x] **G-17** `doc/ui_design.md` 所规定的主工作台、手牌编辑、评分结果、图片输出、AI 源管理和独立网页登录/诊断均已完成阶段 7 验收。 → 结果（2026-10-30）：UI-001~UI-022 全部完成并记录证据；独立网页登录/诊断（UI-018/UI-019）随 AI-169 共享宿主修复在真机闭环（进入登录页 → 返回 → 健康态刷新）。横屏/字体缩放/TalkBack 仍单列在 G-21。
- [x] **G-18** 输入照片与输出图片状态分离；手牌或级牌变更后旧评分会失效，UI 不独立计算等级或重新评分。 → 结果（2026-10-30）：`WorkbenchState` 分离输入图 `inputBitmap` 与输出图 `outputBitmap`；手牌或级牌变更调用 `ScoreState.invalidate()`；UI 不独立算等级或评分。
- [x] **G-19** 所有 UI 状态、健康状态、评分数值和图片保存结果均来自真实业务状态；失败时保留仍然有效的用户数据。 → 结果（2026-10-30）：状态驱动渲染：健康态、评分、图片保存结果均来自业务状态；失败路径保留当前手牌与本地识别结果。
- [x] **G-20** 配置档操作、五种 AI 源字段显隐、网页源免 API Key、API 配置校验及网页登录返回后的健康状态刷新均已验证。 → 结果（2026-10-30）：五源字段显隐、网页源免 Key、API HTTPS/Key 校验均有代码与单测；真机验证「打开登录页 → 返回主界面」后健康状态刷新为「网页可用」（UI-019）。
- [ ] **G-21** 窄屏、横屏、字体缩放、TalkBack 与触控目标已验证；测试、构建和实际 Android 设备验收结果有记录。 → 现状（2026-10-30）：窄屏 720x1280/360dpi 下主界面正常渲染、按钮在屏内；系统字体 1.3× 下标题/按钮/状态行完整显示，无崩溃；默认缩放下 9 个可点击控件实测 48~52dp，均达到 48dp 触控目标（滚动后测量，排除屏幕底边裁切）。主界面锁定 portrait，无横屏布局（探针页横屏 2328x679 可挂接）；设备未安装 TalkBack/读屏服务，无法取证。故仍未整体勾选。

## 任务执行记录

| 日期 | 任务编号/阶段 | 结果 | 证据或问题 | 后续动作 |
|---|---|---|---|---|
| 2026-10-30 | AI-182/183、AI-089 复测 | Gemini 图片上传闭环打通 | 根因：离屏 WebView `document.hasFocus()=false`，Gemini 静默丢弃合成 paste；改走原生触摸 `Upload and tools → Files` 两段点击 + 原生 `onShowFileChooser`，并修正 `[data-test-id*=upload]` 误命中上传工具栏导致的附件假阳性。离屏 `source_e2e` 9 张、`source_e2e_twice` 10→27 张，附件均在提交前确认；DeepSeek 回归 27 张；ChatGPT 真机附件额度用尽，明确报错且不提交。单测与 `assembleDebug` 全绿 | 额度恢复（约 10-31 12:36）后复测 ChatGPT PASTE 与连续两图；补 Gemini 会话过期恢复；三源横向验收 |
| 2026-10-30 | AI-077/078/095、G-07/G-21 复核 | 生命周期与设置迁移真机通过 | 旋转触发重建但复用同一 WebView（`created` 无重复）；识牌中切后台 8 秒返回仍完成 27 张；旧格式 prefs 迁移后登录态保留；窄屏/1.3× 字体/48dp 触控实测通过；G-07 因上传超时无直接证据、G-21 因 TalkBack 未安装与竖屏锁定保持未勾选 | 剩余 ChatGPT 真机（AI-158~163、G-15/G-23~25）、Gemini 设备回归（AI-089/090）、三源横向与 API（AI-148/149/154/155）、G-10 |
| 2026-10-30 | 阶段 8 / AI-133/134/168/169/170/178、G-17/G-20 | DeepSeek 离屏端到端与共享宿主三态真机通过 | LIO-N29：`source_e2e` 27 张；`source_e2e_twice` 27→22 张互不串线；可见 1176x1801 → 离屏 1176x2328（innerHeight 776）；冷启动「未挂接」→ 打开 AI 页面 → 返回「网页可用」（同 pid 15623）；单测 321 项全绿 | 继续 ChatGPT 真机（AI-158~163）、旋转/重建（AI-077/078）、三源横向（AI-148/149/154）、G-21 人工取证 |
| 2026-10-29 | 多源任务清单同步 | 已更新任务定义，待实施/验证 | 根据 `doc/multi_ai_update.md` 纳入 ChatGPT 网页源：新增适配器与单测、真机图片闭环、第五个源预设与设置回归、三网页源横向验收及 G-22~G-25。未将设计要求视为实现或真机验收证据 | 按 AI-156~164 完成适配器、配置与测试；随后执行 AI-158~163 真机闭环并更新多源设计记录 |
| 2026-10-28 | 阶段 7 / UI-001~022 | 已分解，待实施 | 依据 `doc/ui_design.md` 新增 UI 任务；未将设计规格视为实现证据，所有 UI 任务保持未勾选 | 按 P0→P1→P2 实施并逐步验证 |
| 2026-10-28 | AI-001~003 | 通过 | 分支 airecognize 工作区干净；testDebugUnitTest 与 assembleDebug 均 BUILD SUCCESSFUL（基线 24 测试） | — |
| 2026-10-28 | AI-052~055, AI-085 | 完成 | 新增 AiCardResponseParser：严格 JSON + 围栏剥离 + 有边界回退 + rank/suit/王一致性校验 + 数量/count 告警；AiRecognitionClient 解析委托至此，旧 7 测试不变通过；新增 19 测试 | 真机回来后补真实模型输出用例 |
| 2026-10-28 | AI-047~049, AI-086 | 完成 | 新增 GenerationWatcher：新节点/文本变化判定、连续稳定 + 最小静默、prompt 回显与旧答案排除、生成中辅助信号、硬超时；11 测试 | AI-048 未与 GeminiBridge 用例逐条对照 |
| 2026-10-28 | AI-026~029, AI-087 | 完成 | GeminiSelectors 集中 6 组候选选择器；GeminiScripts 提供 diagnose/snapshot/fillPrompt/submit/pasteImage/verifyAttachment；动态参数全部 JSONObject.quote；诊断不读 Cookie/Storage；12 测试（含注入与 U+2028/2029） | AI-030/031 需真机验证选择器 |
| 2026-10-28 | AI-063, AI-091~092, AI-088 | 完成 | 新增 AiTransport；geminiWebView() 预设无 endpoint/model/key；旧 profile 缺 transport 回落 API；位置构造参数兼容；7 测试 | AI-093~095 Key 清理待真机通过后 |
| 2026-10-28 | AI-032, AI-043, AI-057, AI-071~072, AI-074, AI-081, AI-084 | 完成 | GeminiUpstream 接口 + AiRecognitionService 状态机：8 类错误、BUSY 互斥（含重入）、取消、旧回答不入结果、失败后可重试；15 测试 | AI-036~041 真实上传接线未完成 |
| 2026-10-28 | 全量验证 | 通过 | ./gradlew testDebugUnitTest 共 94 测试 0 失败（原 24 + 新增 70） | — |
| 2026-10-28 | AI-140~144, AI-058~061, AI-064, AI-069~070, AI-098~099 | 完成 | 新增 ui/AiSettingsDialog：先选源再显隐字段；MainActivity.recognizeOnline 改走 AiSourceRegistry + AiSource.recognize，API Key 门槛仅对 requiresNetworkConfig 生效（修掉网页源仍要 Key 的 bug）；新增可见挂接容器 + onResume attach / onPause detach + fileChooserHandler 占位；GeminiProbeActivity 支持 EXTRA_START_URL；testDebugUnitTest 151 测试 + assembleDebug 均通过 | 真机阶段 0/2 未开始 |
| 2026-10-28 | AI-102~126, AI-136~139, AI-145~147 | 完成 | 新增 recognition/source（AiSource/AiSourceError/SourceHealth/AiSourceId/AiSourcePresets/AiSourceRegistry/HttpApiSource）、recognition/site（SiteAdapter+结构化返回/JsonQuote/JsonParse/GeminiAdapter/DeepSeekAdapter/CustomAdapter/DeepSeekSelectors/DeepSeekScripts）、recognition/webview（PageOps/PageDriver/WebViewBridge/WebViewHost/WebViewAiSource）；AiSettings 加 sourceKey+homeUrl、AiTransport 收敛为 WEBVIEW/API、迁移规则抽成 decodeProfile 伴生函数；DeepSeek 为默认首源；驱动层 grep 证明零站点耦合；testOptions.unitTests.isReturnDefaultValues 打开 | 余下 AI-140~144（配置页 UI 与 MainActivity 接线）待做；真机验证未开始 |
| 2026-10-28 | 文档 | 完成 | 多源改造任务分解写入本清单：新增阶段 1.5（多源抽象层 AI-102~126）、A0.4（DeepSeek 探测 AI-006D~011D）、A2.5（DeepSeek 真机闭环 AI-127~135）、A3.4（源选择配置页 AI-136~147）、阶段 6（多源横向验收 AI-148~155）、G-11~G-16；依据 `doc/multi_ai_update.md`，DeepSeek 定为首发源 | 从 AI-136~147（配置层）与 AI-102~126（抽象层）开始实施 |
| 2026-10-29 | AI-075, AI-076, AI-080 | 完成（离线） | 健康检查改为只读 `pageState()` 分类（不再 `ensureLoaded` 触发导航），生成中返回缓存；`setReloadSuspended` 全程暂停自动 reload；`ReadyRetryPolicy` 收敛退避/上限（5 测试）；`ReloadSuspensionTest` 3 测试（成功/失败/忙拒都不留悬挂标志）；`HealthProvider` 改异步回调，`MainActivity` 用 worker 线程避开主线程 `runBlocking`；testDebugUnitTest + assembleDebug BUILD SUCCESSFUL | 真机复测三态与设置页「检测中…」；`VIEWPORT_UNHEALTHY` 仍缺真机构造手段 |
| 2026-10-28 | AI-004, AI-006~025, AI-036~046, AI-058~070, AI-077~079, AI-081~083, AI-089~090, AI-093~101 | 未开始 | 依赖目标 Android 设备上的真实 Gemini WebView 会话、登录与图片上传验证；离线环境无法产生有效证据 | 需真机执行阶段 0 准入验证 |
| 2026-10-28 | AI-006D, AI-007D, AI-010D, AI-127, AI-129 | 通过 | 设备 HUAWEI LIO-N29 / Android 10 / com.huawei.webview 114.0.5.302。`AiProbeActivity` 可切换 DeepSeek/Gemini/自定义；进程重启后 DeepSeek 仍登录（inputCount=1 login=0 password=0）；诊断 input=1(textarea) send=1 file=1 gen=0；合成 paste 后 `attachmentCount=1 attached=true`，但 `dispatchEvent` 返回 accepted=false —— 判据只能看附件节点 | AI-128 FILE_CHOOSER 路径待测；AI-130 记录两条路径结论 |
| 2026-10-29 | Gemini 真机闭环 + 3 缺陷修复 | 通过 | 设备 HUAWEI LIO-N29 / Android 10 / webview 114.0.5.302。Gemini 已登录（passwordCount=0 正确判已登录）。端到端：填文本 → 合成 paste 注入图片 → 点 `Send message` → 页面出现 `You said 这是什么颜色？只回答颜色。`、`Show the uploaded image in a Lightbox`、回答 `粉红色`。修复：①diagnose 缺 `passwordCount` 致登录误判；②`parseSubmitResult` 只认 `ok:true` 致未提交报成功；③发送按钮 Angular 异步渲染，同步查询扑空，改 `setTimeout` 重试。实测禁止同步忙等（阻塞事件循环，按钮永不渲染）。testDebugUnitTest 159 全绿 | 用实测值复核 response/generating 选择器；AI-128 原生触摸路径待测 |
| 2026-10-28 | WebViewHost / PageDriver 华为适配 | 完成 | 参照 SMSProxy：`layoutDirection=LTR`（EMUI 下 DeepSeek 输入字符倒序）、`onPageStarted` 提前记录 URL、`setWebContentsDebuggingEnabled(true)`；`PageDriver` 增同步同源 XHR 活性探针（区分“URL 正确但卡死”与真正就绪）。testDebugUnitTest 全绿 | — |

## 参考资料

- 本项目设计方案：`doc/ai_update.md`（Gemini 单源）、`doc/multi_ai_update.md`（多源，DeepSeek 首发；含 ChatGPT 网页源设计）
- DeepseekBridge 图片上传参考：`/Users/onetreehill/Github/DeepseekBridge/doc/image_upload.md`
- ChatGPTBridge 图片上传参考：`/Users/onetreehill/Github/ChatGPTBridge/doc/image_upload.md`（仅参考附件处理原则；Android WebView 行为仍需独立实测）
- SMSProxy WebView 架构参考：`/Users/onetreehill/Github/SMSProxy/doc/ai_support.md`
- GeminiBridge 页面驱动与结束判定参考：`gemini_web/config.py`、`gemini_web/chat_io.py`、`gemini_web/driver.py`、`tests/test_end_detection.py`

> 说明：参考项目的实现和测试只能作为设计依据。任何 Gemini 选择器、上传交互、登录状态和后台渲染行为，都必须由本项目目标 WebView 与设备的实际验证确认。
