# 本地识图引擎 —— 自动化执行现状（真实进度）

依据 `doc/local_engine_tasks.md` §7。只写实际执行过的结果；复跑入口：`model_training/run_all.sh 20261101`。
本轮运行目录：`output/runs/20261101/`（每个任务含 `report.json`、日志与输入/输出 SHA-256）。

生成时间：2026-11-01（第三轮：M2/M3 训练与 LiteRT 导出打通）。门禁汇总：`output/runs/20261101/X3/gate-summary.md`。

## 结果总表（39 份报告：34 PASS / 3 NOT_RUN / 2 BLOCKED）

门禁（X3）统计 33 个任务：**29 PASS / 0 FAIL / 1 BLOCKED / 3 NOT_RUN，无缺失任务**，退出码 **4**（存在未解决的 BLOCKED 前置条件）。另一份 BLOCKED 报告是门禁自身的 X3。

| 任务 | 状态 | 证据 |
| --- | --- | --- |
| T0.1 | PASS | `ImagePreprocessorTest`：1600px 限长、EXIF、旋转 |
| T0.2 | PASS | `RecognitionWorkerTest` + `ImageMemoryTest`（12 tests）：纯函数 pass 计划、并发上限、逐张回收、27 张提前停；`CardRecognizer` 已改懒加载 |
| T0.3 | PASS | `RecognitionCoordinatorTest` |
| T0.4 | PASS | `VisualCardCodecTest`（10 tests，Robolectric NATIVE）：往返 100 次、缩放 0.5/0.75/1.5/2×、JPEG 95/85/70、保留条码裁切、50 张噪声零误命中、校验位损坏拒绝 |
| T0.5 | **BLOCKED** | 验证集已扩到 `testcases/` 全部 **14 张**（hash 全部冻结）；但只有 case3（27 张）/case4（26 张）有人工真值并签核，其余 **12 张缺真值**，状态按规范记 BLOCKED |
| T1.1–T1.5 | PASS | 9 来源 + 300 张公开图；293 张四点转换；2000 合成场景；54 类；审计与双签核 |
| T2.1 | PASS | YOLO11n-OBB baseline（1136/264 场景、18977 牌角标签） |
| T2.2 | PASS | 8 epoch 正式训练：val **P=0.9999 / R=0.9868 / mAP50=0.985 / mAP50-95=0.968** |
| T2.3 | PASS | 960 输入重新导出：TFLite **10,869,952 B**，`[1,3,960,960]` → 主输出 `[1,6,18900]`，全部有限；`verify_models.py` 形状/哈希/魔数全过 |
| T2.4 | PASS | 960 输入、完整 test **206 图 / 2440 可视牌角**：**recall 0.991803** ✓、误检 **0.0194/图** ✓、角点 IoU **0.9273** ✓（阈值 0.6） |
| T2.5 | PASS | 960 输入重扫：0.3–0.8 广阈值 recall 恒为 0.99932（检测数为分母）、precision 0.8 起为 1.0，冻结 **threshold=0.6** |
| T3.1 | PASS | 18000 crop（train 12520 / val 2684 / calibration 986 / test 1810），54 类全覆盖 |
| T3.2 | PASS | MobileNetV3Small 双头 12 epoch，val exact **0.9966** |
| T3.3 | PASS | 493 张 calibration crop 温度标定，**T=2.0** |
| T3.4 | PASS | float16 TFLite **1.92 MB**（门槛 15 MB） |
| T3.5 | PASS | test 905 crop：exact **0.996685** ✓、Joker **1.0** ✓（34/34）、ECE **0.0195** ✓、unknown 率 0.77% |
| T4.1 | PASS | LiteRT **2.3.0** + `assets/models/`（detector 10.8 MB + classifier 1.9 MB = 12 MB ≤ 15 MB）；`ModelManifest`/`ModelAssets` 做 SHA-256 与字节数双校验；`ModelManifestTest` 9 项 + `verify_models.py --assets` 复核均过 |
| T4.2 | PASS | `CardGeometry`（letterbox / OBB 解码 / 多边形 IoU / NMS / 逆映射）+ `CardCornerDetector`；`CardGeometryTest` 12 项、`CardCornerDetectorTest` 6 项 |
| T4.3 | PASS | `CardCornerClassifier`（四点透视拉正 96×128、0°/180° TTA、温度缩放、阈值→`UNKNOWN`、大小王按颜色复核）；`CardCornerClassifierTest` 9 项 + `JokerColorTest` 5 项 |
| T4.4 | PASS | 引擎开关 `HEURISTIC / LEARNED / SHADOW` + `EnginePolicy`（shadow 只跑不采用）；`RecognitionCoordinatorTest` + `RecognitionShadowTest` 5 项 |
| T4.5 | PASS | `RecognitionResult` 增 `issues`/`stats`/`summary()`（INFO/WARNING/ERROR、待核对张数）；`RecognitionResultTest` 6 项 + `RecognitionStateTest` 4 项 |
| T4.6 | PASS | 资产缺失、hash 不符、manifest 非法、推理异常、牌角不足、超时全部回退启发式；`RecognitionFallbackTest` 6 项 |
| T4.7 | NOT_RUN | 无 API 29 真机 |
| T5.1 | PASS（干跑） | `active_learning.py --crops` 把 crop 级预测聚合成图像级：calibration 33 图 → **2 个低 margin 候选入队**，31 张成功样本自动跳过，case3/case4 排除检查通过 |
| T5.2 | PASS | `package_model.py` 版本包（`version.json` + 上一版备份）+ `rollback_test.py` 回滚演练均 PASS |
| T5.3 | NOT_RUN | 需真机 shadow 对比 |
| T5.4 | PASS | `validate_docs.py`（20 个脚本入口 / 34 条任务映射 / 无断链）PASS；全量 `./gradlew test` **BUILD SUCCESSFUL** |
| X1 | PASS | 8 个白名单来源，AGPL 阻断 |
| X2 | NOT_RUN | 静态扫描通过；禁网真机测试未执行 |
| X3 | FAIL | 33 任务 29 PASS / 0 FAIL / 1 BLOCKED / 3 NOT_RUN；未过项仅剩 T0.5（12 张缺真值）与 T4.7/X2/T5.3（无 API 29 设备） |

