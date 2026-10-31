# 手工拆牌实施任务清单

> **依据：** [`doc/patterns_design.md`](patterns_design.md)（交互、状态、接口与验收的唯一设计真源）、[`doc/ui_design.md`](ui_design.md) §5.4–§5.5、[`doc/掼蛋牌型量化评分方案.md`](掼蛋牌型量化评分方案.md)、[`doc/手牌综合牌力评估方法.md`](手牌综合牌力评估方法.md)。
>
> **目标：** 按 P0–P5 落地「手工拆牌」：在不改变最优拆牌公式与数值的前提下，让用户对同一手牌做拆分、合并、改判，并始终保留「最优拆牌 / 手工拆牌」两套分值用于对照。
>
> **当前状态（2026-10-31，真机补验后）：** P0–P5 共 37 项 **全部完成**；真机 MP-035 六项全部通过，MP-036 ⑦⑧⑨⑩ 通过，MP-019（单张隐藏 / `Failed`）与 MP-022（连对 / 火箭拆分）已补验。
>
> **本轮真机新增修复（详见执行记录）：** ① 重建后勾选被 View 状态恢复清空（`isSaveEnabled=false`）；② `None`/`Running`/`Failed` 时旧结果卡的手工入口未隐藏；③ 返回确认里的「保留并返回」此前只是关弹窗、实际丢弃修改，现改为应用并返回。
>
> **设备状态：** LIO-N29 已通过 WiFi adb 重连（`adb connect 192.168.3.158:5555`），MP-019/MP-022 的补验即在此连接下完成。
>
> **执行原则：** 先做评分层等价性，再做纯逻辑状态机，最后做 UI 与主界面集成。每项任务只有在 JVM 测试或真机证据可核对后才勾选；代码写完不等于完成。设计中的「待确认项」未决定前，不擅自补规则。

## 任务总览

| 阶段 | 任务编号 | 范围 | 完成门槛 | 依赖 |
|---|---|---|---|---|
| P0 | MP-001～MP-005 | 评分层抽取与等价性 | `HandScorer` 数值逐位不变，395 项全绿 | 无 |
| P1 | MP-006～MP-009 | 分区模型、摘要、编解码 | 合法分区可解析、可往返，非法输入被拒绝 | P0 |
| P2 | MP-010～MP-017 | 纯逻辑编辑器 | 拆分/合并/改判/撤销/重置全部通过单测 | P1 |
| P3 | MP-018～MP-026 | 手工拆牌页面 | 真机可进入、可编辑，双列对照可读 | P2 |
| P4 | MP-027～MP-032 | 主界面状态与集成 | 应用、来源行、最优明细、恢复、失效规则通过 | P3 |
| P5 | MP-033～MP-037 | 回归、真机与收尾 | 395+新增测试全绿，真机 10 项通过，文档同步 | P4 |

**任务与设计章节：**

| 阶段 | 对应设计章节 |
|---|---|
| P0 | §5.2、§10.3 |
| P1 | §5.1、§5.3、§8.3 |
| P2 | §4.2–§4.6、§6.2、§6.3、§7 |
| P3 | §3、§4.1、§4.7、§4.8、§9 |
| P4 | §4.7、§5.4、§6.1、§6.3、§8.2 |
| P5 | §10、§11、§12、§13 |

## 通用验收基线

- `export JAVA_HOME=/opt/homebrew/opt/openjdk@17` 后运行 `./gradlew testDebugUnitTest assembleDebug`；改造前 39 个测试类 / 395 项为基线，当前 49 个测试类 / 457 项全绿。
- 不修改 SPI/NSPI 公式、评级阈值、`T_max`、牌型分值；不新增 RecyclerView / Compose / 拖拽库等依赖。
- 手工路径只走 `PartitionEvaluator`，不得复制公式，不得在 UI 层计分。
- 手工结果不持久化；仅通过 `onSaveInstanceState` 抗配置变更。
- 真机基线：HUAWEI LIO-N29 / Android 10 / WiFi adb `192.168.3.158:5555`。每项真机证据至少包含日期、页面状态与 `logcat -b crash` 结果。

---

## P0：评分层抽取与等价性门禁

**阶段目标：** 把 `HandScorer` 的牌型解释、计分与汇总抽成共享层，在不动 UI 的前提下证明最优结果逐位不变。P0 未通过前不进入 P1。

