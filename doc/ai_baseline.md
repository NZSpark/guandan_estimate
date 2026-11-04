# AI 识牌迁移前基线（AI-004）

> 对应任务：`doc/ai_tasks.md` 中 **AI-004**。
> 目的：在迁移前固定一组可核对的「人工正确牌面 ↔ 现有 AI 输出 ↔ 本地识别结果」，作为迁移后回归对照。
> 本文件只记录可复现的事实；未实测项显式标 `待测`，不因设计存在而视为通过。
>
> **2026-11-02 更新**：`testcases/case3.png` 已按用户决定删除——单张平铺摆放不代表真实扇形堆叠牌型，无验证价值。下文 case3 的牌面与结论保留为历史记录；现行 golden 集见 `model_training/datasets/golden_truth.json`（9 张真实图 / 242 张牌）。另有 `testcases/auto_case2.jpg`、`testcases/auto_case3.jpg`、`testcases/auto_case4.jpg`、`testcases/auto_case5.jpg`、`testcases/auto_case6.jpg`、`testcases/auto_case7.jpg`、`testcases/auto_case8.jpg`、`testcases/auto_case9.jpg`、`testcases/auto_case10.jpg`、`testcases/auto_case11.jpg`、`testcases/auto_case12.jpg`、`testcases/auto_case13.jpg`、`testcases/auto_case14.jpg`、`testcases/auto_case15.jpg`、`testcases/auto_case16.jpg`、`testcases/auto_case17.jpg`、`testcases/auto_case18.jpg`、`testcases/auto_case19.jpg`、`testcases/auto_case20.jpg`、`testcases/auto_case21.jpg`、`testcases/auto_case22.jpg`、`testcases/auto_case23.jpg`、`testcases/auto_case24.jpg`、`testcases/auto_case25.jpg`、`testcases/auto_case26.jpg`、`testcases/auto_case27.jpg`、`testcases/auto_case28.jpg`、`testcases/auto_case29.jpg`、`testcases/auto_case30.jpg`、`testcases/auto_case31.jpg`、`testcases/auto_case32.jpg`、`testcases/auto_case33.jpg`、`testcases/auto_case34.jpg`、`testcases/auto_case35.jpg`、`testcases/auto_case36.jpg`、`testcases/auto_case37.jpg`、`testcases/auto_case38.jpg`、`testcases/auto_case39.jpg`、`testcases/auto_case40.jpg`、`testcases/auto_case41.jpg`、`testcases/auto_case42.jpg`、`testcases/auto_case43.jpg`、`testcases/auto_case44.jpg`、`testcases/auto_case45.jpg`、`testcases/auto_case46.jpg`、`testcases/auto_case47.jpg`、`testcases/auto_case48.jpg`、`testcases/auto_case49.jpg`、`testcases/auto_case50.jpg`、`testcases/auto_case51.jpg`、`testcases/auto_case52.jpg`、`testcases/auto_case53.jpg`、`testcases/auto_case54.jpg`、`testcases/auto_case55.jpg`、`testcases/auto_case56.jpg`、`testcases/auto_case57.jpg`、`testcases/auto_case58.jpg`、`testcases/auto_case59.jpg`、`testcases/auto_case60.jpg`、`testcases/auto_case61.jpg`、`testcases/auto_case62.jpg`、`testcases/auto_case63.jpg`、`testcases/auto_case64.jpg`、`testcases/auto_case65.jpg`、`testcases/auto_case66.jpg`、`testcases/auto_case67.jpg`、`testcases/auto_case68.jpg`、`testcases/auto_case69.jpg`、`testcases/auto_case70.jpg`、`testcases/auto_case71.jpg`、`testcases/auto_case72.jpg`、`testcases/auto_case73.jpg`、`testcases/auto_case74.jpg`、`testcases/auto_case75.jpg`、`testcases/auto_case76.jpg`、`testcases/auto_case77.jpg`、`testcases/auto_case78.jpg`、`testcases/auto_case79.jpg`、`testcases/auto_case80.jpg`、`testcases/auto_case81.jpg`、`testcases/auto_case82.jpg` 八十一张 AI 生成训练样本，不计入这 9 张 golden。

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

行 3：`♠10  ♣J  ♥J  ♦Q  ♠Q  ♣K  ♦A  ♥A  小王`

- 张数：27，符合 `VisualCardCodec.CARD_COUNT = 27`。
- 重复检查：无重复牌（大王唯一）。
- Joker 归属：图中为彩色小丑，按 `CardAssetNames` 约定 `JOKER-A.png = 大王 (BIG_JOKER)`、`JOKER-B.png = 小王 (SMALL_JOKER)`；已由人工签核确认为 **小王**（`model_training/approvals/golden-definition.json` v1），真机识别结果仍需复核（见 §4 待测项）。

### `testcases/case4.jpg`（辅助，含遮挡）

可见牌面（非严格顺序，含重叠）：`♣2 ♠4 ♠4 ♣A ♠5 ♣Q ♥4 ♦4 ♣9 ♣10 ♦9 ♣10 ♦10 ♥9 ♠10 ♥10 ♠J ♥J ♦K ♠K ♥8 ♦8 ♦2 ♥2 大王 大王` 及部分被遮挡牌。

- 用途：仅作「真实扇形场景下现有管线能认出多少张」的对照，**不要求 27 张全对**。

### `testcases/auto_case2.jpg`（生成训练样本，27 张）

上排（左→右，14 张）：`♣7  ♠9  ♥2  ♥7  ♣6  大王  ♠4  ♦7  ♣9  ♥9  ♣2  ♣10  ♦6  ♠10`

下排（左→右，13 张）：`♦3  ♦A  小王  ♦10  ♥Q  ♦2  ♦J  ♣A  ♦5  ♦9  ♠K  ♦4  ♠Q`

- 张数：27；生成种子：`20261102`；两张 Joker 按项目约定：`JOKER-A`=大王（BJ），`JOKER-B`=小王（SJ）。
- 用途：生成域训练样本/密集扇形回归参考；**未人工签核**，`role=training`，不进入 T0.5 的 9 张真实 golden/242 牌，也不作为 T5.3 或真实域指标。
- 机器可读真值：`model_training/datasets/golden_truth.json` v15 与 `model_training/approvals/golden-definition.json` v3（`confirmed=false`）。

### `testcases/auto_case3.jpg`（生成训练样本，27 张）

上排（左→右，14 张，宽扇）：`♥2  ♠8  ♥9  ♦A  ♥K  ♥Q  ♥10  ♣5  ♦5  ♦9  ♦6  ♣2  ♦10  小王`

下排（左→右，13 张，窄扇）：`♦7  ♥5  ♦K  ♦3  ♦Q  ♥6  大王  ♣3  ♣A  ♠7  ♠5  ♠2  ♣7`

- 张数：27；生成种子：`20261103`；布局：上排宽、下排窄的两层扇形，左手持牌。
- 用途：生成域训练样本/密集扇形回归参考；**未人工签核**，`role=training`，不进入 T0.5 的 9 张真实 golden/242 牌，也不作为 T5.3 或真实域指标。
- 机器可读真值：`model_training/datasets/golden_truth.json` v16 与 `model_training/approvals/golden-definition.json` v4（`confirmed=false`）。

