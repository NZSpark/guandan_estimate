# AI 识牌迁移前基线（AI-004）

> 对应任务：`doc/ai_tasks.md` 中 **AI-004**。
> 目的：在迁移前固定一组可核对的「人工正确牌面 ↔ 现有 AI 输出 ↔ 本地识别结果」，作为迁移后回归对照。
> 本文件只记录可复现的事实；未实测项显式标 `待测`，不因设计存在而视为通过。

## 1. 选用的测试图片

| 图片 | 尺寸/格式 | 是否选用 | 说明 |
| --- | --- | --- | --- |
| `testcases/case3.png` | PNG，约 2.4 MB | ✅ 主基线 | 27 张牌三行平铺、无遮挡、逐张可读，最适合作为精确对照 |
| `testcases/case4.jpg` | JPG，约 2.0 MB | ✅ 辅助 | 桌面扇形摊牌，含一张 Joker；牌角有重叠，作为真实场景对照 |
| `testcases/case1.jpg` | JPG，约 116 KB | 参考 | 手持扇形牌、有手指遮挡，牌角大量被覆盖 |
| `testcases/case2.jpg` | JPG，约 54 KB | 参考 | 手持扇形牌、重叠严重且带干扰文字，人工判读本身不可靠 |

**结论：** 精确基线以 `case3.png` 为准；扇形场景用 `case4.jpg` 作为现实难度对照。`case1/2` 因遮挡过多，人工正确牌面本身存在歧义，仅作鲁棒性参考，不作为精确回归基准。

## 2. 人工确认的正确牌面

### `testcases/case3.png`（27 张，按行从左到右）

行 1：`♣2  ♦3  ♠3  ♥4  ♣5  ♦6  ♠6  ♥6  ♣7`

行 2：`♦7  ♠7  ♣8  ♦8  ♥8  ♠8  ♣9  ♥9  ♦10`

行 3：`♠10  ♣J  ♥J  ♦Q  ♠Q  ♣K  ♦A  ♥A  大王`

- 张数：27，符合 `VisualCardCodec.CARD_COUNT = 27`。
- 重复检查：无重复牌（大王唯一）。
- Joker 归属：图中为彩色小丑，按 `CardAssetNames` 约定 `JOKER-A.png = 大王 (BIG_JOKER)`、`JOKER-B.png = 小王 (SMALL_JOKER)`，暂定 **大王**；此判定需真机识别结果复核（见 §4 待测项）。

### `testcases/case4.jpg`（辅助，含遮挡）

可见牌面（非严格顺序，含重叠）：`♣2 ♠4 ♠4 ♣A ♠5 ♣Q ♦4 ♦4 ♣9 ♣10 ♦10 ♥9 ♠10 ♥10 ♠J ♥J ♦K ♠K ♥8 ♦8 ♦2 ♥2 Joker` 及部分被遮挡牌。

- 用途：仅作「真实扇形场景下现有管线能认出多少张」的对照，**不要求 27 张全对**。

## 3. 现有输出通道（迁移前）

| 路径 | 入口 | 运行前提 | 输出类型 |
| --- | --- | --- | --- |
| 本地三阶段 | `CardRecognizer.recognize(bitmap)` | 无网络，需 Android 运行时 | `RecognitionResult`（可能缺牌） |
| 联网 AI（WebView 网页源，迁移目标） | `WebViewAiSource` / `SiteAdapter` | 网络 + 网页登录态，**免 API Key** | `List<Card>` |
| 联网 AI（HTTP API，旧路径） | `AiRecognitionClient.recognize(bitmap, settings)` | 网络 + 有效 API Key | `List<Card>` |
| 生成图回读 | `VisualCardCodec.decode(bitmap)` | 图须由本 App 生成（含条码） | `List<Card>?` |

> 三路结果都必须进入 UI 人工校正流程，不得跳过确认直接计算。

## 4. 实测记录

本地三阶段与网页源 AI 都需 Android 运行时（WebView 网页源免 API Key，只需网页登录态）。桌面会话不能直接跑，因此基线以项目已有的真机证据为锚，未覆盖项由后续真机任务补填。

**已有真机证据（迁移前现状，来自 `harness/state.md`）：**

- Gemini 网页源端到端闭环已通过：填文本 → 合成 paste 注入图片 → 点 `Send message` → 页面出现 `You said …`、`Show the uploaded image in a Lightbox`、回答正常返回。
- DeepSeek 网页源：登录态跨进程保持；诊断 `input=1 send=1 file=1`；合成 paste 后 `attached=true`。
- 这些说明网页源在迁移前已能真实识别，且不依赖 API Key。

| 图片 | 路径 | 现有输出 | 正确牌面 | 差异 | 状态 |
| --- | --- | --- | --- | --- | --- |
| case3.png | 本地三阶段（`CardRecognizer`） | — | 见 §2 | — | 待测（真机；本图为项目既有手工验证集） |
| case3.png | 联网 AI（Gemini 网页源，免 Key） | 未针对本图记录 | 见 §2 | — | 待测（真机，登录态已具备） |
| case3.png | 联网 AI（DeepSeek 网页源，免 Key） | 未针对本图记录 | 见 §2 | — | 待测（真机，粘贴闭环已具备） |
| case3.png | 生成图回读 | — | 见 §2 | — | 不适用（case3 非本 App 生成，无条码） |
| case4.jpg | 本地三阶段 | — | 见 §2 辅助 | — | 待测（真机） |

**待人工复核项：**
1. case3 的 Joker 是否应判为大王（`BIG_JOKER`）。
2. case3 第 2 行末尾 `♦10` 与第 3 行首 `♠10` 相邻，易混淆，需确认识别不串位。
3. case4 重叠牌的「应有张数」需人工二次清点后补全。

## 5. 使用方式

迁移（WebView 多源化）完成后，用同一批图片复跑三条路径，把实际输出填入 §4 表格，与 §2 人工牌面逐张比对，即可量化迁移是否引入回归。
