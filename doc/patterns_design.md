# 拆牌结果调整页面设计（Patterns Adjust）

> 目标读者：实现 `MeldAdjustActivity` 的开发者、评审与真机验收人员。
> 评分公式与牌型合法性以 `doc/掼蛋牌型量化评分方案.md`、`doc/手牌综合牌力评估方法.md` 为唯一真源；本文只规定交互、状态与接口，不复制公式数值。
> 文档名沿用需求中的 `patterns_design.md`，代码统一使用 `MeldAdjust*`（与 `scoring.Meld` 域命名一致）。

---

## 1. 现状与问题

当前主界面的「04 综合牌力」卡片只给出 `HandScorer.score()` 算出的**唯一最优拆牌**：按分数降序展示各组牌型，用户只能看，不能改。

实战中常见的判断与算法不同：

- 算法为最大化 SPI 拆散炸弹去凑顺子，但用户这局更想留炸控场。
- 三带二里的对子想换成更大的对子，或把三张让给另一组。
- 用户想验证「如果我这样拆，牌力会掉多少」，需要一个可对比的数字，而不是凭感觉。

没有调整入口时，用户只能改手牌再重算——但那会破坏真实手牌，也无法表达「同一手牌、不同拆法」。

## 2. 目标与非目标

### 2.1 目标

1. 从主界面结果卡片一键进入「拆牌结果调整」，对**当前这手牌**的分组做拆分、合并、改判。
2. 任一次合法调整后，立即用**与最优搜索完全相同的评分口径**重算 SPI / NSPI / 分子 / 轮数 / 散牌惩罚 / 评级。
3. 始终显示当前方案与系统最优方案的差值，让用户知道自己在用什么代价换什么打法。
4. 应用后主界面展示调整结果并明确标注「已调整」，可一键恢复最优。
5. 所有中间状态都是**合法分区**（每组都是掼蛋合法牌型、所有牌恰好用一次），因此随时可计算，不存在「半成品无法评分」的死角。

### 2.2 非目标

- 不修改 `HandScorer`、SPI/NSPI 公式、评级阈值、`T_max`、牌型分值（`ui_design.md` 原则 7）。
- 不提供自由拖拽单牌到任意分组的画布式编辑（见 §12 取舍）。
- 不持久化手动拆牌：进程被杀后重开回到未计算状态，重新计算得到系统最优方案（旋转等配置变更除外，见 §6.3）。
- 不支持跨手牌比较，不支持给拆牌方案命名或收藏。
- 不新增第三方依赖（不引入 RecyclerView / Compose / 拖拽库）。

## 3. 信息架构与路由

### 3.1 入口

在主界面结果卡片 `resultCard` 内新增一个按钮，位置在 `breakdown` / `scoreExplain` 之后、`sortedImage` 之前：

```xml
<com.google.android.material.button.MaterialButton
    android:id="@+id/adjustMelds"
    style="@style/Widget.Material3.Button.OutlinedButton"
    android:layout_width="match_parent"
    android:layout_height="@dimen/button_height"
    android:layout_marginTop="@dimen/space_4"
    android:text="拆牌结果调整" />
```

按钮上方新增一行结果来源说明 `scoreOrigin`（默认 `gone`），用于「已调整」提示；同行尾部的文字按钮 `restoreOptimal` 只在已调整时可见。

入口可见性与可用性（`renderScore()` 内统一决策）：

| `state.score` | 调整按钮 | 说明 |
| --- | --- | --- |
| `None` / `Running` / `Failed` | 隐藏 | 没有可调整的结果 |
| `Stale` | 可见但禁用 | 文案「手牌已修改，请先重新计算」 |
| `Success`，`cardCount == 1` | 隐藏 | 单张只有一种拆法 |
| `Success`，`cardCount ∈ 2..27` | 可用 | 进入调整页 |
| `Success` 且 `origin == Adjusted` | 可用，文案「继续调整」 | 在调整结果上继续编辑 |