### `testcases/auto_case4.jpg`（生成训练样本，27 张）

上排（左→右，14 张，宽扇）：`J♠  Q♣  K♠  K♣  5♠  10♥  K♥  7♦  Q♠  7♠  3♣  6♣  8♣  9♣`

下排（左→右，13 张，窄扇）：`9♦  6♦  10♠  4♦  5♦  8♥  4♠  7♣  K♦  10♦  10♣  J♥  4♣`

- 张数：27；生成种子：`20261104`；布局：上排宽、下排窄的两层扇形，左手持牌。
- 用途：生成域训练样本/密集扇形回归参考；**未人工签核**，`role=training`，不进入 T0.5 的 9 张真实 golden/242 牌，也不作为 T5.3 或真实域指标。
- 机器可读真值：`model_training/datasets/golden_truth.json` v17 与 `model_training/approvals/golden-definition.json` v5（`confirmed=false`）。

### `testcases/auto_case5.jpg`（生成训练样本，27 张）

上排（左→右，9 张）：`2♣  8♥  7♥  5♦  6♠  3♥  4♦  大王  9♦`

中排（左→右，9 张）：`7♠  K♣  2♠  8♣  3♦  J♥  9♥  10♣  5♣`

下排（左→右，9 张）：`2♦  5♠  A♥  10♠  6♣  5♥  3♣  Q♦  10♦`

- 张数：27；生成种子：`20261109`；布局：三排从左向右展开，右牌压左牌并露出左牌左上角点数/花色；每排右侧牌相对左侧逐张向右多倾斜，上排最宽、中排次之、下排最窄，下排覆盖上排约 60%。背景参考 `case14` 绿色牌桌，左手持牌。
- 用途：生成域训练样本；`role=training`、`confirmed=false`，不进入 T0.5 的 9 张真实 golden/242 牌，也不作为 T5.3 或真实域指标。
- 机器可读真值：`model_training/datasets/golden_truth.json` v25 与 `model_training/approvals/golden-definition.json` v13（`confirmed=false`）。

### `testcases/auto_case6.jpg`（生成训练样本，27 张）

上排（左→右，9 张）：`5♦  5♣  K♥  9♦  3♣  10♦  10♣  大王  7♥`

中排（左→右，9 张）：`A♥  9♣  2♣  8♣  Q♣  Q♥  9♥  K♠  4♣`

下排（左→右，9 张）：`8♥  J♠  6♥  8♠  小王  3♦  K♣  5♥  3♠`

- 张数：27；生成种子：`20261108`；布局：三排从左向右展开，右牌压左牌并露出左牌左上角点数/花色；上排最宽、中排次之、下排最窄，下排覆盖上排约 60%。背景参考 `case14` 绿色牌桌，左手持牌。
- 用途：生成域训练样本；`role=training`、`confirmed=false`，不进入 T0.5 的 9 张真实 golden/242 牌，也不作为 T5.3 或真实域指标。
- 机器可读真值：`model_training/datasets/golden_truth.json` v24 与 `model_training/approvals/golden-definition.json` v12（`confirmed=false`）。

### `testcases/auto_case7.jpg`（生成训练样本，27 张）

上排（左→右，9 张）：`A♦  4♦  10♣  Q♦  J♠  J♣  4♥  4♣  2♥`

中排（左→右，9 张）：`7♥  A♠  A♣  6♥  小王  2♣  3♣  8♥  K♥`

下排（左→右，9 张）：`大王  5♣  7♣  5♦  3♦  9♦  6♦  6♣  8♣`

- 张数：27；生成种子：`20261110`；布局：参考 `case14` 的三排牌扇，从左向右展开，右牌压左牌并露出左牌左上角点数/花色；左侧牌轻向左、右侧牌逐张向右倾斜，顶边宽、底边窄；上排最宽、中排次之、下排最窄，下排覆盖上排约 60%。绿色牌桌背景，左手持牌。
- 用途：生成域训练样本；`role=training`、`confirmed=false`，不进入 T0.5 的 9 张真实 golden/242 牌，也不作为 T5.3 或真实域指标。
- 机器可读真值：`model_training/datasets/golden_truth.json` v26 与 `model_training/approvals/golden-definition.json` v14（`confirmed=false`）。

### `testcases/auto_case8.jpg`（生成训练样本，27 张）

上排（左→右，9 张）：`3♥  5♦  10♦  J♣  4♥  8♦  3♠  Q♥  8♣`

中排（左→右，9 张）：`Q♣  5♣  3♣  5♥  9♠  4♦  10♣  6♦  J♦`

下排（左→右，9 张）：`7♠  大王  K♣  5♠  8♥  J♥  4♠  2♣  3♦`

- 张数：27；生成种子：`20261111`；布局：参考 `case14` 的三排牌扇，从左向右展开，右牌压左牌并露出左牌左上角点数/花色；左侧牌轻向左、右侧牌逐张向右倾斜，顶边宽、底边窄；上排最宽、中排次之、下排最窄，下排覆盖上排约 60%。绿色牌桌背景，左手持牌。
- 用途：生成域训练样本；`role=training`、`confirmed=false`，不进入 T0.5 的 9 张真实 golden/242 牌，也不作为 T5.3 或真实域指标。
- 机器可读真值：`model_training/datasets/golden_truth.json` v27 与 `model_training/approvals/golden-definition.json` v15（`confirmed=false`）。

### `testcases/auto_case9.jpg`（生成训练样本，27 张）

上排（左→右，9 张）：`A♦  Q♠  7♥  小王  Q♦  8♠  3♦  K♥  8♣`

中排（左→右，9 张）：`9♠  2♥  10♦  Q♣  5♥  9♥  大王  2♦  6♥`

下排（左→右，9 张）：`5♠  10♥  2♣  7♦  6♣  4♦  4♠  A♠  3♥`

- 张数：27；生成种子：`20261112`；布局：参考 `case14` 的三排牌扇，从左向右展开，右牌压左牌并露出左牌左上角点数/花色；左侧牌轻向左、右侧牌逐张向右倾斜，顶边宽、底边窄；上排最宽、中排次之、下排最窄，下排覆盖上排约 60%。绿色牌桌背景，左手持牌。
- 用途：生成域训练样本；`role=training`、`confirmed=false`，不进入 T0.5 的 9 张真实 golden/242 牌，也不作为 T5.3 或真实域指标。
- 机器可读真值：`model_training/datasets/golden_truth.json` v27 与 `model_training/approvals/golden-definition.json` v15（`confirmed=false`）。

### `testcases/auto_case10.jpg`（生成训练样本，27 张）

上排（左→右，9 张）：`Q♣  2♣  9♣  J♦  2♠  A♣  4♥  5♣  7♥`

中排（左→右，9 张）：`K♠  7♣  4♣  K♦  4♠  10♥  8♣  7♦  大王`

下排（左→右，9 张）：`Q♥  6♥  10♠  A♠  2♥  6♠  3♠  J♥  3♦`

