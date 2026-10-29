# State —— 当前状态

> 只保留恢复工作所必需的信息。任务完成即就地改写，不追加历史流水。
> 最后更新：2026-10-30

## 当前分支

`airecognize`

## 当前任务

**执行 `doc/ai_tasks.md`：多 AI 源 WebView 迁移 + 阶段 7 UI**（阶段 7 已完成）

### 已完成（离线可验证）

- 阶段 0 A0.1 基线；AI-052~055、AI-047~049、AI-026~029、AI-063/091/092/088、AI-032~084 离线部分。
- 多源抽象层：`recognition/source`、`recognition/site`（Gemini/DeepSeek/Custom 适配器）、`recognition/webview`（PageOps/PageDriver/WebViewBridge/WebViewHost/WebViewAiSource）。
- `ui/AiSettingsDialog` 源选择 + 按源显隐字段；网页源免 API Key。
- `probe/AiProbeActivity`：站点切换 + 诊断/快照/基线/粘贴/填+贴+发 + `--es auto` 无人值守入口（`e2e` / `baseline`）。

### 已完成（真机验证，HUAWEI LIO-N29 / Android 10 / webview 114.0.5.302）

- DeepSeek：登录态跨进程保持；诊断 input=1 send=1 file=1；合成 paste 后 `attached=true`（`dispatchEvent` 返回 false，不可作判据）。
- Gemini：**已登录**，`Ask Gemini` contenteditable + `Upload and tools`；`passwordCount=0` 正确判为已登录。
- Gemini 端到端闭环通过：填文本 → 合成 paste 注入图片 → 点 `Send message` → 页面出现 `You said …`、`Show the uploaded image in a Lightbox`、回答 `粉红色`。
- **DeepSeek 端到端识牌闭环通过（AI-128 验收）**：`prepareChooserImage` → 原生触摸回形针 → `onShowFileChooser` 回填真实手牌图 → 附件验证 `attached=true` → 提交 → 轮询 → `cards=10 warnings=[]`，识别结果与合成手牌图逐张一致（♠A ♠Q ♥K ♣7 ♦9 ♠2 ♥8 ♦J ♣Q ♠4）。

### 真机暴露并已修复的缺陷

1. `diagnose()` 不输出 `passwordCount` → `parseLogin` 恒 false（误判登录态）。已补 `GeminiSelectors.PASSWORD` 及 diagnose/snapshot 字段，`DiagnoseResult.passwordCount`。
2. `parseSubmitResult` 只认 `ok:true` → 未提交也报成功。改为必须 `filled && submitted`。
3. 发送按钮由 Angular **异步渲染**，同步 `querySelector` 必扑空 → 误退 Enter。改为 `setTimeout(retry,50)`×40 异步重试。
4. 同步忙等（`while(Date.now()<dl)`）会阻塞 JS 事件循环，Angular 永远渲染不出按钮 —— 实测 2s 内 `bySel/byAria` 恒 0。**禁止同步忙等**，已有单测断言。
5. 探针 `runScript` 只做 `removeSurrounding("\"")`，未还原 `\n`/`\\`/`\uXXXX` 转义 → 含引号的 JSON 数组回答解析成 null（`E2E_POLL: snapshot null`）。改用 `JSONTokener` 正确解码（AI-133）。
6. `AiCardResponseParser.parseStrict` 只认 `{"cards":[...]}` 对象格式 → 模型返回顶层裸数组 `[...]`（探针 prompt 要求）时必然退回正则兜底并告警。已兼容裸数组（AI-134），新增 2 个单测。
7. **`JsonParse.parse` 漏解 `evaluateJavascript` 的二次 JSON 编码**（2026-10-30 补修）：`evaluateJavascript` 对字符串返回值会再编码一次，脚本 `JSON.stringify(obj)` 到达 Kotlin 时是带外层引号、内部反斜杠转义的字符串字面量。旧实现直接 `JSONObject(raw)` 必失败，`probeLiveness` 恒 `NOT_READY`，表现为「页面健康却永远判未就绪」。现 `JsonParse.parse` 先判 `{` 直解，否则按 JSON 字符串字面量 `JSONTokener` 反转义后再解析；新增 `JsonParseTest` 4 例（对象文本 / 二次编码字面量 / 空与 null / 垃圾输入）。
8. `probeLiveness` 的 `NOT_READY` 原先静默返回，无法区分「页面未 commit」「视口塌陷」「composer 缺失」。现三条失败分支各打一行 `Log.i(TAG, "probeLiveness NOT_READY: ...")`，带 `readyState / href(已脱敏) / innerHeight / inputCount`，配合 `UrlRedactor` 不泄漏完整 URL 查询串。

### 已完成（AI-011 窗口基线）

- 探针新增「基线」按钮 + `--es auto baseline`（`logBaseline()` + `PAGE_BASELINE`）。真机 DeepSeek 实测：视图 `1176x1801` / density 3.0 / dpi 480 / 屏幕 `1176x2328` / `attachedToWindow=true` / `isShown=true` / parent=LinearLayout；页面 `visible` / `innerWidth×Height=392×601` / DPR=3 / `docClient=392×601` / `scroll=0,0` / `docScrollH=601` / `readyState=complete`。切后台对照须比对这些字段。

### 已完成（AI-075 / AI-076 / AI-080 健康检查只读化 + 退避上限，离线）

