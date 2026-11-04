# State —— 当前状态

> 只保留恢复工作所必需的信息。任务完成即就地改写，不追加历史流水。
> 最后更新：2026-11-05（组牌 UI 与文档术语统一；重新构建并更新 release/GuanDanScore-1.0-release.apk；完成 README.md 说明补充，分支合并与推送至 main。）

## 2026-11-03 修复识别调度死循环（P0）

- **现象**：连续导入图片时，识别到第二张牌后卡住空转，不结束、不出结果。
- **根因**：`CardRecognizer.heuristic()` 的 `pump()` 可重入但非重入安全——`inFlight`/`isExhausted` 为两次独立加锁读取，OCR 回调与主循环交错时重复派发同一个 pass，导致 `release` 多于 `next`、计数失衡；完成判据依赖 `isExhausted && inFlight == 0` 的时序，任一条件短暂不满足即递归重派，`finish()` 永不执行。
- **修复**：① `RecognitionWorker` 新增 `isDrained`（队列空与在飞为 0 在同一临界区判定）；② `pump()` 收敛为唯一派发点，`next()` 返回 null 直接 return，回调只做 `release` + `settle()` + 受 `finished` 保护的 `pump()`；③ 完成判据改为“已回收数 ≥ 计划数”（`settled` 仅在 `release` 内自增）。
- **验证**：`./gradlew testDebugUnitTest` 成功，24 项单测通过；`assembleDebug` + `adb install -r` 到 LIO-N29 成功；真机导入图片识别到 **27 张、2 条警告**（1 张低置信度需核对、heuristic 补入 ♣6），流程正常结束，未再卡住。用户确认真机验证通过。
- **边界**：上述死循环属时序问题，单测无法覆盖 OCR 回调交错；本次仅以真机单图识别验证恢复，未做多图连续压力与性能/内存验收，T5.3 仍 `NOT_RUN`。

## 2026-11-03 本地识别引擎优化冻结

- **停止继续自动化优化**：生成域已接近饱和，真实密集手牌/逐牌角/27 张签核数据缺失；继续扫阈值、TTA、候选排序或做合成微调没有可验证收益，只会过拟合。
- **最终策略**：基础 detector threshold `0.6`；仅当检测数少于 27 时用 `0.3` 补漏；低阈值框按 IoU `0.5` 去重，先分类且只接纳 known，最终上限 27。
- **落地位置**：`app/src/main/assets/models/model_manifest.json`（`version=20261102.4`, `fallback_score_threshold=0.3`）；`ModelManifest.kt`、`CardCornerDetector.kt`、`LearnedRecognition.kt` 的 `AdaptiveCornerPolicy`。
- **默认引擎为 `LEARNED`**（ADR-0044）：`MainActivity` 通过 `ModelAssets.load()` 加载检测器/分类器，并注入 `CardRecognizer`；模型加载、推理、超时或牌角不足时回退启发式并记录原因。T5.3 仍为 `NOT_RUN`，不再是默认启用的前置门禁。

## 最终桌面证据（生成域，非 T5.3）

- 固定 0.6：**1609/1620** 牌角几何命中，0 误检。
- 短场景 0.6→0.3 + known-only：**1615/1620（99.69%）**，0 误检；留出 `auto_case23~52` **807/810（99.63%）**，调参 `53~82` **808/810（99.75%）**。
- 分类器在匹配牌角上 exact **1.0**；剩余 5 个是检测几何漏检，不是分类错误。
- 证据保留：`output/runs/20261103/learned-benchmark-adaptive/report.json`、`...-holdout-adaptive/report.json`、`...-tune-adaptive/report.json`、`corner-ranking/report.json`。

## 2026-11-03 方案 A：learned + heuristic 补缺（非发布）

- 离线补缺评估脚本已完成（`hybrid_eval.py`）；按用户要求 `model_training/` 已从仓库移除，证据保留在 `output/runs/20261103/hybrid-eval-*/report.json`（`output/` 被忽略）。
- 严格 A/B 需要真实 Android heuristic JSONL，当前没有，状态 `BLOCKED`；桌面 `card_engine.py` 仅作明确标记的 proxy，不能授权 HYBRID。
- 9 张签核真实图 proxy 诊断（8 training + 1 validation）：learned per-card **0.0992**、heuristic proxy **0.0496**、补缺 **0.1322**；full-scene 均 **0**，confident error **0.1724 → 0.2558**。因此当前 **DO_NOT_ENABLE**，不接入 HYBRID。
- 证据：`output/runs/20261103/hybrid-eval-proxy/report.json`、`hybrid-eval-strict/report.json`、`hybrid-eval-learned-only/report.json`；报告固定 `formal_t5_3=false`、`release_eligible=false`。

