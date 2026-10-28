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
- `probe/AiProbeActivity`：站点切换 + 诊断/快照/粘贴/填+贴+发 + `--es auto` 无人值守入口。

### 已完成（真机验证，HUAWEI LIO-N29 / Android 10 / webview 114.0.5.302）

- DeepSeek：登录态跨进程保持；诊断 input=1 send=1 file=1；合成 paste 后 `attached=true`（`dispatchEvent` 返回 false，不可作判据）。
- Gemini：**已登录**，`Ask Gemini` contenteditable + `Upload and tools`；`passwordCount=0` 正确判为已登录。
- Gemini 端到端闭环通过：填文本 → 合成 paste 注入图片 → 点 `Send message` → 页面出现 `You said …`、`Show the uploaded image in a Lightbox`、回答 `粉红色`。

### 真机暴露并已修复的缺陷

1. `diagnose()` 不输出 `passwordCount` → `parseLogin` 恒 false（误判登录态）。已补 `GeminiSelectors.PASSWORD` 及 diagnose/snapshot 字段，`DiagnoseResult.passwordCount`。
2. `parseSubmitResult` 只认 `ok:true` → 未提交也报成功。改为必须 `filled && submitted`。
3. 发送按钮由 Angular **异步渲染**，同步 `querySelector` 必扑空 → 误退 Enter。改为 `setTimeout(retry,50)`×40 异步重试。
4. 同步忙等（`while(Date.now()<dl)`）会阻塞 JS 事件循环，Angular 永远渲染不出按钮 —— 实测 2s 内 `bySel/byAria` 恒 0。**禁止同步忙等**，已有单测断言。

### 未完成

- AI-128 FILE_CHOOSER 原生触摸路径的真机验收。
- 阶段 7 UI-001~022（见 `doc/ui_design.md`）。
- 旧 Gemini Key 清理（AI-093~101）。

## 上次完成

2026-10-29 · Gemini 真机端到端闭环 + 3 个逻辑缺陷修复

## 阻塞

无。真机可复测（`adb -s J7R0220110001216`）。

## 下一步

1. 用真机实测值复核 `GeminiSelectors` 其余候选（response/generating）。
2. AI-128：原生触摸点回形针 → `onShowFileChooser` 路径验收。
3. 阶段 7 UI 按 P0→P1→P2 实施。

## 已知问题

- `ScrollView` 只能有一个直接子节点；动态 View 必须挂 `mainContent`。
- **`evaluateJavascript` 只返回同步值**，Promise / setTimeout 结果拿不到。异步副作用必须由调用方用后续快照验证，不能靠脚本返回值。
- **JS 里禁止同步忙等**：会卡死事件循环，Angular/React 渲染全部停摆。
- 本机无系统 JDK，需 `export JAVA_HOME=/opt/homebrew/opt/openjdk@17`。
- macOS 无 `timeout`、无 `which`；adb 在 `~/Library/Android/sdk/platform-tools/adb`。
- 涉及轮询的测试，时钟函数必须递增。
- `model_training/install_model.ps1` 仅 PowerShell。