## 验证集（testcases 全量）

`model_training/datasets/golden_truth.json` v3 登记了 `testcases/` 下全部 14 张图（`.DS_Store` 不算），`golden.py prepare --cases all` 会把它们全部写入 `golden_manifest.json`，并冻结每张图的 SHA-256。

| 图片 | 真值 | 状态 |
| --- | --- | --- |
| case3.png | 27 张（含小王） | 已签核 |
| case4.jpg | 26 张（含 ♥4/♦9/♣10×2/大王×2） | 已签核 |
| case10、case12、case13、case14、case18、case19 | 各 27 张 | 已签核 |
| case21.jpg | 28 张 | 已签核 |
| case116.jpg | 26 张（两张彩色 JOKER=BJ） | 已签核 |

2026-11-01 更新：case2/case11/case17/case115 的图片已从 `testcases/` 删除，并按用户决定从 `golden_truth.json` 剔除；验证集现为 **10 张 / 269 张牌**，**全部人工签核**（case116 为 26 张），`golden.py --cases all` → **PASS**（exit 0）。


缺真值的 12 张不会自动编造牌面：`annotations.json` 里它们的框数为 0，脚本只能登记与冻结 hash，并在报告里把状态标成 BLOCKED。补齐方式是在 `golden_truth.json` 给对应 case 填 `cards`，再由人工签核 `approvals/golden-definition.json`。

### 真机照片的启发式草稿（`model_training/draft_labels.py`）

