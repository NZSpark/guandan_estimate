# 识别管线与 AI Provider（recognition-pipeline）

> 只记约束、契约与排错入口；功能描述见 `README.md`，训练流程见 `model_training/README.md`。

## 三条识别路径

| 路径 | 入口 | 前提 | 输出 |
| --- | --- | --- | --- |
| 本地三阶段 | `CardRecognizer.recognize(bitmap)` | 无网络 | `RecognitionResult`（可能缺牌） |
| 联网 AI（网页） | `AiSourceRegistry.create(...).recognize(RecognizeRequest)` | 用户主动触发 + 网页登录 | `List<Card>` |
| 联网 AI（HTTP） | `HttpApiSource` → `AiRecognitionClient.recognize(bitmap, settings)` | 用户主动触发 + 有效 Key | `List<Card>` |
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

## WebView 分层（ADR-0005 / ADR-0013 / ADR-0014）

| 层 | 文件 | 依赖 Android | 作用 |
| --- | --- | --- | --- |
| 解析 | `AiCardResponseParser` | 否 | 回复文本 → `Card`，严格校验 + 告警 |
| 结束判定 | `GenerationWatcher` | 否 | 新回答识别、稳定结束、超时 |
| 业务契约 | `recognition/source/AiSource` | 否 | `RecognizeRequest`、九类错误、健康态 |
| 站点适配 | `recognition/site/*Adapter` / `*Scripts` / `*Selectors` | 否 | 选择器、注入脚本、上传策略、结构化解析 |
| 页面驱动 | `PageDriver`（`PageOps`） | 是 | 导航、就绪分类、上传、提交三态、新会话、快照 |
| 编排 | `WebViewAiSource` | 是（经 PageOps） | 互斥、取消、基线、上传回退、回答等待 |
| HTTP | `HttpApiSource` | 是（Bitmap） | 保留原 `AiRecognitionClient` 协议 |

**当前状态**：这是生产路径；Gemini 专用的旧 `AiRecognitionService` / `GeminiUpstream` 已删除。当前 49 个测试类 / 457 项单测全绿（手工拆牌新增 10 类）。

关键不变量：

- 上传成功只认附件预览节点；事件派发成功、文件框打开、脚本 `ok` 都不算。
- 驱动层只尝试 `SiteAdapter.supportedUploads` 声明的策略；未验证的回退不得启用。
- 回答必须相对发送前基线是新的；旧回答与 prompt 回显一律丢弃。
- 发送前快照必须记录 `inputText` / `attached` / `sessionId`；输入区有残留时先建立经 DOM 确认的新会话（`responseCount==0` + 输入区干净），确认不了就失败。
- 同一 `AiSource` 同一时刻只允许一个任务（`AtomicBoolean` → `Busy`）。
- 脚本动态参数一律 `JSONObject.quote`；诊断脚本不得读取 Cookie / Storage / Base64。
- **`evaluateJavascript` 只返回同步值**。Promise 与 `setTimeout` 里的赋值拿不到；异步副作用必须由调用方用后续快照（`snapshot()` / `awaitSubmitStarted`）验证，脚本返回值只作"已派发"信号。
- **JS 内禁止同步忙等**（`while (Date.now() < dl)`）。它会阻塞事件循环，Angular/React 变更检测停摆，实测 2s 内发送按钮始终不渲染。等待渲染只能用 `setTimeout` 重试或轮询下一帧。
- 登录态判据必须同时看密码框与登录 UI；只查登录选择器会把已登录页的账户菜单误判为未登录（Gemini 已登录页 `passwordCount=0`）。
- 提交成功不能只看脚本 `ok:true`；必须确认 `submitted:true`，最终成败以回答基线变化为准。
- 进程内只有一个 `WebViewHost` 实例：登录/诊断页也用它，页面代码不得自建 WebView；`MainActivity.onDestroy()` 不得销毁它。
- 可见容器与离屏锚点之间切换必须由宿主先解除旧父容器；`WindowStatus` 如实反映 Attached / Offscreen / NotAttached / NotCreated。
- 附件计数只算 composer 之上第 3 层；历史消息区域的图片不算本轮附件（真机 levels `0|0|1…` vs 第 6 层）。
- 没有可用发送按钮就不提交：先 `awaitSendEnabled()`，再重填一次；`sentEnter=true` 不能单独作为提交证据。

## 排错顺序

1. 确认是哪条路径出的问题（日志/是否点了“联网识图”/是否本 App 生成图）。
2. 本地路径：先用 `testcases/case*.jpg|png` 复现，再缩小到定位 / 点数 / 花色哪一阶段。
3. 联网路径：核对 Provider 地址、模型名、协议分支、返回 JSON 解析；不要打印 Key。
4. WebView 路径：先看 `PageDriver.pageState()` 的分类（READY / LOADING / COMPOSER_MISSING / VIEWPORT_UNHEALTHY / LOGIN_REQUIRED / HOST_NOT_ATTACHED），再看 `diagnose()` 的 `inputCount` / `responseCount` / `fileInputCount` / `loginIndicatorCount`；最后确认上传后 `verifyAttachment()` 的 `attached` 为 true。
5. 素材相关：核对 `CardAssetNames` 与 `card_picture/card_png/` 文件名一致。

## 相关 Skill

`skills/ai-recognition-debug` —— 分阶段复现与验证清单。