### MP-001 建立改造前基线

- [x] **MP-001** 记录改造前的 `HandResult` 快照：固定样例 + 至少 200 个随机种子，覆盖 `spi`、`numerator`、`rounds`、`waste`、`melds`（mask/type/score）与 `exhaustive`；运行现有单元测试与 `assembleDebug`，把 39 类 / 395 项与构建结果写入「执行记录」。
  - 验收：基线可在 JVM 测试中复现；后续 MP-005 直接与同一份快照比对；不把临时文件写入仓库核心目录。

### MP-002 抽出 `MeldEvaluator`

- [x] **MP-002** 从 `HandScorer.candidates()` 抽出 `MeldEvaluator.interpretations(hand, indices, level, considerSuits = true)`，保留按 `(mask, type)` 去重、同键取最高分；同花顺、顺子、级牌补位、炸弹与火箭规则只保留一份实现。
  - 验收：新增 `MeldEvaluatorTest`，覆盖同组牌同时给出同花顺/顺子、级牌补位、A2345、四王火箭；`HandScorer.candidates()` 按 mask 的输出与改造前逐项一致。

### MP-003 抽出共享计分函数

- [x] **MP-003** 将 `controlPercent`、`isLowWaste`、`netValue100` 从 `HandScorer` 抽成 `scoring/MeldScoring.kt` 的 `internal` 顶层函数，带 `level` / `handSize` 参数；优化器与手工汇总共用，禁止重写。
  - 验收：新增 `MeldScoringTest`；原 α、散牌惩罚、分子与评级测试全绿；随机手牌的中间值逐位不变。

### MP-004 新增 `PartitionEvaluator`

- [x] **MP-004** 新增公开 `PartitionEvaluator.resolve` / `evaluate` / `summarize`：`resolve` 校验并解析手工分区，`evaluate` 返回完整 `HandResult`，`summarize` 与优化器共用同一汇总口径。
  - 验收：`PartitionEvaluatorTest` 对固定样例和 200 个随机种子的最优分区复算，`spi`、`numerator`、`rounds`、`waste`、各组 `score` 与 `HandScorer.score()` 完全一致；`N < 10` 时 P=0；非激活级牌的 α 规则不变；非法分区抛 `IllegalArgumentException`。

### MP-005 `HandScorer` 委托与 P0 门禁

- [x] **MP-005** 将 `HandScorer.score()` 改为「搜索得到 `List<Meld>` → `summarize(...)`」，`candidates()` 委托 `MeldEvaluator`；保留 anytime 搜索、束搜索与 `exhaustive` 语义，不改变搜索预算与决策顺序。
  - 验收：395 项全绿；MP-001 快照逐字段一致；`assembleDebug` 成功；`HandScorer.kt` 中不再保留重复的牌型/计分实现。**P0 门禁通过后才能开始 P1。**

---

## P1：分区模型、摘要与传输格式

**阶段目标：** 用不依赖 Android 的数据模型表达手工分区，并锁定 Intent 与配置变更的编解码边界。

### MP-006 `MeldGroup` 与分区校验

- [x] **MP-006** 新增 `MeldGroup(indices, type)`：`indices` 是手牌下标，不是 `Card.id`；不携带分值。新增统一校验：每组非空、下标不越界且不重复、所有组合并后恰好覆盖 `0 until hand.size`。
  - 验收：`MeldGroupTest` 覆盖空组、重复下标、越界、缺牌、多牌、合法分区；错误信息可区分「不完整 / 引用无效 / 空组」。

### MP-007 `ScoreSnapshot` 与投影

- [x] **MP-007** 新增 `ScoreSnapshot(spi, numerator, rounds, nspi, grade, exhaustive)` 与 `HandResult.toSnapshot()`；只投影，不携带 `melds`，子页面不得重算或改写最优值。
  - 验收：`ScoreSnapshotTest` 对固定样例逐位一致；`exhaustive=true/false` 均往返正确；主界面结果卡与左侧最优列使用同一投影。

### MP-008 `PartitionCodec`

- [x] **MP-008** 新增 `PartitionCodec`，编解码 `level` / `hand` / `groups`，供 `EXTRA_PARTITION`（入口）与 `EXTRA_GROUPS`（返回）复用；拒绝重复下标、越界、空组、张数不符与非法 `MeldType`。
  - 验收：`PartitionCodecTest` 往返、异常路径、27 张体积上界（约 1 KB）；解析失败不崩溃，返回可显示的拒绝原因。