### 3.2 页面结构

新页面 `MeldAdjustActivity` 为独立 Activity（与 `AiSettingsActivity` 同构：`exported=false`、竖屏、`windowSoftInputMode=adjustResize`），全屏承载，不使用弹窗。

```text
┌──────────────────────────────────┐
│ ←  拆牌结果调整           ⟲ 重置 │
├──────────────────────────────────┤
│ 当前方案                 12 轮    │
│ 强势                             │
│ SPI 138.20          NSPI 921.3   │
│ 较系统最优 -8.02 · 多 1 轮        │
├──────────────────────────────────┤
│ 勾选两组及以上可合并；点卡片可拆分 │
│ ┌──────────────────────────────┐ │
│ │ ☐  同花顺                 494 │ │
│ │    ♠A ♠K ♠Q ♠J ♠10     [拆分] │ │
│ └──────────────────────────────┘ │
│ ┌──────────────────────────────┐ │
│ │ ☑  对子                    43 │ │
│ │    ♥2 ♣2               [拆分] │ │
│ └──────────────────────────────┘ │
│ ┌──────────────────────────────┐ │
│ │ ☐  单张                    10 │ │
│ │    ♦9                  [拆分] │ │
│ └──────────────────────────────┘ │
│              …（按分值降序）      │
├──────────────────────────────────┤
│ [合并所选 (2)]                    │
│ [应用结果]                        │
└──────────────────────────────────┘
```

结构规则：

- 顶部摘要卡（`summaryCard`）**吸顶**，滚动分组列表时始终可见 SPI 与差值。
- 分组列表用 `ScrollView` + 纵向 `LinearLayout` 动态重建；一手牌最多 27 组，无需虚拟化，不引入 RecyclerView。
- 分组按 `score` 降序，与主界面拆牌明细的排序语义一致（`sortedByDescending { it.score }`）。
- 单组行：左侧 48dp 复选目标 + 中部牌型名/分值/牌面 + 右侧「拆分」文字按钮（48dp）。整行可点开操作面板。
- 底部固定操作栏：「合并所选 (n)」+「应用结果」，不与滚动内容混排。
- 牌面用现有 `Card.display`（`♠A`、`♥10`、`小王`），不新造牌面文案。

### 3.3 牌型标签

直接使用 `MeldType.title`（单张／对子／三张／顺子／三带二／连对／钢板／炸弹／同花顺／火箭），不得在 UI 另建名称映射。

## 4. 交互规格

### 4.1 打开与关闭

- 打开：`registerForActivityResult(ActivityResultContracts.StartActivityForResult())`（与现有相册/相机回调同构），Intent 携带 `EXTRA_PARTITION`（§8.3）。
- 返回键 / `←`：若无修改直接 `finish()`；有修改弹确认「放弃本次调整？」（`保留并返回` / `放弃调整`）。
- 应用成功：`setResult(RESULT_OK, data)` 后 `finish()`，主界面在结果回调里重算并刷新。

### 4.2 勾选与多选

- 点复选框切换该组是否参与合并；选中组数 `n` 显示在底部按钮「合并所选 (n)」。
- 选中 0 或 1 组：按钮禁用，提示「至少选择两组才能合并」。
- 选中 ≥2 组：实时判断并集能否组成合法牌型（§4.3）。
  - 恰好 1 种合法牌型：按钮可用，点击直接合并。
  - 多种合法牌型：点击先弹出候选面板，选中后合并。
  - 无合法牌型：按钮禁用，按钮下方提示「所选 2 组无法组成合法牌型」。
- 选中状态不因滚动丢失；调整、撤销、重置后清空选择。

### 4.3 合并（多组 → 一组）

合并的合法性**不新写规则**：把选中组的全部下标取出，交给 `MeldEvaluator.interpretations(hand, 选中下标)`（从 `HandScorer.candidates()` 抽出的同一套生成器），只保留 `mask` 恰好覆盖全部下标的条目。