- `PageOps.pageState()`：只读一次诊断并分类，**不导航、不 reload**（默认返回 null）。`healthCheck()` 改走它，不再用 `ensureLoaded()` 做健康探测——原来的实现会让「点一下设置页的健康检查」变成一次真实导航。
- 分类映射显式化：`LOGIN_REQUIRED → NotLoggedIn`；`LOADING / COMPOSER_MISSING / VIEWPORT_UNHEALTHY → Unavailable`（附 `PageStateClassifier.reason`）。**选择器缺失与视口异常绝不能被报成未登录**，已有单测钉住。不支持只读诊断的实现回落 `legacyHealthProbe()`，行为不变。
- `PageOps.setReloadSuspended()` + `WebViewAiSource.recognize` 全程置位：生成中 reload 会直接丢弃正在进行的请求，比多等一会儿糟糕得多。失败路径同样恢复（否则一次失败会永久关掉后续 reload），忙拒发生在置位之前、不留悬挂标志——三条都有单测。
- 新增 `ReadyRetryPolicy`（纯函数）：300ms 起 ×1.5 退避封顶 1200ms、reload ≤1 次、生成中/刚导航不得 reload，并给出跳过原因供日志；`awaitReady` 超时把分类原因写进 `lastReadyFailure` → `AiSourceError.NotReady`。
- `AiSettingsDialog.healthProvider` 契约改为异步 `(AiSettings, (String) -> Unit) -> Unit`，先显示「检测中…」；`MainActivity` 用 worker 线程跑健康检查再回主线程，避开 AI-023 的主线程 `runBlocking` 死锁防线。
- 新增 `ReadyRetryPolicyTest`（5）+ `ReloadSuspensionTest`（3）+ `WebViewAiSourceTest` 6 例；`./gradlew testDebugUnitTest assembleDebug` BUILD SUCCESSFUL。
- **真机复测（LIO-N29 / Android 10，18:00~18:05）**：Gemini 已登录首页 `READY / PROCEED`（input=2 file=1 login=1 password=0）；DeepSeek 重定向 `/sign_in` 时 DOM 全 0（input/send/login/password 均 0）仍正确判 `LOGIN_REQUIRED / NEEDS_USER`，证明 URL 判据是唯一有效依据。
- **未取得证据的两项**：① `VIEWPORT_UNHEALTHY` 仍无真机构造手段；② 「生成中暂停 reload」被前置阻塞挡住——Gemini 原生触摸上传 `attached=false`（e2e 按 AI-123 中止，防线本身正确），改走粘贴路径 `filled/pasted/submitted=true` 但 `processSession → net::ERR_FAILED`（同一域名 ping 通，非断网，疑登录态/风控）。二者分别对应未完成的 AI-015 与 AI-089。
- 真机操作备忘：`am force-stop` 在本机不生效（pid 不变、`--es auto` 不被消费），须 `am force-stop` + `am kill` 组合；`logcat -c` 不清主缓冲，会混入 10-25 旧日志，必须用 `logcat -d --pid=$(pidof ...)` 按进程过滤。

### 未完成

- ~~AI-009 / AI-128 FILE_CHOOSER 原生触摸路径的真机验收~~ ✅ 已完成（DeepSeek 真机 10 张牌零告警）。
- AI-012 压缩后识牌精度的真机复跑记录（DeepSeek 端到端已在 AI-128 用同源真实手牌图通过）。
- ~~AI-166/AI-167 离屏挂接的真机验证~~ ✅ 已通过（2026-10-30，LIO-N29 / Android 10）。探针 `--es auto offscreen` 走真实 `WebViewHost.attachOffscreenViaProvider()`：`attachOffscreen: window=1x1 webviewLayout=1176x2328 display=1176×2328 anchorAttached=true`、`OFFSCREEN_ATTACH: attached=true status=Offscreen`、Gemini 首页 `readyState=complete`、`innerWidth×Height=392×776`、`OFFSCREEN_OK innerHeight=776`（远超 `AttachGate.MIN_VIEWPORT_HEIGHT=100`）。`WindowStatusTest` 5 例钉住「Offscreen 与 Attached 等价可用」。
- ~~离屏 + 文件选择器闭环~~ ✅ 已通过（同日）：探针 `--es auto offscreen_e2e` 走 `PageDriver.prepareFileChooserUpload`（写 cache → FileProvider → `prepareUpload` → 原生触摸 → `onShowFileChooser` 回填），DeepSeek 实测 `hasImage=true` / `attachmentCount=1` / `OFFSCREEN_E2E_OK`。**本轮暴露并修掉的真 bug**：`res/xml/file_paths.xml` 只声明了 `cache-path photos/`，而驱动层写 `cacheDir/ai-upload/`，`FileProvider.getUriForFile` 抛 `IllegalArgumentException: Failed to find configured root`（此前只跑过探针自己的 `Uri.fromFile` 路径，故未暴露）；已补 `ai-upload/` 与 `probe/` 两条 path。另：探针内不得在主线程调用 `PageDriver.prepareFileChooserUpload`（内部 `runOnMainSync`，触发 AI-023 断言），已改为 worker 线程 + `runOnUiThread` 回主线程验证。
- AI-015 Gemini 端 `--es site gemini_web` 的原生触摸 → `onShowFileChooser` 回填真机实测（代码路径已就绪，DeepSeek 同路径已由 AI-128 验收）。