- 张数：27；生成种子：`20261113`；布局：参考 `case14` 的三排牌扇，从左向右展开，右牌压左牌并露出左牌左上角点数/花色；左侧牌轻向左、右侧牌逐张向右倾斜，顶边宽、底边窄；上排最宽、中排次之、下排最窄，下排覆盖上排约 60%。绿色牌桌背景，左手持牌。
- 用途：生成域训练样本；`role=training`、`confirmed=false`，不进入 T0.5 的 9 张真实 golden/242 牌，也不作为 T5.3 或真实域指标。
- 机器可读真值：`model_training/datasets/golden_truth.json` v27 与 `model_training/approvals/golden-definition.json` v15（`confirmed=false`）。

### `testcases/auto_case11.jpg`（生成训练样本，27 张）

上排（左→右，9 张）：`3♥  Q♥  8♥  Q♦  6♦  8♠  A♦  7♠  9♣`

中排（左→右，9 张）：`A♥  7♣  2♥  10♠  3♠  7♦  9♠  4♥  J♦`

下排（左→右，9 张）：`9♦  J♣  A♠  6♣  K♠  2♣  4♣  10♦  Q♣`

- 张数：27；生成种子：`20261120`；布局：参考 `case19` 的红色桌布与三排牌扇，从左向右展开，右牌压左牌并露出左牌点数/花色；右侧牌逐张向右倾斜，顶边宽、底边窄；下排覆盖上排一多半。左手持牌。
- 用途：生成域训练样本；`role=training`、`confirmed=false`，不进入 T0.5 的 9 张真实 golden/242 牌，也不作为 T5.3 或真实域指标。
- 机器可读真值：`model_training/datasets/golden_truth.json` v28 与 `model_training/approvals/golden-definition.json` v16（`confirmed=false`）。

### `testcases/auto_case12.jpg`（生成训练样本，27 张）

上排（左→右，9 张）：`8♥  Q♣  5♦  10♦  K♥  6♣  9♦  5♣  A♣`

中排（左→右，9 张）：`大王  J♣  J♦  Q♠  5♠  2♠  J♠  4♠  K♦`

下排（左→右，9 张）：`3♠  10♥  8♣  3♦  A♥  4♥  3♣  7♥  5♥`

- 张数：27；生成种子：`20261121`；布局：参考 `case19` 的红色桌布与三排牌扇，从左向右展开，右牌压左牌并露出左牌点数/花色；右侧牌逐张向右倾斜，顶边宽、底边窄；下排覆盖上排一多半。左手持牌。
- 用途：生成域训练样本；`role=training`、`confirmed=false`，不进入 T0.5 的 9 张真实 golden/242 牌，也不作为 T5.3 或真实域指标。
- 机器可读真值：`model_training/datasets/golden_truth.json` v28 与 `model_training/approvals/golden-definition.json` v16（`confirmed=false`）。

### `testcases/auto_case13.jpg`（生成训练样本，27 张）

上排（左→右，9 张）：`J♥  7♥  3♦  2♠  Q♦  4♠  A♠  3♣  4♣`

中排（左→右，9 张）：`Q♠  J♠  2♣  J♣  2♥  6♥  9♠  2♦  J♦`

下排（左→右，9 张）：`7♦  K♦  8♥  4♦  5♥  4♥  10♣  3♠  5♣`

- 张数：27；生成种子：`20261122`；布局：参考 `case19` 的红色桌布与三排牌扇，从左向右展开，右牌压左牌并露出左牌点数/花色；右侧牌逐张向右倾斜，顶边宽、底边窄；下排覆盖上排一多半。左手持牌。
- 用途：生成域训练样本；`role=training`、`confirmed=false`，不进入 T0.5 的 9 张真实 golden/242 牌，也不作为 T5.3 或真实域指标。
- 机器可读真值：`model_training/datasets/golden_truth.json` v28 与 `model_training/approvals/golden-definition.json` v16（`confirmed=false`）。

### `testcases/auto_case14.jpg`（生成训练样本，27 张）

上排（左→右，9 张）：`10♠  J♥  3♠  6♥  J♣  Q♣  10♥  6♣  9♥`

中排（左→右，9 张）：`Q♦  6♠  小王  2♣  5♣  7♣  2♥  5♦  8♣`

下排（左→右，9 张）：`4♣  6♦  9♦  4♥  8♦  K♠  5♥  5♠  9♣`

- 张数：27；生成种子：`20261123`；布局：参考 `case19` 的红色桌布与三排牌扇，从左向右展开，右牌压左牌并露出左牌点数/花色；右侧牌逐张向右倾斜，顶边宽、底边窄；下排覆盖上排一多半。左手持牌。
- 用途：生成域训练样本；`role=training`、`confirmed=false`，不进入 T0.5 的 9 张真实 golden/242 牌，也不作为 T5.3 或真实域指标。
- 机器可读真值：`model_training/datasets/golden_truth.json` v28 与 `model_training/approvals/golden-definition.json` v16（`confirmed=false`）。

### `testcases/auto_case15.jpg`（生成训练样本，27 张）

上排（左→右，9 张）：`大王  Q♣  5♠  A♣  3♣  K♠  6♠  9♣  K♥`

中排（左→右，9 张）：`4♥  6♥  J♣  3♥  2♥  J♥  3♦  5♣  Q♠`

下排（左→右，9 张）：`10♥  4♣  10♣  3♠  6♦  7♥  A♠  Q♦  K♦`

- 张数：27；生成种子：`20261124`；布局：参考 `case19` 的红色桌布与三排牌扇，从左向右展开，右牌压左牌并露出左牌点数/花色；右侧牌逐张向右倾斜，顶边宽、底边窄；下排覆盖上排一多半。左手持牌。
- 用途：生成域训练样本；`role=training`、`confirmed=false`，不进入 T0.5 的 9 张真实 golden/242 牌，也不作为 T5.3 或真实域指标。
- 机器可读真值：`model_training/datasets/golden_truth.json` v28 与 `model_training/approvals/golden-definition.json` v16（`confirmed=false`）。

### `testcases/auto_case16.jpg`（生成训练样本，27 张）

上排（左→右，9 张）：`A♣  8♦  3♥  10♥  3♦  3♣  8♥  K♦  Q♣`

中排（左→右，9 张）：`4♠  5♥  8♣  7♦  A♠  K♠  4♣  3♠  10♠`

下排（左→右，9 张）：`2♣  6♦  7♠  6♣  J♠  9♣  A♦  9♦  J♣`

- 张数：27；生成种子：`20261125`；布局：参考 `case19` 的红色桌布与三排牌扇，从左向右展开，右牌压左牌并露出左牌点数/花色；右侧牌逐张向右倾斜，顶边宽、底边窄；下排覆盖上排一多半。左手持牌。
- 用途：生成域训练样本；`role=training`、`confirmed=false`，不进入 T0.5 的 9 张真实 golden/242 牌，也不作为 T5.3 或真实域指标。
- 机器可读真值：`model_training/datasets/golden_truth.json` v28 与 `model_training/approvals/golden-definition.json` v16（`confirmed=false`）。