### MP-009 Intent 契约与一致性校验

- [x] **MP-009** 定义 `EXTRA_PARTITION`、`EXTRA_GROUPS`、`EXTRA_BASE` 契约：入口校验 hand 张数、级牌、牌面未被替换；`EXTRA_BASE` 只传 `ScoreSnapshot`；返回只接收 `groups`，主界面必须重新 `PartitionEvaluator.evaluate`，不信任子页面分数。
  - 验收：Intent round-trip 测试；缺字段、张数变化、级牌变化、牌面替换均被拒绝；Toast 文案与设计 §7 一致。

---

## P2：纯逻辑编辑器

**阶段目标：** 在无 Android 依赖的 `MeldAdjustEditor` 中实现所有编辑事件，保证任何时刻都是合法分区。P2 不通过不进入 P3。

### MP-010 状态与派生值

- [x] **MP-010** 建立 `MeldAdjustState(hand, level, base, groups, initial, selection, undo, sheet)` 与派生值：`melds`、`result`、`optimal = base`、`delta`、`dirty`；所有派生值每次渲染重新计算，不缓存。
  - 验收：`MeldAdjustEditorTest` 覆盖初始状态、delta 正/负/零、dirty 判定；编辑器不调用 `HandScorer.score()`，只调用 `PartitionEvaluator`。

### MP-011 选择与合并候选

- [x] **MP-011** 实现勾选：选中 0/1 组时禁用合并；选中 ≥2 组时用 `MeldEvaluator.interpretations` 判断并集，只保留 mask 恰好覆盖全部选中下标的候选；唯一候选可直接合并，多候选列出，空候选禁用并显示原因。
  - 验收：选择不因滚动丢失；编辑、撤销、重置后清空；候选按分值降序；至少两组才能合并。

### MP-012 拆分

- [x] **MP-012** 实现「拆成单张」与「拆成基础组」预设：对子→2 单张；三张→对子+单张；三带二→三张+对子；顺子/同花顺→5 单张；连对→各对子；钢板→2 三张；炸弹→⌊n/2⌋ 对子+余单张；火箭→2 对子。预设结果必须经 `MeldEvaluator` 校验。
  - 验收：每类牌型至少一个 JVM 用例；拆分后分数按新牌型重算；解析失败视为实现缺陷，不静默降级。
  - 说明：7 张以上炸弹是否提供「4 炸 + 余牌」预设见「待确认项 1」，决定前不实现该分支。

### MP-013 合并

- [x] **MP-013** 实现合并：删除选中组、插入候选组、清空选择、压栈；合并 n 组为一组时轮数减少 n−1，不拆分出新组；候选只允许覆盖全部选中牌。
  - 验收：唯一/多候选、无候选、合并后每张牌恰好一次、撤销恢复原分区；非法候选不改变状态。

### MP-014 改判

- [x] **MP-014** 实现同组牌换牌型：只允许相同 `mask`、不同 `type` 的解释；当前类型置灰；只有一种解释时不显示入口。
  - 验收：同花顺↔顺子、级牌相关解释、火箭/炸弹边界；改判后分数随 `type` 重算；不把「对子+单张」误列为改判。

### MP-015 撤销与重置

- [x] **MP-015** 实现撤销栈（上限 20）、栈空禁用；撤销只回退 `groups`，不回退选择；重置回到 `initial`，清空选择与栈，UI 侧二次确认。
  - 验收：20 步边界、重置后 `dirty=false`、撤销后选择清空、非法事件不压栈。

### MP-016 非法事件与错误文案

- [x] **MP-016** 统一返回 `EditOutcome.Ok(state)` / `Rejected(state, message)`；非法输入状态不变并携带文案，不向 UI 抛异常。
  - 验收：空组、重复/越界、非法 type、合并少于两组、候选为空、手牌张数/级牌变化全部覆盖；文案与设计 §7 表一致。

### MP-017 草稿保存与恢复

- [x] **MP-017** 用 `PartitionCodec` 保存 `groups + selection` 到 `onSaveInstanceState` 的 JSON；重建后重新解析，失败回退 `initial` 并提示「手工拆牌草稿已失效，已恢复初始方案」；不写 SharedPreferences / 文件。
  - 验收：编解码往返；损坏数据、手牌变化、级牌变化回退；杀进程重开不残留。