- 候选按 `score` 降序展示：`同花顺 · 494`、`顺子 · 210` 等。
- 每组候选显示牌型名与分值，点选即应用。
- 合并后新组的牌序沿用候选生成时的顺序；UI 展示以 `Card.display` 为准，不额外排序。
- 合并结果可能让轮数减少 1 或更多（多组并一组只减 1；一次合并不会拆分）。

### 4.4 拆分（一组 → 多组）

点「拆分」弹出两种模式：

| 模式 | 行为 | 适用 |
| --- | --- | --- |
| 拆成单张 | 该组每组一张，全部变 `单张` | 任意牌型 |
| 拆成基础组 | 按固定预设拆成较小的合法牌型 | 见下表 |

「拆成基础组」预设（全部由既有牌型规则导出，不新增规则）：

| 原牌型 | 拆分结果 |
| --- | --- |
| 对子 | 2 张单张 |
| 三张 | 1 个对子 + 1 张单张 |
| 三带二 | 1 个三张 + 1 个对子 |
| 顺子 / 同花顺 | 5 张单张 |
| 连对（3 对） | 3 个对子 |
| 钢板（2 个三张） | 2 个三张 |
| 炸弹（n 张） | ⌊n/2⌋ 个对子 + n mod 2 张单张 |
| 火箭 | 2 个对子（两小王、两大王） |

- 预设结果必须再经 `MeldEvaluator` 解析校验；解析失败视为实现缺陷，不静默降级。
- 拆分后各组分数按新牌型重算，不保留原组分数。

### 4.5 改判（同组牌、换牌型）

点卡片正文（非复选框、非「拆分」）打开操作面板：

```text
┌──────────────────────────┐
│ 同花顺 · 494              │
│ [改判为顺子]              │
│ [拆分为多组]              │
└──────────────────────────┘
```

- 「改判」列出同一组牌的全部合法解释（`interpretations` 中 `mask` 相同、`type` 不同的条目），当前牌型置灰不可选。
- 典型场景：同花顺 → 顺子（放弃 400 分档）；含级牌的三张可解释为「三张」或「对子 + 单张」不在此列（后者是拆分）。
- 只有一种解释时不显示「改判」。

### 4.6 撤销与重置

- 工具栏 `⟲ 重置`：回到进入页面时的初始分区（通常是系统最优），清空选择与撤销栈，需二次确认。
- 工具栏左侧返回箭头旁提供「撤销」图标按钮（`undo`），撤销栈上限 20 步，栈空时禁用。
- 撤销只回退分区，不回退选择状态（选择一律清空）。

### 4.7 应用与对比

- 顶部摘要始终显示：评级、SPI、NSPI、轮数、与系统最优的差值。
- 差值文案（`delta = 当前 SPI − 系统最优 SPI`）：
  - `delta < -0.005`：`较系统最优 -8.02 · 多 1 轮`（轮数相同则省略后半句）
  - `|delta| ≤ 0.005`：`与系统最优相同`
  - `delta > 0.005`：`优于系统方案 +3.10`（优化器受 3 秒 / 12 万状态上限约束，手动方案**可能**更高，这不是 bug）
- 「应用结果」在分区未变化时禁用，文案「尚未修改」。
- 应用后主界面：
  - 结果卡片显示调整后的全部指标；
  - `scoreOrigin` 显示 `已调整 · 较系统最优 -8.02`；
  - `restoreOptimal` 显示，点击后回到 `HandScorer.score()` 的结果并隐藏该行；
  - `sortedImage` 仍可用（27 张时），按调整后的分组渲染拆牌图。

### 4.8 长列表、字体与键盘

