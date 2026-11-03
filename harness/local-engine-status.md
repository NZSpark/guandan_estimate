# 本地识图引擎 —— 自动化执行现状（真实进度）

依据 `doc/local_engine_tasks.md` §7。只写实际执行过的结果；复跑入口：`model_training/run_all.sh 20261101`。
本轮运行目录：`output/runs/20261102/`。2026-11-03 已清理旧 T3.1c/T3.5c/T4.7b 中间产物；保留 `T3.5r/`、`T3.6-pseudo/`、`T3.6-real-mix/`、`T5.3p-test/report.json` 等最终证据。本地引擎优化已冻结。

生成时间：2026-11-02（第七轮：牌角遮挡修复 + T4.7 真机尝试）。门禁汇总：`output/runs/20261102/X3c/`。

## 结果总表（2026-11-02 门禁：34 个规范任务 30 PASS / 1 FAIL / 3 NOT_RUN）

门禁（X3）统计 34 个规范任务：**30 PASS / 1 FAIL / 3 NOT_RUN**；FAIL 是 X3 自身（3 个 NOT_RUN 未清）；纳入 T2.6–T2.8 实验报告后为 **37 份 33 PASS / 1 FAIL / 3 NOT_RUN**。

| 任务 | 状态 | 证据 |
| --- | --- | --- |
| T0.1 | PASS | `ImagePreprocessorTest`：1600px 限长、EXIF、旋转 |
| T0.2 | PASS | `RecognitionWorkerTest` + `ImageMemoryTest`（12 tests）：纯函数 pass 计划、并发上限、逐张回收、27 张提前停；`CardRecognizer` 已改懒加载 |
| T0.3 | PASS | `RecognitionCoordinatorTest` |
| T0.4 | PASS | `VisualCardCodecTest`（10 tests，Robolectric NATIVE）：往返 100 次、缩放 0.5/0.75/1.5/2×、JPEG 95/85/70、保留条码裁切、50 张噪声零误命中、校验位损坏拒绝 |
| T0.5 | **PASS** | 2026-11-02 起 8 张转训练、**只有 case13 校验**（27 张牌）；`golden.py --cases all` → 1 图/27 牌 PASS。旧 9 图/242 牌为切分前历史 |
| T1.1–T1.5 | PASS | 9 来源 + 300 张公开图；293 张四点转换；2000 合成场景；54 类；审计与双签核 |
| T2.1 | PASS | YOLO11n-OBB baseline（1136/264 场景、18977 牌角标签） |
| T2.2 | PASS | 8 epoch 正式训练：val **P=0.9999 / R=0.9868 / mAP50=0.985 / mAP50-95=0.968** |
| T2.3 | PASS | 960 输入重新导出：TFLite **10,869,952 B**，`[1,3,960,960]` → 主输出 `[1,6,18900]`，全部有限；`verify_models.py` 形状/哈希/魔数全过 |
| T2.4 | PASS | 960 输入、test **224 图 / 3019 可视牌角**：**recall 0.991057** ✓、误检 **0.0179/图** ✓、角点 IoU **0.9501** ✓（阈值 0.6；T2.2v2 权重） |
| T2.5 | PASS | 960 输入重扫（T2.2v2）：0.3–0.6 广阈值 recall 0.9863（检测数为分母）、precision 1.0，冻结 **threshold=0.6** |
| T3.1 | PASS | **45,866 crop**（train 31,114 / val 6,740 / calibration 2,664 / test 5,348），54 类全覆盖；新增整牌几何遮挡过滤，剔除 **2,933** 个牌角覆盖 >0.2 的样本（6.4%） |
| T3.2 | PASS | MobileNetV3Small 双头 12 epoch，val exact **0.9966** |
| T3.3 | PASS | 571 张 calibration crop：温度 **T=0.5**；门控扫描（coverage≥0.95）选中 **0.5 / 0.0**（0.02 同分：coverage 0.9947、exact 1.0）；manifest 现行 0.5 / 0.02 由此得到支持。本次为 20261102 候选分类器，T 与已部署 20261101 资产的 2.0 不可直接互换 |
| T3.4 | PASS | float16 TFLite **1.92 MB**（门槛 15 MB） |
| T3.5 | **PASS** | 遮挡清洗后 test **2,674 组**：exact **1.0**、Joker **1.0**、ECE **0.0121**、unknown 0（已部署 20261101 分类器）。旧 946 组 FAIL 是遮挡标签错误，不是模型回退；test 仍全部为合成 crop，真实照片留给 T5.3 |
| T4.1 | PASS | LiteRT **2.3.0** + `assets/models/`（detector 10.8 MB + classifier 1.9 MB = 12 MB ≤ 15 MB）；`ModelManifest`/`ModelAssets` 做 SHA-256 与字节数双校验；`ModelManifestTest` 9 项 + `verify_models.py --assets` 复核均过 |
| T4.2 | PASS | `CardGeometry`（letterbox / OBB 解码 / 多边形 IoU / NMS / 逆映射）+ `CardCornerDetector`；`CardGeometryTest` 12 项、`CardCornerDetectorTest` 7 项（含显式 fallback 阈值） |
| T4.3 | PASS | `CardCornerClassifier`（四点透视拉正 96×128、0°/180° TTA、温度缩放、阈值→`UNKNOWN`、大小王按颜色复核）；`CardCornerClassifierTest` 9 项 + `JokerColorTest` 5 项 |
| T4.4 | PASS | 引擎开关 `HEURISTIC / LEARNED / SHADOW` + `EnginePolicy`（shadow 只跑不采用）；`RecognitionCoordinatorTest` + `RecognitionShadowTest` 5 项 |
| T4.5 | PASS | `RecognitionResult` 增 `issues`/`stats`/`summary()`（INFO/WARNING/ERROR、待核对张数）；`RecognitionResultTest` 6 项 + `RecognitionStateTest` 4 项 |
| T4.6 | PASS | 资产缺失、hash 不符、manifest 非法、推理异常、牌角不足、超时全部回退启发式；`RecognitionFallbackTest` 8 项（含短场景补漏与 unknown 拒绝） |
| T4.7 | NOT_RUN | API 29 物理机 **LIO-N29 / Android 10** 已连接；`RealDeviceBenchmarkTest` 在 learned 路径 >5 分钟无结果，未生成 runner report。`device_benchmark.py` 记录设备证据与 NOT_RUN，不拿桌面数据补 |
| T5.1 | PASS（干跑） | `active_learning.py --crops` 把 crop 级预测聚合成图像级：calibration 33 图 → **2 个低 margin 候选入队**，31 张成功样本自动跳过，case3/case4 排除检查通过 |
| T5.2 | PASS | `package_model.py` 版本包（`version.json` + 上一版备份）+ `rollback_test.py` 回滚演练均 PASS |
| T5.3 | NOT_RUN | 需真实 test / 逐牌角可见性 / `release-qa` 签核；不再是默认启用门禁（ADR-0044） |
| T5.4 | PASS | `validate_docs.py`（20 个脚本入口 / 34 条任务映射 / 无断链）PASS；全量 `./gradlew test` **BUILD SUCCESSFUL** |
| X1 | PASS | 8 个白名单来源，AGPL 阻断 |
| X2 | NOT_RUN | 静态扫描通过；禁网真机测试未执行 |
| X3 | FAIL | 34 个规范任务 30 PASS / 1 FAIL（X3 自身）/ 3 NOT_RUN；37 份报告 33 PASS / 1 FAIL / 3 NOT_RUN |

