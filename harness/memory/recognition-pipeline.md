# 识别管线与 AI Provider（recognition-pipeline）

> 只记约束、契约与排错入口；功能描述见 `README.md`，训练流程见 `model_training/README.md`。

## 三条识别路径

| 路径 | 入口 | 前提 | 输出 |
| --- | --- | --- | --- |
| 本地三阶段 | `CardRecognizer.recognize(bitmap)` | 无网络 | `RecognitionResult`（可能缺牌） |
| 联网 AI | `AiRecognitionClient.recognize(bitmap, settings)` | 用户主动触发 + 有效 Key | `List<Card>` |
| 生成图回读 | `VisualCardCodec.decode(bitmap)` | 图由本 App 生成 | `List<Card>?`（校验码命中） |

三条路径结果都必须进入同一套 UI 校正流程，**不允许**任何一条跳过人工确认直接计算。

## 本地三阶段

1. 牌张定位与计数（确定 N 与每个牌角框）。
2. 点数识别（OCR）。
3. 点数下方花色图形识别（`CardTemplateMatcher` 模板匹配）。

被相邻牌或手指遮挡的牌角无法从单张照片可靠恢复 —— 这是**物理限制**，不要试图用规则“猜”补全，应提示用户核对。

## AI Provider 契约

- 支持两类协议：Google Gemini（`generativelanguage.googleapis.com/v1beta/models`）与 OpenAI Responses / Chat Completions 兼容协议。
- 预设：`AiPresets.gemini()`（默认 `gemini-2.5-flash`）、`AiPresets.openAi()`。
- 多 Provider 配置由 `AiSettingsStore` 管理：`load/loadAll/save/select/delete`，API Key 用 Android Keystore 加密后存本地。
- **Key 绝不入库**；生产更推荐自有后端代理。
- 新增 Provider 时：先扩展 `AiPresets`，再在 `AiRecognitionClient` 加协议分支，最后补 `AiRecognitionClientTest`。

## 离线模型（`model_training/`）

- 与 Android 构建完全解耦；依赖不进 APK。
- 两阶段：OBB 旋转目标检测（边界+角度）→ 透视拉正后点数/花色分类。
- 关键指标 `validation_exact_card_accuracy`（点+花色同时正确）。
- 产物：`card_corner_classifier.tflite` + `model_manifest.json`，经 `install_model.ps1` 放入 `app/src/main/assets/models/`。
- **接入闸门**：独立验证达标 + 15 点数 5 花色样本充足 + API 29 真机耗时/内存/稳定性验证，之后才启用 LiteRT 推理路径。

## WebView 迁移分层（ADR-0005）

| 层 | 文件 | 依赖 Android | 作用 |
| --- | --- | --- | --- |
| 解析 | `AiCardResponseParser` | 否 | 回复文本 → `Card`，严格校验 + 告警 |
| 结束判定 | `GenerationWatcher` | 否 | 新回答识别、稳定结束、超时 |
| 页面能力 | `GeminiUpstream`（接口） | 接口否 / 实现是 | 就绪检查、基线、上传、提交、快照 |
| 编排 | `AiRecognitionService` | 否 | 互斥 BUSY、取消、8 类错误映射 |
| 站点适配 | `GeminiSelectors` / `GeminiScripts` | 否 | 选择器与注入脚本单一来源 |

**当前状态**：前四层已完成并有 94 个单测覆盖；`GeminiUpstream` 的真实 WebView 实现**尚未编写**（需真机验证后按实测选择器实现）。

关键不变量：

- `attachImage()` 返回 true 必须意味着附件预览真实出现，事件派发成功不算。
- 回答必须相对发送前基线是新的；旧回答与 prompt 回显一律丢弃。
- 同一上游同一时刻只允许一个任务（`AiRecognitionService.isBusy`）。
- 脚本动态参数一律 `JSONObject.quote`；诊断脚本不得读取 Cookie / Storage / Base64。
- **`evaluateJavascript` 只返回同步值**。Promise 与 `setTimeout` 里的赋值拿不到；异步副作用必须由调用方用后续快照（`snapshot()` / `awaitSubmitStarted`）验证，脚本返回值只作"已派发"信号。
- **JS 内禁止同步忙等**（`while (Date.now() < dl)`）。它会阻塞事件循环，Angular/React 变更检测停摆，实测 2s 内发送按钮始终不渲染。等待渲染只能用 `setTimeout` 重试或轮询下一帧。
- 登录态判据必须同时看密码框与登录 UI；只查登录选择器会把已登录页的账户菜单误判为未登录（Gemini 已登录页 `passwordCount=0`）。
- 提交成功不能只看脚本 `ok:true`；必须确认 `submitted:true`，最终成败以回答基线变化为准。

## 排错顺序

1. 确认是哪条路径出的问题（日志/是否点了“联网识图”/是否本 App 生成图）。
2. 本地路径：先用 `testcases/case*.jpg|png` 复现，再缩小到定位 / 点数 / 花色哪一阶段。
3. 联网路径：核对 Provider 地址、模型名、协议分支、返回 JSON 解析；不要打印 Key。
4. WebView 路径：先跑 `GeminiScripts.diagnose()` 看 `inputCount` / `responseCount` / `fileInputCount` / `loginIndicatorCount`；再确认上传后 `verifyAttachment()` 的 `attached` 为 true。
5. 素材相关：核对 `CardAssetNames` 与 `card_picture/card_png/` 文件名一致。

## 相关 Skill

`skills/ai-recognition-debug` —— 分阶段复现与验证清单。