- 旧 Gemini Key 清理（AI-093~101）。
- 阶段 8（`doc/ai_tasks.md` AI-165~178）尚未开工：仅完成任务书撰写，代码未动。

### 已完成（阶段 7 UI 全面重设计，2026-10-30）

代码：

- `res/layout/activity_main.xml` 重写为五分区（01 输入 / 02 识别 / 03 核对 / 级牌+计算 / 04 结果），全部走 View Binding。
- 新增 `ui/WorkbenchState.kt`：8 个状态域（image / localRecognition / aiRecognition / hand / level / score / outputImage / source）+ `ScoreState.invalidate()` 失效规则，纯 JVM 可测。
- 新增 `ui/AiSettingsScreen.kt` 取代旧对话框：按源显隐字段（网页源整体隐藏 endpoint/model/key 及其标签）、字段级错误文案、删除二次确认写明 profile 名、真实 healthCheck。
- 新增 `res/values/dimens.xml`、`styles.xml`，扩充 `colors.xml` 为设计 Token。
- `MainActivity.kt` 重写为状态驱动渲染；`inputBitmap` / `outputBitmap` 分离（UI-013），保存当前图片永远取输入图。
- 新增 `WorkbenchStateTest`（8 例）。

真机（LIO-N29 / Android 10 / 1176×2400 / density 480，WiFi adb `192.168.3.158:5555`）：

- 主流程：空状态 → 随机 27 张 → 网格 27/27 → 计算 → 结果卡（强势 / SPI 187.13 / NSPI 1247.6 / 分子 2245.60 / 轮数 12 / T_max 15 / P=45）→ 展开拆牌 12 组按分值降序。
- 单张校正弹窗保留原值（Q / ♦）；AI 源管理五源可选、网页源无 API 字段、删除有二次确认。

真机暴露并已修复：

9. `Meld.score` 是 `Int`，UI 用 `%.2f` → 计算后 `IllegalFormatConversionException` 崩溃。改为 `%d`。
10. 网页源隐藏了 API 输入框却没隐藏其标签。改为收集 `apiSection` 整体显隐。

未取证：横屏 / 字体缩放 / TalkBack 实测（仅代码层 48dp 与 contentDescription 保证）；登录页往返（UI-019）待阶段 8 离屏宿主就绪后复验。

### 已完成（AI-036「DeepSeek 已登录却显示不可用」根因修复，真机验证）

**根因**：健康检查链路两处串联失真。
1. `AiSettingsScreen.checkHealth` 以 `host = null` 建源，registry 回退到 `WebViewHost.get(context)` 单例；进程内若尚未挂接过页面，该宿主 `currentWebView() == null`。
2. `WebViewBridge.evaluateRaw` 在 `view == null` 时静默 `resume("")` → `PageDriver.pageState()` 的 `diagnose()` 解析失败返回 `null` → `WebViewAiSource.healthCheck()` 把 `null` 当「实现不支持只读诊断」落到 `legacyHealthProbe()` → `ensureLoaded()` false → 笼统 `Unavailable("页面未就绪")`。已登录也显示不可用，且真实原因（宿主未挂接）丢失。

**改动**：
- 新增 `PageState.HOST_NOT_ATTACHED`（宿主问题，既非账号也非选择器），`PageAction.NEEDS_USER`，reason 文案「AI 页面未挂接（请先打开一次 AI 页面）」。
- `PageDriver.pageState()`：主线程查 `host.currentWebView()`，为 null 直接返回 `HOST_NOT_ATTACHED` 并 `Log.w`，不再返回 null 触发降级。
- `WebViewAiSource.healthCheck()`：显式映射 `HOST_NOT_ATTACHED → Unavailable(reason)`。
- `AiSettingsScreen.checkHealth`：`host = null` 改为 `WebViewHost.get(applicationContext)`（与主界面同一单例）。
- `WebViewBridge.evaluateRaw`：WebView 为空时 `Log.w("webView is null (host not attached)")`，避免静默空串。
- 单测 +3：`PageStateClassifierTest.hostNotAttachedNeedsUser`、`WebViewAiSourceTest.healthCheckMapsHostNotAttachedToUnavailableWithHint`。

**验证**：`testDebugUnitTest assembleDebug` BUILD SUCCESSFUL，296 tests 0 failures；真机 LIO-N29 冷启动显示「页面暂不可用（AI 页面未挂接（请先打开一次 AI 页面））」，不再是笼统「页面未就绪」。

**新发现阻塞（UI-019 的实质）**：`AiProbeActivity` 自建 `WebView(this)`，**从不挂到共享 `WebViewHost` 单例**。因此从「登录」入口打开 DeepSeek（真机确认已登录、输入框在）并返回主界面后，共享宿主仍是 `NotAttached`，联网识图不可用。登录态本身通过 `CookieManager` 全局共享，隔离的只是宿主对象。修复需让 `AiProbeActivity` 使用共享宿主（或登录页与会话宿主统一），属阶段 8 离屏宿主范畴，未在本轮实施。

### 已完成（AI-018 带图/不带图对照，真机实测）

- 探针新增 `--es auto textonly`：prompt 与 `e2e` 逐字相同但不上传附件。
- 真机 DeepSeek：不带图 → 模型明确回答「没有看到手牌照片」，`cards=0`；带图（`testcases/case3.png`，1536×1024 → JPEG q=85 224599 B）→ `attached=true`，输出 20 张具体牌面，`cards=20`。
- 结论：模型确实读像素，非凭 prompt 猜测。
- 遗留：该次回答带 `jsonCopyDownload[` 前缀，结构化解析退回逐条提取（告警非错误，牌面正确）。