为减少人工录牌成本，`draft_labels.py` 会把无真值图片跑一遍现有链路（检测器 → 透视拉正 → 分类器 0°/180° TTA），输出 `draft_labels.json`/`draft_labels.md` 和逐张叠加图。所有条目都带 `needs_review: true`，**不写入任何真值文件**。

实测结论（12 张图，2026-11-01）：

- 检测器必须用**训练尺寸 640**；改用 1280/1920 反而掉召回（case17：640→11 框，1280→3 框，1920→0 框），大图只是分布漂移。
- 召回约 **50%**：case21 实际约 20 张只检出 10 张，case17 约 22 张只检出 11 张；原因是这些照片牌角被重叠加遮挡得只剩细缝，与合成场景差异大。
- 牌面错误不少（同一张图里会连出多条 `HEART A`），即便正确检出也不宜直接当标签。

所以草稿只能当“待改”的起点，不能代替人工确认；要真正拉高真机召回，得补真实照片的遮挡样本重训检测器。

### 在两张有真值的图上的实测精度（`T0.5-draft-all`）

| 图 | 真值 | 草稿检出 | 完全命中 | precision | recall |
| --- | --- | --- | --- | --- | --- |
| case3.png | 27 | 32 | 11 | 0.344 | 0.407 |
| case4.jpg | 26 | 0（960）/ 1（640） | 0 | 0 | 0 |

即：目前只能当“逐张改”的起点，**不能用作标签来源**；两张图仍必须人工录入。

### 真图掉点的主因：方向不变性缺失（已实测）

把同一张图旋转后重测（960、conf 0.03），检出的牌角数量变化极大：

| 图 | 0° | 45° | 60° | 90° |
| --- | --- | --- | --- | --- |
| case3 | 32 | 11 | 1 | 6 |
| case4 | **0** | 9 | 14 | 17 |
| case21 | 17 | 10 | 5 | 0 |

合成训练只覆盖 ±20°（`augmentation.degrees: 15`，生成器 `angle: rng.uniform(-20, 20)`），
而真机手牌常摆到 45°–90°。所以 case4 在 0° 一个框都没有，转 60° 才出框。

推理侧改善（`draft_labels.py`，同一批真图）：

| 配置 | case3 命中 | case4 命中 | 检出 3/4 |
| --- | --- | --- | --- |
| 0° 单角度 | 11/27 | 0/26 | 32 / 0 |
| 旋转 TTA 0/30/60/90 | 12/27 | **4/26** | 59 / 27 |
| 2×2 切片 | 4/27 | 0/26 | — |
| 2×2 切片 + CLAHE | 6/27 | 0/26 | — |
| 四点四向 TTA（已回退，宽高互换取到空白） | 2/27 | 0/26 | — |

结论：切片/增强/多角度 TTA 都无法补上训练分布的缺口，必须用全角度增广重训检测器。

### 全角度增广微调后（T2.6，1 epoch，960）

| 权重 | 配置 | case3 | case4 | 检出 3/4 |
| --- | --- | --- | --- | --- |
| 20261101.2 | 0° | 11/27 | 0/26 | 32 / 0 |
| 20261101.2 | 旋转 TTA | 12/27 | 4/26 | 59 / 27 |
| T2.6 ep1 | 0° | 6/27 | 11/26 | 18 / 25 |
| **T2.6 ep1** | **旋转 TTA** | **18/27** | **20/26** | 96 / 83 |

即：同一份真图，牌面命中 case3 0.407→0.667、case4 0→0.769。代价是框变多（conf 0.05 下 precision 仅 0.19），
需配合更高 conf 或去重。尚未重导出 LiteRT、未替换 `assets/models`。