---

## P3：手工拆牌页面

**阶段目标：** 实现独立 Activity，把 P2 的纯逻辑状态机接到双列对照与分组编辑 UI。页面不重新搜索最优、不重新实现计分。

### MP-018 页面骨架与路由

- [x] **MP-018** 新增 `MeldAdjustActivity`、`activity_meld_adjust.xml`、`item_meld_group.xml`，在 Manifest 注册：`exported=false`、`parentActivityName=".MainActivity"`、竖屏、`adjustResize`；全屏承载，不使用弹窗。
  - 验收：真机可从入口启动/返回；Activity 重建不崩；Manifest 属性与设计一致。
  - 真机：从主界面结果卡进入、`←` 与返回键均正常；字体缩放触发重建不崩；`aapt2 dump xmltree` 已确认 `exported=false`、`screenOrientation=1`、`windowSoftInputMode=0x10`、`parentActivityName=.MainActivity`。

### MP-019 入口与可用性

- [x] **MP-019** 在结果卡 `breakdown` / `scoreExplain` 之后、`sortedImage` 之前新增 `adjustMelds` 按钮；按 `ScoreState` 控制隐藏/禁用/文案：`None`/`Running`/`Failed` 隐藏，`Stale` 禁用并提示重新计算，`cardCount == 1` 隐藏，`2..27` 可用，`Adjusted` 可继续编辑。
  - 验收：五类状态逐项真机检查；`Stale` 不进入页面；`Adjusted` 进入后以当前手工分区为起点；单张不显示入口。
  - 真机（2026-10-31）：`None` 隐藏、`Success` 可用、`Stale` 禁用并提示、`Adjusted` 继续编辑；单张手牌计算成功后结果卡显示「1 张」，手工入口不出现；构造 ♣2×3 的非法手牌后重算进入 `Failed`，结果卡与手工入口全部隐藏，提示「计算失败：两副牌中同一张牌最多出现 2 次」。

### MP-020 双列摘要卡

- [x] **MP-020** 实现吸顶摘要卡：左列「最优拆牌」只读（`base` 的 grade/SPI/NSPI/分子/轮数），右列「手工拆牌」实时计算同组指标；下方单独一行差值（`delta = 手工 SPI − 最优 SPI`）与轮数差；`exhaustive=false` 时左列标题显示「最优（近似）」及「未穷举」。
  - 验收：左列与主界面结果卡逐项一致；编辑过程中左列不变；不省略任一列绝对值；字体 1.3× 不截断；两列列头有文字。
  - 真机：27 张随机手牌左列为「强势 / 218.09 / NSPI 1453.9 / 分子 1744.7 / 8 轮」，与主界面结果卡逐项一致；多次拆分/合并/改判后左列不变；1.3× 字体下指标完整显示。

### MP-021 分组列表

- [x] **MP-021** 用 `ScrollView` + 纵向 `LinearLayout` 动态重建分组：按 `score` 降序；每行 48dp 复选目标、牌型名/分值/牌面、右侧「拆分」文字按钮；整行可打开操作面板；最多 27 组不引入 RecyclerView。
  - 验收：勾选/取消、拆分入口、整行点击均可用；牌面统一 `Card.display`；滚动不丢选择。
  - 真机：按分值降序正确（470/397/191/155/125…）；拆分与整行点击（改判/拆分面板）可用；勾选滚动后保持，重建后由 `state.selection` 恢复；连续拆到 27 组（27 轮）后列表滚动、底部操作栏固定。

### MP-022 拆分面板

- [x] **MP-022** 实现「拆成单张 / 拆成基础组」面板；按牌型展示可用预设；应用后替换该组，取消不改状态；非法预设给出原因。
  - 验收：真机对子/三张/三带二/顺子/连对/钢板/炸弹/火箭至少各拆一次；选择清空；撤销可回退。
  - 真机（2026-10-31）：已拆 **对子、三张、三带二、钢板、炸弹、同花顺**（同花顺与顺子共用「拆成单张」预设）；补录 ♠5♥5♠6♥6♠7♥7 组成连对，面板提供「拆成单张 / 拆成基础组」，拆成 3 对子后 101→65.67（-35.33、多 2 轮），撤销回到 101；补录 2 小王 + 2 大王组成火箭，拆成大小王两对后 1150→185.50（-964.50、多 1 轮），撤销回到 1150。