- 27 组时列表可滚动，底部操作栏固定；摘要卡吸顶不参与滚动。
- 系统字体放大时，牌面 chip 与牌型名换行，分值不换行、不截断。
- 本页无输入框，键盘仅在系统强制弹出时处理 `adjustResize` 滚动避让。

## 5. 评分与数据模型

### 5.1 分区表示

```kotlin
// scoring/MeldGroup.kt
/**
 * 一次手动拆牌中的一组：引用手牌下标 + 选定的牌型解释。
 * 不携带分值：分值由 MeldEvaluator 按牌型规则重新解析，避免 UI 侧伪造分数。
 */
data class MeldGroup(val indices: List<Int>, val type: MeldType)
```

- `indices` 是 `state.hand` 的**下标**（不是 `Card.id`：识别与编辑后 `id` 可能不连续），与 `Meld.mask` 的位序一致（`1 shl index`）。
- 一手牌的所有 `MeldGroup.indices` 必须**两两不相交且并集等于 `0 until hand.size`**。
- `MeldGroup` 不存 `score`：同组牌可能有多种解释（同花顺／顺子），分数随 `type` 由评分层给出。

### 5.2 评分入口

`HandScorer.candidates()` 内的打分与牌型判定是唯一真源。抽出共享层，禁止在 UI 或新文件里复制公式：

```kotlin
// scoring/MeldEvaluator.kt（由 HandScorer.candidates() 抽出）
internal object MeldEvaluator {
    /**
     * 全部合法解释，按 (mask, type) 去重，同键取最高分。
     * indices 是 hand 的子集下标，返回的 Meld.mask 使用**手牌下标位序**，可直接拼进 HandResult。
     */
    fun interpretations(
        hand: List<Card>,
        indices: List<Int>,
        level: Rank,
        considerSuits: Boolean = true,
    ): List<Meld>
}

// scoring/PartitionEvaluator.kt（新增，公开）
object PartitionEvaluator {
    /** 把手牌 + 手动分区解析成 Meld 列表；任一不合法即抛 IllegalArgumentException。 */
    fun resolve(hand: List<Card>, groups: List<MeldGroup>, level: Rank): List<Meld>

    /** 与 HandScorer.score() 完全相同的汇总口径：α、P、rounds、numerator、spi、waste。 */
    fun evaluate(hand: List<Card>, groups: List<MeldGroup>, level: Rank): HandResult

    /** 优化器与手动路径共用；level 参与 α 与散牌判定，handSize 决定 P 的 N ≥ 10 门槛与 T_max。 */
    internal fun summarize(handSize: Int, level: Rank, melds: List<Meld>): HandResult
}
```

约束：

- `HandScorer.score()` 改为「搜索得到 `List<Meld>` → `summarize(...)`」，行为与数值必须与改造前逐位相同（用现有 379 项测试与新增等价性测试锁定）。
- `HandScorer.candidates()` 对外仍返回**按 mask 取最高分**的列表（与今天一致）；多牌型解释只在 `interpretations` 里额外保留，避免改变优化器搜索规模与结果。
- `controlPercent`（α 的百分数）/ `isLowWaste` / `netValue100`（分子按 0.01 累计）从 `HandScorer` 抽成 `scoring/MeldScoring.kt` 的 `internal` 顶层函数（带 `level` / `handSize` 参数），优化器与 `PartitionEvaluator.summarize` 共用，不得重写。
- 手动路径不调用优化器，复杂度 O(组数 × 单组牌数)，无 3 秒预算问题。
- `HandResult` 结构不变；`origin` 只存在于 UI 状态（§6.1），不进评分模型。

### 5.3 与系统最优的关系

- 进入页面时只传主界面结果的摘要，不传完整 `HandResult`（否则需要在子页面伪造 melds）：

```kotlin
data class ScoreSnapshot(val spi: Double, val rounds: Int, val numerator: Double)
```

- 差值只比较 `spi`；轮数差单独一行，不合成「性价比」之类未定义指标。
- 不自动应用、不替换最优：调整永远是用户显式动作。