### 已完成（AI-017 替代上传路径评估，真机实测）

- 探针新增「拖放测试」「系统剪贴板」按钮与 `--es auto upload_alt`（基线 → 拖放 → 剪贴板，每步后 `verifyAttachment`）。
- 真机 DeepSeek（LIO-N29 / Android 10 / webview 114）结果：基线 `attached=false count=0` → 合成 `dragenter/dragover/drop` 后 `attached=true count=1` → `ClipboardManager` + `ClipData.newUri`（FileProvider `cache/photos/`）+ 原生 `KEYCODE_PASTE` 后 `attached=true count=1`。
- 结论：两条替代路径均可用；`WebViewAiSource.uploadWithFallback` 的 PASTE ⇄ FILE_CHOOSER 双策略保持不变，不新增第三种。拖放/剪贴板仅作探针手工排障入口。
- 踩坑：`FileProvider` 只暴露 `cache/photos/`（`res/xml/file_paths.xml`），剪贴板 URI 必须放该目录，否则 `IllegalArgumentException: Failed to find configured root`。

### 已完成（AI-015 Gemini FILE_CHOOSER 交接路径）

- `GeminiSelectors.UPLOAD_TOOLS_CANDIDATES`（含真机实测 `Upload and tools`）+ `uploadTools()`。
- `GeminiScripts.uploadTrigger()`：如实返回 `js-click-needs-user-activation`，不伪装成功。
- `GeminiScripts.locateUploadIcon()`：返回视口坐标 / innerHeight / dpr，无同步忙等（单测断言）。
- `GeminiAdapter.uploadTriggerScript()` / `uploadIconScript()` 已实现，与 DeepSeek 共用同一原生触摸驱动路径。

### 已完成（AI-073 FILE_CHOOSER 回调唯一所有者重构，离线）

- 新增 `recognition/webview/FileChooserSlot.kt`：把 `onShowFileChooser` 回调的所有权/释放规则独立成不持有 WebView 的纯 Kotlin 类 —— `offer`（换入新回调时以 `null` 释放旧的不同实例）、`take`（交出所有权）、`release`（幂等释放）、`peek`（只读）。同一实例重复 `offer` 不重复释放（系统可能重入）。
- `WebViewHost` 删除裸 `var filePathCallback` 字段，改用 `private val fileChooser = FileChooserSlot<...>()`；孤儿释放、无 handler 释放、handler 返回 false / 抛异常释放、`destroy()` 释放全部统一走该槽位，不再存在「字段置空但回调没释放」的路径。
- 新增 `FileChooserSlotTest`（5 例）：offer→take 移交、替换并释放旧回调、同实例重复 offer 不释放、null 被忽略、release 幂等。
- `./gradlew testDebugUnitTest --tests 'nz.org.aotearoa.guandanscore.recognition.webview.*'` BUILD SUCCESSFUL（仅剩既有 `databaseEnabled` / `saveFormData` deprecated 警告）。

### 已完成（AI-022 主线程修复 + AI-073 真机验证）

真机 LIO-N29 / Android 10 / webview 114，DeepSeek 网页源，testcases/case14.jpg（1440×1920 → 1350×1800 q=85 254147 B）端到端跑通：

- PREP 编码真实图 → TAPICON 原生触摸 (857,955) → FILECHOOSER: delivering probe_upload.jpg → E2E_VERIFY attached=true → E2E_SUBMIT ok=true → E2E_PARSE cards=27 warnings=[]。
- 27/27 逐张核对一致：♥Q ♠Q ♠A ♠A ♦A ♥A ♦10 ♦9 ♠9 ♦K ♠K ♠K ♠4 ♦4 ♥K ♣K ♥2 ♣2 ♦2 ♠2 ♠J ♣10 ♣9 ♣8 ♠7 小王 大王。
- 修复前后对照：修复前 worker 线程直调 host.destroy()/attach()/status() 抛 WebViewHost must be used on the main thread (M1/AI-022), current-pool-1-thread-1，识别必失败。

AI-073 FileChooserSlot：连续多次重跑探针，每次 onShowFileChooser 都正常响应，未出现「上传一次后永久卡死」。

踩坑备忘：
1. EXTRA_TEST_IMAGE 不能指向 /sdcard/...（应用无存储权限，EACCES 后静默回退 70 字节占位图，模型会回答「纯色背景图」）。正确做法：adb push 到 /data/local/tmp → run-as <pkg> cp 进 cache/probe/ → 传应用私有绝对路径。
2. 单条 logcat 消息上限约 4KB，E2E_ANSWER 的 take(600) 会截断长回答。已新增 E2E_CARDS（按 8 张一批 SUIT:RANK 输出）用于逐张核对。
3. hif-dliq.deepseek.com -> ERR_NAME_NOT_RESOLVED 是 DeepSeek 页面自身的次要请求失败，不影响主流程（同一次运行仍拿到 27 张牌）。不要误判为网络阻断。
4. 本机 am force-stop 不生效，必须 am force-stop + am kill 并用 pidof 确认 GONE 后再 logcat -c，否则会读不到 --es auto 的输出。
- 新增 4 个单测；`./gradlew testDebugUnitTest` 176 项全绿。

