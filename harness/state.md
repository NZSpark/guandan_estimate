# State —— 当前状态

> 只保留恢复工作所必需的信息。任务完成即就地改写，不追加历史流水。
> 最后更新：2026-10-31（V4.2 评分已落地；图文用户手册 `doc/usermanual.md` 已补齐 AI 源登录、配置、校验与退出保护截图。更早的 AI 阶段记录见 [`archive/state-2026-10-31.md`](archive/state-2026-10-31.md)）。

## 当前分支

`airecognize`

## 当前任务

执行 [`doc/patterns_tasks.md`](../doc/patterns_tasks.md)：手工拆牌 **37/37 完成**，P0–P5 全部任务均有 JVM 测试或真机证据，真机清单 1–10 通过。评分体系已按 V4.2 落地，仅剩真机复测。

## 本轮完成（V4.2 代码落地）

- **三带二**：`MeldEvaluator.fullHouseScore` 原始分区间 `16..103 → 16..109`（分母 87 → 93）；候选生成允许对子为一对小王/大王，王对只取实体牌（红心配不得替代王）。`222+AA` 191→**182**、`222+33` 155→**149**、`AAAKK` 180→**171**、三张级牌+对大王 = **191**、三张级牌+对小王 = 188。
- **综合评估**：`HandResult` 新增 `control`（`N_control`）、`controlRatio`（`R_control = N_control/(T_plan-1)`，`T_plan=1` 返回 `null`）、`stage`；评级改为 27 张 80/140/220、13~18 张 70/130/200、1~9 张 60/120/180（辅助），**19~26 张与 10~12 张返回「待标定」**，不外推相邻区间（§5.2 / T13）。
- **散牌惩罚**：取消 `N≥10` 豁免，按出牌单元对 1~27 张统一计 `P = 15 × N_waste`（§4.3 与示例 2：AAAA+2 → SPI 149.30、R=1.00）。
- **UI**：结果卡摘要增加阶段；残局显示「控制覆盖比 R = …（控制单元 x / 需再次夺权 y 次）」，`T_plan=1` 显示「单次出牌方案」。
- **A 口径**：按评分方案 §2.3 第 7 条，10JQKA 中 A 恒记 14、级牌身份不影响顺子取值，§三 表格不变，原先的待裁决项关闭。
- **测试**：`ScoringV4Test` 同步 182/149/141/170/177/171/117、王对三带二 191、9/10 炸级牌为 2 时的 821/922、待标定与 `R_control` 用例；`MeldScoringTest` / `HandScorerSearchTest` 改为残局计惩罚口径；`222+AA` 因整手最优改拆「三张级牌单张（α=1.15）+ 对 A」，改为校验牌型候选分。
- **同步文档**：README 牌力指标节（公式、阶段阈值、V4.2 已落地）、`harness/CRITICAL.md` 第 1/4/5 条、`harness/memory/scoring-rules.md`、ADR-0030。
- **验证**：`./gradlew testDebugUnitTest assembleDebug` → 49 类 / **460 项 0 失败**，APK 构建成功。

## 本轮完成（用户手册）

- 新增 `doc/usermanual.md`：安装与启动、四分区总览、五分钟快速上手、拍照/相册/随机导入、本地与联网识牌、核对/删除/补录、级牌、计算、结果字段与评级阈值、手工拆牌、图片保存、AI 源配置、常见问题、隐私与术语。
- 新增 `doc/pic/` 十三张真机截图（LIO-N29，1176×2400，font_scale 1.0）：空状态、随机 27 张、核对区、结果卡、拆牌明细、手工拆牌、AI 源管理、校正对话框，以及 AI 网页登录页、网页源配置、API 源表单、字段校验、未保存退出确认。
- README 增加用户手册入口，并修正两处「App 代码仍按 V4.1」的过期表述为 V4.2 已对齐。

## 本轮完成（2026-10-31 真机）

