# State —— 当前状态

> 只保留恢复工作所必需的信息。任务完成即就地改写，不追加历史流水。
> 最后更新：2026-10-31（手工拆牌 37/37 完成；旧版 AI 阶段记录见 [`archive/state-2026-10-31.md`](archive/state-2026-10-31.md)）。

## 当前分支

`airecognize`

## 当前任务

执行 [`doc/patterns_tasks.md`](../doc/patterns_tasks.md)：手工拆牌 **37/37 完成**。P0–P5 全部任务均有 JVM 测试或真机证据，真机清单 1–10 通过。

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

49 个测试类 / **457 项 0 失败**；`assembleDebug` 成功。

## 阻塞

- `MeldAdjustActivity` 锁定竖屏，旋转验收以 `font_scale` 配置变更替代（同一 `onSaveInstanceState` 路径）。

## 下一步

1. 回到 AI 源管理页真机剩余项（API 完整配置态、最后一份删除禁用、字体/键盘、重建草稿、冷启动并发）与 ChatGPT/Gemini 复测。

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
- 子页面未导出（`exported=false`）时，`adb shell am start` 无法注入测试分区；验证要走主界面真实入口。