### MP-023 合并面板

- [x] **MP-023** 实现合并候选面板：选中 ≥2 组时显示合法候选（按分值降序）；唯一候选直接合并，多候选点选后合并，无候选禁用并显示「所选牌无法组成合法牌型」。
  - 验收：候选牌型/分值正确；合并后轮数/分数即时变化；失败不改变分区；合并后选择清空。
  - 真机：唯一候选（单张 ♥2 + ♠A → 对子，级牌补位）直接合并，SPI 136.69→131.84、轮数 12→11、选择清空；两王合并提示「所选牌无法组成合法牌型」且按钮禁用（火箭需 4 张王，符合规则）。

### MP-024 改判面板

- [x] **MP-024** 点卡片正文（非复选框、非拆分）打开改判面板：列出同 mask 的不同解释，当前类型置灰；只有一种解释时不显示；选择后重算。
  - 验收：同花顺→顺子、级牌解释、取消无变化；面板不误触复选框/拆分。
  - 真机：同花顺 ♣6♣7♣8♣9♥2 面板只列「改判为 顺子 · 124」与「拆成单张」，当前类型不重复；改判后 8 轮不变、SPI 218.09→166.03。

### MP-025 工具栏、应用与返回

- [x] **MP-025** 实现工具栏撤销、重置（二次确认）、返回/`←` 未保存确认（「保留并返回 / 放弃」）；`保留并返回` = 应用当前分区并返回（设计 §4.8 已注明）；底部固定「合并所选 (n)」与「应用结果」；`dirty=false` 时应用禁用并显示「尚未修改」。
  - 验收：无修改直接返回；有修改弹确认；应用返回 `RESULT_OK` + groups；重置清空选择/栈；`dirty=false` 按钮不可用。
  - 真机：无修改返回直接退出；有修改返回弹确认，「放弃」丢弃、「保留并返回」回传结果（主界面出现来源行与手工分值）；重置二次确认后回到入口分区（与最优相同、应用按钮「尚未修改」）；撤销可回退拆分；底部按钮固定。

### MP-026 字体与滚动

- [x] **MP-026** 差值同时给正负号与文字；27 组满屏滚动、底部操作栏固定；1.3× 字体下牌面 chip/牌型名换行，分值不截断。
  - 验收：27 组滚动、底部固定、1.3× 字体完整显示；差值同时有正负号与文字；无截断、无键盘遮挡（本页无输入框）。
  - 真机（2026-10-31）：1.3× 字体、27 组滚动、底部固定与差值正负号均通过。

---

## P4：主界面状态与集成

**阶段目标：** 把手工结果接回主界面，同时保留完整最优结果用于对照、展开与恢复。禁止在结果回调里信任子页面算出的分数。

### MP-027 `ScoreOrigin` 与主界面状态

- [x] **MP-027** 将 `ScoreOrigin.Adjusted` 改为持有完整 `HandResult`；`Success` 携带 `origin`；`invalidate()` 兼容 `Adjusted`；`calculate()` 产生 `Optimal`，应用手工结果产生 `Adjusted(baseOptimal)`；`restoreOptimal` 把 `result` 换回 `base`、`origin` 换回 `Optimal`。
  - 验收：`WorkbenchStateTest` 验证 `Adjusted.base` 保留完整 melds；恢复最优不调用评分器；手牌/级牌变化后变 `Stale`；重新计算默认 `Optimal`。

### MP-028 应用回调与重算

- [x] **MP-028** 在 `registerForActivityResult` 回调中解析 groups、校验 hand/level、调用 `PartitionEvaluator.evaluate` 重算；失败 Toast「手工拆牌结果无效，已保留原方案」，保留原结果。返回契约已抽成 Android 无关的 `MeldAdjustApply`，由 `MeldAdjustApplyTest` 覆盖缺字段、损坏、张数/级牌变化与非法牌型；回调不读取子页面分值。
  - 验收：合法结果更新主界面；重复/越界/张数不符/级牌变化全部拒绝；不信任子页面传入的 SPI/分子。

### MP-029 来源行、最优明细与恢复

