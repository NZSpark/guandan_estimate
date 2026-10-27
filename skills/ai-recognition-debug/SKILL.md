---
name: ai-recognition-debug
description: 诊断掼蛋 App 的牌面识别问题，包括本地三阶段识别（定位/点数/花色）、联网 AI Provider（Gemini / OpenAI 协议）、生成图校验码回读，以及离线牌角模型训练与接入。当出现识别缺牌、错牌、花色错、Provider 报错、模型接入前验收时使用。
---

# 识别与 Provider 排障

## 适用场景

- 真实照片识别结果缺牌/多牌/点数或花色错误。
- 联网识图报错、超时、返回解析失败、切换 Provider 后异常。
- 生成图回读失效。
- 离线 `.tflite` 模型接入 App 前的验收。

## 输入

- 复现图片（优先 `testcases/case1~4.*`；新图放 `testcases/`，命名 `IMG_*` 会被忽略）。
- `harness/memory/recognition-pipeline.md`。
- 相关源码：`recognition/`、`imaging/VisualCardCodec.kt`、`imaging/CardAssetNames.kt`。

## 步骤

### A. 先定位路径

1. 确认结果来自哪条路径：本地 / 联网 / 生成图回读。
2. 生成图回读失败 → 走 C。联网失败 → 走 D。本地错误 → 走 B。

### B. 本地三阶段

1. 用固定 case 复现，记录「期望牌 vs 实际牌」。
2. 判断错误落在哪一阶段：
   - 张数不对 → 定位与计数阶段（遮挡、重叠、边缘裁切）。
   - 点数错 → OCR 阶段（牌角朝向、光照、字体歧义 6/9、10/J）。
   - 点数对但花色错 → `CardTemplateMatcher` 模板匹配（模板缺失、缩放、旋转）。
3. 检查 `CardAssetNames` 与 `card_picture/card_png/` 文件是否一一对应。
4. 遮挡导致的信息缺失属于物理限制：**不要**加启发式猜测补牌，改为 UI 提示核对。

### C. 生成图回读

1. 确认图由本 App 生成（`HandImageRenderer` + `VisualCardCodec.encode`）。
2. 检查编码/解码的校验位与排序假设是否被渲染改动破坏。
3. 外部图片不应走此路径。

### D. 联网 Provider

1. 核对 `AiSettings`：协议类型、HTTPS 地址、模型名、Key 是否存在（**不要打印 Key**）。
2. 对照 `AiRecognitionClient` 的协议分支：Gemini 用 `v1beta/models`，OpenAI 用 Responses / Chat Completions。
3. 检查返回 JSON 的字段路径与容错（空响应、markdown 包裹的 JSON、多余文本）。
4. 新增 Provider 顺序：`AiPresets` → `AiRecognitionClient` 分支 → `AiRecognitionClientTest`。

### E. 离线模型验收

1. 看 `model_manifest.json` 的 `validation_exact_card_accuracy`。
2. 确认 15 种点数 + 5 种花色样本充足。
3. API 29 真机验证耗时、内存、稳定性。
4. 全部达标才启用 LiteRT 推理路径；否则保留现有 OCR/模板/AI 路径。

## 硬规则

- 不打印、不写入、不提交任何 API Key。
- 未验收的模型不得进入正式识别路径。
- 不得用“猜补”掩盖识别缺陷；无法从照片恢复的信息必须交还用户确认。
- 修识别问题时优先补可复现的回归用例（素材映射、编解码已有测试模式）。

## 验收标准

- [ ] 能在固定 case 上说明错误阶段与根因，而非只描述现象。
- [ ] 修复后同一 case 结果正确，且未破坏其它 case。
- [ ] `./gradlew testDebugUnitTest` 通过。
- [ ] 涉及 Provider 时已更新 `AiRecognitionClientTest`。
- [ ] 结论与修复写入 `harness/state.md`；路径约束有变化则更新 `memory/recognition-pipeline.md`。