### 5.4 失效规则

| 事件 | 调整结果 |
| --- | --- |
| 手牌增删改（`withHand`） | `ScoreState.Success → Stale`，调整结果一并失效；主界面调整按钮禁用 |
| 级牌变化（`withLevel`） | 同上（α 与级牌取值都会变） |
| 重新计算 | 用 `HandScorer.score()` 覆盖为最优，`origin = Optimal` |
| 进程被杀后重开 | 不恢复，回到未计算状态 |
| 旋转 / Activity 重建 | 用 `onSaveInstanceState` 保留当前分区与选择（§6.3） |

## 6. 状态模型

### 6.1 主界面状态扩展

```kotlin
sealed interface ScoreOrigin {
    data object Optimal : ScoreOrigin
    /** base 始终指向**系统最优**摘要：在调整结果上继续调整时不被覆盖，差值才有稳定基准。 */
    data class Adjusted(val base: ScoreSnapshot) : ScoreOrigin
}

// WorkbenchState.kt
data class Success(val result: HandResult, val origin: ScoreOrigin = ScoreOrigin.Optimal) : ScoreState

// invalidate()：Success（含 Adjusted）→ Stale，保留 result 用于「需重新计算」提示
fun ScoreState.invalidate(): ScoreState = when (this) {
    is ScoreState.Success -> ScoreState.Stale(result)
    else -> this
}
```

- `MainActivity.calculate()` 产生 `Success(result, Optimal)`。
- 调整返回后产生 `Success(result, Adjusted(base))`。
- `renderScore()` 的 `Stale` / `Failed` / `Running` 分支不变；`Success` 分支新增来源行渲染。

### 6.2 调整页状态

```kotlin
data class MeldAdjustState(
    val hand: List<Card>,
    val level: Rank,
    val base: ScoreSnapshot,            // 系统最优摘要（只读，用于差值）
    val groups: List<MeldGroup>,        // 当前分区
    val initial: List<MeldGroup>,       // 进入页面时 / 最近一次重置后的分区
    val selection: Set<Int>,            // 勾选用于合并的组下标
    val undo: List<List<MeldGroup>>,    // 撤销栈，上限 20
    val sheet: SheetState?              // Split / Reinterpret / MergeCandidates
)
```

派生值（每次渲染时计算，不缓存）：

- `melds = PartitionEvaluator.resolve(hand, groups, level)`
- `result = PartitionEvaluator.summarize(hand.size, level, melds)`
- `delta = result.spi - base.spi`
- `dirty = groups != initial`

事件转换：

| 事件 | 前置条件 | 结果 |
| --- | --- | --- |
| `ToggleSelect(i)` | `i in groups.indices` | 切换 `selection`，不动 `groups` |
| `Split(i, IntoSingles)` | 组存在且牌数 ≥ 2 | 用单张组替换第 `i` 组，压栈 |
| `Split(i, Preset)` | 预设对该牌型有定义 | 用预设组替换第 `i` 组，压栈 |
| `Reinterpret(i, type)` | `interpretations` 中存在该 `type` 且 `mask` 相同 | 替换第 `i` 组的 `type`，压栈 |
| `Merge(candidate)` | `selection.size ≥ 2` 且候选覆盖全部选中牌 | 删除选中组、插入合并组，清空选择，压栈 |
| `Undo` | 栈非空 | 弹出恢复，清空选择 |
| `Reset` | 已确认 | `groups = initial`，清空选择与栈 |
| `Apply` | `dirty` | `RESULT_OK` + 分区 JSON |

- 所有事件在 `MeldAdjustEditor`（纯 Kotlin，无 Android 依赖）中实现，UI 只负责渲染与转发。
- 编辑器对任何非法输入返回 `sealed interface EditOutcome { Ok(state) / Rejected(state, message) }`，状态不变并携带文案，不抛异常到 UI。