## 真实域预检（T3.5r，非门禁）

2026-11-02 用公开整牌框做了自动预检，**不改变 34 个规范任务的状态**：

- 数据：RF100 test 128 图 / 544 牌 + TeogopK real 93 图有标签 / 324 牌（仓库实有 98 图）；合计 868 牌 / 6,944 个 bbox 角候选 crop。
- 模型：已部署 `classifier.tflite`，T=2.0，四候选 + 0°/180° TTA；分类器只按置信度选候选，不读真值。
- 结果：exact **0.104839（91/868）**，RF100 **0.125**、TeogopK **0.071**，ECE **0.097854**；`T3.5r-eval` 按 T3.5 阈值 FAIL。
- 结论：真实域严重失配；但整牌 bbox 没有逐牌角真值，四个候选可能裁到牌面中央/空白，因此该分数只是**下限诊断**，不能当作 T3.5 回退或 T5.3 发布证据。
- 证据：`output/runs/20261102/T3.5r/`、`T3.5r-predict/`、`T3.5r-eval/`。
- 追加：`drFarid/French-Playing-Cards`（52 类 / 1,875 张真实单牌 / MIT）抽样自动定位+模板 SIFT 配准失败（4 张仅 1–3 好匹配、RANSAC 0 inliers），不能稳定生成牌角 crop；未进入训练。

