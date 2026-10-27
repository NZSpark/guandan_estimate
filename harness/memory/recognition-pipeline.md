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

## 排错顺序

1. 确认是哪条路径出的问题（日志/是否点了“联网识图”/是否本 App 生成图）。
2. 本地路径：先用 `testcases/case*.jpg|png` 复现，再缩小到定位 / 点数 / 花色哪一阶段。
3. 联网路径：核对 Provider 地址、模型名、协议分支、返回 JSON 解析；不要打印 Key。
4. 素材相关：核对 `CardAssetNames` 与 `card_picture/card_png/` 文件名一致。

## 相关 Skill

`skills/ai-recognition-debug` —— 分阶段复现与验证清单。
