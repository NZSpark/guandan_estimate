# State —— 当前状态

> 只保留恢复工作所必需的信息。任务完成即就地改写，不追加历史流水。
> 最后更新：2026-11-01（本地识图引擎自动化管线落地并跑通 35 项任务入口；V4.2 评分已落地；图文用户手册 `doc/usermanual.md` 已补齐 AI 源登录、配置、校验与退出保护截图，并新增本地识图引擎分析 `doc/local_engine.md`。更早的 AI 阶段记录见 [`archive/state-2026-10-31.md`](archive/state-2026-10-31.md)）。

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

## 本轮完成（本地识图分析）

- 新增 `doc/local_engine.md`：静态分析 `CardRecognizer` / `CardTemplateMatcher` / `VisualCardCodec` / `HandImageRenderer` 与 `MainActivity` 调用链，给出 P0/P1/P2 问题、分阶段优化、测试基线与验收指标。未改识别代码。
- 核心结论：18 个 pass 的 Bitmap 同时驻留且主线程构图；输入图整数倍降采样未真正限制到 1800px；本地引擎无回归测试；先修管线，再调聚类/别名/模板阈值，最后评估 `model_training/` 模型。
- 2026-10-31 补充 §5 可自动化目标方案：公开数据/合成/主动学习、OBB 牌角检测 + 15×5 分类器、LiteRT 接入、自动化脚本与验收门槛；修正「从零标 300–500」的表述。

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

## 本轮完成（2026-11-01 本地引擎自动化）