## T3.6 testcases 伪框预检（非门禁）

- 8 张训练图（除 case13）用 T2.2v2@960 / `--pass-plan app` 生成 344 个候选；`pseudo_from_testcases.py` 按检测分、分类置信度、IoU 去重，并要求人工牌面清单仍有剩余同类标签，得到 **55 crop / 29 类**；81 个框因标签不匹配丢弃。
- `case13` 训练记录 **0**；混合训练为合成 5,104 条 + 55 个真实 crop 过采样 10×，T3.6 报告 PASS，合成 val exact **0.993492**。
- `case13` 同检测器对照：新模型最佳 F1 **0.5000**（14/27），旧模型 **0.4643**（13/27）；App 门控 `det .6 / cls .5 / margin .02` 下两者均 **5/27**（新 7 框、旧 6 框）。不能据此替换资产或关闭 T5.3。
- 证据：`output/runs/20261102/T3.6-pseudo/`、`T3.6-real-mix/`、`T3.6-real-mix-export/`、`T3.6-case13-new/`、`T3.6-case13-old/`、`T0.5-case13-only/`。

## 历史 golden 集（2026-11-02 切分前 9 张）

切分前 `model_training/datasets/golden_truth.json` 登记 9 张图；**当前只校验 case13**（case3 已按用户决定删除，`.DS_Store` 不算），`golden.py prepare --cases all` 会把它们全部写入 `golden_manifest.json`，并冻结每张图的 SHA-256。

| 图片 | 真值 | 状态 |
| --- | --- | --- |
| case4.jpg | 26 张（含 ♥4/♦9/♣10×2/大王×2） | 已签核 |
| case10、case12、case13、case14、case18、case19 | 各 27 张 | 已签核 |
| case21.jpg | 28 张 | 已签核 |
| case116.jpg | 26 张（两张彩色 JOKER=BJ） | 已签核 |

2026-11-02 更新：case2/case11/case17/case115/case3 已从 `testcases/` 删除并移出 `golden_truth.json`；验证集为 **9 张 / 242 张牌**，**全部人工签核**，`golden.py --cases all` → **PASS**（exit 0）。


验证集不再包含缺真值图片；新增图片必须先补 `cards` 并由人工签核，否则 T0.5 记为 BLOCKED。

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
- 默认引擎是 `LEARNED`（ADR-0044）：`MainActivity` 从 `ModelAssets` 加载模型；加载失败、推理异常、超时或牌角不足都会带原因回退启发式。`SHADOW` 只跑模型做对照，正式结果仍取启发式。
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

## 2026-11-02 真机照片复测（P1/P2）

- App 18-pass 配方、9 张 / 242 牌：T2.2v2@960 最优 **84/242（R 0.347 / P 0.357 / F1 0.352）**；现行 App 阈值（det 0.6 / cls 0.5）下 **35/242（R 0.145）**。T2.2 资产@960 最优 40/242（F1 0.200），640 下 64/242（F1 0.283），证实资产是 640 训练、960 导出。
- T2.8（全角度+透视，1600 场景）合成 mAP50-95 0.358；真机图最优 F1 **0.282**（det 0.25 / cls 0.2，R 0.397 / P 0.218），App 阈值下 0 命中——不采用，T2.2v2 仍是候选。
- 未改 `app/src/main/assets/models`：T2.2v2 换入属发布变更，T4.7/T5.3 仍 NOT_RUN。

