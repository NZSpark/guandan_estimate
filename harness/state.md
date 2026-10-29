# State —— 当前状态

> 只保留恢复工作所必需的信息。任务完成即就地改写，不追加历史流水。
> 最后更新：2026-10-29

## 当前分支

`airecognize`

## 当前任务

**执行 `doc/ai_tasks.md`：多 AI 源 WebView 迁移 + 阶段 7 UI**（进行中）

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

### 已完成（AI-011 窗口基线）

- 探针新增「基线」按钮 + `--es auto baseline`（`logBaseline()` + `PAGE_BASELINE`）。真机 DeepSeek 实测：视图 `1176x1801` / density 3.0 / dpi 480 / 屏幕 `1176x2328` / `attachedToWindow=true` / `isShown=true` / parent=LinearLayout；页面 `visible` / `innerWidth×Height=392×601` / DPR=3 / `docClient=392×601` / `scroll=0,0` / `docScrollH=601` / `readyState=complete`。切后台对照须比对这些字段。

### 未完成

- ~~AI-009 / AI-128 FILE_CHOOSER 原生触摸路径的真机验收~~ ✅ 已完成（DeepSeek 真机 10 张牌零告警）。
- AI-012 压缩后识牌精度的真机复跑记录（DeepSeek 端到端已在 AI-128 用同源真实手牌图通过）。
- AI-015 Gemini 端 `--es site gemini_web` 的原生触摸 → `onShowFileChooser` 回填真机实测（代码路径已就绪，DeepSeek 同路径已由 AI-128 验收）。
- 阶段 7 UI-001~022（见 `doc/ui_design.md`）。
- 旧 Gemini Key 清理（AI-093~101）。

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
- 新增 4 个单测；`./gradlew testDebugUnitTest` 176 项全绿。

### 已完成（AI-012 上传图片压缩）

- `imaging/UploadImageEncoder`：长边上限 1800、JPEG q=85、超 1.5 MB 逐级降到 60、只缩不放、等比；纯算术 `targetSize` / `shouldDownscale` / `nextQuality`，单测 11 例。
- 探针 `prepareChooserImage`：真实图 → 解码 → EXIF 旋正 → 压缩 → `probe/probe_upload.jpg`，日志含原尺寸→目标尺寸/q/scaled/字节数；占位图仍为 1×1 PNG。

## 上次完成

2026-10-29 · AI-022 主线程契约显式化（离线完成）：审计确认执行路径本已正确（`WebViewHost` 全方法 `requireMainThread()`、`WebViewBridge` 全部 `main.post`、探针 `runScript` 走 `runOnUiThread`），缺的是**契约与断言**。改动：① `WebViewHost.currentWebView()` 加 `requireMainThread()`，消息带线程名；② `PageOps` 补线程契约 KDoc（可后台调用，实现方负责切主线程，调用方不得主线程阻塞）；③ `AiProbeActivity` 新增 `requireUiThread(where)` 并在 `switchTo`/`prepareChooserImage`/`tapUploadIcon` 三处断言。`testDebugUnitTest --rerun-tasks` + `assembleDebug` BUILD SUCCESSFUL。

2026-10-29 · AI-021 宿主生命周期语义显式化（离线完成，待真机复测）：`WebViewHost.detachFromVisible()` = 仅移除，WebView / Cookie / DOM Storage / 页面全保留，状态 NotAttached，可 `attach()` 挂回；`destroy()` = 终态，释放挂起 filePathCallback → 清离屏残留 → `WebView.destroy()` → 置空引用 + `currentUrl` / `navigatedTarget` / `pendingUploadUri`，状态 NotCreated，下次重建。顺手修：① `removeHostFromParent()` 在 Attached 时回落 NotAttached（attach 失败重挂不再留假 Attached）；② 新增 `navigatedTarget`，destroy 清零，避免重建后 `ensureLoaded()` 跳过导航；③ `MainActivity.onDestroy()` 补 `webHost.destroy()`（此前 Activity 销毁不清理宿主），onPause 仍为 detach。`testDebugUnitTest --rerun-tasks` BUILD SUCCESSFUL。