- [x] **MP-029** 实现 `scoreOrigin`：`手工拆牌 · 最优 X → Y（delta · 轮数差）`，`exhaustive=false` 时写「最优（近似）」；新增 `optimalDetail` 展开 `base.melds` 的只读分组（按分值降序）；`restoreOptimal` 直接使用 `base`，隐藏来源行与明细。
  - 验收：应用后最优分值仍可见；展开只读且不出现第二套可编辑列表；恢复无 15 秒等待；未手工时两控件隐藏。
  - 真机：来源行「手工拆牌 · 最优 218.09 → 166.03（较最优 -52.06）」正确；展开最优明细为只读降序列表（470/397/191/155/125/123/77/63）；恢复最优 1 秒内完成、SPI 回到 218.09 且来源行/明细按钮隐藏。

### MP-030 失效规则

- [x] **MP-030** 手牌增删改或级牌变化时 `Success → Stale`，手工按钮禁用并提示重新计算；重新计算覆盖为最优；进程被杀后不恢复手工结果。
  - 验收：`WorkbenchStateTest` + 真机；`Stale` 不显示可点击的手工入口；重算后 `origin = Optimal`。

### MP-031 拆牌图命名与渲染

- [x] **MP-031** 手工结果下按当前 groups 渲染拆牌图，按钮/保存名/图上标注改为「手工拆牌图」；恢复最优后回到「最优拆牌图」；27 张限制与渲染/保存失败逻辑保持不变。
  - 验收：真机生成并保存手工拆牌图；标签不冒充最优；不足 27 张禁用；失败保留结果与图片状态。
  - 真机：手工结果下按钮为「生成并保存手工拆牌图」，保存成功提示 `guandan-手工拆牌图-…png`；恢复最优后变回「生成并保存最优拆牌图」。

### MP-032 旋转、重建与进程死亡

- [x] **MP-032** 手工页在旋转/Activity 重建时用 `onSaveInstanceState` 保留 groups + selection；解析失败回退 initial 并提示；不写入 SharedPreferences / 文件。
  - 验收：旋转后草稿与选择保留；返回再进正常；杀进程重开不残留；左列仍是最优基准。
  - 说明：页面按 MP-018 锁定竖屏，无法用物理旋转制造重建；用 `font_scale` 变更触发同一 `onSaveInstanceState` 重建路径取证。
  - 真机：1.15×/1.3× 重建后分区（10 轮 / -17.60）与勾选（合并所选 1）均保留，左列仍为最优；杀进程后重开手牌/结果/草稿全部清空。

---

## P5：回归、真机与收尾

**阶段目标：** 用完整 JVM 回归与真机清单锁住交付，不把「代码写完」当成完成。

### MP-033 JVM 测试与回归

- [x] **MP-033** 补齐并跑通：`MeldEvaluatorTest`、`MeldScoringTest`、`PartitionEvaluatorTest`、`MeldGroupTest`、`PartitionCodecTest`、`ScoreSnapshotTest`、`MeldAdjustEditorTest`、`WorkbenchStateTest`；当前 49 类 / 457 项全绿，`assembleDebug` 成功；随机等价性 200 手，改造前快照 202/202 行一致。
  - 验收：命令与输出留档；非手工路径的渲染/评分测试无变化；失败项修复根因，不放宽断言。

### MP-034 性能与稳定性

- [x] **MP-034** 用 27 组样例连续执行 50 次拆分/合并/撤销，确认每次只走 `PartitionEvaluator`、不调用 `HandScorer.score()`、无主线程 ANR；记录 JVM 单次重算耗时基线；列表滚动可用。
  - 验收：日志确认无优化器调用、无 15 秒等待；27 组滚动/操作无 ANR；耗时数据写入执行记录。

### MP-035 真机核心验收（1–6）

- [x] **MP-035** 在 LIO-N29 / Android 10 验证：① 27 张进入，左列与主界面逐项一致，近似解有标注；② 合并后右列变化、左列不变、差值为负；③ 三带二拆分后撤销；④ 同花顺改判为顺子；⑤ 应用后来源行、最优明细、最优分值仍可见；⑥ 恢复最优无 15 秒等待。
  - 验收：每项有截图/录屏与 `logcat -b crash` 为空；任何一项失败不得勾选。
  - 真机（2026-10-31，27 张随机手牌）：① 左列与主界面逐位一致；② 合并后右列变化、左列不变、差值 -86.25；③ 三带二拆成三张+对子后撤销，回到「与最优相同」；④ 同花顺改判顺子后 166.03；⑤ 应用后来源行/最优明细/最优分值均可见，手工拆牌图保存成功；⑥ 恢复最优即时完成。`logcat -b crash` 全程为空。