- 按 `doc/local_engine_tasks.md` §7 落地全套 CLI：`_cli.py`（report.json/退出码/签核）、`generate_backgrounds.py`、`generate_synthetic.py`、`validate_dataset.py`、`golden.py`、`evaluate.py`、`calibrate.py`、`prepare_crops.py`、`train.py`、`train_detector.py`、`verify_models.py`、`fetch_public_data.py`、`convert_public_annotations.py`、`active_learning.py`、`import_corrections.py`、`package_model.py`、`rollback_test.py`、`device_benchmark.py`、`license_audit.py`、`offline_audit.py`、`validate_docs.py`、`gate.py`、`run_gradle_task.py`、`task_manifest.json`、`run_all.sh`。
- 一键执行：`model_training/run_all.sh 20261101`，35 份报告在 `output/runs/20261101/`，汇总见 `X3/gate-summary.md`，逐项状态见 `harness/local-engine-status.md`。
- 2026-11-01 人工签核到位（reviewer=Spark），T0.5/T1.1/T1.4/T1.5/X1 由 BLOCKED 转 PASS。
- 2026-11-01 第二轮：Kotlin T0.2/T0.4 落地（Robolectric NATIVE 图形模式，`RecognitionWorker` 懒渲染 pass + 12 项内存/计划断言），分类器链路 T3.1→T3.2→T3.3→T3.4 全 PASS，检测器 baseline T2.1 PASS。
- 2026-11-01 第三轮：M2 全链打通（T2.1/T2.2/T2.3/T2.5 PASS），LiteRT 导出用独立子进程 `litert_export_worker.py` 解决 SIGSEGV。
- 2026-11-01 按用户要求把 `testcases/` 全部 14 张图登记进验证集（`golden_truth.json` v3，hash 全冻结）；其中 12 张无人工真值，T0.5 因此回到 BLOCKED。
- 2026-11-01 新增 `model_training/draft_labels.py`，对 12 张无真值图出启发式草稿（含逐张叠加图）。实测：检测器必须用训练尺寸 640（1280/1920 掉召回），真机照片召回仅约 50%，牌面错误多 → 草稿仅供人工核对，不可当真值。
- 2026-11-01 第四轮：M4 Android 接入落地（T4.1–T4.6 全 PASS）——LiteRT 2.3.0、`assets/models`（12 MB）、`ModelManifest`/`ModelAssets`（SHA-256 + 字节数双校验）、`CardGeometry`/`CardCornerDetector`/`CardCornerClassifier`、引擎开关 `HEURISTIC|LEARNED|SHADOW`、`RecognitionResult.issues/stats/summary()`；新增 51 项 Kotlin 测试；T5.2 版本包 + 回滚演练 PASS；T5.4 文档检查与全量 `./gradlew test` PASS。
- 2026-11-01 第五轮：检测器改在 **960 输入**（calibration 同口径 640→0.98457、960→0.98793，误检不变），T2.4 test **recall 0.991803** / 误检 0.0194 图 / IoU 0.927 → PASS；T2.5 按 960 重扫冻结 threshold=0.6；T2.3 重新导出 960（`[1,3,960,960]` → `[1,6,18900]`，10,869,952 B），assets/manifest 升到 `20261101.2`；T3.5 修掉评测里「BJ+HEART」这种非 54 合法组合的口径 bug（`predict.py` 对 Joker 强制 suit=JOKER，与 Android 一致），重评 exact 0.996685 / Joker 1.0 / ECE 0.0195 → PASS。
- 2026-11-01 大小王口径固化（用户声明：**大王彩色、小王黑白**）：资产（`JOKER-A`=BJ 饱和度 0.132 / `JOKER-B`=SJ 0.002）、`generate_synthetic.py`、`CardAssetNames.kt`、真值 case3（黑白=SJ）/case4（彩色=2×BJ）四处映射核对一致；新增 `labels.joker_rank_by_color` 与 Kotlin `JokerColor`（中心 60% 高饱和像素占比 ≥0.12→BJ、≤0.01→SJ，灰区保留模型结论），并接进 `predict.py` 与 `CardCornerClassifier`；T4.3 增 5 项测试，T3.5 复跑无回归。
- 2026-11-01 第六轮：T5.1 打通——`active_learning.py` 新增 `--crops`，把 crop 级预测聚合成图像级（均值置信度 / 最小 margin），calibration 33 图产出 2 个低 margin 候选、31 张成功样本自动跳过，case3/case4 排除检查通过；仍缺真机 inbox 与重训前后对照。
- 2026-11-01 第七轮（真图掉点定位）：旋转实验证明检测器缺**方向不变性**——case4 在 0° 检出 0 框，转 60°/90° 才出 14/17 框；case3 从 0° 的 32 框掉到 60° 的 1 框。合成训练只覆盖 ±20°。`draft_labels.py` 新增 `--rotations` 旋转 TTA（case3 11→12/27、case4 0→4/26，检出 0→27）；2×2 切片、CLAHE、四点四向 TTA 实测无益或更差（已回退四向）。`generate_synthetic.py` 新增 `--angle-range/--perspective-probability`（场景级 homography + 角点同步映射并裁回画布，200 场景 PASS）；`train_detector.py` 新增 `--degrees/--perspective/--scale/--patience` 覆盖。已用 `degrees=180` 在 T2.2 权重上微调（T2.6，960、4 epoch；第 1 epoch 后 case3 18/27、case4 20/26，需继续调高）。用户要求继续训练：后台写成接续链（PID 11575，等 T2.6 退出后自动从其 best.pt 跑 T2.7，960、6 epoch、degrees=180、perspective=0.0012、scale=0.5），权落下后仍需复测与导出。实测表明继续加 epoch 反而掉真图（T2.6 ep1 case4 20/26 → ep4 15/26；T2.7 ep6 case3 20/27、case4 15/26，合成 mAP50-95 0.9491 稳步变好），属对「竖直合成图」过拟合，因此改走换数据：后台生成 1600 张全角度（±180）+ 透视（0.7 概率、0.6 强度）场景到 `output/runs/20261102/synth-v2`，导出 `model_training/output/obb_dataset_v2` 后自动从 T2.7 权重开 T2.8（960、8 epoch）。
- 结果：**34 PASS / 3 NOT_RUN / 2 BLOCKED / 0 FAIL**（39 份报告，另一份 BLOCKED 为门禁自身 X3）；门禁 33 任务 = 29 PASS / 0 FAIL / 1 BLOCKED / 3 NOT_RUN，无缺失，退出码 **4**（仅因未解决的 BLOCKED）。
- 第八轮（2026-11-02）：T2.8 全角度+透视数据重训**仍在跑**——`output/runs/20261102/` 有 T0.1/T0.2/synth-v2 报告，`T2.8/card_corner_obb_train` 已到 epoch 7/8（`best.pt` 27.2 MB，2026-11-01 23:28），但 `report.json` 未落盘，`run-all.log` 只记录到 T0.4 就中断，接续链进程已不在（`ps 11575` 空）。**不要重复启动**：先等/确认训练进程状态，再补 T2.8 报告、复测 case3/case4、按需重导 LiteRT。
- 关键坑：ultralytics `export(format='litert')` 与本进程内 `litert_torch.convert` 在本机必 SIGSEGV(139)（子进程隔离后 8 秒导出）；litert 不支持 FP16，需显式降级 FP32 并记录；TF 2.19 `from_saved_model().convert()` 原生崩溃（改 `from_keras_model`）；Robolectric 默认 LEGACY 图形模式导致 Canvas 空绘制。
- 许可政策变更（经授权）：`datasets/licenses.json` 升 schema 2，`unknown` 从 blocked 移入 train_allowed，仍不在 release_allowed（发布需另签 release-qa）。
- 结果：**11 PASS / 9 FAIL / 15 NOT_RUN / 0 BLOCKED**（M1 全线通过）。真值按签核同步：case3 Joker → 小王，case4 → 26 张；`golden.py` 新增 `signed_match_*` 交叉校验。
- 数据：2000 个合成场景 / 26947 张牌 / 54 类全覆盖；`model_training/datasets/dataset_manifest.json` 指向本次运行目录。

