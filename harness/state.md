# State —— 当前状态

> 只保留恢复工作所必需的信息。任务完成即就地改写，不追加历史流水。
> 最后更新：2026-10-31（最优拆牌 anytime 搜索 + V4.1 计分落地 + 级牌对子/三张修正）；旧版全文归档于 [`archive/state-2026-10-31.md`](archive/state-2026-10-31.md)（更早一轮见 `archive/state-2026-10-30.md`）。

## 当前分支

`airecognize`

## 当前任务

执行 `doc/ai_tasks.md`：多 AI 源 WebView 迁移 + 阶段 7 UI + 阶段 8 宿主/提交协议。
清单共 235 项，**217 项已勾选**；剩余 18 项集中在 ChatGPT 额度恢复后的验收、Gemini 会话过期、横向验收。

近期重心：AI 源管理独立页面（UI-023）、登录入口与登录态（见下两节）。

## 本轮完成（2026-10-31，最优拆牌 anytime 搜索）

- **问题**：旧搜索 3 秒 / 12 万状态，超限后把剩余牌全按单张收尾。基准显示单纯加时间几乎不改变结果——先撞状态上限，退化发生在「剩余单张」而不是时间不足。
- **改动**：`HandScorer` 改为三层 anytime 搜索：5 种贪心策略保底（预算 0 也有完整方案）→ 可中断精确覆盖 DP（15 万状态，完成即全局最优并提前返回）→ 未完成时按 512/2048/8192 逐级加宽束搜索。束搜索一半名额按综合上界、一半按炸弹/火箭覆盖牌数保留分支，避免密堆手的最优多炸方案被同花顺路径挤掉。总预算 **15 秒**，结果新增 `HandResult.exhaustive`；主界面摘要标注「已穷举最优 / 近似最优」。
- **基准（JVM，同机）**：8/8/8/3 级牌 2 **456.54 → 678.21**（5→4 轮）；6/6/6/6/3 级牌 2 **355.23 → 534.19**；8/8/8/3 级牌 5 **477.87 → 702.08**；6×5/7×6/8×6/9×5/10×5 级牌 2 **403.50 → 507.38**；4/4/5/5/5/4 级牌 4 **317.31 → 339.83**。余下样例持平或略升。
- **时间收益**：8/8/8/3 级牌 2 在 1 秒内 456.54，3 秒与 15 秒均为 678.21；8/7/7/5 级牌 2 在 1/3/15 秒均为 715.88。15 秒是上限而非常态，`dense hand finds bomb-heavy plan` 约 1.8 秒即可到 507.38。
- **测试**：新增 4 项搜索回归（密堆多炸、预算单调、小手牌穷举标记、零预算仍返回合法贪心方案）；全量 `./gradlew testDebugUnitTest` **395 项 0 失败**，`assembleDebug` **BUILD SUCCESSFUL**。
- **真机（2026-10-31 12:11，LIO-N29 / Android 10）**：安装 debug APK → 随机 27 张 → 级牌 2 → 计算；结果「强势 / SPI 189.22 / 分子 1513.75 / 8 轮 / T_max 15」，摘要显示「已穷举最优」；logcat 无 FATAL / AndroidRuntime 异常。近似 `exhaustive=false` 文案只由 JVM 测试覆盖，未在真机构造出密堆手。截图存 `/tmp/guandan_result.png`（未入库）。
- **限制**：困难手牌仍是 anytime 近似（`exhaustive=false`），真机只验证了穷举路径；DP 早停后只保留已探索部分的较优解，束搜索负责补足。

## 更早完成（已归档）

- V4.1 计分、级牌修正、拆牌性能优化、拆牌调整设计、AI 源管理页等已完成记录见 [`archive/state-2026-10-31.md`](archive/state-2026-10-31.md)；当前规则以 ADR-0021~0025 为准。

## 最近清理（2026-10-31）

- `state.md` 104→75 行；已完成轮次移入 `harness/archive/state-2026-10-31.md`，归档内相对链接已改为 `../../doc/`；规则内容未改写。

## 验证基线

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew testDebugUnitTest assembleDebug
```

真机：HUAWEI LIO-N29 / Android 10 / WebView 114.0.5.302（WiFi adb `192.168.3.158:5555`，当前锁屏需人工解锁）。
测试图：`/data/local/tmp/hand_test.png`（Gemini 9/10 张）、`/data/local/tmp/case14.jpg`（27 张）。

## 剩余未完成（18 项）

- **AI 源管理页真机清单**：`doc/ai_configure.md` §11.2 剩余 7 的完整 API 配置态与 9–12（最后一份删除禁用、字体缩放/键盘、重建草稿、冷启动并发）。
- **ChatGPT**：AI-158（未登录侧）、159~163、G-23~25。额度恢复后重跑 `source_e2e` / `source_e2e_twice`。
- **Gemini**：AI-089 还差「会话过期及恢复」；单图与连续两图已通过。
- **横向**：AI-148/149/154/155、G-15、G-10。
- **其他**：G-21（TalkBack）。

## 已知缺口 / 阻塞

- **真机 UI 取证**：主路径已通过（见上），但未把 §11.2 全清单冒充完成；剩余项见「剩余未完成」。
- **ChatGPT 附件额度**：测试账号额度用尽，页面提示等待约 10-31 12:36；当前无法取得附件成功证据。
- **ChatGPT 新 DOM**：composer 为 `div.ProseMirror[aria-label='Ask ChatGPT']`，`#prompt-textarea` 已不存在；`ChatGPTSelectors.INPUT` 靠 `div.ProseMirror` 兜底命中。
- **Gemini 合成 paste**：只在 `document.hasFocus()` 为 true 时生效；生产识牌走离屏 1×1 锚点，故只用 FILE_CHOOSER。
- **TalkBack**：设备未安装读屏服务，G-21 无法取证。
- **HTTP API**：仍无可用 Key，AI-154 未回归。
- **残局控制比未接线**：`doc/手牌综合牌力评估方法.md` §四 的 `N_control/(T_plan−1)` 未实现；`N_control` 未定义，且 §三 仍给残局 SPI 阈值。当前 N<10 仍按 SPI/评级输出，需规则作者确认后再改。

## 下一步

1. 按 `doc/ai_configure.md` §11.2 补剩余项：API 完整配置态、最后一份删除禁用、1.3× 字体/键盘、重建草稿、冷启动并发。
2. 2026-10-31 12:36 后复测 ChatGPT `source_e2e` 与 `source_e2e_twice`，跑 AI-159/160/162、G-23/G-24。
3. 补 Gemini 会话过期与恢复取证（AI-089）。
4. 三源稳定后跑 AI-148/149/154/155、G-15/G-25，回写 §5.6。

## 已知问题（踩坑备忘）

- `evaluateJavascript` 只返回同步值；Promise / `setTimeout` 结果拿不到，异步副作用必须用后续快照验证。
- **JS 里禁止同步忙等**：会卡死事件循环，Angular/React 渲染全部停摆。
- **WebView/Host 的所有读取必须在主线程**：在 worker 线程调用 `host.currentWebView()` 会直接 `IllegalStateException`。
- **共享 WebView 的健康检查必须串行**：主界面与本页同时 `ensureLoaded()` 会互抢页面；统一走 `HealthCheckGate.run`，且调用方必须在后台线程。