## 2026-11-02 牌角遮挡标签修复（T3.1c / T3.5c）

> 2026-11-03 清理：T3.1c/T3.5c 的明细与 crop 中间产物已删除；以下保留当时的结论与指标，当前可复现入口为 `prepare_crops.py` / `T3.1`，不作为新增证据。

- 根因：合成场景按绘制顺序叠加，`visible` 只看牌角框 IoU；被后画整牌覆盖 49%–78% 的牌角仍被当作可见，crop 里其实是覆盖牌的牌面。6 个“错例”全部落在覆盖 >49% 的样本。
- 修复：`prepare_crops.py` 增加整牌多边形覆盖计算，默认过滤牌角覆盖 >0.2 的样本；T3.1c 生成 **45,866 crop**，过滤 **2,933**（6.4%），test **2,674 组**。
- 重评：已部署 20261101 分类器 exact **1.0**、Joker 1.0、ECE 0.0121；候选 20261102 的 7 个错例同样来自遮挡样本。旧 946 组 FAIL 报告保留在 `T3.5/`，新 PASS 在 `T3.5c/`。
- 边界：test 仍全部是合成 crop；真实照片逐牌角 test 仍缺，T5.3 不能因此算通过。

## 2026-11-02 T4.7 真机尝试

- 设备：**HUAWEI LIO-N29 / Android 10 / API 29 物理机**（`device_benchmark.py` 已记录 build fingerprint）。
- 新增 `RealDeviceBenchmarkTest`：case116 有 6 个 learned 检测，直接跑 `LearnedCardRecognizer`，10 次计时 + 20 次压力，导出 `t4_7_runner.json`。
- 实际运行在 learned 路径 >5 分钟无返回，runner JSON 未生成；停掉后记 **NOT_RUN**。下一步需抓 logcat/native 栈，判断是 LiteRT 初始化、模型映射还是设备兼容问题。

## 2026-11-03 方案 A 补缺评估（非门禁）

- 新增 `model_training/hybrid_eval.py`：learned 主结果；heuristic 仅补 learned 未产出的牌面副本，不替换 learned，不用真值决定补哪张。
- 严格 A/B 需要真实 Android heuristic 预测 JSONL，当前没有，learned-only 报告为 `BLOCKED`；桌面 `card_engine.py` 只能作为明确标记的 proxy。
- 9 张签核真实图 proxy 诊断：learned per-card **0.0992**、heuristic proxy **0.0496**、补缺 **0.1322**；full-scene 均 **0**，confident error **0.1724 → 0.2558**。按“per-card/full-scene 同时提升且 confident error 不明显增加”的规则，结果 **DO_NOT_ENABLE**。
- 证据：`output/runs/20261103/hybrid-eval-proxy/report.json`、`hybrid-eval-learned-only/report.json`；不改变 T5.3 / X3 状态。

## 2026-11-03 方案 A App 补缺落地

- learned 成功且少于 27 张时调用 heuristic，只补 learned 未产出的 `(rank, suit)` 副本；最多两副、总上限 27，不替换 learned 牌。
- 补入牌 `Card.needsReview=true`，UI chip 显示“需核对”；补入数量计入 review 统计。
- 验证：`testDebugUnitTest` **568 tests / 0 failures / 0 errors**；`assembleDebug` 成功；已用 `adb install -r` 安装到 **LIO-N29（Android 10 / API 29）**。
- 边界：这是补缺，不是 HYBRID/冲突仲裁；未做真机识图效果、耗时和内存验收；T5.3 仍 `NOT_RUN`。

## 剩余阻塞（都不是靠改脚本能过的）