### 6.3 生命周期与草稿

- `onSaveInstanceState` 保存 `groups` + `selection` 的 JSON（`PartitionCodec` 与 Intent 复用同一格式）。
- 重建后重新解析分区；解析失败（手牌已变、数据损坏）时回退到 `initial` 并提示「调整草稿已失效，已恢复初始方案」。
- 不写入 SharedPreferences / 文件：拆牌是一次会话内的决策，不应跨手牌残留。

## 7. 校验规则与错误文案

| 规则 | 违反时文案（Snackbar / 行内） |
| --- | --- |
| 每张牌恰好使用一次 | `分区不完整，请重置后重试`（正常路径不可达，属防御） |
| 每组 ≥ 1 张 | `空的分组不能应用` |
| `indices` 不重复、不越界 | `分区引用无效，请重置后重试` |
| 每组可解析为合法牌型 | `该组合不是合法牌型` |
| 合并选中 ≥ 2 组 | `至少选择两组才能合并` |
| 合并候选非空 | `所选牌无法组成合法牌型` |
| 手牌张数一致 | `手牌已变化，请返回重新计算` |
| 级牌一致 | `级牌已变化，请返回重新计算` |

文案统一走 `strings.xml` 不强制（当前项目大量文案硬编码在 Kotlin/XML 中，保持一致即可）。

## 8. 文件与接口变更

### 8.1 新增

| 文件 | 职责 |
| --- | --- |
| `app/src/main/java/nz/org/aotearoa/guandanscore/scoring/MeldGroup.kt` | 手动分区数据类 |
| `app/src/main/java/nz/org/aotearoa/guandanscore/scoring/MeldEvaluator.kt` | 从 `candidates()` 抽出的合法解释生成与打分 |
| `app/src/main/java/nz/org/aotearoa/guandanscore/scoring/MeldScoring.kt` | `controlWeight` / `isLowWaste` / `netValue` 共享实现 |
| `app/src/main/java/nz/org/aotearoa/guandanscore/scoring/PartitionEvaluator.kt` | 解析 + 汇总，公开给 UI 层 |
| `app/src/main/java/nz/org/aotearoa/guandanscore/ui/MeldAdjustEditor.kt` | 纯逻辑状态机（拆分/合并/改判/撤销/重置） |
| `app/src/main/java/nz/org/aotearoa/guandanscore/ui/MeldAdjustActivity.kt` | 页面与渲染 |
| `app/src/main/java/nz/org/aotearoa/guandanscore/ui/PartitionCodec.kt` | Intent / 保存状态用的 JSON 编解码 |
| `app/src/main/res/layout/activity_meld_adjust.xml` | 页面骨架 |
| `app/src/main/res/layout/item_meld_group.xml` | 单组行 |

### 8.2 修改

| 文件 | 变更 |
| --- | --- |
| `scoring/HandScorer.kt` | `candidates()` 委托 `MeldEvaluator`；`score()` 末尾走 `summarize`；`controlWeight` / `isLowWaste` / `netValue` 抽为共享 |
| `ui/WorkbenchState.kt` | `ScoreState.Success` 增加 `origin`；`invalidate()` 兼容 |
| `MainActivity.kt` | `adjustMelds` 点击、`registerForActivityResult`、结果回调、来源行渲染、`restoreOptimal` |
| `res/layout/activity_main.xml` | 新增 `adjustMelds`、`scoreOrigin`、`restoreOptimal` |
| `AndroidManifest.xml` | 注册 `MeldAdjustActivity`（`exported=false`、`parentActivityName=".MainActivity"`、`portrait`、`adjustResize`） |
| `doc/ui_design.md` | §5.4 增加指向本文的入口说明 |
| `doc/tasks.md` | 文档索引新增本文 |

### 8.3 传输格式

`EXTRA_PARTITION`（入口）与 `EXTRA_GROUPS`（返回）共用 `PartitionCodec`：