### 进行中（AI-015 视口坐标换算修复 + 真机复测，2026-10-29 18:45）

- 新增 `recognition/webview/ViewportTapMapper`（纯函数）：横纵**分别**缩放，不再用单一高度比例缩放 x。真机点偏的根因是旧实现假设「WebView 宽高比 == 视口宽高比」，而探测页/正式页 WebView 都被布局拉伸。缺 `innerWidth` 时退回纵向比例（旧脚本兼容，降级路径）。`isWithinViewport()` 供换算前拒绝越界点。`ViewportTapMapperTest` 6 例（含「x 必须用横向比例算出」的回归断言）。
- `WebViewHost.tapViewportPoint` 新增 `viewportWidth`（默认 0 走降级），改用 `ViewportTapMapper`；日志打印 `view/viewport/scaleX/scaleY`。`PageDriver` 从脚本 JSON 读 `innerWidth` 一并传入。`GeminiScripts.locateUploadIcon()` 补 `innerWidth`。
- 探针 `tapUploadIcon()` 删掉自带的「按高度等比」实现，改调 `ViewportTapMapper`（此前与驱动层各写一套、真机漂移）；`logBaseline()` 增加 WebView `getLocationInWindow` 输出（`at=x,y`）。
- `./gradlew testDebugUnitTest assembleDebug` BUILD SUCCESSFUL。
- **真机复测（LIO-N29 / Android 10，18:45，Gemini）**：`TAPICON: icon at css (56,507) viewport=392x601` → `dispatched at (168,1519) px, view=1176x1801 scaleX=3.0 scaleY≈2.9967`。截图确认**触摸精确命中了 `Upload and tools`**，Gemini 弹出附件面板（Photos / Files / Drive / Google Photos）。坐标换算修复**已验证有效**。
- **但 `FILECHOOSER` 零输出**：该面板是网页自渲染菜单，不是系统文件选择器；「点上传图标即触发 `onShowFileChooser`」这一前提对当前 Gemini 版本不成立，需再点面板里的 `Photos` / `Files` 才会唤起 chooser。
- **Gemini 登录态已掉**：截图显示右上 `Sign in`，页面有 `Add files, create and try tools / Sign in to try tools`。`gemini_diag` 实测 `inputCount=2 fileInputCount=1 loginIndicatorCount=1 passwordCount=0` → `parseLogin` 因缺 password 正确判为**未登录**。
- 结论：AI-015 下一步须先恢复 Gemini 登录态，再验证「上传图标 → 面板 → Photos/Files → onShowFileChooser」两段式路径；`uploadTrigger()` / `locateUploadIcon()` 对面板内二级入口尚无实现。

### 已完成（AI-012 上传图片压缩）

- `imaging/UploadImageEncoder`：长边上限 1800、JPEG q=85、超 1.5 MB 逐级降到 60、只缩不放、等比；纯算术 `targetSize` / `shouldDownscale` / `nextQuality`，单测 11 例。
- 探针 `prepareChooserImage`：真实图 → 解码 → EXIF 旋正 → 压缩 → `probe/probe_upload.jpg`，日志含原尺寸→目标尺寸/q/scaled/字节数；占位图仍为 1×1 PNG。

### 已完成（AI-031 页面状态分类，离线完成）

- 新增 `recognition/webview/PageState.kt`：`PageState`（READY / LOADING / COMPOSER_MISSING / VIEWPORT_UNHEALTHY / LOGIN_REQUIRED）、`PageAction`（PROCEED / RETRY / RELOAD / NEEDS_USER）、纯函数 `PageStateClassifier`（`classify` / `actionFor` / `reason`）。**判定顺序固定**：文档未 commit → 登录 → 视口 → 选择器；顺序颠倒会把登录页误报成选择器缺失。
- 视口阈值不重复定义，复用 `AttachGate.viewportRejectReason`（MIN_VIEWPORT_HEIGHT=100）。
- `PageDriver.probeLiveness()` 每轮把 `DiagnoseResult` 分类存入 `lastProbeState` / `lastProbeDiagnose`；`awaitReady()` 遇到 `NEEDS_USER` 立刻中止（登录失效 / 视口塌陷重试无用，不再空等 15s 超时），并把原因写入 `lastReadyFailure`。
- `PageOps` 新增 `lastFailureReason(): String? = null`（默认实现，既有 Fake 零改动）；`PageDriver` 实现之，`ensureLoaded()` 开头清空上一次原因。
- `WebViewAiSource` 未就绪时用该原因构造 `AiSourceError.NotReady`，不再统一报「页面在超时内未就绪」。
- 新增 `PageStateClassifierTest` + `WebViewAiSourceTest` + `SiteAdapterScriptsTest` 共 9 例；`./gradlew testDebugUnitTest assembleDebug` BUILD SUCCESSFUL。驱动层零站点耦合复检 CLEAN。

**真机验证（LIO-N29 / Android 10，17:03~17:11）**

| 场景 | 分类 | 动作 |
| --- | --- | --- |
| DeepSeek 已登录首页 | READY | PROCEED |
| Gemini 已登录首页 | READY | PROCEED |
| DeepSeek 未登录（重定向 `/sign_in`） | LOGIN_REQUIRED | NEEDS_USER |

