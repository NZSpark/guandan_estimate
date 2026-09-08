# 掼蛋牌力评估 Android App

一款面向掼蛋手牌分析的原生 Android 应用，提供照片识别、联网 AI 识图、人工校正、最优拆牌与综合牌力计算。

评分依据仓库内的：

- [掼蛋牌型量化评分方案.md](./掼蛋牌型量化评分方案.md)
- [手牌综合牌力评估方法.md](./手牌综合牌力评估方法.md)

## 主要功能

- 拍照或从系统相册选择手牌照片。
- 三阶段本地识别：牌张定位与计数、点数识别、点数下方花色图形识别。
- 可配置联网 AI 识图，原生支持 Google Gemini（默认推荐）以及 OpenAI Responses / Chat Completions 协议兼容 Provider。
- 内置 Google Gemini（默认模型 `gemini-2.5-flash`，兼容 `gemini-3.8-flash` 等）与 OpenAI（默认模型 `gpt-5.6-sol`）快捷预设。
- 点选识别结果进行校正、删除或补录。
- 支持对任意 `1～27` 张已确认手牌计算最优拆牌。
- 显示 SPI 分子（控制加权并扣除散牌惩罚后）、计划出牌轮数、SPI、NSPI、动态等级及逐组拆牌明细。
- 随机生成27张掼蛋牌图片，使用仓库提供的标准 PNG 牌面素材，保存后可重新读入识别。
- 完整27张手牌可生成最优拆牌图：不同牌型从左到右分列，按牌型分值从高到低排列。

## 牌力指标

```text
SPI  = [Σ(Fᵢ × αᵢ) − P] ÷ T_plan
NSPI = SPI ÷ T_max(N) × 100
```

- `Fᵢ`：牌型分值。普通牌型先算原始分再线性映射到 `191` 上限（最小四张炸弹 `192`，严格高于所有普通牌型）；4/5 张炸弹、同花顺按文档直接计分；6 张及以上炸弹走对数平滑；火箭固定 `1000`。
- `αᵢ`：控制力系数。炸弹、同花顺、火箭、单张大王、单张级牌取 `1.15`，其余取 `1.00`。
- `P`：散牌惩罚 `= 15 × N_waste`，其中 `N_waste` 为点数 `<10` 且无法成牌的孤立单张/小对子数量；残局（`N<10` 张）按文档不计散牌惩罚。

应用会根据当前手牌张数采用动态 `T_max(N)` 和评级阈值，覆盖开局、中局与残局：

| 阶段 | 弱势 | 均衡 | 强势 | 碾压级 |
| --- | --- | --- | --- | --- |
| 残局 1~12 张 | SPI < 60 | 60 ~ 120 | 120 ~ 180 | ≥ 180 |
| 中局 13~18 张 | SPI < 70 | 70 ~ 130 | 130 ~ 200 | ≥ 200 |
| 开局 ≥19 张 | SPI < 80 | 80 ~ 140 | 140 ~ 220 | ≥ 220 |

当前实现与仓库内两份 V4.0 规则文档保持一致。

## 使用方法

1. 点击“拍照”或“相册”载入照片。
2. 等待本地三阶段识别完成；需要时点击“联网识图”获取 AI 识别结果。
3. 点击牌面标签校正，点击 `×` 删除，或通过“+ 补录”添加牌。
4. 选择“本局打”的级牌。
5. 点击“计算综合牌力”查看最优拆牌和评估结果。

也可以点击“随机手牌”生成一手27张牌并保存。完成综合牌力计算后，点击“最优拆牌图”即可生成并保存按牌型分列、按分值降序排列的新图片。

被相邻牌或手指完全遮挡的牌角无法从单张照片可靠恢复，计算前应核对当前牌张。

## AI Provider 设置

“AI 设置”支持保存多个 Provider 配置并一键切换：

- **Google Gemini（推荐）**：使用 Google 账号登录 [Google AI Studio](https://aistudio.google.com/) 免费创建 API Key，填入即可使用超高精度的 Gemini 识图能力（默认模型 `gemini-2.5-flash`，接口 `https://generativelanguage.googleapis.com/v1beta/models`）。
- **OpenAI / 自定义 Provider**：支持 OpenAI Responses API 以及标准 Chat Completions API 协议。
- **配置项**：
  - Provider 名称
  - HTTPS API 地址
  - 模型名称
  - API Key

API Key 不会写入源码或 APK 资源，而是使用 Android Keystore 加密后保存在设备本地。不要在 Git、截图、Issue 或聊天记录中提交真实密钥。生产应用更推荐通过自有后端代理 API 请求，避免在客户端持有长期密钥。

## 构建环境

- Android Studio / Android Gradle Plugin 8.7.3
- Gradle 8.9
- JDK 17
- `compileSdk 35`
- `minSdk 26`
- 已在 Android API 29 真机验证

配置本机 Android SDK 后执行：

```powershell
./gradlew.bat testDebugUnitTest assembleDebug
```

生成的调试 APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

安装到已连接设备：

```powershell
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
```

## 项目结构

```text
app/src/main/java/nz/org/aotearoa/guandanscore/
├── imaging/       # 随机手牌、排序图片与图片校验码
├── model/         # 扑克牌数据模型
├── recognition/   # 本地 OCR、AI 识图与 Provider 配置
├── scoring/       # 牌型候选生成、最优拆牌和 SPI/NSPI
└── MainActivity.kt
card_picture/
├── card_png/      # App实际使用的54张标准PNG牌面及背景图
└── card_gif/      # 同套牌面的GIF版本
testcases/         # 手工验证图片
model_training/    # 独立的真实照片标注、训练、评估与TFLite导出工具
```

## 离线模型训练

`model_training/` 提供独立于 Android 构建的牌角标注和训练工具。它以 `testcases/` 中的真实照片为输入，训练点数与花色双输出模型，并导出 `.tflite` 和模型 manifest。详细流程参见 [model_training/README.md](./model_training/README.md)。训练模型通过独立验证后再接入 App；现有本地 OCR、模板匹配和联网识图功能保持可用。

## 测试

单元测试覆盖：

- 炸弹、火箭、顺子、同花顺等牌型分值。
- 级牌作为逢人配时的组合计算。
- 1～27张部分或完整手牌的最优拆牌。
- 动态 `T_max(N)`、SPI、NSPI 和评级。
- V4.0普通牌型线性映射上下界（最高191）、最小四炸（192）、高阶炸弹对数分与固定火箭分（1000）。
- 54张标准牌面到 PNG 素材文件名的映射，包括大小王。
- 规则文档中的关键计算示例。

## 隐私与网络

- 本地识别不上传图片。
- 只有用户主动点击“联网识图”时，当前照片才会发送到所选 AI Provider。
- 图片和 API 请求受相应 Provider 的隐私政策与计费规则约束。

## License

当前仓库尚未指定开源许可证。未经许可，不代表授予复制、修改或分发权利。