## 2026-11-03 方案 A App 落地（补缺，非 HYBRID）

- `CardRecognizer`：learned 成功且少于 27 张时再跑 heuristic；`fillMissingCards()` 只追加 learned 尚未产出的 `(rank, suit)` 副本，按两副上限最多 2 张，不替换 learned 牌。learned 已满 27 张时不跑 heuristic。
- `Card.needsReview`：所有补入牌置 true；手牌 chip 显示“需核对”并高亮，手工校正后清除。补入数量写入 `RecognitionStats.unknown`，并产生可操作的补缺警告。
- 新增 `MissingCardFillTest` 5 项：只补缺、不替换、同牌面不重复、两副上限、满 27 不补。
- 验证：`./gradlew :app:testDebugUnitTest :app:assembleDebug` 成功；JVM 结果 **568 tests / 0 failures / 0 errors**；`adb install -r` 成功，设备 **LIO-N29（Android 10 / API 29）**。
- 边界：这是补缺实现，不是冲突仲裁，也不是 HYBRID；没有在真机上执行识图效果/耗时/内存验收，T5.3 仍 `NOT_RUN`。

## 其他当前证据与边界

- `output/runs/20261102/T5.3p-test/report.json`：公开真实图 per-card **0.192483**、full-scene **0.00905**、review **0.27765**、confident error **0.451299**；仍是 FAIL，不替代 T5.3。
- `output/runs/20261102/T3.5r/`：公开整牌框真实域预检 exact **0.104839（91/868）**；只作真实域下限诊断。
- `output/runs/20261102/T3.6-pseudo/`、`T3.6-real-mix/`：testcases 伪框/混合训练候选，非发布证据。
- `output/runs/20261102/T2.2v2/`、`T2.8/`、`20261101/T2.3/export/`、`T3.4/export/`、`T5.2/`：保留的模型/打包证据。
- App 资产：detector `a17d8e10…`、10,869,952 B；classifier 资产未替换；默认 `LEARNED`，失败回退 `HEURISTIC`。
- T4.7 真机 runner 仍 `NOT_RUN`；T5.3、真实 test、逐牌角签核、release-qa 仍未完成。手机 p95/内存/禁网、首次模型加载耗时和主线程开销不在自动化门禁内，是未验证风险。

## 2026-11-03 清理结果

- `model_training/annotations.json`：删除 15 个没有对应图片的 `IMG_20260809_*` key / 310 个框；保留 **9 图 / 242 框**（case4/10/12/13/14/18/19/21/116）。
- 删除 `model_training/output/obb_dataset_v2`、Python 缓存、空 `tools` 和误建的 `.venv/probe.py<`；保留默认训练使用的 `obb_dataset`、数据集和虚拟环境。
- 删除 `output/` 下旧预览/探针、chains/crops/predictions、无日期临时 run、旧 sweep/check/重复 crop 集，以及已放弃的 T5.3p 候选中间产物；保留 `20261101`、`20261102`、`20261103` 的最终任务证据和当前训练数据。
- 本次清理共释放约 **7.1 GB**。

## 当前任务

- 本轮 UI/文档术语统一：用户可见的相关功能名称统一为“组牌”，涉及 Android 主界面、手工组牌编辑流程、用户手册、设计/任务文档与 Harness 记忆；不改变牌型搜索、评分公式或识别算法。
- `doc/ui_design.md` 已同步为当前事实：`model_training/` 训练/评估工具链已从仓库移除，不再引用其 README 作为可执行入口；模型资产仍在 `app/src/main/assets/models/` 并随 App 打包。`doc/local_engine_tasks.md` 中对旧 README 的提及属于历史任务涉及文件记录，保留其历史语境。
- 验证：显式使用 `/opt/homebrew/opt/openjdk@17` 后，`./gradlew testDebugUnitTest assembleDebug` 执行成功（`BUILD SUCCESSFUL`）；两个主界面布局 XML 均可解析，`git diff --check` 通过。该结果不代表 T5.3、真实照片验收或 release-qa 已完成。
- 本地识别引擎：**FROZEN**，不再做自动化参数/排序/TTA 优化。
- 评分 V4.2 已落地；当前分支 `airecognize`。本轮不修改评分公式。
- learned 已是默认；方案 A 的补缺路径已落到 App 并安装到真机。T5.3 继续作为质量证据，当前仍 `NOT_RUN`；严格 A/B 仍缺真实 Android heuristic 预测，proxy 不支持 HYBRID/冲突仲裁。只有补充真实密集手牌、逐牌角可见性和 `release-qa` 签核后，才重新评估优化是否解冻。