- **真机暴露并修复的缺陷**：未登录时 DeepSeek 重定向到 `/sign_in`，页面上 `inputCount=0 passwordCount=0 loginIndicatorCount=0`，DOM 判据完全失效，原逻辑归为 `COMPOSER_MISSING → RELOAD`，而重载只会再次落回 `/sign_in`，形成空转。修复：`SiteAdapter.isLoginUrl(url)` 按**路径段精确匹配** `sign_in/signin/login/auth`（首版 `startsWith("auth")` 会误判 `/authority`，已改并补回归测试），`PageStateClassifier.classify(diag, urlIsLoginPage)` 中 URL 登录页优先于 DOM 判据。
- 探针新增 `--es auto pagestate`（只读）：打印 classify / action / reason + 诊断摘要，用于真机复现三态。
- 注意：`--es site` 合法值是 `AiSourceId.key`，即 `deepseek_web` / `gemini_web`，不是 `deepseek_webview`；传错会静默回落 Gemini URL。
- 未覆盖：`VIEWPORT_UNHEALTHY` 尚无真机构造手段（需临时压扁 WebView 高度），仅单测覆盖。

### 已完成（AI-030 真机验证七组选择器）

- 真机（LIO-N29 / Android 10 / webview 114）三态实测：首页静止 / 已填文本未提交 / 有回答。
- 结果：`input` `2|1|0|0`（三态一致）、`send` 静止 `0|0|0|0|0|0` → 填文后 `1|1|0|0|0|0`、`response` 静止 `0|0|0|0` → 有回答 `1|1|1|1`、`uploadTools` `1|1|0|0`、`fileInput` `4|1|0`、`login`/`generating` 恒 0、`passwordCount=0`。
- 结论：七组候选全部至少一条真机命中；每组首选候选即命中，无需兜底；`textarea` 兜底（input 第 4 条）三态恒 0，说明当前 Gemini 不用 textarea。**无需修正任何选择器常量**。
- `send`/`response` 静止页为 0 是页面本来没有这些元素，不是选择器错——这正是 AI-030 要排除的误判，已在文档中记录，避免后人再当缺陷排查。
- 新增探针入口 `--es auto selector_probe`：同一 Activity 内 填文本→探针(`DEEP_FILLED`)→提交→等 30s→再探针(`DEEP_ANSWERED`)，可重复执行该验证。

### 已完成（AI-030 逐候选选择器探针）

- `GeminiScripts.selectorProbe()`：按 `INPUT/SEND/RESPONSE/UPLOAD_TOOLS/FILE_INPUT/LOGIN/GENERATING` 七组候选**逐条**数命中，返回 `input:"0|0|1|1"` 形式的计数串 + 每组 `<key>First`（从 0 起第一个非 0 下标），另带 `passwordCount` / `href` / `readyState`。只读：不点击、不派发事件、不读 Cookie / Storage、无同步忙等。
- 私有 `jsonArray()` 统一把候选列表编码成 JSON 数组字面量，避免手工拼引号。
- `GeminiAdapter.deepProbeScript()` 承载该探针（复用既有可选探测挂点，不新增接口）。
- 动机：`diagnose()` 只报并集命中数，并集为 1 也可能是第 4 条 `textarea` 兜底命中，无法回答“哪条候选真的生效”。
- 测试：`GeminiScriptsTest` 新增 4 例（候选顺序、`counts.join`/`firstHit`/`result[k + 'First']` 与各组 key 存在、只读性、`input` 候选数组可被 `JSONArray` 解析且逐项相等）；`SiteAdapterScriptsTest` 把 `onlyDeepSeekSupportsOptionalProbeScripts` 拆为 `onlyDeepSeekSupportsFileInputProbe` + `geminiDeepProbeIsSelectorProbeAndReadOnly`（Gemini 已真正具备该能力，旧断言过时）。
- `./gradlew testDebugUnitTest` 全绿。

### 已完成（README 全量重写，2026-10-30）

- 依据当前代码而非旧 README 重写 `README.md`：五类 AI 源与网页驱动机制、网页源 / HTTP API 双轨、`T_max(N)` 与评级表、300 项单测、探针 `--es auto` 模式、离屏挂接、已知限制。
- 核对：两份 V4.0 评分文档与 `HandScorer` 的 191 / 192 / 1000、`T_max`、评级阈值一致；README 内 17 个相对链接全部存在；`./gradlew testDebugUnitTest` BUILD SUCCESSFUL（32 个测试类 / 300 项）。
- 发现但未改：`AiProbeActivity.switchTo` 对未知 `--es site` 回落为「DeepSeek 适配器 + Gemini 地址」；探针站点栏不含 ChatGPT（该源只在正式设置页可用）。

## 上次完成

2026-10-29 · AI-025 WebView 安全配置契约 + URL 日志脱敏（离线完成，待真机复测）：新增 `webview/WebViewSecurityConfig` —— JS / DOM Storage / database 开，`allowFileAccess` / `allowContentAccess` / `saveFormData` / `geolocationEnabled` 关，混合内容 `NEVER_ALLOW`，接受 Cookie（含第三方，OAuth 跳转依赖）；`violations()` 自检，默认值即契约。`WebViewHost.applySecurityConfig()` 把契约落到 `WebSettings` + `CookieManager.setAcceptCookie`；第三方 Cookie 需具体实例，改在 `webView = view` 之后由 `applyThirdPartyCookies()` 设置（创建期传 null 会 NPE）。新增 `webview/UrlRedactor`：保留 scheme/host/path 便于判断是否被重定向到登录页，query 与 fragment 的**值**替换为 `<redacted>`、键名保留，无 `=` 的裸片段整体抹掉；`onPageStarted` / `onPageFinished` / 新增 `onReceivedError` 全部经它输出，避免 `?token=` / `#access_token=` 进 logcat。全仓 grep 确认无 cookie/token/password/secret/authorization/localStorage 直出日志。新增 `WebViewSecurityConfigTest` 8 例、`UrlRedactorTest` 7 例；`./gradlew testDebugUnitTest assembleDebug` BUILD SUCCESSFUL。