```json
{
  "level": "TWO",
  "hand": [
    {"rank": "ACE", "suit": "SPADE"},
    {"rank": "KING", "suit": "SPADE"},
    {"rank": "QUEEN", "suit": "SPADE"},
    {"rank": "JACK", "suit": "SPADE"},
    {"rank": "TEN", "suit": "SPADE"},
    {"rank": "TWO", "suit": "HEART"},
    {"rank": "TWO", "suit": "CLUB"}
  ],
  "groups": [
    {"type": "STRAIGHT_FLUSH", "cards": [0, 1, 2, 3, 4]},
    {"type": "PAIR", "cards": [5, 6]}
  ]
}
```

- `cards` 是**手牌下标**（`hand` 数组位置），不是 `Card.id`；`hand` 顺序即下标定义，`rank`/`suit` 只用于校验牌面未被替换。
- 基准摘要不进分区 JSON，另行通过 `EXTRA_BASE_SPI` / `EXTRA_BASE_ROUNDS` 传入，避免子页面伪造「最优」结果。
- 返回时只回传 `groups`（+ 原样回传 `level`、`hand` 供校验）；主界面必须重新 `PartitionEvaluator.evaluate`，不信任子页面算出的分数。
- 解析失败、重复下标、越界、张数不符一律拒绝并保留原结果，Toast「调整结果无效，已保留原方案」。
- 体积：27 张约 1 KB，远低于 Binder 限制。

## 9. 可访问性与适配

- 每组行给出聚合 `contentDescription`：`同花顺，黑桃A 黑桃K 黑桃Q 黑桃J 黑桃10，分值 494，未选中用于合并`。
- 复选框有独立 48×48dp 触控区与 `contentDescription`：`选择 对子 ♥2 ♣2 用于合并`。
- 合法性不只用颜色表达：禁用按钮旁始终有文字原因。
- 分值、差值同时有正负号与文字（`-8.02` 与「较系统最优」），不依赖红/绿。
- 字号：牌型名 `16sp`、牌面 `14sp`、指标 `22sp/18sp`，与主界面 `TextAppearance.GuanDan.*` 保持一致；系统字体放大到 1.3× 时不得出现截断。
- 竖屏为主；`screenOrientation="portrait"` 与主界面一致，横屏不在本轮范围。

## 10. 测试与验收

### 10.1 JVM 单测

| 测试类 | 覆盖 |
| --- | --- |
| `MeldEvaluatorTest` | 各牌型解释生成与分值；`interpretations` 对同一组牌同时给出同花顺与顺子；级牌补位（对子/三张/顺子 A2345/炸弹）；火箭要求 2 小王 + 2 大王 |
| `PartitionEvaluatorTest` | 最优分区经 `evaluate` 复算的 SPI/分子/轮数/waste 与 `HandScorer.score` 完全一致（固定样例 + 随机种子各 200 手）；`N < 10` 时 P = 0；非激活级牌时 α 规则；非法分区抛错 |
| `PartitionCodecTest` | 编解码往返；重复下标、越界、空组、张数不符、非法 type 全部拒绝；27 张体积上界 |
| `MeldAdjustEditorTest` | 拆分两种模式、合并唯一/多候选、改判、撤销上限、重置、选择清空、`dirty` 判定、非法事件返回失败且状态不变 |
| `WorkbenchStateTest`（扩展） | `Adjusted` 结果在手牌/级牌变化后变 `Stale`；重新计算的默认来源为 `Optimal` |

### 10.2 真机验收清单（LIO-N29 / Android 10）