## 验证基线

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew testDebugUnitTest assembleDebug
# 本地引擎自动化（全部任务入口 + 门禁汇总）
model_training/run_all.sh 20261101
```

49 个测试类 / **460 项 0 失败**；`assembleDebug` 成功。

发布包：`./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release-unsigned.apk`，已用本机 debug keystore 签名后输出 `release/GuanDanScore-1.0-release.apk`（`/release/` 已加入 `.gitignore`）。项目无 release 签名配置，正式分发前需自建 keystore。

## 阻塞

- `MeldAdjustActivity` 锁定竖屏，旋转验收以 `font_scale` 配置变更替代（同一 `onSaveInstanceState` 路径）。
- 本地引擎自动化剩余项：T0.5 验证集 10 张**全部签核**（case3 27、case4 26、case10/12/13/14/18/19 各 27、case21 28、case116 26，合计 **269 张牌**），`golden.py --cases all` 已转 **PASS**（exit 0）；case2/case11/case17/case115 的图片已删除，按用户决定从 golden 集剔除；T5.1 仅 calibration 干跑（2 个候选），缺真机 inbox 与重训前后对照；T4.7/X2/T5.3 以及 T4.2–T4.6 的 `connectedAndroidTest` 缺 API 29 真机。真机照片识别（召回约 50%）按用户要求暂停，等重训提升后再做。
- 签核待复核：`reviewed_at` 已改为真实审核时间；`dataset-license.json` 对 6 个 `license=unknown` 来源给 allow，已同步到 `licenses.json` 策略（train_allowed 含 unknown，release_allowed 不含）。
- `app/src/main/assets/models/` 为 960 版检测器（`20261101.2`，10,869,952 B）+ 1.92 MB 分类器，合计 12.8 MB；默认引擎仍是 `HEURISTIC`（模型未过 §5.10 门槛），只有显式传 `RecognitionEngine.LEARNED` 才走模型；`min_confidence=0.5` / `min_margin=0.02` 是暂定值，装机前需重标。**960 推理耗时尚未在真机验证**，T4.7 必须补。
- V4.2 改动目前只有 JVM 证据，未装机复测；装机前先 `clean assembleDebug`（gradle 增量可能漏编）。

## 下一步

1. **V4.2 真机复测**：三带二王对（三张级牌+对大王 191）、残局 P 与 `R_control` 文案、10~12 / 19~26 张显示「待标定」、`T_plan=1` 的「单次出牌方案」。用户手册截图已覆盖主流程，但未覆盖上述边界态。
2. 回到 AI 源管理页真机剩余项（API 完整配置态、最后一份删除禁用、字体/键盘、重建草稿、冷启动并发）与 ChatGPT/Gemini 复测。
3. **本地引擎下一步**：T2.8（synth-v2 全角度+透视、960、8 epoch）已训练到 7/8 epoch 但报告缺失、进程已退出——先确认权重可用（`output/runs/20261102/T2.8/card_corner_obb_train/weights/best.pt`），补跑导出与 case3/case4 复测（0° 与旋转 TTA 各一遍），再决定是否替换 `assets/models`。不许把「脚本存在」当完成：T0.5 与 T4.7/X2/T5.3 仍按实际状态记为 BLOCKED/NOT_RUN。
4. 连上 API 29 真机后补 T4.2/T4.3/T4.4/T4.6 的 `connectedAndroidTest` 与 T4.7/X2/T5.3 设备证据，并按真机分布重标分类器的 `min_confidence`/`min_margin`。

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
