# State —— 当前状态

> 只保留恢复工作所必需的信息。任务完成即就地改写，不追加历史流水。
> 最后更新：2026-10-28

## 当前分支

`airecognize`

## 当前任务

**执行 `doc/ai_tasks.md`：Gemini API Key → Gemini WebView 迁移**（进行中）

已完成（离线可验证部分，单测 94 个全绿）：

- 阶段 0 A0.1：基线记录，`testDebugUnitTest` + `assembleDebug` 通过。
- AI-052~055 / AI-085：`AiCardResponseParser`（严格 JSON + Markdown 剥离 + 宽松回退 + 校验告警），19 测试。`AiRecognitionClient` 改为委托，行为不变。
- AI-047~049 / AI-086：`GenerationWatcher`（新回答判定、稳定结束、prompt 回显排除、超时），11 测试。
- AI-026~029 / AI-087：`GeminiSelectors` + `GeminiScripts`（JSON 安全编码、结构化返回、诊断不读 Cookie、附件必须真实预览），12 测试。
- AI-063 / AI-091 / AI-092 / AI-088：`AiTransport` 枚举、`geminiWebView()` 预设、旧 profile 回落 API、位置参数兼容，7 测试。
- AI-032 / AI-043 / AI-057 / AI-071 / AI-072 / AI-074 / AI-081 / AI-084：`GeminiUpstream` 接口 + `AiRecognitionService` 状态机（互斥 BUSY、取消、8 类错误、旧回答不入结果），15 测试。

- 启动崩溃修复：`MainActivity` 把 `webContainer` 直接 add 到 `ScrollView`（`mainScroll`）导致 `IllegalStateException: ScrollView can host only one direct child`。已给布局内层 `LinearLayout` 加 `@+id/mainContent`，改为 `mainContent.addView(webContainer, 0, ...)`。真机 J7R0220110001216 验证启动正常、crash buffer 为空。

**未完成（必须真机，不可离线伪造）**：

- AI-006~019：WebView 登录、DOM 探测、真实图片上传闭环 —— 阶段 0 准入条件。
- AI-020~025：`GeminiWebViewHost` 真实实现。
- AI-036~046：真实上传与提交确认接线。
- AI-058~070：`MainActivity` / 设置 UI 接入。
- AI-075~083、AI-089~090：生命周期与真机回归。
- AI-093~101：Key 清理与发布收尾。

## 上次完成

2026-10-28 · 修复 MainActivity 启动崩溃（ScrollView 单子节点约束）

## 阻塞

真机验证。图片上传闭环未在目标 Android WebView 上确认前，不得继续业务接线。

## 下一步

1. 在目标设备完成 AI-006~019（登录、DOM 诊断、真实上传 + 带图/不带图对照）。
2. 用实测选择器替换 `GeminiSelectors` 候选值，同步 `GeminiScriptsTest`。
3. 实现 `GeminiWebViewHost` + `GeminiUpstream` 真实实现，接入 `MainActivity`。
4. 阶段 5 清理旧 Gemini Key、更新 README。

## 已知问题

- `ScrollView` 只能有一个直接子节点；任何动态 View 必须挂到 `mainContent`，不得挂到 `mainScroll`。
- `GeminiSelectors` 全部为**未验证的候选选择器**，代码注释已标注。
- 本机无系统 JDK，需 `export JAVA_HOME=/opt/homebrew/opt/openjdk@17`。
- macOS 无 `timeout` 命令。
- 涉及轮询的测试，时钟函数必须递增；常量时钟会让稳定判定永不满足而死循环。
- `model_training/install_model.ps1` 仅 PowerShell；macOS 需手动复制模型文件。