### MP-036 真机边界验收（7–10）

- [x] **MP-036** 验证：⑦ 应用后改一张手牌变 `Stale`、按钮禁用；⑧ 页面旋转/返回再进保留草稿，杀进程不残留；⑨ 27 组滚动、1.3× 字体；⑩ 全程无 FATAL/AndroidRuntime 异常。
  - 验收：与 MP-035 的证据分开记录。
  - 真机：⑦ 应用后删一张手牌 → `Stale`、入口禁用；⑧ 重建后草稿/勾选保留、杀进程不残留；⑨ 27 组滚动与 1.3× 字体通过；⑩ `logcat -b crash` 为空。

### MP-037 文档与任务收尾

- [x] **MP-037** 同步 [`doc/patterns_design.md`](patterns_design.md) 的实施状态、[`doc/tasks.md`](tasks.md) 的进度、`harness/state.md` 的当前任务与验证基线；如接口有偏差，更新 ADR-0020（Proposed → Active 或记录新 ADR）；勾选本文件任务并填写执行记录。
  - 验收：文档链接/行数/状态一致；没有「代码完成但无证据」的勾选；明确列出未完成项与阻塞。

---

## 测试覆盖映射

| 测试类 | 覆盖任务 | 关键断言 |
|---|---|---|
| `MeldEvaluatorTest` | MP-002、MP-011、MP-014 | 同组多解释、级牌补位、火箭；候选 mask 精确覆盖 |
| `MeldScoringTest` | MP-003 | α、P、分子与改造前逐位一致 |
| `PartitionEvaluatorTest` | MP-004、MP-005、MP-028 | 最优分区复算全等；`N < 10` P=0；非法分区抛错 |
| `MeldGroupTest` | MP-006 | 非空、下标不重复/不越界、恰好覆盖手牌 |
| `PartitionCodecTest` | MP-008、MP-017 | 往返、拒绝异常、27 张体积 |
| `ScoreSnapshotTest` | MP-007 | 六字段投影与往返，`exhaustive` 保留 |
| `MeldAdjustEditorTest` | MP-010～MP-017 | 拆分/合并/改判/撤销/重置/拒绝/dirty/草稿 |
| `WorkbenchStateTest`（扩展） | MP-027、MP-030 | `Adjusted.base` 完整；恢复不重算；失效规则 |

## 实施前待确认（仍未决策，不阻塞当前实现）

1. **7 张以上炸弹的预设**：是否提供「4 炸 + 余牌」，还是维持当前「对子 + 单张」定义？影响 MP-012，未决定前不实现额外分支。
2. **合并默认选择**：多候选时是否默认选中最高分并只保留「改判」入口？影响 MP-023，当前按「列出候选、用户点选」实现。
3. **主界面拆牌明细引导**：是否在明细行标注「第 3 组可拆」？影响 MP-019/MP-021，当前不做。

## 执行记录

