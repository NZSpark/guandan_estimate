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

2026-10-29 · AI-008D DeepSeek 页面结构深度探测脚本（DeepSeekScripts.deepProbe() + SiteAdapter.deepProbeScript() + DeepSeekAdapter 实现）：输出输入框唯一性/最内层、发送键可点击判据、回答节点容器与深度、Shadow DOM 宿主计数；./gradlew testDebugUnitTest 全绿

## 阻塞

无。真机可复测（`adb -s J7R0220110001216`）。

## 下一步

1. 按 `doc/ai_tasks.md` 顺序推进：下一个未完成项 AI-008D（DeepSeek 页面 DOM 深度探测）。
2. 用真机实测值复核 `GeminiSelectors` 其余候选（response/generating）。
2. 阶段 7 UI 按 P0→P1→P2 实施。
3. 把探针 e2e 能力沉淀为正式入口（`EXTRA_TEST_IMAGE` 已具备，考虑接入真实拍照流程）。

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