置信度扫描（T2.6 ep1 + 旋转 TTA）：conf 0.05 → 18/27、20/26（precision 0.19/0.24）；
0.10 → 18/27、19/26（0.24/0.28）；0.20 → 17/27、15/26；0.30 → 15/27、11/26。
precision 低主要是因为一张牌的两个可见牌角各出一个框（同牌多框），不是牌被认错；
草稿里已有的 `duplicate_of` 标记会提示，真正去重应在引擎层按实体牌合并。
`train_detector.py` 已加 `--degrees/--perspective/--scale/--patience` 覆盖；
`generate_synthetic.py` 已加 `--angle-range/--perspective-probability`（场景级 homography，
角点过同一矩阵并裁回画布，200 场景校验 `labels_within_canvas` PASS）。

## LiteRT 导出这一关是怎么过的

`ai_edge_torch`（已改名 `litert-torch`）装上后，`ultralytics export(format='litert')` 仍在本机 **SIGSEGV(139)**，父进程连 `report.json` 都写不出来——任务看起来“凭空消失”。逐层定位：

1. `litert_torch` 单独跑玩具模型 → 正常，输出合法 TFLite。
2. `--quantize 16`（FP16）→ ultralytics 明确拒绝：litert 只支持 32/8/w8a16/w8a32。按规范**显式降级到 FP32**并写入 manifest，不静默换格式。
3. 在 CLI 进程里 `import litert_torch` 或调 `YOLO.export(format='litert')` → 仍然 139（faulthandler 落在 `litert_torch/converter_v2.py` 的 import）。
4. 最终方案：新增 `model_training/litert_export_worker.py`，把转换放进**独立子进程**。同样的权重、同样的 640 输入，8 秒导出 10.8 MB TFLite；父进程用返回码判断，崩溃会如实记为 FAIL 而不再丢报告。

## Android 侧接入（T4.1–T4.6）

- 依赖 `com.google.ai.edge.litert:litert:2.3.0`（Google Maven，非 Maven Central）。该 jar 带 Kotlin 2.4 编译的元数据，本仓库编译器更低；我们只用其 **Java** 侧 `org.tensorflow.lite.Interpreter`，因此在 `app/build.gradle.kts` 加 `-Xskip-metadata-version-check`，不引用任何 LiteRT 的 Kotlin 扩展。
- 两个模型的输入布局不同：检测器导出为 **NCHW** `[1,3,640,640]`，分类器为 **NHWC** `[1,128,96,3]`。布局写进 `model_manifest.json` 并由 `CardGeometry.toInputTensor` 按 manifest 排布，代码里不写死。
- 阈值全部来自 manifest（detector `score_threshold=0.4` 来自 T2.5 冻结，classifier `T=2.0` 来自 T3.3）；Android 侧没有任何魔法数字。
- 默认引擎是 `HEURISTIC`：模型未过 §5.10 门槛前不得设为默认。`LEARNED` 失败/超时/牌角不足会带原因回退；`SHADOW` 只跑模型做对照，正式结果仍取启发式。
- 所有故障路径（资产缺失、hash 不符、manifest 非法、推理异常、超时）只返回 `Unavailable`，不会把空结果当“识别完成”。

## 本轮其它踩坑

1. **Robolectric 默认 LEGACY 图形模式**：Canvas 变空操作、`getPixel` 恒 0，条码永远解不出；2736×3648 还因 AWT BufferedImage 直接 OOM。加 `graphicsMode=NATIVE` + `@GraphicsMode` 后 21 项全绿。
2. **TF 2.19 的 `from_saved_model().convert()` 原生崩溃**：分类器导出改 `from_keras_model` 后稳定（1.92 MB float16）。
3. **ultralytics `deterministic=True` 在 torch 2.1x 上 ImportError**（`torch._inductor.config` 缺失），改 `deterministic=False`，复现性靠 `--seed` + 固定数据。
4. **`train.py` / `calibrate.py` 缺 `--task`**：报告被写成 T3.2/T3.3，门禁看不到 T3.4/T2.5。已补。
5. **阈值必须来自 calibration**：最初 T2.4 用拍的 0.4 且 `--min-confidence 0` 导致 287 误检/图；改成 calibration 扫表冻结后用 test 评一次。

## 检测器输入尺寸与 Joker 口径（第五轮）