2026-10-29 · AI-009D 文件选择器探测真机通过 + accept 日志缺陷修复（LIO-N29 / Android 10 / webview 114.0.5.302）：fileInputCount=1，accept 整串 5868 字符 / 981 条目（imageCount=18）、multiple=true、capture 空、hidden=true、disabled=false；原生触摸回形针真实触发 onShowFileChooser（mode=1 MODE_OPEN）并回填 cache/probe/probe_upload.png；JS click 仍不触发。复测暴露并修复：accept 前 N 项全是 .wxml/.wxss 等非图片类型，按项截断的日志看不到图片真相 —— 抽出 recognition/webview/AcceptTypes（大小写不敏感、imageTypes/imageSummary 优先列图片、超长 (...+N)，禁止打印整串），探针 FILECHOOSER 与 WebViewHost.onShowFileChooser 统一改用；补 8 个 AcceptTypesTest。复测日志 acceptCount=981 imageTypes=.png|.jpg|...(+10) (imageCount=18)，delivering 日志不再被挤掉。testDebugUnitTest BUILD SUCCESSFUL。

2026-10-29 · AI-011D 停止生成控件探测真机通过（LIO-N29 / Android 10 / webview 114.0.5.302，DeepSeek 已登录静止页）：新增 DeepSeekScripts.stopControlProbe()（只读不点击）、SiteAdapter.stopControlProbeScript() 契约、DeepSeekAdapter 实现、探针「停止控件」按钮与 --es auto stop_probe。实测 presetCount=0、labelHits=[]（扫描 8 个可见 button/div[role=button]）、note=no-stop-control: must-not-be-used-as-completion-signal。结论：DeepSeek 无停止控件，generating 恒 false，结束判定只能靠文本增长 + 连续稳定。新增 2 个单测。

2026-10-29 · AI-020 通用 WebViewHost 生命周期补齐（LIO-N29 / Android 10 / webview 114.0.5.302）：核对创建 / 持有 / 挂接 / 移除 / 销毁五段，缺口在 destroy()。补齐：释放挂起的 filePathCallback（否则页面永久等待）、清 offscreenHost 残留子视图、复位 visibleParent 与 pendingUploadUri、加日志。**回归修复**：首版 destroy() 误清注入配置（fileChooserHandler / onPageFinished / attachTargetProvider），而 PageDriver.ensureLoaded() 先 destroy() 再 attach() 重建视图，导致重建后无人接管文件选择器 —— 真机实测 attachmentCount=0 attached=false；改为只清 WebView 自身状态、保留注入配置后复测 attachmentCount=1 attached=true。testDebugUnitTest + assembleDebug BUILD SUCCESSFUL。

2026-10-29 · G-13 驱动层零站点耦合复检：PageDriver.kt / WebViewAiSource.kt 两处注释里的站点名已改为中性表述，grep -niE "gemini|deepseek|chatgpt" 结果为空（CLEAN）。testDebugUnitTest + assembleDebug BUILD SUCCESSFUL。

## 阻塞

无。真机可复测（WiFi adb -s 192.168.3.158:5555，USB adb -s J7R0220110001216）。

## 下一步
1. 按 doc/ai_tasks.md 顺序推进：下一个未完成项是 AI-023（JS 回调 ↔ 协程/异步等待，避免 UI 线程轮询 sleep 或阻塞等待）。
   注意：AI-023 与现有实现直接冲突——`PageDriver.runBlockingEvaluate()` 用 `runBlocking` 包裹 `bridge.evaluateRaw`，而 `evaluateRaw` 把回调 post 到主线程；若调用方本就在主线程将直接死锁，需先确认真实调用线程。
   另：AI-021 / AI-022 需真机复测一次「后台→前台挂回不丢登录态」「Activity 销毁后重建可用」「识牌全链路无 CalledFromWrongThread」。
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