2026-10-29 · AI-024 单父容器 + 挂接体检（离线完成，待真机复测）：抽出纯逻辑 `recognition/webview/AttachGate`（容器体检 `isAttachedToWindow` / `isShown` / 尺寸非零；页面体检 `MIN_VIEWPORT_HEIGHT=100`），`AttachGateTest` 7 例全绿。`WebViewHost.attach()`：`enforceSingleParent()` 按真实 `view.parent` 摘除（不再信任自记的 `visibleParent`，异常路径下直接 `addView` 会抛 `IllegalStateException`）；容器体检不过回落 `NotAttached` 交 `MainActivity.onResume` 重试、不谎报 `Attached`；`alreadyParent` 时只纠正状态。`evaluateJavascript` 只异步回调，`attach()` 内无法同步读 `innerHeight`，故 `verifyViewportAfterAttach()` 仅打接线点日志、不做假实现；出帧判定落在 `PageDriver.probeLiveness()`，其硬编码 `innerHeight >= 100` 改为 `AttachGate.viewportRejectReason()`，阈值单点定义。`removeHostFromParent()` 同样按真实 parent 摘除。`./gradlew testDebugUnitTest assembleDebug` BUILD SUCCESSFUL。

2026-10-29 · AI-023 JS 回调 ↔ 协程/同步壳的死锁防线（离线完成）：确认既有机制合法前提是「`AiSource.recognize` 在 worker 线程同步调用」，但 `PageDriver.runBlockingEvaluate` 的 `runBlocking` 在主线程会**静默永久卡死**（回调永远排不上主线程），此前无任何防线。新增 `recognition/webview/BlockingBridge`：`awaitBlocking(where, onMain, block)` 在主线程**进入挂起块之前**抛 `IllegalStateException`（消息含 AI-023 与调用点），worker 线程才 `runBlocking`；`isMainThread()` 对纯 JVM 单测降级为 false。`PageDriver.runBlockingEvaluate` 接入；`awaitReady`/`verifyAttachment`/`awaitSubmitStarted` 补 KDoc 说明 `sleepMillis`（默认 `Thread.sleep`）只能在 worker 线程、单测注入 no-op。新增 `BlockingBridgeTest` 3 例。`testDebugUnitTest --rerun-tasks` + `assembleDebug` BUILD SUCCESSFUL。

2026-10-29 · AI-022 主线程契约显式化（离线完成）：审计确认执行路径本已正确（`WebViewHost` 全方法 `requireMainThread()`、`WebViewBridge` 全部 `main.post`、探针 `runScript` 走 `runOnUiThread`），缺的是**契约与断言**。改动：① `WebViewHost.currentWebView()` 加 `requireMainThread()`，消息带线程名；② `PageOps` 补线程契约 KDoc（可后台调用，实现方负责切主线程，调用方不得主线程阻塞）；③ `AiProbeActivity` 新增 `requireUiThread(where)` 并在 `switchTo`/`prepareChooserImage`/`tapUploadIcon` 三处断言。`testDebugUnitTest --rerun-tasks` + `assembleDebug` BUILD SUCCESSFUL。

2026-10-29 · AI-021 宿主生命周期语义显式化（离线完成，待真机复测）：`WebViewHost.detachFromVisible()` = 仅移除，WebView / Cookie / DOM Storage / 页面全保留，状态 NotAttached，可 `attach()` 挂回；`destroy()` = 终态，释放挂起 filePathCallback → 清离屏残留 → `WebView.destroy()` → 置空引用 + `currentUrl` / `navigatedTarget` / `pendingUploadUri`，状态 NotCreated，下次重建。顺手修：① `removeHostFromParent()` 在 Attached 时回落 NotAttached（attach 失败重挂不再留假 Attached）；② 新增 `navigatedTarget`，destroy 清零，避免重建后 `ensureLoaded()` 跳过导航；③ `MainActivity.onDestroy()` 补 `webHost.destroy()`（此前 Activity 销毁不清理宿主），onPause 仍为 detach。`testDebugUnitTest --rerun-tasks` BUILD SUCCESSFUL。

2026-10-29 · AI-009D 文件选择器探测真机通过 + accept 日志缺陷修复（LIO-N29 / Android 10 / webview 114.0.5.302）：fileInputCount=1，accept 整串 5868 字符 / 981 条目（imageCount=18）、multiple=true、capture 空、hidden=true、disabled=false；原生触摸回形针真实触发 onShowFileChooser（mode=1 MODE_OPEN）并回填 cache/probe/probe_upload.png；JS click 仍不触发。复测暴露并修复：accept 前 N 项全是 .wxml/.wxss 等非图片类型，按项截断的日志看不到图片真相 —— 抽出 recognition/webview/AcceptTypes（大小写不敏感、imageTypes/imageSummary 优先列图片、超长 (...+N)，禁止打印整串），探针 FILECHOOSER 与 WebViewHost.onShowFileChooser 统一改用；补 8 个 AcceptTypesTest。复测日志 acceptCount=981 imageTypes=.png|.jpg|...(+10) (imageCount=18)，delivering 日志不再被挤掉。testDebugUnitTest BUILD SUCCESSFUL。