1. **T3.5 只在合成 test 上过线**：遮挡清洗后 2,674 组合成 crop exact 1.0；真实照片没有逐牌角真值，按 §7.5 仍不能替代真实 test，留给 T5.3。
2. **T4.7 LiteRT 真机阻塞**：API 29 物理机（LIO-N29）已连接，但 `RealDeviceBenchmarkTest` 在 learned 路径上 >5 分钟无结果，`t4_7_runner.json` 未生成；`device_benchmark.py` 记 NOT_RUN，不能拿桌面数据补。
3. **T5.1 只是干跑**：队列来自 calibration 的 2 个低 margin 候选，还没跑「自动校正 → 合并回 manifest → 重训」的 before/after 对照；真机 inbox 要等 T4.7。
4. **X2 / T5.3 仍需设备/签核**：X2 的禁网测试在 WiFi ADB 下会断测试通道（无 USB/root），T5.3 需要真实 test + `release-qa` 人工签核；两者均未执行。

## 结论

M1→M3 与 M4 的 JVM 适配层已跑通并有可复现产物（T4.7 真机证据仍缺）：数据（45,866 crop / 1136 检测场景）→ 训练（分类器 val 0.9966、检测器 mAP50-95 0.968）→ 导出（1.92 MB 分类器、10.9 MB 检测器 @960）→ 标定（det threshold=0.6；classifier 门控 0.5/0.02）→ 评估（T2.4 recall 0.9911 达标；T3.5 遮挡清洗后 exact 1.0，但仍是合成 test）→ Android 接入（LiteRT 2.3.0、manifest 驱动、三条引擎路径与回退，51 项 Kotlin 测试）。
没有把未完成项改写成通过：T3.5 只在合成 test 上 PASS；T4.7 真机测试无结果；X2/T5.3 未执行。

## 2026-11-02 全量重跑（run `20261102`）

| 结果 | 任务 |
| --- | --- |
| PASS（30） | T0.1–T0.5、T1.1–T1.5、T2.1–T2.5、T3.1–T3.5、T4.1–T4.6、T5.1、T5.2、T5.4、X1 |
| FAIL（1） | X3 自身（T4.7/X2/T5.3 仍 NOT_RUN） |
| NOT_RUN（3） | T4.7（LiteRT 真机测试无结果）/ X2 / T5.3 |

检测器已修（T2.4 0.9897 → **0.9911** PASS）：640 训练 / 960 推理的尺度不一致导致牌角定位偏，改为 960 续训（T2.2v2，20 epoch）后 recall 达标、误检降到 0.0179/图、IoU 0.950。

分类器塌陷已修复（0.0201 → 0.9926）：0°/180° 同标签的标签噪声 + `--no-imagenet` 从零训练是根因，改 0° only + imagenet + cosine 后恢复正常；`calibrate.py` 分类器分支的 `confidence` 取值错误也已修（此前 T3.3 阈值表恒 `kept=0`）。

本轮同时修掉管线缺口：`run_all.sh` 的 T0.5 改为全量 golden；T1.2 用 `--format hf-json` 直读 Hugging Face 标注（`splits/single/*.json`）；T2.4/T2.5/T3.3/T3.5/T5.1 增加 `predict.py` 预测生成步骤；`calibrate.py` 的 detector 阈值扫描改为按牌角逐条统计（之前按图取 `score` 恒为 0，`kept=0`）。

## 2026-11-03 自动牌角排序（非门禁）

- 用 `auto_case53~82` 的 30 图/810 牌做生成域回归，并只用已签核 case18/19/21 做真实护栏；不改真实 golden、T5.3 或 App。
- 规则：80 候选、25px NMS、top-27 不变；仅当整图至少 15 个候选同时有两条 `>=50px` 长边时，质量加入 `0.5*normalized_edge`，否则继续 `ink,white,score`。
- 结果：生成 top-27 **393/810 → 471/810**；真实签核 **32/82（11/8/13）** 不变。无条件边长排序真实降到 26/82、单候选 `min>=50` 降到 29/82，均拒绝。
- 证据：`output/runs/20261103/corner-ranking/report.json`、`auto_case53-overlay.jpg`；`test_card_engine.py` 28/28 PASS。