- **960 输入的决定来自 calibration**：同一批 1491 个真值牌角，640 → recall 0.98457、960 → 0.98793，误检与 IoU 不变差，因此按 §7「只在 calibration 调」选定 960；test 只评一次。
- 960 导出的 TFLite 是 `[1,3,960,960] → [1,6,18900]`（10,869,952 B），assets/manifest 升到 `20261101.2`，`score_threshold` 由 960 的 calibration 扫描冻结为 **0.6**（0.4 也能过，但 0.6 把误检从 0.0388 降到 0.0194/图）。
- **Joker 恒为 JOKER**：分类器两个头独立 argmax，会产出 `BJ+HEART` 这种 54 组合之外的牌；`predict.py` 现在与 Android `CardCornerClassifier` 一样强制 `rank∈{SJ,BJ} → suit=JOKER`。这是评测口径 bug，不是放宽阈值——exact 因而从 0.99558 升到 0.996685，Joker 从 0.9706 到 1.0。
- **大小王按颜色区分（用户口径：大王彩色、小王黑白）**。四处映射已逐一核对一致：`JOKER-A.png`=BJ（平均饱和度 0.132）、`JOKER-B.png`=SJ（0.002）、`generate_synthetic.py` 的 `JOKER_FILES`、`CardAssetNames.kt`，以及真值 case3（黑白线稿=SJ）/case4（彩色=2×BJ）。这条规则现在不再是隐性知识：`labels.joker_rank_by_color` 与 Kotlin `JokerColor` 用「中心 60% 区域、HSV 饱和度 > 0.25 的像素占比」判定，≥0.12 判 BJ、≤0.01 判 SJ，中间灰区保留模型结论（327 张 crop 实测 SJ 中位 0.012 / BJ 中位 0.163，两端分布仍有重叠，不拿一个会错的阈值硬猜）。已接进 `predict.py` 与 `CardCornerClassifier`；T3.5 复跑无回归（exact 0.996685 / Joker 1.0）。

## 主动学习队列（T5.1 干跑）

- crop 级预测（493 行）不能直接喂 `active_learning.py`：它要的是图像级行（confidence/margin/cards）。新增 `--crops` 参数按 `source_image` 聚合：置信度取均值、margin 取最小、卡片取预测值。
- calibration 493 个 crop → 33 张图，**31 张无异常直接跳过**，2 个低 margin 候选入队（`T5.1/queue.json`），每条带原因与预测牌面。
- 这不是真机闭环：没有人工校正，也没有重训前后的指标对比；真机样本要等 T4.7。

## 剩余阻塞（都不是靠改脚本能过的）

1. **T5.1 只是干跑**：队列来自 calibration 的 2 个低 margin 候选，还没跑「人工校正 → 合并回 manifest → 重训」的 before/after 对照；真机 inbox 要等 T4.7。
2. **T4.x 只差设备证据**：JVM 侧已全部 PASS，但 T4.2/T4.3/T4.4/T4.6 的 `connectedAndroidTest` 需要 API 29 真机；manifest 里的 `min_confidence=0.5` / `min_margin=0.02` 是**暂定值**（T3.3 只冻结了温度 T=2.0），装机前必须按真机分布重标。
3. **真机**：T4.7 / X2 / T5.3 需要 API 29 设备。

## 结论

M1→M4 已经全部跑通并有可复现产物：数据（18000 crop / 1136 检测场景）→ 训练（分类器 val 0.9966、检测器 mAP50-95 0.968）→ 导出（1.92 MB 分类器、10.9 MB 检测器 @960）→ 标定（T=2.0、threshold=0.6）→ 评估（T2.4 recall 0.9918、T3.5 exact 0.9967，均达标）→ Android 接入（LiteRT 2.3.0、manifest 驱动、三条引擎路径与回退，51 项 Kotlin 测试）。
没有任何一项被改写成通过：T5.1 空队列、T0.5 缺真值、三台设备项都按实际状态记录。