2026-10-29 · AI-011D 停止生成控件探测真机通过（LIO-N29 / Android 10 / webview 114.0.5.302，DeepSeek 已登录静止页）：新增 DeepSeekScripts.stopControlProbe()（只读不点击）、SiteAdapter.stopControlProbeScript() 契约、DeepSeekAdapter 实现、探针「停止控件」按钮与 --es auto stop_probe。实测 presetCount=0、labelHits=[]（扫描 8 个可见 button/div[role=button]）、note=no-stop-control: must-not-be-used-as-completion-signal。结论：DeepSeek 无停止控件，generating 恒 false，结束判定只能靠文本增长 + 连续稳定。新增 2 个单测。

2026-10-29 · AI-020 通用 WebViewHost 生命周期补齐（LIO-N29 / Android 10 / webview 114.0.5.302）：核对创建 / 持有 / 挂接 / 移除 / 销毁五段，缺口在 destroy()。补齐：释放挂起的 filePathCallback（否则页面永久等待）、清 offscreenHost 残留子视图、复位 visibleParent 与 pendingUploadUri、加日志。**回归修复**：首版 destroy() 误清注入配置（fileChooserHandler / onPageFinished / attachTargetProvider），而 PageDriver.ensureLoaded() 先 destroy() 再 attach() 重建视图，导致重建后无人接管文件选择器 —— 真机实测 attachmentCount=0 attached=false；改为只清 WebView 自身状态、保留注入配置后复测 attachmentCount=1 attached=true。testDebugUnitTest + assembleDebug BUILD SUCCESSFUL。

2026-10-29 · G-13 驱动层零站点耦合复检：PageDriver.kt / WebViewAiSource.kt 两处注释里的站点名已改为中性表述，grep -niE "gemini|deepseek|chatgpt" 结果为空（CLEAN）。testDebugUnitTest + assembleDebug BUILD SUCCESSFUL。

## 阻塞

无。真机可复测（WiFi adb -s 192.168.3.158:5555，USB adb -s J7R0220110001216）。

## 下一步
1. 按 doc/ai_tasks.md 顺序推进：AI-031 / AI-075 / AI-076 / AI-080 已完成（AI-075/076/080 待真机复测）；`VIEWPORT_UNHEALTHY` 仍缺真机构造手段。下一个未完成项按文档顺序取（AI-077~079、AI-082~083、AI-089~090、AI-093~101、阶段 7 UI）。
   另：AI-021 / AI-022 / AI-023 / AI-024 需真机复测一次「后台→前台挂回不丢登录态」「Activity 销毁后重建可用」「识牌全链路无 CalledFromWrongThread、无主线程阻塞」「容器不可用时不挂假 Attached」。
2. 用真机实测值复核 GeminiSelectors 其余候选（response/generating）。
3. 阶段 7 UI 按 P0→P1→P2 实施。
4. 把探针 e2e 能力沉淀为正式入口（EXTRA_TEST_IMAGE 已具备，考虑接入真实拍照流程）。

## 已知问题

- `ScrollView` 只能有一个直接子节点；动态 View 必须挂 `mainContent`。
- **`evaluateJavascript` 只返回同步值**，Promise / setTimeout 结果拿不到。异步副作用必须由调用方用后续快照验证，不能靠脚本返回值。
- **JS 里禁止同步忙等**：会卡死事件循环，Angular/React 渲染全部停摆。
- `FileProvider` 仅暴露 `cache/photos/`（`res/xml/file_paths.xml`）；任何 `content://` 分享都必须落在该目录。
- 拖放/粘贴事件 `dispatchEvent` 返回 false 不等于失败，唯一判据是 `verifyAttachment()` 的附件预览计数。
- DeepSeek 回答可能带 UI 文本前缀（如 `jsonCopyDownload[`），`parseDetailed` 会退回逐条提取并告警；牌面仍正确，勿按解析失败处理。
- 本机无系统 JDK，需 `export JAVA_HOME=/opt/homebrew/opt/openjdk@17`。
- macOS 无 `timeout`、无 `which`；adb 在 `~/Library/Android/sdk/platform-tools/adb`。
- 涉及轮询的测试，时钟函数必须递增。
- 探针测试真实图：`adb push 图 /data/local/tmp/x.png && adb shell run-as nz.org.aotearoa.guandanscore cp /data/local/tmp/x.png /data/user/0/nz.org.aotearoa.guandanscore/cache/x.png`，再 `--es test_image <该绝对路径>`。设备无存储权限，勿放 `/sdcard`。
- `model_training/install_model.ps1` 仅 PowerShell。
- 真机 WiFi 调试：`adb connect 192.168.3.158:5555`（Mac 192.168.3.160）。首次或手机重启后需 USB 插线执行 `adb tcpip 5555`。设备：LIO-N29 / Android 10 / 1176x2400 / density 480。adb 30.0.5 不支持 `adb pair`。