1. 27 张手牌计算 → 结果卡出现「拆牌结果调整」，点击进入，摘要与主界面 SPI 一致。
2. 选两组 → 合并，SPI 与轮数即时变化，差值为负。
3. 拆分一个三带二为「三张 + 对子」，再撤销，回到原分区。
4. 同花顺改判为顺子 → SPI 下降，按钮「应用结果」可用。
5. 应用返回 → 主界面显示调整结果 +「已调整 · 较系统最优 …」，「恢复最优方案」可点。
6. 点击恢复 → 回到最优结果，来源行消失。
7. 已调整后改一张手牌 → 结果变 `Stale`，调整按钮禁用并提示重新计算。
8. 调整页旋转/返回再进入：草稿保留；杀进程后重开：不残留。
9. 27 组满屏滚动、字体 1.3×、TalkBack 朗读组行与复选框。
10. 全程 `logcat -b crash` 为空。

### 10.3 回归

- 改造 `HandScorer` 后，现有 39 个测试类 / 379 项必须全绿，且随机样例的 `HandResult` 逐字段（`spi`、`numerator`、`rounds`、`waste`、各组 `score`）与改造前一致。
- 主界面未进入调整页时的渲染与非调整路径不得变化。

## 11. 实施顺序

| 阶段 | 内容 | 验收 |
| --- | --- | --- |
| P0 | 抽出 `MeldEvaluator` / `summarize`，`HandScorer` 委托；补等价性测试 | 379 项全绿 + 新增等价性测试通过 |
| P1 | `MeldGroup` / `PartitionEvaluator` / `PartitionCodec` + 单测 | 分区解析、评分、编解码测试通过 |
| P2 | `MeldAdjustEditor` 纯逻辑 + 单测 | 拆分/合并/改判/撤销/重置全通过 |
| P3 | `MeldAdjustActivity`、布局、Manifest、入口按钮与 `ScoreOrigin` | 真机可进入、可调整、可应用 |
| P4 | 恢复最优、失效规则、旋转草稿、异常路径 | 清单 7/8 项通过 |
| P5 | 无障碍与字体、拆牌图回归、文档同步 | 清单 9/10 项通过，`doc/tasks.md` 更新 |

## 12. 风险与取舍

| 风险 | 取舍 |
| --- | --- |
| 手动方案通常更差，用户可能误以为应用算错 | 始终显示与系统最优的差值；不自动应用；`恢复最优` 一键回退 |
| 优化器有 3 秒 / 12 万状态上限，手动分区可能更高 | 允许 `delta > 0` 并如实显示「优于系统方案」，不强行钳制为 0 |
| 自由拖拽单牌表达力最强，但会产生大量非法中间态 | 本轮只做「拆分 / 合并 / 改判」，每步都落在合法分区上；拖拽留作后续（需引入非法态预览与自动修复） |
| 级牌「逢人配」让同一组牌有多种解释 | 用 `interpretations` 枚举，提示用户选择；同键取最高分，避免 UI 自造分 |
| 调整结果引用手牌下标，手牌一变即失效 | 统一走 `invalidate()`；解码时校验张数与级牌，失败保留原结果 |
| 页面状态机复杂 | 全部放进 `MeldAdjustEditor` 纯逻辑，UI 只渲染；沿用 `AiSettingsEditor` 的既有模式 |

## 13. 已定决策与待确认项

已定：

1. 独立 Activity，不弹窗；入口在结果卡内，非顶层导航。
2. 只允许合法分区，不提供自由拖拽。
3. 评分复用 `MeldEvaluator` / `summarize`，不复制公式，不改 `HandResult` 结构。
4. 调整结果不持久化，仅 `onSaveInstanceState` 抗配置变更。
5. 不做「保存为自定义方案 / 收藏 / 分享」。

待确认：

1. 「拆成基础组」对 7 张以上炸弹是否提供 `4 炸 + 余牌` 的预设（当前定义为对子 + 单张）。
2. 合并候选存在多种解释时，是否默认选中最高分并只给「改判」入口，以减少一次点击。
3. 是否需要在主界面拆牌明细上直接标出「第 3 组可拆」之类的引导（当前不做）。