### `testcases/auto_case17.jpg`（生成训练样本，27 张）

上排（左→右，9 张）：`5♥  3♥  3♣  A♠  4♠  6♣  6♦  8♦  2♠`

中排（左→右，9 张）：`2♦  7♠  5♠  9♠  7♣  A♥  10♦  3♦  9♥`

下排（左→右，9 张）：`9♣  J♥  K♦  8♥  J♦  Q♣  J♠  Q♥  Q♠`

- 张数：27；生成种子：`20261126`；布局：参考 `case19` 的红色桌布与三排牌扇，从左向右展开，右牌压左牌并露出左牌点数/花色；右侧牌逐张向右倾斜，顶边宽、底边窄；下排覆盖上排一多半。左手持牌。
- 用途：生成域训练样本；`role=training`、`confirmed=false`，不进入 T0.5 的 9 张真实 golden/242 牌，也不作为 T5.3 或真实域指标。
- 机器可读真值：`model_training/datasets/golden_truth.json` v28 与 `model_training/approvals/golden-definition.json` v16（`confirmed=false`）。

### `testcases/auto_case18.jpg`（生成训练样本，27 张）

上排（左→右，9 张）：`3♣  Q♥  4♦  7♦  9♥  10♣  J♣  6♦  K♦`

中排（左→右，9 张）：`3♦  5♦  6♣  9♠  4♥  小王  3♠  A♦  A♠`

下排（左→右，9 张）：`6♥  10♠  2♣  4♣  3♥  2♥  2♠  K♠  8♠`

- 张数：27；生成种子：`20261127`；布局：参考 `case19` 的红色桌布与三排牌扇，从左向右展开，右牌压左牌并露出左牌点数/花色；右侧牌逐张向右倾斜，顶边宽、底边窄；下排覆盖上排一多半。左手持牌。
- 用途：生成域训练样本；`role=training`、`confirmed=false`，不进入 T0.5 的 9 张真实 golden/242 牌，也不作为 T5.3 或真实域指标。
- 机器可读真值：`model_training/datasets/golden_truth.json` v28 与 `model_training/approvals/golden-definition.json` v16（`confirmed=false`）。

### `testcases/auto_case19.jpg`（生成训练样本，27 张）

上排（左→右，9 张）：`4♥  8♣  8♠  J♠  7♣  A♣  2♠  K♦  Q♦`

中排（左→右，9 张）：`J♦  10♥  4♣  2♦  10♦  Q♠  6♥  K♣  5♥`

下排（左→右，9 张）：`5♦  6♠  3♣  大王  3♠  3♥  8♦  A♦  J♥`

- 张数：27；生成种子：`20261128`；布局：参考 `case19` 的红色桌布与三排牌扇，从左向右展开，右牌压左牌并露出左牌点数/花色；右侧牌逐张向右倾斜，顶边宽、底边窄；下排覆盖上排一多半。左手持牌。
- 用途：生成域训练样本；`role=training`、`confirmed=false`，不进入 T0.5 的 9 张真实 golden/242 牌，也不作为 T5.3 或真实域指标。
- 机器可读真值：`model_training/datasets/golden_truth.json` v28 与 `model_training/approvals/golden-definition.json` v16（`confirmed=false`）。

### `testcases/auto_case20.jpg`（生成训练样本，27 张）

上排（左→右，9 张）：`2♣  2♥  5♠  6♦  J♣  3♣  K♦  7♦  J♠`

中排（左→右，9 张）：`K♣  2♠  小王  6♣  5♥  10♣  4♣  3♦  Q♦`

下排（左→右，9 张）：`8♥  7♥  3♠  大王  A♣  8♠  Q♠  K♠  9♠`

- 张数：27；生成种子：`20261129`；布局：参考 `case19` 的红色桌布与三排牌扇，从左向右展开，右牌压左牌并露出左牌点数/花色；右侧牌逐张向右倾斜，顶边宽、底边窄；下排覆盖上排一多半。左手持牌。
- 用途：生成域训练样本；`role=training`、`confirmed=false`，不进入 T0.5 的 9 张真实 golden/242 牌，也不作为 T5.3 或真实域指标。
- 机器可读真值：`model_training/datasets/golden_truth.json` v28 与 `model_training/approvals/golden-definition.json` v16（`confirmed=false`）。

### `testcases/auto_case21.jpg`（生成训练样本，27 张）

上排（左→右，9 张）：`Q♠  K♠  2♥  J♥  6♥  A♦  10♥  J♣  K♣`

中排（左→右，9 张）：`9♠  2♠  10♣  5♥  4♥  5♦  7♣  J♠  K♦`

下排（左→右，9 张）：`6♠  2♦  3♣  10♦  8♥  3♦  7♥  7♠  K♥`

- 张数：27；生成种子：`20261131`；布局：参考 `case14` 的三排左→右牌扇，右牌压左牌并露出左牌左上角点数/花色；右侧牌逐张向右倾斜，下排覆盖上排一多半；左手持牌。
- **逐牌角几何**：每张牌的 `quad` 是整牌 TL/TR/BR/BL 四点，`corner_quad` 是左上 35% 可见牌角区域的 TL/TR/BR/BL 四点；同时记录 `visible`、`occlusion`、`rotation`。27/27 牌角可见，坐标由生成器直接计算，叠加核对见 `output/runs/20261102/auto_case21-corners.jpg`。
- 用途：生成域训练样本；`role=training`、`confirmed=false`，不进入 T0.5 的 9 张真实 golden/242 牌，也不作为 T5.3 或真实域指标。
- 机器可读真值：`model_training/datasets/golden_truth.json` v29 与 `model_training/approvals/golden-definition.json` v17（`confirmed=false`）。

### `testcases/auto_case22.jpg`（生成训练样本，27 张）

上排（左→右，9 张）：`10♦  大王  10♠  4♣  A♣  9♦  7♠  5♥  6♣`

中排（左→右，9 张）：`3♣  7♥  A♥  J♠  Q♦  9♣  小王  5♦  Q♣`

下排（左→右，9 张）：`J♣  K♠  K♥  4♥  9♥  2♥  6♥  K♣  8♠`

- 张数：27；生成种子：`20261132`；随机参考：`testcases/case18.jpg`。布局模拟参考图的木质桌面、分层牌扇与左手持牌：三排均从左向右展开，右牌压左牌并露出左牌左上角点数/花色；右侧牌逐张向右倾斜；下排覆盖上排约 60%。
- **逐牌角几何**：每张牌的 `quad` 是整牌 TL/TR/BR/BL 四点，`corner_quad` 是左上 35% 可见牌角区域的 TL/TR/BR/BL 四点；同时记录 `visible`、`occlusion`、`rotation`。27/27 牌角可见，坐标由生成器计算，叠加核对见 `output/runs/20261102/auto22-work/auto_case22-corners.jpg`。
- 用途：生成域训练样本；`role=training`、`confirmed=false`，不进入 T0.5 的 9 张真实 golden/242 牌，也不作为 T5.3 或真实域指标。
- 机器可读真值：`model_training/datasets/golden_truth.json` v30 与 `model_training/approvals/golden-definition.json` v18（`confirmed=false`）。