- **Wi-Fi 调试**：`adb tcpip 5555` + `adb connect 192.168.3.158:5555`，当前通过 Wi-Fi adb 连接 LIO-N29。
- **MP-019 补验**：单张手牌 Success 显示「1 张」且无手工入口；♣2×3 非法手牌重算进入 `Failed`，结果卡与手工入口全部隐藏，错误文案「计算失败：两副牌中同一张牌最多出现 2 次」。
- **MP-022 补验**：连对 ♠5♥5♠6♥6♠7♥7 → 3 对子（101→65.67，-35.33，多 2 轮）后撤销复原；火箭 2 小王 + 2 大王 → 大小王两对（1150→185.50，-964.50，多 1 轮）后撤销复原。
- **真机入口与对照**：27 张随机手牌从主界面进入；左列与主界面结果卡逐位一致（强势 / 218.09 / NSPI 1453.9 / 分子 1744.7 / 8 轮），编辑全程左列不变。
- **编辑**：拆分（同花顺、三带二、三张、钢板、对子、炸弹）、合并（唯一候选直接合并、无候选禁用并提示）、改判（同花顺→顺子）、撤销、重置二次确认、应用与继续编辑、恢复最优。
- **主界面集成**：来源行 `手工拆牌 · 最优 218.09 → 166.03（较最优 -52.06 · 多 3 轮）`、最优明细只读降序、恢复最优 <1 s、手工拆牌图保存为 `guandan-手工拆牌图-…png`。
- **重建与死亡**：`font_scale` 触发重建后分区与勾选保留；杀进程重开不残留；27 组（27 轮）滚动时底部栏固定。
- **崩溃**：真机 `logcat -b crash` 全程为空。

## 本轮修复（真机发现）

- `MeldAdjustActivity` 勾选在重建后丢失：框架 `onRestoreInstanceState` 用旧 View 状态覆盖模型值；改为 `row.select.isSaveEnabled = false`，选择只由 `state.selection` 维护。
- `MainActivity.renderScore` 在 `None`/`Running`/`Failed` 时未隐藏旧结果卡的手工入口；三个分支显式 `adjustMelds.visibility = GONE`。
- 返回确认「保留并返回」原先只是关弹窗、修改实际丢失；现等价于应用当前分区并返回（`RESULT_OK` + groups），设计 §4.8 已同步说明。

## 验证基线

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew testDebugUnitTest assembleDebug
```

49 个测试类 / **460 项 0 失败**；`assembleDebug` 成功。

## 阻塞

- `MeldAdjustActivity` 锁定竖屏，旋转验收以 `font_scale` 配置变更替代（同一 `onSaveInstanceState` 路径）。
- V4.2 改动目前只有 JVM 证据，未装机复测；装机前先 `clean assembleDebug`（gradle 增量可能漏编）。

## 下一步

1. **V4.2 真机复测**：三带二王对（三张级牌+对大王 191）、残局 P 与 `R_control` 文案、10~12 / 19~26 张显示「待标定」、`T_plan=1` 的「单次出牌方案」。用户手册截图已覆盖主流程，但未覆盖上述边界态。
2. 回到 AI 源管理页真机剩余项（API 完整配置态、最后一份删除禁用、字体/键盘、重建草稿、冷启动并发）与 ChatGPT/Gemini 复测。

## 其他未完成（AI，非本轮）

- ChatGPT 额度恢复后重跑 `source_e2e` / `source_e2e_twice`；Gemini 会话过期与恢复。
- 横向验收 AI-148/149/154/155、G-15/G-25；HTTP API 仍无可用 Key。
- AI 源管理页真机剩余 7 项（`doc/ai_configure.md` §11.2）。

## 已知问题（踩坑备忘）

- `evaluateJavascript` 只返回同步值；Promise / `setTimeout` 结果拿不到。
- **JS 里禁止同步忙等**：会卡死事件循环。
- **WebView/Host 的所有读取必须在主线程**；健康检查统一走 `HealthCheckGate.run`。
- 手工页每次编辑只调用 `PartitionEvaluator`，不得调用 `HandScorer.score()`；页面不重算最优。
- 带 checkbox/switch 的行如果状态由模型维护，必须 `isSaveEnabled = false`；否则 Activity 重建时框架会晚一步恢复旧 View 状态并覆盖模型。
- **评分文档与代码已对齐 V4.2**；下次升版仍是文档先行，任何「App 已按 V4.x 计分」的表述必须先核对评分方案 §八 落地清单。
- **优化器选优不等于牌型表值**：`222+AA` 的 182 是牌型候选分，整手最优可能改拆（三张级牌单张 α=1.15）；断言牌型分值要用 `fullHouseScore` / `interpretations`，不要断言最优分区。
- **gradle 增量可能漏编**：`assembleDebug` 全 up-to-date 但 APK 时间戳未变时，用 `clean assembleDebug` 强制重编再装机。
- 子页面未导出（`exported=false`）时，`adb shell am start` 无法注入测试分区；验证要走主界面真实入口。
