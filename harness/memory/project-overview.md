# 项目地图（project-overview）

> 只记模块职责、数据流与易踩坑点；功能描述见 `README.md`。

## 一句话

Android 原生掼蛋手牌评估 App：照片 → 识别牌角 → 人工校正 → 最优拆牌 → SPI/NSPI 与等级。

## 模块职责（`app/src/main/java/nz/org/aotearoa/guandanscore/`）

| 包 | 关键类 | 职责 |
| --- | --- | --- |
| `model/` | `Card.kt` | 花色/点数/王 的数据模型，级牌语义的承载者 |
| `scoring/` | `HandScorer.kt` | 生成牌型候选、搜索最优拆牌、计算 SPI/NSPI 与动态评级 |
| `recognition/` | `CardRecognizer.kt` | 本地三阶段识别（定位计数 → 点数 → 点数下方花色） |
| | `CardTemplateMatcher.kt` | 点数下方花色图形的模板匹配 |
| | `AiRecognitionClient.kt` | 联网识图，按 `AiSettings` 适配 Gemini / OpenAI 协议 |
| | `AiSettingsStore.kt` | 多 Provider 配置的 Keystore 加密存取 + 预设 |
| `imaging/` | `HandImageRenderer.kt` | 随机 27 张手牌渲染、最优拆牌图生成 |
| | `VisualCardCodec.kt` | 生成图片内嵌的牌面校验码，回读时免识别 |
| | `CardAssetNames.kt` | 54 张标准牌面 → PNG 素材名映射 |
| 根 | `MainActivity.kt` | 单 Activity UI、状态机、权限与分享 |

## 数据流

```
拍照/相册 → Bitmap
  ├─ 本地：CardRecognizer.recognize() → List<Card>（可部分失败）
  ├─ 联网：AiRecognitionClient.recognize(bitmap, settings) → List<Card>
  └─ 生成图：VisualCardCodec.decode() 直接取回 List<Card>
→ 用户在 UI 点选校正（增/删/改 + 选级牌）
→ HandScorer(level).score(cards) → HandResult（拆分方案 + SPI/NSPI/等级）
→ 可选：HandImageRenderer 输出最优拆牌图并保存
```

## 非 App 资产

| 路径 | 说明 |
| --- | --- |
| `doc/掼蛋牌型量化评分方案.md` | 单牌型分值权威文档（V4.2，文档先行；代码仍按 V4.1，落地清单见其 §八） |
| `doc/手牌综合牌力评估方法.md` | SPI/NSPI/评级权威文档 |
| `card_picture/card_png/` | App 实际使用的 54 张牌面 + 背景 |
| `card_picture/card_gif/` | 同套牌面 GIF 版（备用） |
| `testcases/` | 真实照片手工验证集（case1~4） |
| `model_training/` | 独立的标注/训练/TFLite 导出工具链 |
| `statistic.py` / `statistic_result.txt` | 蒙特卡洛牌型分布与评分统计脚本（标注为 V3.0 时期），**不参与 App 构建** |

## 易踩坑

1. `testcases/IMG_*.jpg` 被 `.gitignore` 忽略，只提交 `case*.jpg|png`。
2. `card_picture` 的素材名必须与 `CardAssetNames` 严格一致，改名会让渲染与回读同时失效。
3. `VisualCardCodec` 的校验码只用于**本 App 生成**的图片；对真实照片必须走 OCR/AI 路径。
4. 级牌是 `HandScorer` 构造参数，不是 `Card` 的属性；忘记传级牌是最常见的算错原因。
5. `statistic.py` 仍写着 V3.0，与文档 V4.2 / 代码 V4.1 都不一致，参考其统计结论前先核对版本。
6. 评分方案文档与 `HandScorer` 代码版本会阶段性分离（V4.2 文档先行、代码 V4.1）；说「App 按 V4.x 计分」前必须先看落地清单。