### `testcases/auto_case23.jpg` ～ `auto_case52.jpg`（批量生成训练样本，30 张）

- 每张 27 张牌，9+9+9 三排；随机参考一张 `testcases/case*.jpg`，模拟参考图的扇形牌型、木质/布面桌色与左手持牌；左→右展开，右牌压左牌并露出左牌点数/花色；下方扇形覆盖上方约 58–64%。
- **逐牌角几何**：每张牌的 `quad` 是整牌 TL/TR/BR/BL 四点，`corner_quad` 是左上 35% 可见牌角区域的 TL/TR/BR/BL 四点；同时记录 `visible`、`occlusion`、`rotation`。30 张均 27/27 牌角可见。叠加核对见 `output/runs/20261102/auto23-52-batch/overlays/`。
- 用途：生成域训练样本；`role=training`、`confirmed=false`，不进入 T0.5 的 9 张真实 golden/242 牌，也不作为 T5.3 或真实域指标。
- 机器可读真值：`model_training/datasets/golden_truth.json`（批次 v31–v60，共 60 个 case）与 `model_training/approvals/golden-definition.json` v48。批量几何与 sha256：`output/runs/20261102/auto23-52-batch/auto23-52-meta.json`。总览：`output/runs/20261102/auto23-52-batch/auto23-52-contact-sheet.jpg`。
- 牌面（每例三排，左→右）：
- `auto_case23`（参考 `case12.jpg`，seed `20261201`）：上排 A♦ 5♥ 2♠ K♥ K♠ 8♣ 8♥ Q♦ J♥；中排 4♥ 3♣ 6♦ 8♦ K♣ 3♥ 4♣ 小王 10♦；下排 3♠ 4♠ 7♥ 9♣ J♣ 5♦ A♣ 9♦ Q♣。
- `auto_case24`（参考 `case12.jpg`，seed `20261202`）：上排 4♠ Q♥ 10♣ K♦ 6♥ K♣ J♦ A♣ 6♦；中排 Q♠ 7♠ 9♠ 3♥ 10♦ 3♦ 7♣ 8♣ 8♠；下排 大王 小王 10♠ K♠ 5♥ A♠ 8♥ 6♠ 2♠。
- `auto_case25`（参考 `case12.jpg`，seed `20261203`）：上排 2♣ Q♥ 4♥ Q♣ 8♦ 7♦ 大王 5♦ 6♦；中排 10♦ 9♠ 2♥ 6♣ 9♣ 3♥ K♣ 3♠ Q♠；下排 7♣ 9♦ K♠ J♠ 8♥ K♦ 10♠ 3♦ 5♥。
- `auto_case26`（参考 `case21.jpg`，seed `20261204`）：上排 6♦ K♠ Q♣ 7♥ 4♣ Q♥ 8♠ A♣ 7♣；中排 3♣ 5♦ J♠ K♥ 9♠ K♦ 3♠ 10♥ 3♦；下排 10♣ 2♣ 6♣ 5♣ 4♥ 9♣ 6♥ 4♦ 9♦。
- `auto_case27`（参考 `case10.jpg`，seed `20261205`）：上排 6♦ 10♠ 9♦ 9♥ Q♥ A♣ 5♠ 8♥ 6♠；中排 K♦ 7♣ 6♥ 3♥ 8♠ A♥ Q♠ 2♣ 9♠；下排 A♠ K♠ 2♠ 5♥ 4♦ 4♣ 3♣ K♣ 7♥。
- `auto_case28`（参考 `case116.jpg`，seed `20261206`）：上排 A♦ 2♦ 大王 Q♣ 6♣ 7♠ 6♦ 10♣ K♦；中排 6♠ 4♠ J♦ 9♥ 2♣ 6♥ 3♣ 3♦ K♣；下排 10♠ 8♥ 10♥ J♠ A♣ 3♥ J♣ 5♠ Q♦。
- `auto_case29`（参考 `case12.jpg`，seed `20261207`）：上排 4♠ 7♥ 5♦ A♦ 10♣ 3♠ 9♠ 6♦ 2♠；中排 7♦ 小王 A♠ 6♠ 5♣ 8♣ 8♠ Q♥ Q♣；下排 A♥ 3♣ 3♦ Q♦ 9♣ 10♦ 5♠ J♦ 2♣。
- `auto_case30`（参考 `case4.jpg`，seed `20261208`）：上排 4♥ Q♠ 9♦ K♥ 8♠ 5♠ J♣ 5♣ K♣；中排 3♣ 大王 10♦ 2♦ 10♠ A♣ 7♣ Q♣ 7♠；下排 2♥ 5♦ 4♦ 2♣ 6♦ A♠ K♠ 4♠ 5♥。
- `auto_case31`（参考 `case14.jpg`，seed `20261209`）：上排 A♦ 3♥ 5♦ 2♥ 6♦ 4♦ 大王 3♠ J♣；中排 4♥ Q♥ 6♠ K♥ 9♣ 8♣ 2♦ K♦ 8♦；下排 4♣ 5♣ K♣ 2♣ 2♠ 7♦ J♦ 7♥ Q♠。
- `auto_case32`（参考 `case4.jpg`，seed `20261210`）：上排 Q♥ K♦ 9♦ 3♥ 6♦ 2♥ A♠ 小王 A♦；中排 4♣ J♦ 大王 A♥ 3♦ A♣ 9♣ 6♥ Q♠；下排 6♠ 8♣ 5♦ J♣ 4♥ 2♣ 2♠ 4♦ 2♦。
- `auto_case33`（参考 `case21.jpg`，seed `20261211`）：上排 J♦ 5♦ J♠ 7♦ 9♠ K♣ 8♠ J♣ 2♦；中排 3♠ 2♠ Q♣ K♠ 7♥ 6♥ Q♦ 3♥ K♦；下排 6♠ 10♠ A♦ 10♦ 6♣ 大王 Q♠ 9♥ Q♥。
- `auto_case34`（参考 `case14.jpg`，seed `20261212`）：上排 8♦ Q♥ 8♣ 7♣ 5♠ J♥ 9♠ 4♠ K♣；中排 6♣ 10♦ K♦ 3♠ 10♣ J♣ K♥ 4♦ 2♣；下排 A♠ 大王 J♠ 5♦ 9♣ K♠ 2♦ 5♥ 小王。
- `auto_case35`（参考 `case14.jpg`，seed `20261213`）：上排 10♣ 2♣ J♥ K♥ 9♠ 2♥ 6♣ 10♦ 9♣；中排 3♦ 8♠ 大王 A♣ J♠ 2♦ 10♥ 6♠ 8♥；下排 3♣ 5♣ A♦ 7♦ Q♠ 5♥ K♦ 小王 6♦。
- `auto_case36`（参考 `case116.jpg`，seed `20261214`）：上排 3♠ 5♣ J♠ 2♦ 5♠ A♣ Q♥ 小王 9♣；中排 9♦ 4♥ 2♠ K♠ K♣ 7♦ 大王 8♦ K♦；下排 4♠ 10♦ 9♠ 9♥ A♠ Q♦ J♦ 5♦ 7♥。
- `auto_case37`（参考 `case18.jpg`，seed `20261215`）：上排 9♥ 5♣ 7♠ Q♥ A♣ 5♦ 8♠ 5♥ K♦；中排 6♠ J♣ K♠ 4♥ Q♦ 7♣ 2♥ J♥ 4♦；下排 10♥ 3♦ 5♠ K♣ 9♦ 4♣ 2♦ 小王 4♠。
- `auto_case38`（参考 `case116.jpg`，seed `20261216`）：上排 J♦ 10♣ J♣ 4♥ 7♥ A♣ 10♥ 7♠ 8♠；中排 Q♥ 2♥ 5♣ 9♣ 8♣ 5♥ 3♦ 2♦ 9♥；下排 K♦ 3♠ Q♦ 4♣ 8♥ 7♦ 3♣ J♥ A♥。
- `auto_case39`（参考 `case116.jpg`，seed `20261217`）：上排 5♠ 3♠ 8♣ J♥ 4♦ 4♣ A♠ 4♥ 7♠；中排 大王 Q♠ 10♥ 小王 7♣ 8♦ A♦ 2♣ 7♥；下排 8♠ 10♦ K♦ 8♥ 5♣ 2♦ A♣ 6♠ 6♥。
- `auto_case40`（参考 `case19.jpg`，seed `20261218`）：上排 10♣ 9♥ A♥ J♣ 4♣ A♦ A♠ Q♥ J♠；中排 Q♦ 9♦ 10♠ A♣ K♦ 5♣ J♦ 小王 大王；下排 9♣ 3♣ K♠ 4♥ 10♥ 5♠ K♥ 5♦ 8♦。
- `auto_case41`（参考 `case18.jpg`，seed `20261219`）：上排 7♥ 3♦ Q♠ 10♠ 6♥ 8♣ 7♠ 3♣ 2♣；中排 3♥ Q♥ J♠ A♣ A♦ 4♥ 9♦ J♥ 7♣；下排 5♥ 6♠ 6♦ A♥ Q♦ 大王 2♠ 3♠ 8♠。
- `auto_case42`（参考 `case116.jpg`，seed `20261220`）：上排 K♠ 10♣ 3♣ 4♣ 5♦ 7♥ 6♣ A♣ 5♣；中排 5♠ 大王 Q♠ 小王 Q♦ 7♠ 9♠ 9♦ J♣；下排 5♥ 2♣ 4♥ 4♦ 9♥ J♠ 6♥ 8♠ J♥。
- `auto_case43`（参考 `case10.jpg`，seed `20261221`）：上排 K♥ 2♥ 9♥ 5♠ J♣ A♠ 7♠ 3♦ Q♥；中排 9♠ 2♣ 8♠ 4♣ 8♦ Q♣ 6♣ 10♠ 9♦；下排 Q♠ 7♦ J♥ 8♣ 7♣ 4♥ 4♦ 6♥ 小王。
- `auto_case44`（参考 `case21.jpg`，seed `20261222`）：上排 4♠ 6♥ 10♠ 9♦ 6♦ A♠ 8♣ 6♣ 3♠；中排 3♣ Q♥ 2♣ 4♣ 5♠ 8♥ 9♣ 6♠ A♥；下排 7♣ 2♠ 大王 3♥ 3♦ A♣ 4♥ J♠ 10♦。
- `auto_case45`（参考 `case4.jpg`，seed `20261223`）：上排 5♦ 6♠ 3♥ 8♦ 8♥ 3♣ 8♣ 4♠ 7♥；中排 6♣ 3♠ 5♥ 2♣ J♠ 5♠ 2♦ Q♥ J♦；下排 2♥ 7♦ 小王 10♠ 5♣ 9♠ 4♥ 10♥ Q♦。
- `auto_case46`（参考 `case116.jpg`，seed `20261224`）：上排 3♥ 9♦ 10♣ 5♠ A♠ 5♥ 2♠ 9♥ A♥；中排 4♣ Q♠ 大王 10♥ 7♠ 4♦ Q♣ K♠ K♦；下排 6♣ 2♦ 9♣ A♣ 7♣ 4♠ 3♣ J♦ 2♥。
- `auto_case47`（参考 `case19.jpg`，seed `20261225`）：上排 2♣ K♥ K♣ K♠ 4♥ 4♦ Q♥ 7♦ 7♠；中排 7♥ 10♥ 8♥ 9♠ A♠ 小王 6♥ 6♣ 5♣；下排 10♦ 8♦ 3♦ 9♣ J♥ Q♦ J♦ J♣ 大王。
- `auto_case48`（参考 `case12.jpg`，seed `20261226`）：上排 6♣ J♥ Q♣ 7♣ 7♥ 大王 K♣ 10♥ 9♥；中排 9♠ 8♠ 6♠ 10♣ K♥ 9♣ 3♦ J♣ 小王；下排 7♦ A♥ 2♣ 5♠ 8♥ 3♥ 6♦ K♦ Q♦。
- `auto_case49`（参考 `case116.jpg`，seed `20261227`）：上排 J♦ 9♥ 3♣ 8♠ 4♦ 6♦ J♥ 2♥ 10♠；中排 10♣ Q♠ 9♦ 10♦ 2♦ 大王 A♥ 6♥ 4♠；下排 2♠ 7♣ K♠ 3♠ 4♣ 6♠ 8♦ 10♥ A♠。
- `auto_case50`（参考 `case19.jpg`，seed `20261228`）：上排 J♠ 4♠ J♣ 6♣ A♠ 4♣ 2♠ 10♣ J♥；中排 Q♠ 8♣ 2♦ 8♠ K♣ 6♦ K♥ 小王 10♠；下排 4♥ Q♥ Q♣ 3♣ 5♦ 3♦ 9♠ 6♥ J♦。
- `auto_case51`（参考 `case13.jpg`，seed `20261229`）：上排 A♥ 4♥ A♦ 9♥ 大王 6♣ J♥ Q♠ J♠；中排 A♣ 小王 4♠ 6♦ 9♣ 10♥ K♦ J♦ 7♥；下排 10♠ 8♦ J♣ 6♠ A♠ 8♣ 10♣ 8♥ 5♣。
- `auto_case52`（参考 `case13.jpg`，seed `20261230`）：上排 3♥ J♦ 大王 2♦ 3♣ 4♣ 8♣ A♠ 2♥；中排 8♦ K♦ 10♠ 8♥ 7♦ 5♠ 9♠ 7♣ 10♥；下排 K♠ 5♣ 6♣ 3♦ 4♥ 2♠ 7♥ 4♦ 5♦。