| 日期 | 任务 | 结果 / 证据 |
|---|---|---|
| 2026-10-31 | 任务分解 | 新建本文件；P0–P5 分解完成 |
| 2026-10-31 | MP-001/005 | 用 `git worktree` 检出改造前 HEAD，固定样例 + 200 个随机种子的 `spi/numerator/rounds/waste/exhaustive/melds` 快照与新实现逐字节一致（202/202 行 diff 为空） |
| 2026-10-31 | MP-002–004 | `MeldEvaluator`、`MeldScoring`、`PartitionEvaluator` 抽离；200 种子最优分区复算通过；新增 MeldEvaluator/MeldScoring/PartitionEvaluator/MeldGroup 测试 |
| 2026-10-31 | MP-006–009 | `MeldGroup`、`ScoreSnapshot`、`PartitionCodec`、Intent 一致性校验完成；27 张分区体积测试 < 4 KB |
| 2026-10-31 | MP-010–017 | 编辑器拆分/合并/改判/撤销上限/重置/草稿恢复与拒绝路径通过单测 |
| 2026-10-31 | MP-018–032 | 页面、双列摘要、分组列表、面板、主界面来源行/最优明细/恢复、失效状态代码完成；真机项待解锁 |
| 2026-10-31 | MP-018（部分） | `aapt2 dump xmltree` 确认 `MeldAdjustActivity`：`exported=false`、`screenOrientation=1`、`windowSoftInputMode=0x10`、`parentActivityName=.MainActivity`；真机启动/返回仍被安全锁屏阻断 |
| 2026-10-31 | 编辑边界修复 | `mergeCandidates` / `interpretationsFor` / `reinterpret` 只接受覆盖整组的解释；新增“子集对子不能作为合并候选”“5 张同花顺不能改判为对子”用例 |
| 2026-10-31 | MP-028 | `MeldAdjustApply` + `MeldAdjustApplyTest`：合法结果重算，缺字段/损坏/张数/级牌/非法牌型分别拒绝 |
| 2026-10-31 | MP-029/031 | `ScoreText` 统一来源行、差值、近似标题、拆牌图标签与最优明细排序；`ScoreTextTest` 覆盖正负/零差值与近似标注 |
| 2026-10-31 | MP-033/034 | `testDebugUnitTest` 457 项 0 失败；`assembleDebug` 成功；27 张 50 次合并/撤销 JVM 基线 233.1 ms（4.66 ms/cycle） |
| 2026-10-31 | 真机 MP-018/019/020/021/023/024/025 | LIO-N29 解锁后从主界面进入：双列与主卡逐位一致（218.09 / 1453.9 / 1744.7 / 8 轮）；拆分/合并/改判/撤销/重置/应用/继续编辑/恢复最优逐项验证；27 组（27 轮）滚动时底部操作栏固定 |
| 2026-10-31 | 真机 MP-019 | 单张手牌 Success 时结果卡显示「1 张」且无手工入口；♣2×3 触发 `Failed`，结果卡与入口隐藏、错误文案正确；Wi-Fi adb `192.168.3.158:5555` |
| 2026-10-31 | 真机 MP-022 | 连对 ♠5♥5♠6♥6♠7♥7 → 3 对子（101→65.67，-35.33，多 2 轮）后撤销复原；火箭 4 王 → 大小王两对（1150→185.50，-964.50，多 1 轮）后撤销复原 |
| 2026-10-31 | 真机 MP-029/031/032 | 来源行「218.09 → 166.03（-52.06）」、最优明细只读降序、恢复最优 <1 s；手工拆牌图保存为 `guandan-手工拆牌图-…png`；`font_scale` 重建后分区与勾选保留，杀进程后不残留 |
| 2026-10-31 | 真机 MP-035/036 | MP-035 ①–⑥ 全通过；MP-036 ⑦ Stale 后入口禁用、⑧ 重建/杀进程、⑨ 27 组滚动与 1.3× 字体、⑩ `logcat -b crash` 为空 |
| 2026-10-31 | 真机缺陷修复① | Activity 重建后勾选丢失：框架 `onRestoreInstanceState` 用旧 View 状态覆盖模型值 → `row.select.isSaveEnabled = false`，重建后 `合并所选 (1)` 保持 |
| 2026-10-31 | 真机缺陷修复② | `None`/`Running`/`Failed` 分支未隐藏旧结果卡中的手工入口 → `renderScore` 三个分支显式 `adjustMelds.visibility = GONE` |
| 2026-10-31 | 真机缺陷修复③ | 返回确认「保留并返回」原先只关弹窗、修改实际丢失 → 改为应用分区并返回 `RESULT_OK`（设计 §4.8 同步说明） |
| 2026-10-31 | 设备掉线 | 验收结束后 WiFi adb 显示 `device offline`，重复 `adb connect` 超时；剩余 MP-019/022/026/036 取证需重新连接设备 |


## 完成定义（DoD）

- MP-001～MP-037 全部勾选，且每项有测试或真机证据（当前 37/37）。
- 当前 49 类 / 457 项全绿（改造前 39 类 / 395 项基线不变），`assembleDebug` 成功。
- 真机 1–10 项通过；环境导致的未测项单独列出，不冒充通过。
- 非手工路径的渲染、评分、拆牌图命名与失效行为无回归。
- `doc/patterns_design.md`、`doc/tasks.md`、`harness/state.md` 与 ADR 状态一致。
