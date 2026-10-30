# State —— 当前状态

> 只保留恢复工作所必需的信息。任务完成即就地改写，不追加历史流水。
> 最后更新：2026-10-31（V4.1 计分落地 + 级牌对子/三张修正）；旧版全文归档于 `harness/archive/state-2026-10-30.md`。

## 当前分支

`airecognize`

## 当前任务

执行 `doc/ai_tasks.md`：多 AI 源 WebView 迁移 + 阶段 7 UI + 阶段 8 宿主/提交协议。
清单共 235 项，**217 项已勾选**；剩余 18 项集中在 ChatGPT 额度恢复后的验收、Gemini 会话过期、横向验收。

近期重心：AI 源管理独立页面（UI-023）、登录入口与登录态（见下两节）。

## 本轮完成（2026-10-31，V4.1 计分落地 + 级牌对子/三张修正）

- **炸弹逢人配**：`HandScorer` 的炸弹 `Sum` 改为 `n×V`，逢人配按替代点数计、与同点数自然炸同分。三张5+配 220→**210**（< 四张6 的 216）；5张2+配 553→**538**；8张2+1配 837→**828**（V4.1 再调为 810）；8张2+2配 928→**912**。自然炸及 4 炸 192 / 5 炸 298 下限不变。
- **测试**：`ScoringV4Test` 更新两项含配炸弹断言，新增 10 张炸弹公式与 `wildSubstitutedBombScoresAsItsRepresentedRank`；当前全量 `./gradlew testDebugUnitTest` 39 类 / **392 项 0 失败**。
- **文档**：§二 级牌规则、CRITICAL、scoring-rules、README 语义列表；ADR-0022。`doc/conflict.md` §7 记录旧公式族的张数层级倒挂（后由 V4.1 处理）。
- **V4.1 文档重标定**：采纳 `doc/conflict.md` §8 方案 B；评分方案升 V4.1（6 炸 `523+55·log`、7 炸 `577+73·log`、8 炸 `672+75·log`、9 炸 `784+66·log`、10 炸不变；层级 538/600、601/690、700/799、810/895、912/987），`手牌综合牌力评估方法` 的 F_i 区间与 README 同步，评级阈值保留 V4.0 并加注。
- **V4.1 代码落地**：`HandScorer` 新增 `bombScore()` 集中 4~10 炸公式，6~9 炸常数按方案 B 替换；6 炸 K **638→593**、9 炸 8张2+1配 **828→810**，7/8 炸 2 为 **601/700**，4/5/10 炸不变。`ScoringV4Test` 同步期望并新增 6炸K、7/8炸、炸弹层级属性用例。
- **级牌对子/三张**：`pairScore()` / `tripleScore()` 统一按 `baseValue`，自然级牌与用于级牌自身的逢人配按 15，替代非级牌按替代点数；对子 raw 45→**168 分**、三张 raw 75→**191 分**，不再走 `3v/5v`。新增候选分回归。
- **记录**：`doc/conflict.md` §8.5 改为已落地；ADR-0023 状态 Active，新增 ADR-0024；`CRITICAL`、`scoring-rules`、`project-overview` 同步。

## 同日上一轮完成（2026-10-31，级牌三带二修正 + 6 炸文档冲突）

- **级牌三带二修正**：`HandScorer.fullHouseScore()` 统一自然牌/逢人配两条路径；自然级牌（含用于级牌自身的逢人配）按 15，逢人配替代非级牌按替代点数；映射区间由 `16–96` 扩到 `16–103`。级牌 2 时 `222+33` 50→**155**，`AAA+33` 156→**147**，最大值 `222+AA` **191 < 192**。
- **测试**：`ScoringV4Test` 新增 6 项（级牌三张/对子、最大值、无级牌映射、逢人配替代）；全量 `./gradlew testDebugUnitTest` 39 类 / **385 项 0 失败**；`assembleDebug` BUILD SUCCESSFUL。
- **文档**：新增 [`doc/conflict.md`](../doc/conflict.md)（6 炸表值 531/595 与公式 538/650 的矛盾仍未决）；同步 `doc/掼蛋牌型量化评分方案.md` §三/级牌规则、`harness/CRITICAL.md`、`harness/memory/scoring-rules.md`；规则变更记入 ADR-0021。

## 同日上一轮完成（2026-10-30，最优拆牌性能优化）

- **问题**：旧搜索给每个状态新建 `State` 并拷贝 melds 列表，GC 压力大；9 组三张、4 炸、8 张同点等结构化 27 张手牌稳定跑满 3 秒上限，只能靠「剩余单张」收尾。
- **改动**：分子改百分位整数（`IntArray`）；每个 mask 只存 `num[r]` + `pick[r]`，方案按 pick 重建、不再拷贝列表；静态「候选最少优先」替代每状态重扫；预计算候选净值；预算退路同样可重建。新增 `HandScorerSearchTest`（6 项）。
- **实测**（同机中位数）：9 组三张 3024→642ms，4 炸 899→123ms，8 张同点 3046→717ms，wild 密集 3035→1044ms，随机 27 张 239→71ms；密堆手仍触 3 秒上限，但 SPI 由 329.45 升到 375.73（30 秒参考 376.69）。
- **验证**：`./gradlew testDebugUnitTest` 39 类 / **379 项 0 失败**。

## 同日上一轮完成（拆牌调整设计 + singleTask）

- **设计**：新增 [`doc/patterns_design.md`](../doc/patterns_design.md)（`MeldAdjustActivity`、`MeldGroup`、共享 `MeldEvaluator`、`ScoreOrigin`）；待实施 P0–P5。
- **singleTask**：`MainActivity` 加 `launchMode="singleTask"` + `onNewIntent` 刷新；真机修复前 `sz` 1→2→3，修复后恒为 1 个实例。见 ADR-0019。

## 同日更早完成（2026-10-30，解析撤销 + 新图标 + AI 源管理页）

- **撤销**：`WebViewAiSource` 恢复「`parsed.cards` 为空才抛 `ParseFailed`」，保留五档新 `mipmap-*/ic_launcher.png`（91f0ddc）。
- **AI 源管理页**：`AiSettingsActivity` 全屏承载源/配置档/表单/健康态；`AiSettingsEditor`、`AiSettingsValidation`、`HealthCheckGate` 均为纯逻辑（新增 28 项 JVM 测试）；真机主路径通过，剩余完整 API 配置态、字体/TalkBack、重建草稿、冷启动并发。
- **兼容**：图片解码在 API 26–27 回退 `BitmapFactory`；`lintDebug` 不再报 `NewApi`。

## 更早完成（2026-10-30）

- AI-184 宿主自动挂接；图片上传收口（Gemini 原生文件选择、DeepSeek paste + FILE_CHOOSER、ChatGPT PASTE，详见 AI-182）。

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