### `testcases/auto_case53.jpg` ～ `auto_case82.jpg`（批量生成训练样本，30 张）

- 每张 27 张牌，9+9+9 三排；随机参考一张 `testcases/case*.jpg`，模拟参考图的扇形牌型、木质/布面桌色与左手持牌；左→右展开，右牌压左牌并露出左牌点数/花色；下方扇形覆盖上方约 58–64%。
- **逐牌角几何**：每张牌的 `quad` 是整牌 TL/TR/BR/BL 四点，`corner_quad` 是左上 35% 可见牌角区域的 TL/TR/BR/BL 四点；同时记录 `visible`、`occlusion`、`rotation`。30 张均 27/27 牌角可见。叠加核对见 `output/runs/20261103/auto53-82-batch/overlays/`。
- 用途：生成域训练样本；`role=training`、`confirmed=false`，不进入 T0.5 的 9 张真实 golden/242 牌，也不作为 T5.3 或真实域指标。
- 机器可读真值：`model_training/datasets/golden_truth.json`（批次 v61–v90，共 90 个 case）与 `model_training/approvals/golden-definition.json` v78。批量几何与 sha256：`output/runs/20261103/auto53-82-batch/auto53-82-meta.json`。总览：`output/runs/20261103/auto53-82-batch/auto53-82-contact-sheet.jpg`。
- 牌面（每例三排，左→右）：
- `auto_case53`（参考 `case14.jpg`，seed `20261231`）：上排 J♣ 10♥ 2♣ 2♥ K♣ 2♦ Q♦ 10♠ 9♦；中排 3♣ Q♥ 7♦ J♠ A♣ 5♣ 2♠ 9♠ 6♦；下排 5♠ 3♠ A♥ 8♦ 6♥ A♠ 4♣ 5♦ K♠。
- `auto_case54`（参考 `case4.jpg`，seed `20261232`）：上排 大王 5♥ 10♠ A♦ J♠ 2♠ 4♦ Q♠ 9♦；中排 3♣ A♥ Q♦ 6♥ J♥ 7♦ 8♥ 2♥ 8♣；下排 7♥ 5♣ 3♠ 7♠ 9♥ 2♦ 8♦ 4♠ A♣。
- `auto_case55`（参考 `case10.jpg`，seed `20261233`）：上排 A♥ 8♠ Q♥ 3♦ 6♠ 10♠ 6♦ 6♥ 2♦；中排 5♥ 2♥ 大王 10♥ A♣ K♦ 5♣ A♦ Q♠；下排 5♦ Q♣ 3♠ 7♠ 2♠ A♠ 9♠ 4♦ 4♠。
- `auto_case56`（参考 `case21.jpg`，seed `20261234`）：上排 K♦ A♦ 8♠ 3♥ 9♥ 4♣ 6♣ 5♦ 6♠；中排 Q♦ 6♥ J♣ 5♣ 10♦ 7♣ Q♥ Q♣ 2♦；下排 K♠ 10♥ 小王 7♠ 3♠ 7♥ A♥ 8♦ K♥。
- `auto_case57`（参考 `case18.jpg`，seed `20261235`）：上排 K♥ J♥ 8♦ 7♠ 2♦ 4♠ 9♠ J♠ 2♠；中排 4♥ 2♣ 10♣ 5♥ 8♥ 9♣ 3♥ 3♠ 9♦；下排 6♦ Q♦ 8♠ 7♥ 大王 5♦ 7♣ A♠ 9♥。
- `auto_case58`（参考 `case12.jpg`，seed `20261236`）：上排 4♣ 7♠ 8♠ 大王 5♠ K♣ J♥ 7♥ J♦；中排 A♦ 7♦ 8♥ A♠ K♦ 6♠ 2♣ 9♣ 6♥；下排 6♦ 8♦ 5♥ 9♥ 2♥ 3♦ K♥ 6♣ 5♣。
- `auto_case59`（参考 `case21.jpg`，seed `20261237`）：上排 K♥ 2♣ 10♣ 6♣ A♠ Q♥ A♣ 8♦ J♠；中排 9♣ A♥ 大王 3♦ 6♥ 2♥ Q♣ 7♠ 7♥；下排 8♣ 4♣ 9♥ 2♠ 3♣ Q♦ 6♦ 3♥ 5♠。
- `auto_case60`（参考 `case18.jpg`，seed `20261238`）：上排 10♦ A♣ K♦ 5♠ J♦ 9♥ A♠ Q♥ Q♣；中排 6♦ J♠ K♠ 8♦ 3♥ A♥ 5♣ 8♥ J♣；下排 5♥ 6♠ 8♣ 7♣ 2♥ K♥ 2♦ A♦ 10♣。
- `auto_case61`（参考 `case13.jpg`，seed `20261239`）：上排 8♠ K♥ A♥ 大王 5♠ 6♠ 5♦ 7♠ 5♣；中排 7♣ 6♥ 6♣ 2♥ J♦ 9♠ 4♦ 4♥ K♣；下排 J♠ 小王 Q♠ 9♦ 3♦ 4♣ 8♥ 2♦ 2♣。
- `auto_case62`（参考 `case19.jpg`，seed `20261240`）：上排 9♦ 2♣ 10♥ 2♠ Q♣ K♠ 4♥ 9♣ 4♠；中排 7♦ 7♣ 4♣ 6♥ 6♦ A♦ 3♦ 10♣ K♣；下排 Q♥ 5♦ Q♦ 9♥ A♥ K♦ 6♣ 10♠ 5♥。
- `auto_case63`（参考 `case116.jpg`，seed `20261241`）：上排 8♥ A♥ 5♣ 6♦ 2♠ 7♠ 9♣ K♥ 8♣；中排 2♥ 10♦ 9♠ 5♦ 5♠ 2♦ Q♠ A♠ 8♠；下排 4♣ 3♣ Q♦ J♠ K♣ 4♠ 4♦ 小王 A♦。
- `auto_case64`（参考 `case116.jpg`，seed `20261242`）：上排 4♦ Q♥ 4♠ 10♥ 5♠ A♠ 9♠ 10♠ 4♣；中排 6♠ Q♣ 3♦ 5♥ 5♣ 小王 Q♠ Q♦ 8♠；下排 8♦ A♥ A♣ 2♣ K♣ 10♦ 6♥ 4♥ 9♣。
- `auto_case65`（参考 `case116.jpg`，seed `20261243`）：上排 K♠ 10♦ K♥ 4♦ A♠ 7♦ Q♦ A♣ 6♥；中排 3♦ 8♦ 5♥ Q♥ 2♦ 4♣ 5♠ 5♦ 小王；下排 6♦ 7♣ 9♣ 10♥ 8♣ Q♣ 7♠ 6♣ 2♥。
- `auto_case66`（参考 `case18.jpg`，seed `20261244`）：上排 2♠ 大王 K♣ 7♦ 3♠ 5♦ 2♣ K♠ 6♦；中排 Q♥ 10♥ 2♥ 7♥ 10♦ 5♠ 3♣ J♠ 9♦；下排 J♦ 9♣ 5♥ 7♣ 8♦ 8♥ 7♠ 9♥ 4♥。
- `auto_case67`（参考 `case19.jpg`，seed `20261245`）：上排 5♣ 6♥ 5♥ Q♣ 9♥ K♣ 7♠ 小王 大王；中排 2♥ K♦ A♣ 7♥ 2♦ 10♣ 7♦ 6♣ 4♥；下排 A♥ 8♣ 4♦ K♠ 9♣ 9♦ A♠ K♥ 8♠。
- `auto_case68`（参考 `case10.jpg`，seed `20261246`）：上排 9♠ 8♦ A♠ 4♣ 3♥ 6♠ 3♦ 9♦ 8♠；中排 3♣ J♦ 8♥ 大王 9♥ 5♠ 4♠ K♣ 3♠；下排 2♣ A♣ K♦ 10♠ A♦ 6♥ J♣ A♥ J♥。
- `auto_case69`（参考 `case14.jpg`，seed `20261247`）：上排 6♦ 4♣ K♠ 7♦ J♣ K♥ 3♣ 10♦ 2♣；中排 8♥ 7♣ 小王 6♣ A♠ 4♦ 9♣ Q♥ J♦；下排 6♥ 5♣ 9♠ 7♠ 10♠ 8♦ 3♠ 5♥ 3♥。
- `auto_case70`（参考 `case18.jpg`，seed `20261248`）：上排 7♣ 7♠ 4♠ 9♦ 9♥ 4♣ 3♣ 10♥ 9♠；中排 8♥ 5♠ 6♠ A♥ 10♣ A♣ 5♥ 6♣ 5♣；下排 小王 2♥ 2♦ 9♣ 大王 4♦ 3♥ 2♠ Q♠。
- `auto_case71`（参考 `case4.jpg`，seed `20261249`）：上排 7♦ Q♦ 8♥ 9♠ J♦ 5♣ 8♣ 6♦ A♣；中排 2♠ 7♠ 10♥ Q♠ 3♦ K♠ 大王 10♦ 4♥；下排 A♥ 4♣ 7♥ 5♦ 7♣ 10♠ A♠ 4♦ Q♥。
- `auto_case72`（参考 `case14.jpg`，seed `20261250`）：上排 J♣ J♦ K♦ 3♣ 3♠ 7♦ 2♣ 2♠ 2♥；中排 大王 6♦ 6♠ J♠ 2♦ 6♥ 8♥ 小王 A♥；下排 J♥ Q♥ 8♠ Q♠ 4♠ 9♥ 5♠ K♥ 6♣。
- `auto_case73`（参考 `case4.jpg`，seed `20261251`）：上排 5♥ K♠ 6♦ 小王 3♥ 10♦ 6♥ K♦ J♥；中排 9♦ 5♠ 10♥ Q♠ A♥ 4♣ 4♦ 3♠ A♦；下排 大王 4♥ 3♦ K♣ K♥ 5♣ Q♦ Q♥ 2♣。
- `auto_case74`（参考 `case13.jpg`，seed `20261252`）：上排 3♥ 6♠ K♠ 8♠ J♠ 9♠ 7♠ 4♦ 6♥；中排 3♠ 3♦ 2♠ J♦ 10♦ 小王 9♥ 3♣ 大王；下排 A♥ 5♠ Q♦ 7♦ 6♦ 5♦ 7♣ 8♦ A♦。
- `auto_case75`（参考 `case19.jpg`，seed `20261253`）：上排 A♣ A♦ 7♠ A♥ 6♥ 3♥ 8♦ 2♠ 7♣；中排 K♥ 3♦ 2♦ 4♠ 大王 4♦ J♠ 2♥ K♠；下排 5♣ 10♠ 9♦ Q♦ 6♠ J♦ 3♠ 10♦ Q♣。
- `auto_case76`（参考 `case18.jpg`，seed `20261254`）：上排 9♦ 7♣ Q♦ A♣ 3♦ 6♥ K♠ 2♣ 2♦；中排 Q♥ 4♥ 7♠ K♣ 大王 9♠ 10♣ 3♣ 10♥；下排 5♣ 4♣ J♥ 8♠ 6♣ 小王 A♦ 5♥ 8♦。
- `auto_case77`（参考 `case14.jpg`，seed `20261255`）：上排 10♣ 3♠ 7♠ 9♣ 9♦ 6♥ 大王 K♦ 10♠；中排 2♠ 3♦ 4♠ 10♦ 4♣ 小王 6♠ 5♣ Q♥；下排 Q♣ A♠ 5♥ Q♦ 2♣ 7♥ 6♦ 3♥ 9♥。
- `auto_case78`（参考 `case19.jpg`，seed `20261256`）：上排 A♥ 3♥ 大王 A♠ 4♠ 5♣ 5♠ Q♦ 小王；中排 K♥ A♦ 4♣ 8♥ 8♣ 8♦ 9♣ 10♦ K♣；下排 4♥ Q♠ 7♣ 10♥ 7♥ 6♣ 2♠ Q♥ 2♣。
- `auto_case79`（参考 `case12.jpg`，seed `20261257`）：上排 K♥ 9♦ K♠ 6♥ 9♥ Q♦ Q♠ 6♣ A♣；中排 2♥ 4♦ 6♠ A♦ J♠ 2♠ 7♦ 10♦ 10♥；下排 8♥ 5♠ 5♣ 4♣ 3♠ 3♦ J♣ 8♦ 5♥。
- `auto_case80`（参考 `case21.jpg`，seed `20261258`）：上排 4♦ K♦ 10♦ 5♣ 9♦ 6♣ K♠ Q♠ 8♣；中排 K♣ 小王 5♦ 10♠ J♥ 5♠ 9♠ 2♦ 2♣；下排 3♣ 7♦ 4♣ 6♥ Q♦ Q♣ 3♥ J♣ 9♣。
- `auto_case81`（参考 `case18.jpg`，seed `20261259`）：上排 大王 K♥ Q♠ 5♥ 5♠ 5♣ 3♥ Q♣ 6♠；中排 4♦ 4♣ 4♥ 8♠ 9♦ 10♥ A♠ K♦ 2♠；下排 8♣ 10♣ 10♦ 7♠ 9♠ 2♣ 8♦ 3♣ J♠。
- `auto_case82`（参考 `case13.jpg`，seed `20261260`）：上排 2♠ 5♥ 10♣ 9♦ 3♦ J♣ 3♣ 8♠ 3♠；中排 5♦ 小王 3♥ 6♦ K♣ 7♥ 6♥ 7♦ Q♦；下排 5♣ K♥ K♦ A♥ Q♥ 8♣ J♠ 9♠ 7♠。

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
1. ~~case3 的 Joker 是否应判为大王（`BIG_JOKER`）~~ → 已签核确认为小王（`SMALL_JOKER`）。
2. case3 第 2 行末尾 `♦10` 与第 3 行首 `♠10` 相邻，易混淆，需确认识别不串位。
3. ~~case4 重叠牌的「应有张数」需人工二次清点后补全~~ → 已清点为 26 张（两副牌，含 ♣10×2、大王×2）。

## 5. 使用方式

迁移（WebView 多源化）完成后，用同一批图片复跑三条路径，把实际输出填入 §4 表格，与 §2 人工牌面逐张比对，即可量化迁移是否引入回归。
