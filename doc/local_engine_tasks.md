# 本地识图引擎任务分解

> 依据：`doc/local_engine.md` §5–§7。
> 状态：执行中，**未达发布门槛**；2026-11-02 自动化门禁 30 PASS / 1 FAIL（X3 自身）/ 3 NOT_RUN（T4.7/X2/T5.3）。T3.5 的 1.0 仅是合成 crop 回归，真实 test 尚未建立。
> 发布结论（2026-11-03 用户决策，ADR-0044）：learned 已改为默认识别引擎；T5.3 仍是未完成的质量证据，但不再是默认启用门禁。模型加载/推理失败必须回退 heuristic。
> 自动化边界：真实 test 的拍摄/逐牌角签核/发布 QA 不是 agent 能完成的工作；缺失时只能 `BLOCKED`，不能用合成集冒充真实证据。
> 原则：先做管线安全垫，再建数据和模型；默认 learned 必须保留人工核对和启发式回退；真实 test/签核仍是发布质量项。

## 0. 测试执行位置（2026-11-02 用户约束）

1. 所有准确率、真实 test、回归、端到端评估、性能基准都在电脑上运行；输入是导出的 TFLite/YOLO/Python 管线，不依赖手机。
2. 手机只安装最终优化后的 APK/模型；不运行 instrumentation、`connectedAndroidTest`、手机性能/内存/禁网测试。
3. T4.2/T4.3/T4.4/T4.6 只保留 JVM 测试；T4.7 改为桌面导出模型兼容性；X2 只保留电脑静态离线审计；T5.3 只做电脑端 learned 真实 test，不做手机 heuristic A/B。
4. 手机 p95/内存/OOM/ANR、运行期禁网、手机 heuristic A/B 不再作为自动化门禁，属于未验证风险，不得写成 PASS。
5. 若这些手机指标仍是发布硬门槛，则与“所有 test 在电脑”冲突，必须先改发布定义；当前发布结论仍为未达。

## 1. 里程碑与依赖

| 里程碑 | 对应方案 | 目标 | 退出条件 |
| --- | --- | --- | --- |
| M0 管线安全与基线 | §5.3、§6、§7.2 | 降内存、去主线程、可取消、可回归 | case3 27/27；case4 可见牌 ≥95%；无旧结果覆盖；有性能基线 |
| M1 数据自动化 | §5.5 | 公开数据 + 合成数据 + manifest | 54 类覆盖；两副牌重复场景；无 unknown 许可进入 train |
| M2 牌角检测器 | §5.6.1 | 单类 OBB 检测 `card_corner` | 合成 test 与独立真实 test 均 recall ≥99%；误检 ≤0.1/图；角点 IoU ≥0.8 |
| M3 点数/花色分类器 | §5.6.2 | 15×5 双头分类，有效组合 54 | 真实留出集 exact ≥99.5%；大小王 ≥99%；ECE ≤0.05；合成 crop 只做回归，不能替代真实 test |
| M4 Android 灰度 | §5.7、§5.8 | LiteRT 接入、fallback | 模型 ≤15MB；桌面 TFLite 兼容/哈希通过；可回退；手机 p95/内存不测（风险未验证） |
| M5 主动学习与发布 | §5.9、§5.10 | 真实校正闭环、版本化、灰度 | 真实 test 端到端每张 ≥99.5%；27 张全对 ≥85%（发布 ≥90%）；复核率 ≤5%；`release-qa` 签核 |

依赖关系：

- M0 与 M1 可以并行启动；M0 是 Android 接入的前置条件。
- M1 完成后，M2 和 M3 可以并行。
- M2 + M3 + M0 完成后，进入 M4。
- M4 通过 shadow 验收后，进入 M5。
- 许可、隐私、文档三项横切任务贯穿所有里程碑。

## 2. 任务分解

### M0 管线安全与基线

#### T0.1 输入预处理与精确限长

- **目标**：所有输入图按长边精确限制到 1600px，正确处理 EXIF 方向，只保留一份工作位图。
- **依赖**：无。
- **涉及**：`MainActivity.kt`、新增 `ImagePreprocessor.kt`。
- **步骤**：读取 EXIF；按 `1600 / max(width, height)` 计算精确比例；应用方向；转 RGB；小图不放大；把尺寸计算抽成纯函数。
- **验收/证据**：750×492、1440×1920、2736×3648 三类图片的输出长边均为 1600 或原图较小边；现有识别结果不回归；JVM 单测覆盖比例和方向。

#### T0.2 后台解码与按需构图

- **目标**：解码、缩放和 18-pass 候选图构建移出主线程，逐 pass 生成并及时回收。
- **依赖**：T0.1。
- **涉及**：`CardRecognizer.kt`、`MainActivity.kt`。
- **步骤**：在 `Dispatchers.Default` 执行解码/构图；不要一次性创建 18 个 Bitmap；每个 pass 完成即 `recycle()`；原图优先，只在缺口时生成额外 pass。
- **验收/证据**：主线程无 Bitmap 创建；2736×3648 输入无 OOM；峰值内存不高于基线；记录每阶段耗时。

#### T0.3 jobToken、取消与超时

- **目标**：同一时刻只有一个识别任务；新任务使旧任务结果失效；超时可回退。
- **依赖**：T0.2。
- **涉及**：新增 `RecognitionCoordinator.kt`、`MainActivity.kt`。
- **步骤**：递增 token；保存 `Job`；新请求取消旧请求；旧回调检查 token 后丢弃；设置总超时（建议 6s）；`onDestroy` 取消。
- **验收/证据**：快速连续导入两张图，只显示最后一张结果；识别中换图/随机/联网不出现旧结果覆盖；超时后回退且 UI 不卡死。

#### T0.4 条码快速路径回归

- **目标**：保证 `VisualCardCodec` 始终优先于模型和 OCR，并建立往返测试。
- **依赖**：T0.1。
- **涉及**：`VisualCardCodec.kt`、新增/补充测试。
- **步骤**：生成 27 张图；往返 100 次；覆盖缩放、JPEG、裁切变体；验证错误码/回退。
- **验收/证据**：正确图命中 27/27；错误图不产生高置信度假阳性；条码路径失败时能进入启发式/模型路径。

#### T0.5 golden set 与基线报告

- **目标**：把 case3/case4 变成可重复的 expected JSON，并记录现状性能。
- **依赖**：T0.1。
- **涉及**：`testcases/`、`doc/ai_baseline.md`、新增测试/脚本。
- **步骤**：人工确认可见牌；写 expected JSON；运行现有启发式；记录 exact、张数、p50/p95、峰值内存；修正失效的 `case1` 引用。
- **验收/证据**：case3 27/27；case4 可见牌 exact ≥95%；连续 20 次导入无旧结果覆盖、无 OOM；基线数据写入文档。

### M1 数据自动化

#### T1.1 公开数据登记

- **目标**：建立公开数据/权重的来源、许可、hash 和用途记录。
- **依赖**：无。
- **涉及**：新增 `model_training/datasets/README.md`、`sources.json`。
- **步骤**：登记 Kaggle `andy8744`、Hugging Face `JackFurby/playing-cards`、TeogopK、Complete、PD-Mera；标注 CC0/MIT/Unknown；记录下载 URL、版本、hash；确认 Ultralytics AGPL 影响。
- **验收/证据**：每个来源记录 URL、版本与许可字段；许可只登记不阻断（2026-11-02 起删除 `licenses.json` 与 `dataset-license` 门禁）。

#### T1.2 公开标注转换

- **目标**：把公开四点/牌角框统一成项目 OBB 和 manifest。
- **依赖**：T1.1。
- **涉及**：新增 `convert_public_annotations.py`。
- **步骤**：四点转 OBB；整牌四点映射到左上 35%×35% 牌角；过滤无牌角/不可见样本；校验点顺序、越界、重复；输出统一 `rank/suit/quad`。
- **验收/证据**：抽样可视化通过；转换后无非法标签；54 个有效组合映射正确；大小王不混类。

#### T1.3 合成场景生成器

- **目标**：用自有 54 张牌面生成两副牌、1–27 张、扇形/网格/散牌场景。
- **依赖**：T1.1（背景许可）、现有牌面素材。
- **涉及**：新增 `generate_synthetic.py`。
- **步骤**：无放回抽 1–27 张；允许同 `(rank,suit)` 出现两次；随机角度、间距、重叠 0–80%、缩放、透视；随机光照/阴影/模糊/噪声/JPEG；加入无牌、牌背、半张牌、文字负样本；背景只用自有或明确可商用 CC0 纹理；由渲染参数生成整牌四点、牌角四点、rank/suit/visible。
- **验收/证据**：标签与渲染参数一致；大小王两类都有足够样本；可视化和抽样人工检查；固定种子可复现。

#### T1.4 manifest、划分与防泄漏

- **目标**：所有数据进入统一 manifest，按场景划分 train/val/test。
- **依赖**：T1.2、T1.3。
- **涉及**：`model_training/datasets/dataset_manifest.json`、新增 `validate_dataset.py`。
- **步骤**：记录 `source/license/split/image/scene_id/cards`；按 `scene_id` 分组划分；统计 54 类覆盖；统计两副牌重复实例；禁止同一照片/同一合成场景跨 split；记录图片和模型 hash。
- **验收/证据**：train/val/test 无 scene_id 重叠；54 类全覆盖；两副牌场景存在；`license=unknown` 数量为 0。

#### T1.5 数据审计与验收报告

- **目标**：在训练前冻结数据版本和许可状态。
- **依赖**：T1.4。
- **涉及**：`model_training/reports/data-audit.md`。
- **步骤**：检查类别分布、遮挡比例、背景来源、重复图、标签越界；确认 case3/case4 未进入 train；记录数据 manifest hash。
- **验收/证据**：审计报告签字/记录；无泄漏；无 unknown 许可；数据版本可复现。

### M2 牌角检测器

#### T2.1 检测器 baseline

- **目标**：用公开数据/权重建立单类 `card_corner` 的 YOLO-OBB baseline。
- **依赖**：M1。
- **涉及**：`model_training/train_detector.py`、训练配置。
- **步骤**：固定 YOLO11n-OBB 版本；单类标签；公开数据预训练；记录 baseline 指标；不直接上线。
- **验收/证据**：训练可复现；输出 OBB 四点；baseline 报告包含 recall/FP/IoU。

#### T2.2 检测器训练

- **目标**：用合成数据 + 真实校正集训练正式检测器。
- **依赖**：T2.1、T1.5。
- **涉及**：`train_detector.py`、数据配置。
- **步骤**：输入 640；真实小牌角召回不足时再评估 960 或切片；增强旋转 ±15°、透视、缩放、亮度/模糊/JPEG；禁止水平翻转；保存最佳权重和训练参数。
- **验收/证据**：验证集 recall ≥99%；误检 ≤0.1/图；角点 IoU ≥0.8；分类别/遮挡/背景分层报告。

#### T2.3 LiteRT/TFLite 导出与核对

- **目标**：导出 Android 可用的检测器并验证 OBB 输出。
- **依赖**：T2.2。
- **涉及**：`train_detector.py` 导出逻辑、新增 `verify_models.py`。
- **步骤**：在锁定 Ultralytics 版本验证 `format="litert"` 或 `format="tflite"`；导出 float16 优先；固定图片比对输出形状、四点顺序、分数；记录模型 hash。
- **验收/证据**：Android/LiteRT 能加载；输出与 Python 推理一致；无 NaN/越界；模型文件可追溯。

#### T2.4 检测评估

- **目标**：确认检测器在真实照片、遮挡、大小王、两副牌场景下可用。
- **依赖**：T2.3、T1.5。
- **涉及**：新增 `evaluate.py`、检测报告。
- **步骤**：按场景/光照/遮挡分层；统计可见牌角 recall、误检/图、IoU、漏检原因；单独统计大小王和重叠牌。
- **验收/证据**：合成 test 与独立 `real-test` 均达标；发布判定只认 `real-test`。命中率/误检/IoU 按角度、透视、遮挡、光照分层；失败样本进入主动学习队列；报告含样本数量和置信区间。合成 test 不能替代真实 test。

#### T2.5 阈值、NMS 与输入尺寸标定

- **目标**：只在 baseline 稳定后调整检测后处理。
- **依赖**：T2.4。
- **涉及**：manifest、`CardCornerDetector` 规划接口。
- **步骤**：网格搜索 score threshold、NMS IoU、640/960；每次只改一个变量；只在独立 `calibration` 上调参，`real-test` 只评一次；记录对 recall/FP/耗时的影响。
- **验收/证据**：选定参数写入 manifest；不出现“提高阈值导致大量缺牌”；性能仍满足 M4 门槛；参数必须绑定最终部署模型 hash。

### M3 点数/花色分类器

#### T3.1 牌角裁剪与透视拉正

- **目标**：从检测/合成标签生成 96×128 的牌角训练样本。
- **依赖**：M1。
- **涉及**：新增 `prepare_crops.py`。
- **步骤**：四点排序；透视变换到 96×128；同时生成 0°/180°；校验牌角区域；过滤模糊/过小/不可见样本。
- **验收/证据**：裁剪图可人工辨认；标签与四边形对应；无系统性旋转错误；0°/180° 样本比例合理。

#### T3.2 分类器训练

- **目标**：训练 MobileNetV3Small 双头模型，输出 15 个点数 × 5 个花色。
- **依赖**：T3.1。
- **涉及**：`model_training/train.py`、`labels.py`。
- **步骤**：严格使用 54 个有效组合；`SJ/BJ` 只能配 `JOKER`；类别不均衡用 class weight；禁止水平翻转；记录混淆矩阵。
- **验收/证据**：训练/验证 loss 正常；54 类均有样本；6/9、10/J、红黑花色有专项混淆统计。

#### T3.3 校准与 TTA

- **目标**：让置信度可用于人工复核，而不是只看 softmax 最大值。
- **依赖**：T3.2。
- **涉及**：新增 `calibrate.py`、manifest 阈值。
- **步骤**：验证集 temperature scaling；计算 ECE；0°/180° 两次推理取联合概率更高者；定义 top-1 和 margin 阈值。
- **验收/证据**：ECE ≤0.05；低 margin 样本标记 `UNKNOWN`；阈值写入 manifest，Android 不硬编码。
- **模型身份约束**：温度/阈值必须与最终部署的 classifier hash 绑定；20261102 候选的 T=0.5 不能套到 20261101 资产的 T=2.0；真实 calibration 未建立前，ECE 只能算合成域证据。

#### T3.4 分类器导出

- **目标**：导出 float16/INT8 分类器并验证精度。
- **依赖**：T3.3。
- **涉及**：`train.py` 导出逻辑、`verify_models.py`。
- **步骤**：float16 优先；INT8 用真实校正集做代表样本校准；对比 float32/float16/INT8 的 exact 和混淆；记录模型 hash。
- **验收/证据**：LiteRT 输入/输出形状正确；量化后 exact 下降在可接受范围；大小王和红黑花色不退化。

#### T3.5 分类评估

- **目标**：确认真实留出集上的 exact card 和 Joker 指标。
- **依赖**：T3.4。
- **涉及**：`evaluate.py`、分类报告。
- **步骤**：按牌面样式/光照/角度/遮挡分层；输出 exact、Joker、ECE、未知率、混淆矩阵；单独检查 6/9、10/J。
- **验收/证据**：exact ≥99.5%；Joker ≥99%；ECE ≤0.05；失败样本进入主动学习队列。
- **2026-11-02 证据边界**：T3.5c 的 exact 1.0 / 2,674 crop 全部来自合成 test，只证明遮挡标签修复后的合成域回归；**不能**当作真实留出集证据，T5.3 不得因此通过。真实逐牌角 test + `photo-visibility` 签核完成前，本任务只能算“合成回归通过”，发布判定保持未满足。

### M4 Android 灰度

#### T4.1 LiteRT 依赖、资产与 manifest

- **目标**：模型资产可加载、可校验、可回退。
- **依赖**：M0、T2.3、T3.4。
- **涉及**：`app/build.gradle.kts`、`app/src/main/assets/models/`（新增）、`ModelManifest.kt`。
- **步骤**：加入 LiteRT 2.3.0；放置 detector/classifier/manifest；校验 SHA-256；manifest 驱动输入尺寸、标签、阈值；禁止运行时下载。
- **验收/证据**：模型缺失/损坏时安全回退；manifest 非法不崩溃；APK 资产清单可追溯。

#### T4.2 检测器适配层

- **目标**：在 Android 完成 letterbox、OBB 解码、NMS 和坐标映射。
- **依赖**：T4.1。
- **涉及**：新增 `CardCornerDetector.kt`、`CardGeometry.kt`。
- **步骤**：复用 ByteBuffer；按 manifest 读取输入；解码四点/旋转框；NMS；映射回原图；过滤过小/贴边框。
- **验收/证据**：与 Python 输出一致；几何单测通过；不创建全尺寸中间 Bitmap。

#### T4.3 分类器适配层

- **目标**：完成牌角透视拉正、0°/180° TTA 和阈值判断。
- **依赖**：T4.2、T3.5。
- **涉及**：新增 `CardCornerClassifier.kt`。
- **步骤**：按四点排序拉正到 96×128；两次推理取最大；低置信度/margin 返回 `UNKNOWN`；复用 buffer。
- **验收/证据**：同一牌角在 0°/180° 下结果一致；阈值行为与 Python 一致；无内存泄漏。

#### T4.4 Coordinator 与特征开关

- **目标**：统一调度条码、模型、启发式三条路径。
- **依赖**：T4.1、T4.2、T4.3、T0.3。
- **涉及**：`RecognitionCoordinator.kt`、`MainActivity.kt`。
- **步骤**：实现 `heuristic | learned`；默认 `heuristic`；debug 支持 shadow；模型超时/异常回退；只有最新 token 能写 UI。
- **验收/证据**：shadow 不改变正式 UI；切换开关可复现；快速换图无旧结果覆盖。

#### T4.5 结果模型与 UI 核对

- **目标**：把裸警告升级为可展示的 `cards + issues + stats`。
- **依赖**：T4.4。
- **涉及**：`RecognitionResult`、`CardRecognizer`、UI 状态映射。
- **步骤**：区分 INFO/WARNING/ERROR；记录检测数、分类数、未知数、合并数、耗时；UI 显示待核对数量；条码命中为 INFO。
- **验收/证据**：低置信度进入人工核对；冲突不被静默修正；27 张与部分结果都能正确显示。

#### T4.6 回退与回滚

- **目标**：任何模型失败都不会阻断识别。
- **依赖**：T4.4。
- **涉及**：`RecognitionCoordinator`、manifest、设置/构建开关。
- **步骤**：覆盖模型缺失、校验失败、加载异常、超时、有效牌角过少；保留旧模型资产；模型更新可切回；条码路径始终优先。
- **验收/证据**：故障注入测试全部回退；回滚不需要发新 APK；无崩溃/ANR。

#### T4.7 桌面导出模型兼容性（手机测试策略外）

- **目标**：在电脑上确认最终导出的 detector/classifier TFLite 可加载、输入输出正确、固定样本输出有限且模型尺寸合规；手机只安装最终资产，不跑性能测试。
- **依赖**：T4.5、T4.6。
- **涉及**：`verify_models.py`、桌面 TFLite 推理、桌面报告。
- **步骤**：加载 assets；校验 shape/labels/hash/尺寸；对固定样本跑 detector+classifier；记录桌面兼容性和失败项；不得调用 adb/instrumentation。
- **验收/证据**：模型可加载、hash/形状一致、输出有限、资产 ≤15MB；桌面报告。手机 p95/内存/OOM/ANR **不在测试范围**，不得声称通过。
- **2026-11-02 状态**：原真机 runner 卡死，已停止；按新策略不再执行手机测试，T4.7 需按桌面兼容性重跑。

### M5 主动学习与发布

#### T5.1 主动学习闭环

- **目标**：只标注失败样本，不设固定的 300–500 张指标。
- **依赖**：T4.7。
- **涉及**：新增 `active_learning.py`、`annotate.py`、manifest。
- **步骤**：按低置信度、低 margin、贴边、张数不符、两副牌冲突排序；导出 crop；人工校正；合并回 manifest；下一轮训练。
- **验收/证据**：失败样本可追溯；成功样本不重复标注；case3/case4 不进入训练；每轮有 before/after 指标。
- **当前边界**：只有 calibration 2 个低 margin 候选的干跑，没有人工修正、重训和 before/after；桌面失败样本 inbox 需真实 test 后建立。

#### T5.2 模型版本化与回滚

- **目标**：每次模型更新都可追踪、可回退。
- **依赖**：T5.1。
- **涉及**：manifest、assets、发布记录。
- **步骤**：记录数据版本、训练命令、随机种子、模型 hash、评估报告；保留上一版资产；设置版本切换开关。
- **验收/证据**：任意版本可定位来源；回滚流程演练通过；manifest 与资产一致。

#### T5.3 电脑端真实 test（不做手机 shadow）

- **目标**：在电脑上评估 learned 在独立 `real-test` 上的端到端指标；heuristic 默认不变，不做手机 A/B。
- **依赖**：T5.2、外部 `real-test`。
- **涉及**：桌面 e2e 预测、`evaluate.py --stage e2e`、报告。
- **步骤**：用桌面 TFLite/YOLO 生成逐图预测；在 `real-test` 上统计每张 exact、27 张全对、复核率、自信错误；`heuristic` 仍为默认。
- **验收/证据**：端到端每张 ≥99.5%；27 张全对 ≥85% 起步、≥90% 发布；复核率 ≤5%；自信错误 ≤1%。**手机 heuristic shadow/A-B 不在测试范围；没有电脑可运行的 heuristic baseline，不能声称 A/B。**
- **发布证据量（建议）**：`real-test` ≥600 张真实牌，且 ≥30 个完整 27 张场景；逐牌角 `photo-visibility` 签核；报告给置信区间。样本不足不得判 `PASS`。
- **2026-11-02 状态**：未建立 `real-test`，未跑桌面 e2e，无 `release-qa`；当前为 `NOT_RUN`。

#### T5.4 文档、CI 与发布材料

- **目标**：让后续维护者能复现训练和验收。
- **依赖**：T5.3。
- **涉及**：`model_training/README.md`、`doc/local_engine.md`、`harness/state.md`、NOTICE。
- **步骤**：补训练/导出/评估命令；CI 加轻量几何/标签/manifest 校验；记录许可和 NOTICE；更新当前状态。
- **验收/证据**：新环境按文档可复现；CI 通过；许可清单完整；状态文档无过期描述。

### 横切任务

#### X1 许可与来源审查

- **目标**：数据、代码、预训练权重、最终模型四层许可清晰。
- **依赖**：贯穿 M1–M5。
- **涉及**：NOTICE、发布清单（`licenses.json` 已删除）。
- **验收/证据**：来源与许可字段登记完整；Ultralytics AGPL 不随 APK 分发；不因许可阻断训练或发布。

#### X2 隐私与离线约束

- **目标**：图片不上传，模型不联网下载。
- **依赖**：贯穿 M4–M5。
- **涉及**：网络层、assets、隐私说明。
- **验收/证据**：电脑端 `offline_audit.py` 静态扫描通过；识别路径无网络请求；模型只从 assets 加载；日志不记录图片内容。
- **当前策略**：所有测试电脑；静态审计是唯一门禁，手机运行期禁网不测；运行期离线为未验证风险。

#### X3 状态与决策记录

- **目标**：只记录恢复工作所需的信息。
- **依赖**：贯穿全部里程碑。
- **涉及**：`harness/state.md`、必要时 `harness/DECISIONS.md`。
- **验收/证据**：完成一个里程碑更新一次 state；只有架构/规则决策才追加 DECISIONS；不写流水账。

## 3. 验收门槛

| 指标 | 门槛 | 适用里程碑 |
| --- | --- | --- |
| 可见牌角检测 recall（合成 + 真实） | ≥99% | M2 |
| 检测误检 | ≤0.1/图 | M2 |
| 角点多边形 IoU | ≥0.8 | M2 |
| 分类 exact card（真实 test） | ≥99.5% | M3 |
| 大小王分类 | ≥99% | M3 |
| ECE | ≤0.05 | M3 |
| 真实 test 证据量 | ≥600 张真实牌 + ≥30 个完整 27 张场景；逐牌角签核 | M5 |
| 端到端每张 exact（真实 test） | ≥99.5% | M5 |
| 27 张全对 | ≥85% 起步；≥90% 发布 | M5 |
| 人工复核率 | ≤5% | M5 |
| 自信错误率 | ≤1% | M5 |
| 手机 p95 / 峰值内存 | 不在测试范围（只电脑测试） | — |
| 模型资产 | ≤15MB | M4 |
| 稳定性 | 无 OOM/ANR；连续 20 次无旧结果覆盖 | M0/M4 |
| case3 | 27/27 | M0/M5 |
| case4 可见牌 | exact ≥95% | M0/M5 |
| 许可 | 发布产物中 unknown = 0 | X1 |

以上门槛是建议值，必须在电脑上用独立真实照片标定；手机只安装最终资产，不跑测试。未达标时模型保持 shadow，默认仍走启发式路径。

`real-test` 的 ≥600 张牌 / ≥30 个完整 27 张场景是发布证据量下限建议（0 错误时 95% 上限约 3/600=0.5%），不是训练标注量；样本不足时必须报告置信区间并判 `NOT_RUN`，不得用 9 张 golden、合成 test 或 `generate_synthetic.py` 自动生成的集合代替。`case3.png` 已按用户决定删除；旧 `case3 27/27` 不再是可执行门槛，改用完整 27 张场景的真实 test。

## 4. 完成定义

一个任务只有同时满足以下条件才算完成：

- 代码/脚本已提交，接口与文档一致；
- 有对应单测、instrumentation 或评估脚本；
- 关键指标已记录，且与基线对比；
- 没有引入旧结果覆盖、OOM、ANR 或许可问题；
- 相关文档和 `harness/state.md` 已更新；
- 不是“只放了一个模型文件”或“只看合成验证集”。

每个 gate 记录：commit、数据 manifest hash、模型 hash、设备型号、系统版本、运行命令、指标和失败样本。

## 5. 不做的事

- 不训练 54 类检测器；检测器只学 `card_corner`。
- 不直接把公开 52 类权重上线。
- 不从零人工标 300–500 个牌角；真实照片只做失败样本校正。
- 不使用许可不明的数据或权重。
- 不把模型结果静默补齐，不猜完全遮挡的牌。
- 不用 `take(27)` 代替置信度过滤。
- 不在模型未过门槛时删除 ML Kit/模板路径。
- 不引入云端识别或图片上传。

## 6. 建议执行顺序

1. T0.1 → T0.2 → T0.3，先消除 OOM/ANR 和旧结果覆盖。
2. T0.4 → T0.5，建立条码回归和 case3/case4 基线。
3. T1.1 → T1.5，冻结数据与许可。
4. T2.1–T2.5 与 T3.1–T3.5 并行，分别完成检测器和分类器。
5. T4.1–T4.7，先 shadow，不切默认。
6. T5.1–T5.4，按真实差异做主动学习，达标后再灰度发布。

## 7. 自动化执行规范

本节依据 `doc/local_engine.md` §5.9–§5.10、§6、§7，将所有任务落实为可执行的自动化接口。保留现有 T0–T5、X1–X3 编号、依赖关系和验收阈值。

### 7.1 基本要求

1. 每项任务必须有稳定的 CLI 或自动化测试入口，不依赖 IDE 点击、交互式输入、人工复制文件或未记录的本地环境状态。
2. 所有路径允许显式传入；相对路径一律相对于仓库根目录解析，不依赖当前工作目录。
3. 每次运行写入独立的 `output/runs/<run_id>/<task_id>/`，不得覆盖已有运行结果。
4. 每项任务至少输出机器可读 `report.json`、原始日志、必要的逐样本结果和输入/输出 SHA-256。
5. Python 脚本必须提供 `--help`、`--version`、`--repo-root`、`--output-dir`、`--run-id`。训练、合成等随机任务必须显式提供 `--seed`。
6. 完整命令、参数、依赖版本、输入 hash、输出 hash、数据版本和随机种子必须写入报告。
7. 脚本失败必须返回非零退出码，错误写入 stderr；不得捕获异常后仍返回成功。
8. 自动化负责执行、测量、校验、归档和阻断；不能代替人工确认真实照片可见性、Golden 标签、数据许可或最终发布批准。
9. 本节命令定义为必须实现的稳定 CLI 接口。脚本或测试尚未实现时，对应任务只能记为 `NOT_RUN`，不能因为文档中存在命令就算完成。

### 7.2 统一 CLI 与退出码

| 参数/行为 | 约定 |
| --- | --- |
| `--repo-root` | 仓库根目录；默认从脚本位置定位 |
| `--output-dir` | 本次独立运行目录；目录冲突时拒绝覆盖 |
| `--run-id` | 唯一执行标识，贯穿父子任务报告 |
| `--seed` | 随机种子；训练和合成任务必填并记录 |
| `--config` | 配置文件；记录文件 hash 和展开后的参数 |
| `--input` / `--manifest` / `--model` | 输入路径和 SHA-256 必须写入报告 |
| `--strict` | 严格门禁；缺字段、样本不足、非法数值、缺签核不得通过 |
| `--json` | 报告路径；默认写入本次运行目录的 `report.json` |

退出码统一为：

| 退出码 | 含义 | 报告状态 |
| --- | --- | --- |
| `0` | 任务真实执行且所有适用检查通过 | `PASS` |
| `1` | 测试、评估或指标未通过 | `FAIL` |
| `2` | 参数、配置、输入数据或格式错误 | `FAIL` |
| `3` | 外部依赖、设备 runner 或执行环境不可用 | `NOT_RUN` 或 `BLOCKED`，必须说明原因 |
| `4` | 必需人工签核缺失、无效或被拒绝 | `BLOCKED` |

退出码不能代替报告状态。例如设备没有连接时，即使脚本成功生成诊断报告，设备测试仍然是 `NOT_RUN`。

### 7.3 统一 report.json

每项任务至少输出以下字段；子任务可以增加字段，但不能删除必需字段：

```json
{
  "schema_version": 1,
  "run_id": "unique-run-id",
  "task_id": "T0.1",
  "status": "PASS",
  "started_at": "ISO-8601",
  "finished_at": "ISO-8601",
  "command": "完整命令行",
  "parameters": {},
  "environment": {"os": "", "python": "", "java": "", "gradle": "", "dependencies": {}},
  "inputs": [{"path": "", "sha256": ""}],
  "dataset": {"version": "", "manifest_sha256": "", "split": "", "sample_count": 0},
  "seed": null,
  "outputs": [{"path": "", "sha256": ""}],
  "metrics": {},
  "checks": [],
  "approvals": [],
  "device": null,
  "warnings": [],
  "errors": []
}
```

状态语义：

- `PASS`：要求的测试真实执行，样本数足够，所有适用指标通过，输入输出 hash 完整，必需签核有效。
- `FAIL`：测试已执行但出现失败、回归或指标未达标；不得通过降低样本数或删除失败样本制造通过。
- `NOT_RUN`：测试没有实际执行，例如没有可用的预测文件、桌面运行环境不完整或外部输入缺失；必须说明原因。
- `BLOCKED`：存在尚未解决的前置条件，例如许可未知、Golden 标签未签核或人工修正未审批。

缺少必需字段、NaN/Infinity、空数据集、样本数不足、manifest 与模型不匹配、hash 不一致或报告缺字段，一律不得判为 `PASS`。父级报告必须保留全部子任务状态，不得只汇总成功项。

### 7.4 每项任务的自动化命令与证据

以下命令从仓库根目录执行。`RUN_DIR` 是本任务独立运行目录；`SEED` 必须由调用方显式提供。命令中尚未存在的脚本、测试类均为待实现交付物，不得视为当前已有功能。

#### M0 管线安全与基线

| 任务 | 自动化执行入口 | 必须归档的证据和判定 |
| --- | --- | --- |
| T0.1 | `./gradlew :app:testDebugUnitTest --tests '*ImagePreprocessorTest'` | 尺寸纯函数、EXIF、旋转、颜色测试；750×492、1440×1920、2736×3648 输入输出尺寸。长边不超过 1600px，小图不放大。 |
| T0.2 | `./gradlew :app:testDebugUnitTest --tests '*RecognitionWorkerTest' --tests '*ImageMemoryTest'` | 后台线程断言、Bitmap 生命周期、按需 pass 数、阶段耗时和内存报告。只做 JVM 测试；手机 OOM/内存不测，T4.7 只做桌面兼容性。 |
| T0.3 | `./gradlew :app:testDebugUnitTest --tests '*RecognitionCoordinatorTest'` | 注入可控延迟，测试连续导入、随机、联网、取消、超时和 Activity 销毁；断言只有最新 jobToken 可以更新 UI。 |
| T0.4 | `./gradlew :app:testDebugUnitTest --tests '*VisualCardCodecTest'` | 生成 27 张图并往返 100 次；归档缩放、JPEG、裁切变体的命中率与误命中率。仅对明确支持的变体提出通过要求。 |
| T0.5 | `python model_training/golden.py prepare --cases case3,case4 --output-dir RUN_DIR`；`python model_training/evaluate.py --engine heuristic --split golden --manifest model_training/datasets/golden_manifest.json --output-dir RUN_DIR/evaluation` | expected JSON、逐样本预测、exact、张数、性能和失败差异报告。Golden 真值和可见牌角必须有人工签核；缺签核为 `BLOCKED`。case3/case4 不得进入训练或调参。 |

T0.5 的审核记录必须包含图片 SHA-256、scene_id、可见牌角清单、完全遮挡项、审核人、审核时间和审核版本。程序只能检查记录完整且 hash 一致，不得自动批准照片真值。

#### M1 数据自动化

| 任务 | 自动化执行入口 | 必须归档的证据和判定 |
| --- | --- | --- |
| T1.1 | `python model_training/fetch_public_data.py --sources model_training/datasets/sources.json --output-dir RUN_DIR` | 来源、URL、版本、下载文件 hash、许可和用途限制报告。许可只登记不阻断。下载不完整或 hash 不匹配为 `FAIL`。 |
| T1.2 | `python model_training/convert_public_annotations.py --config model_training/configs/public_conversion.yaml --input RUN_DIR/raw --output-dir RUN_DIR/converted` | 标签转换统计、映射表、非法样本列表、四点/OBB 标签和抽样可视化。自动检查点序、越界、重复、54 个有效组合和 Joker 映射；需要人工视觉确认但未签核时为 `BLOCKED`。 |
| T1.3 | `python model_training/generate_synthetic.py --cards app/src/main/assets --backgrounds model_training/datasets/backgrounds --count 20000 --seed SEED --output-dir RUN_DIR/synthetic` | 合成图片、seed、scene_id、渲染参数、实例标签、四点/牌角标签及 hash。验证复现性、两副牌重复实例、1–27 张场景、负样本和大小王覆盖。背景许可不明为 `BLOCKED`。 |
| T1.4 | `python model_training/validate_dataset.py --manifest model_training/datasets/dataset_manifest.json --check-files --check-hashes --check-labels --check-classes --check-scene-splits --check-leakage --output-dir RUN_DIR` | split 统计、54 类覆盖、scene_id 交集、重复图片 hash、派生关系、两副牌实例统计和错误样本列表。按 scene_id 分组后划分 train/val/calibration/test；数据泄漏为 `FAIL`；许可问题不再阻断。 |
| T1.5 | `python model_training/validate_dataset.py --manifest model_training/datasets/dataset_manifest.json --audit --check-golden-exclusion --output-dir RUN_DIR`；`python model_training/gate.py --task T1.5 --require-approval golden-definition --report RUN_DIR/report.json` | 类别分布、遮挡比例、背景、重复图、标签越界、case4 等 golden 图排除情况、manifest hash 和冻结数据版本。Golden 签核缺失或版本不一致为 `BLOCKED`；许可只登记不阻断。 |

#### M2 牌角检测器

| 任务 | 自动化执行入口 | 必须归档的证据和判定 |
| --- | --- | --- |
| T2.1 | `python model_training/train_detector.py --mode baseline --manifest model_training/datasets/dataset_manifest.json --seed SEED --config model_training/configs/detector.yaml --output-dir RUN_DIR` | baseline 权重、训练日志、配置、环境版本、模型 hash、OBB 输出样本及 recall/FP/IoU 指标；仅为 baseline，不得上线。 |
| T2.2 | `python model_training/train_detector.py --mode train --manifest model_training/datasets/dataset_manifest.json --config model_training/configs/detector.yaml --seed SEED --output-dir RUN_DIR` | best/last 权重、训练曲线、逐 epoch 指标、增强参数、数据版本和 hash。必须经过 T1.5 数据门禁；不得把验证集指标冒充独立 test。 |
| T2.3 | `python model_training/train_detector.py --mode export --weights RUN_DIR/best.pt --format litert --output-dir RUN_DIR/export`；`python model_training/verify_models.py --model RUN_DIR/export/card_corner.tflite --manifest RUN_DIR/export/model_manifest.json --check-shape --check-labels --check-hash --output-dir RUN_DIR/verify` | 导出文件、Tensor 形状、OBB 四点顺序、Python/导出固定样本差异、NaN/越界检查和模型 hash。当前依赖不支持导出时标记 `NOT_RUN` 或 `FAIL`，不得静默换格式。 |
| T2.4 | `python model_training/evaluate.py --stage detector --model RUN_DIR/export/card_corner.tflite --manifest model_training/datasets/dataset_manifest.json --split test --output-dir RUN_DIR/evaluation` | 逐图片/逐牌角 TP/FP/FN、recall、误检/图、角点 IoU、样本数、置信区间和失败样本。按光照、遮挡、背景分层；使用独立 test。 |
| T2.5 | `python model_training/calibrate.py --target detector-postprocess --model RUN_DIR/best.pt --split calibration --config model_training/configs/detector_grid.yaml --output-dir RUN_DIR/calibration` | threshold、NMS IoU、640/960 对照指标表。只允许使用 calibration split 调参；参数冻结后再运行 test，不得查看 test 结果后继续调参。 |

#### M3 点数/花色分类器

| 任务 | 自动化执行入口 | 必须归档的证据和判定 |
| --- | --- | --- |
| T3.1 | `python model_training/prepare_crops.py --manifest model_training/datasets/dataset_manifest.json --size 96 128 --rotations 0 180 --output-dir RUN_DIR/crops` | crop、源图 hash、scene_id、quad、rank/suit、旋转版本、过滤原因和抽样预览。自动检查透视点序和标签继承；真实照片可见性未签核时为 `BLOCKED`。 |
| T3.2 | `python model_training/train.py --mode train --manifest model_training/datasets/dataset_manifest.json --crops RUN_DIR/crops --config model_training/configs/classifier.yaml --seed SEED --output-dir RUN_DIR` | 模型、训练历史、标签顺序、54 个有效组合覆盖、class weight、混淆矩阵和 hash。SJ/BJ 只能对应 JOKER；非法 15×5 组合必须在数据加载时拒绝。 |
| T3.3 | `python model_training/calibrate.py --target classifier --model RUN_DIR/best.keras --split calibration --metrics ece,brier --output-dir RUN_DIR/calibration` | 温度参数、top-1/margin 阈值、ECE、Brier 和可靠性数据。只使用独立 calibration split；阈值写入 manifest，Android 不硬编码。 |
| T3.4 | `python model_training/train.py --mode export --weights RUN_DIR/best.keras --formats float16 --output-dir RUN_DIR/export`；`python model_training/verify_models.py --model RUN_DIR/export/classifier.tflite --manifest RUN_DIR/export/model_manifest.json --check-shape --check-labels --check-hash --output-dir RUN_DIR/verify` | float32/float16（实际启用时另含 INT8）固定样本差异、输入输出形状、量化校准数据 hash、模型大小及模型 hash。无真实校正集时不得将 INT8 标记为发布候选。 |
| T3.5 | `python model_training/evaluate.py --stage classifier --model RUN_DIR/export/classifier.tflite --manifest model_training/datasets/dataset_manifest.json --split test --output-dir RUN_DIR/evaluation` | 逐样本 exact、rank/suit accuracy、Joker accuracy、ECE、未知率、混淆矩阵及 6/9、10/J、红黑花色专项统计。发布准确率必须来自独立真实 test。 |

T2.4/T3.5 表中的 `--split test` 结果仅作合成回归；发布前必须用 `model_training/datasets/real_test_manifest.json --split real-test` 重新执行检测/分类评估，并保存独立报告。

#### M4 Android 灰度

| 任务 | 自动化执行入口 | 必须归档的证据和判定 |
| --- | --- | --- |
| T4.1 | `python model_training/verify_models.py --assets app/src/main/assets/models --manifest app/src/main/assets/models/model_manifest.json --check-shape --check-labels --check-hash --output-dir RUN_DIR`；`./gradlew :app:testDebugUnitTest --tests '*ModelManifestTest'` | LiteRT 依赖锁定、APK asset 清单、manifest schema、SHA-256、缺失/损坏资产回退测试。安装脚本先 staging、hash 校验，再原子替换，并保留上一版资产。 |
| T4.2 | `./gradlew :app:testDebugUnitTest --tests '*CardGeometryTest' --tests '*CardCornerDetectorTest'` | letterbox、OBB 解码、NMS、四点排序、逆坐标映射和 ByteBuffer 复用。只做 JVM 测试；手机推理不测。 |
| T4.3 | `./gradlew :app:testDebugUnitTest --tests '*CardCornerClassifierTest' --tests '*CardGeometryTest'` | 0°/180° 一致性、透视拉正、置信度/margin 阈值、UNKNOWN 输出和 buffer 复用。只做 JVM 测试；手机推理不测。 |
| T4.4 | `./gradlew :app:testDebugUnitTest --tests '*RecognitionCoordinatorTest'` | heuristic/learned/shadow 模式、默认开关、token 竞态、模型超时和异常回退；shadow 不得写正式 UI。只做 JVM 测试。 |
| T4.5 | `./gradlew :app:testDebugUnitTest --tests '*RecognitionResultTest' --tests '*RecognitionStateTest'` | cards/issues/stats schema、INFO/WARNING/ERROR 映射、未知牌和冲突展示、实际阶段计数及 27 张/部分结果状态测试。 |
| T4.6 | `./gradlew :app:testDebugUnitTest --tests '*RecognitionFallbackTest'` | 模型缺失、hash 错误、manifest 非法、加载异常、超时、检测数不足、回滚和旧资产恢复测试。任一模型故障导致崩溃或阻断 heuristic 为 `FAIL`。只做 JVM 测试。 |
| T4.7 | `python model_training/verify_models.py --task T4.7 --assets app/src/main/assets/models --check-shape --check-labels --check-hash --output-dir RUN_DIR` | 桌面加载最终 TFLite、shape/labels/hash、固定样本输出有限、模型尺寸；手机 p95/内存/OOM/ANR 不在测试范围，不得声称通过。 |

T4.7 只检查桌面加载/形状/hash/输出有限性和模型尺寸；不调用 adb、不跑 instrumentation、不采手机性能数据。桌面报告绑定模型 hash 与 assets 清单。

#### M5 主动学习与发布

| 任务 | 自动化执行入口 | 必须归档的证据和判定 |
| --- | --- | --- |
| T5.1 | `python model_training/active_learning.py --model RUN_DIR/model.tflite --manifest model_training/datasets/dataset_manifest.json --input model_training/inbox --sort-by confidence,margin,border,count,duplicate --output-dir RUN_DIR/queue`；审核后 `python model_training/import_corrections.py --annotations RUN_DIR/approved/annotations.json --manifest model_training/datasets/dataset_manifest.json --output-dir RUN_DIR/import` | 低置信候选、crop、源图片 hash、模型版本、修正前后标签和导入记录。人工修正必须带审核人、时间和样本 hash；未审批修正不得进入训练。case3/case4 永远不得导入 train。 |
| T5.2 | `python model_training/package_model.py --model RUN_DIR/model.tflite --manifest RUN_DIR/model_manifest.json --previous app/src/main/assets/models --output-dir RUN_DIR/package`；`python model_training/verify_models.py --assets RUN_DIR/package --check-hash --check-manifest --output-dir RUN_DIR/verify`；`python model_training/rollback_test.py --current RUN_DIR/package --previous app/src/main/assets/models/previous --output-dir RUN_DIR/rollback` | 版本清单、数据/模型 hash、训练命令、seed、依赖版本、评估报告引用、上一版本备份和回滚演练。manifest 与资产不一致为 `FAIL`。 |
| T5.3 | `python model_training/evaluate.py --stage e2e --mode shadow --model RUN_DIR/model.tflite --split real-test --manifest model_training/datasets/real_test_manifest.json --predictions RUN_DIR/predictions.jsonl --output-dir RUN_DIR/shadow`；`python model_training/gate.py --task T5.3 --milestone M5 --report RUN_DIR/shadow/report.json --require-approval release-qa` | 电脑端 learned 逐张 exact、27 张全对率、复核率、自信错误率、失败样本和真实 test 样本数；不做手机 heuristic A/B。test 不可用于调参；T5.3 不再是默认启用门禁（ADR-0044），但未完成时仍须如实标记。 |
| T5.4 | `python model_training/validate_docs.py --check-commands --check-task-coverage --check-links --output-dir RUN_DIR`；`python model_training/gate.py --task T5.4 --milestone M5 --ci-report RUN_DIR/ci/report.json --output-dir RUN_DIR/gate`；`./gradlew test` | README/NOTICE、CI 配置、文档链接、任务覆盖率、训练/导出/评估复现命令、Gradle 与 Python 检查报告。每个任务 ID 必须出现在任务映射表中，入口必须可由 CLI `--help` 或静态检查验证。 |

#### 横切任务

| 任务 | 自动化执行入口 | 必须归档的证据和判定 |
| --- | --- | --- |
| X1 许可与来源审查 | `python model_training/license_audit.py --sources model_training/datasets/sources.json --artifacts RUN_DIR/package --output-dir RUN_DIR/license-audit` | 数据、代码、预训练权重、最终模型逐层记录来源、版本、许可、NOTICE、分发限制和 hash。许可只登记，不阻断训练/发布。 |
| X2 隐私与离线约束 | `python model_training/offline_audit.py --apk app/build/outputs/apk/debug/app-debug.apk --source app/src/main --assets app/src/main/assets/models --output-dir RUN_DIR` | 电脑端静态扫描网络调用/模型下载；检查图片未上传、日志未包含图片内容、模型只从 assets 加载。手机运行期禁网不测，为未验证风险。 |
| X3 状态与决策记录 | `python model_training/gate.py --milestone all --reports output/runs --state harness/state.md --decisions harness/DECISIONS.md --output-dir RUN_DIR` | 汇总任务状态、依赖链、报告/hash、未完成项和阻断原因；生成 state 更新建议。不得覆盖既有决策，不得把 `NOT_RUN`/`BLOCKED` 自动转成完成。架构决策须经人工批准后才追加 DECISIONS。 |

### 7.5 数据集划分、真实性与防泄漏

1. 正式训练 split 固定为 `train`、`val`、`calibration`、`test`；`real-test` 是独立真实照片评估清单，不参与训练、校准或阈值选择，不能与 test/golden 混用。golden 使用独立 manifest 或独立标识。先按 `scene_id` 分组再划分，同一照片、连拍、增强图、裁剪图及同一合成场景的派生图不得跨集合。
2. 每个输入记录 SHA-256；同图异名、重复文件、裁剪派生关系和 scene_id 均纳入泄漏检查。来源关系不明的样本不得默认视为独立样本。
3. 训练只读 train；早停和模型选择只读 val；温度、阈值、NMS 和后处理标定只读 calibration；最终指标只读 test；golden 只用于固定回归，不得参与训练、校准和反复调参。
4. test/golden 真值文件必须与训练入口隔离。`evaluate.py` 记录阶段、split、manifest hash；检测到 test/golden 被用于训练或 calibration 时立即 `BLOCKED`。
5. `license=unknown`、来源缺失、许可不在白名单、图片 hash 缺失或签核失效的样本不得进入正式训练。下载脚本可以登记许可资料，不能自动批准许可证。
6. 合成数据必须记录 seed、scene_id、牌面素材版本、背景许可、布局和增强参数。同 seed、相同配置及素材版本应能复现图像标签；seed 缺失为 `FAIL`。
7. 评估报告必须保留逐样本预测和失败项，不能在统计前删除 UNKNOWN、漏检、无牌照片或困难样本。
8. `real-test` 必须记录 `source=photo`、scene_id、图片/逐牌角 hash、可见性签核和真值；`evaluate.py --split real-test` 只读该清单。没有 `real-test` 时，T2.4/T3.5/T5.3 只能记 `NOT_RUN`，不能拿合成 test 或 golden 代替。

### 7.6 人工签核接口与阻断条件

以下工作不能由程序代签，但必须转换为结构化记录供门禁校验：

| 签核类型 | 对应任务 | 必需字段 | 缺失时状态 |
| --- | --- | --- | --- |
| 数据许可审核 | T1.1、T1.5、X1 | source_id、许可结论、用途范围、审核人、时间、记录版本 | `BLOCKED` |
| Golden 真值确认 | T0.5、T1.5 | 图片 hash、scene_id、可见牌角及真值、遮挡说明、审核人、时间 | `BLOCKED` |
| 真实照片可见性确认 | T1.2、T3.1、T5.1 | 原图 hash、crop hash、四点坐标、可见性、审核结论、审核人、时间 | `BLOCKED` |
| 人工修正批准 | T5.1 | 修正前后标签、来源样本 hash、审核人、审核时间 | `BLOCKED` |
| 手机运行期证据 | 不适用（桌面测试策略） | 无；手机只安装最终资产 | — |
| 最终发布 QA | T5.3、T5.4 | 候选版本、test 报告、设备报告、许可报告、回滚结果、批准人、时间 | `BLOCKED` |

签核文件统一放在 `model_training/approvals/`，采用 JSON schema 校验。每条签核绑定数据/模型/样本 hash 与版本；签核撤销、过期或 hash 不一致时门禁失效。自动化只检查签核存在、格式和绑定关系，不伪造签名、不自动勾选批准。

### 7.7 CI 与设备 Runner 分层

| 流水线 | 自动执行 | 不能代替的内容 |
| --- | --- | --- |
| Pull Request | Python lint/type check、CLI/schema、标签与几何单测、manifest 校验、数据泄漏检查、模型形状冒烟、Gradle JVM 单测、离线静态扫描 | 真实照片最终准确率、手机运行期性能（未验证）、许可批准、golden 真值签核 |
| Nightly | 固定 seed 合成小数据、训练冒烟、完整 golden 回归、模型导出、Python/Android 固定样本一致性、故障注入 | 手机不跑测试；合成验证不能代替真实 test |
| Manual/Release | 完整训练和导出、电脑端独立真实 test、发布 gate、许可和签核核验 | 人工签核须由指定审核人完成 |
| 手机运行期 | 不执行（桌面测试策略） | 手机只安装最终资产；性能/禁网为未验证风险 |

每个 CI job 必须上传 `report.json`、原始日志、测试结果、输入/输出 hash 和 runner 环境。Nightly 失败不能被后续成功 job 覆盖；Release gate 必须引用确定的 run_id 和报告 hash。

### 7.8 自动化完成定义与总门禁

任务只有同时满足以下条件才能标记完成：

- 对应 CLI 入口已实现，`--help` 可运行，参数和退出码符合 §7.2；
- 自动化测试/评估真实执行，原始结果、`report.json` 和输入输出 hash 已归档；
- 适用指标达到原任务验收门槛，样本数足够且 split 正确；
- 依赖任务为 `PASS`，适用许可、golden、真实照片和发布签核有效；
- 手机运行期不纳入门禁；桌面任务引用模型/资产 hash 与电脑端真实 test 报告；
- 代码、配置、文档和 `harness/state.md` 与实现一致。

每项任务状态必须明确标为 `PASS`、`FAIL`、`NOT_RUN` 或 `BLOCKED`。不得只用勾选框表示，也不能把“脚本存在”或“命令启动成功”算作完成。

最终汇总命令：

```bash
python model_training/gate.py \
  --task-manifest model_training/task_manifest.json \
  --reports output/runs \
  --require-all-tasks \
  --require-artifact-hashes \
  --require-valid-approvals \
  --require-approval photo-visibility \
  --require-approval release-qa \
  --output-dir output/runs/release-gate
```

该命令必须按依赖关系汇总 T0.1–T5.4 和 X1–X3，检查每项任务是否有可执行入口、执行报告、样本数、指标、版本/hash、设备证据和签核。没有真实 API 29 证据时不得通过 M4/M5 发布门禁；许可或人工批准缺失时为 `BLOCKED`；测试未实际运行时为 `NOT_RUN`；指标失败时为 `FAIL`。全部必需门禁通过后才允许生成发布候选，不能自动改变 learned/heuristic 默认开关。

## 8. 发布差距评估（2026-11-02）

### 8.1 结论

截至 2026-11-02 的评估不具备把 learned 设为默认的条件；2026-11-03 用户已取消该默认门禁（ADR-0044），App 默认 learned 并保留 heuristic 回退。T5.3 仍未完成，不能因此把质量结论写成通过。

缺口不是“再调一个阈值”：

- `model_training/datasets/dataset_manifest.json` 的 2,000 张图全部 `source=synthetic`，`photo=0`；没有 `real-test` split。
- T2.4 的 recall 0.9911 来自 224 张合成 test；真实照片 9 张 / 242 牌在放宽阈值下最好 **84/242（R 0.347 / P 0.357 / F1 0.352）**，现行 App 阈值 **35/242（R 0.145）**。
- T3.5c 的 exact 1.0 来自 2,674 个合成 crop；没有真实逐牌角 test，也没有 `photo-visibility` 签核。
- T4.7 手机 runner 已停止；X2 只有电脑静态审计；T5.3 无电脑端 e2e、无 `release-qa`。
- X3 的 FAIL 是这些未完成项的真实汇总，不是统计错误。

### 8.2 当前证据 vs 发布门槛

| 门槛 | 要求 | 当前证据 | 判定 |
| --- | --- | --- | --- |
| 检测 recall / 误检 / IoU | 真实 test 也达到 99% / 0.1 每图 / 0.8 | T2.4 只在 224 张合成 test 上 0.9911 / 0.0179 / 0.9501；真实检测无独立报告 | **未证明** |
| 分类 exact / Joker / ECE | 真实 test exact ≥99.5%、Joker ≥99%、ECE ≤0.05 | T3.5c 2,674 个合成 crop：1.0 / 1.0 / 0.0121 | **仅合成域通过** |
| 端到端每张 exact | ≥99.5% | 真实照片最好 84/242 匹配（F1 0.352）；现行阈值 35/242 | **未达标** |
| 27 张全对 | 发布 ≥90% | 9 张 golden / 242 牌，无完整 27 张真实 shadow 报告 | **未测** |
| 人工复核率 | ≤5% | 无真实 shadow 报告 | **未测** |
| 自信错误率 | ≤1% | 无真实 shadow 报告 | **未测** |
| 手机 p95 / 峰值内存 | 不在测试范围（只电脑测试） | 无手机测试 | **未验证风险** |
| 模型资产 | ≤15MB | 现 assets 合计 12.8MB | **PASS（非最终候选）** |
| 手机运行期 | 不执行 instrumentation/性能/禁网 | 手机只安装最终资产 | **策略外** |
| 运行期离线 | 手机不测；电脑静态审计 | 静态扫描通过 | **未验证风险** |
| 发布 QA | `release-qa` 绑定候选/test/device/license/rollback | `model_training/approvals/` 只有 `golden-definition.json` | **BLOCKED** |
| 发布证据量 | ≥600 张真实牌 + ≥30 个完整 27 张场景（外部提供） | 9 张 golden / 242 牌，且 golden 只做回归 | **不足/外部前置** |

### 8.3 自动化边界

按“所有建议都必须可自动化”的前提，把工作分三类：

| 类型 | 工作 | 结论 |
| --- | --- | --- |
| A 纯自动化 | 合成验收集；模型评估/校准；T4.7 桌面兼容；T5.1 队列/导入/重训；T5.3 电脑端真实 test；T5.2 打包/回滚；X2 静态审计；文档校验 | 可由脚本/CI 完成 |
| B 自动化执行但依赖外部输入 | 真实 test 上的检测/分类评估；真实失败样本重训；真机性能、禁网测试 | 代码可自动执行；缺真实数据/设备/签核时只能 `BLOCKED`/`NOT_RUN` |
| C 不可自动化 | 拍摄 600 张真实牌/30 个完整场景、逐牌角真值/可见性确认、`photo-visibility`/`release-qa` 签核、USB 设备接入 | 不能写成 agent 任务；只能由人或运维提供 |

**结论**：原来的“建立独立 real-test（600 张真实牌、30 个场景）”不是自动化任务，是 C 类外部前置。可以自动生成同规模的 **synthetic acceptance set**，但它只能做回归和鲁棒性，不能当 real-test 或发布证据。若要求连真实 test 也自动生成，就必须修改 §5.10 的发布定义，不能继续声称“真实域发布”。

### 8.3.1 可下载真实数据替代（2026-11-02 搜索）

| 来源 | 规模/标注 | 可替代 | 缺口/许可 |
| --- | --- | --- | --- |
| [Roboflow 100 Poker Cards（HF 镜像）](https://huggingface.co/datasets/Francesco/poker-cards-cxcvz) | 1,285 张 640×640 真实场景，53 类 bbox | 真实桌面检测/分类预检；HF 直接下载 | 无大小王、无牌角关键点、无 27 张手牌；CC BY 4.0 |
| [TeogopK Real 100](https://github.com/TeogopK/Playing-Cards-Object-Detection) | 100 张实拍，YOLOv8 框，13 类 | 真实域检测预检；GitHub 直接下载 | 类别不全、无大小王/牌角；CC0 |
| [French Playing Cards](https://huggingface.co/datasets/drFarid/French-Playing-Cards) | 1,875 张真实单牌，52 类 imagefolder | 真实单牌分类训练候选；MIT | 无大小王/多牌/牌角真值；抽样自动检测+模板配准失败（SIFT 0 inliers），不能直接裁角 |
| [EdgeFirst Playing Cards](https://doc.edgefirst.ai/saas/datasets/playingcards/) | 1,476 张，13 类 bbox | 真实室内检测预检 | 非商业 CC BY-NC-SA 4.0；不能直接用于商业发布 |
| [The Complete Playing Card Dataset](https://www.kaggle.com/datasets/jaypradipshah/the-complete-playing-card-dataset) | 53 类、每类约 50 张单牌，VOC/COCO/YOLO | 真实单牌分类预检 | 单牌、无多牌/牌角/可见性；许可 unknown，Kaggle 凭据 |
| [Cards Image Dataset-Classification](https://www.kaggle.com/datasets/gpiosenka/cards-image-datasetclassification) | 7,624/265/265，224×224，53 类分类 | 真实单牌分类预检 | 无多牌/牌角；许可 unknown，Kaggle 凭据 |
| [Cards Corner Detection](https://universe.roboflow.com/cursedsheep-xed3h/cards-corner-detection) | 129 张牌角检测 | 仅可作牌角线索 | 页面 403，真实性/许可未核实；数量不足，不能发布 |

结论：

- **能自动下载并替代“部分真实采集”**：RF100 Poker Cards + TeogopK 可覆盖真实桌面、多牌、整牌框；Complete/gpiosenka 可覆盖真实单牌分类。它们不需要拍摄，也不需要人工重标。
- **不能替代的部分**：大小王、两副牌、完整 27 张手牌、逐牌角真值、遮挡可见性、掼蛋牌面样式。公开数据没有同时满足这些条件的数据集。
- **Corner 数据仍缺**：129 张的 Cards Corner Detection 页面不可读、许可未核实且数量太小；不能据此关闭 T2.4/T3.5 的真实牌角 test。
- **推荐用途**：RF100 + TeogopK 作为真实域 **预检/回归**；Complete/gpiosenka 作为真实分类预检；T5.3 发布判定仍需外部 54 类、多牌、牌角可见性 test，不能用这些数据冒充。

**2026-11-02 实测（T3.5r，非发布证据）**

- 已下载 RF100 test（128 图 / 544 牌）与 TeogopK real（93 图有标签 / 324 牌；仓库实有 98 图），`real_pretest.py` 生成 **868 牌 / 6,944 个候选 crop**；类别并集 51，Joker 0。
- 每张整牌框取四个 bbox 角候选；分类器只看自身置信度选择，不读真值；`0°/180°` TTA，已部署 `classifier.tflite`（T=2.0）。
- 结果：exact **0.104839（91/868）**，RF100 **0.125**、TeogopK **0.071**，ECE **0.097854**；大量样本被推向 `3/HEART`。
- 解释边界：整牌框不是牌角真值，四角候选可能裁到牌面中央/空白；这个 10.48% 是真实域**下限诊断**，不是 T3.5 回退，也不能据此关闭 T5.3。
- 证据：`output/runs/20261102/T3.5r/`（构建 PASS）、`T3.5r-predict/`、`T3.5r-eval/`（模型评估 FAIL）。
- 自动化命令：`real_pretest.py --sources rf100,teogopk` → `predict.py --stage classifier` → `evaluate.py --stage classifier --task T3.5r`。
- 补充尝试：`drFarid/French-Playing-Cards`（52 类 / 1,875 张真实单牌 / MIT）。抽样 4 张做 bbox/阈值定位 + 项目牌面 SIFT 配准，只有 1–3 个好匹配、RANSAC **0 inliers**；无法稳定生成真实牌角 crop。该集只能作为后续人工/更强配准的素材，不能直接升级为 T3.5 真实 test。

### 8.3.2 testcases 8 张训练 / case13 唯一校验（T3.6，非发布）

- **用户决定**：`testcases/` 的 8 张转为训练数据，只保留 `case13` 作为校验实例；切分固定在 `model_training/datasets/testcases_split.json`。其余 8 张在 `golden_truth.json` 中标记为 `role=training`，不再进入 `golden.py --cases all`。
- **生成样本**：2026-11-02 新增 `testcases/auto_case2.jpg`（上排 14 + 下排 13，共 27 张，seed `20261102`）；牌面已写入 `golden_truth.json` v15 与 `golden-definition.json` v3，但 `confirmed=false`、未加入 `testcases_split.json`。不改变“case13 唯一校验”，也不作为 T5.3/真实域证据。
- **生成样本 2**：2026-11-02 新增 `testcases/auto_case3.jpg`（上排 14 张宽扇 + 下排 13 张窄扇，共 27 张，seed `20261103`）；牌面已写入 `golden_truth.json` v16 与 `golden-definition.json` v4，`role=training`、`confirmed=false`，未加入 `testcases_split.json`；不改变 T0.5/T5.3 口径。
- **生成样本 3**：2026-11-02 新增 `testcases/auto_case4.jpg`（上排 14 张宽扇 + 下排 13 张窄扇，共 27 张，seed `20261104`）；牌面已写入 `golden_truth.json` v17 与 `golden-definition.json` v5，`role=training`、`confirmed=false`，未加入 `testcases_split.json`；不改变 T0.5/T5.3 口径。
- **生成样本 4（左→右扇形）**：2026-11-02 重生成 `testcases/auto_case5.jpg`：三排均从左向右展开，右牌压左牌并露出左牌左上角点数/花色，右侧牌相对左侧逐张向右多倾斜；上排 9 + 中排 9 + 下排 9 = 27 张（seed `20261109`，`case14` 绿色牌桌），下排覆盖上排约 60%、上宽下窄。牌面与 hash 写入 `golden_truth.json` v25、`golden-definition.json` v13，`role=training`、`confirmed=false`，未加入 `testcases_split.json`。
- **生成样本 5（左→右扇形）**：2026-11-02 重生成 `testcases/auto_case6.jpg`：三排均从左向右展开，右牌压左牌并露出左牌左上角点数/花色；上排 9 + 中排 9 + 下排 9 = 27 张（seed `20261108`，`case14` 绿色牌桌），下排覆盖上排约 60%、上宽下窄。牌面与 hash 写入 `golden_truth.json` v24、`golden-definition.json` v12，`role=training`、`confirmed=false`，未加入 `testcases_split.json`。
- **生成样本 6（case14 参考扇形）**：2026-11-02 新增 `testcases/auto_case7.jpg`：参考 `case14` 的三排左→右牌扇，右牌压左牌并露出左牌左上角点数/花色；左侧牌轻向左、右侧牌逐张向右倾斜，上排 9 + 中排 9 + 下排 9 = 27 张（seed `20261110`，绿色牌桌），下排覆盖上排约 60%、上宽下窄。牌面与 hash 写入 `golden_truth.json` v26、`golden-definition.json` v14，`role=training`、`confirmed=false`，未加入 `testcases_split.json`。
- **生成样本 7（case14 参考扇形）**：2026-11-02 新增 `testcases/auto_case8.jpg`：参考 `case14` 的三排左→右牌扇，右牌压左牌并露出左牌左上角点数/花色；左侧牌轻向左、右侧牌逐张向右倾斜，上排 9 + 中排 9 + 下排 9 = 27 张（seed `20261111`，绿色牌桌），下排覆盖上排约 60%、上宽下窄。牌面与 hash 写入 `golden_truth.json` v27、`golden-definition.json` v15，`role=training`、`confirmed=false`，未加入 `testcases_split.json`。
- **生成样本 8（case14 参考扇形）**：2026-11-02 新增 `testcases/auto_case9.jpg`：参考 `case14` 的三排左→右牌扇，右牌压左牌并露出左牌左上角点数/花色；左侧牌轻向左、右侧牌逐张向右倾斜，上排 9 + 中排 9 + 下排 9 = 27 张（seed `20261112`，绿色牌桌），下排覆盖上排约 60%、上宽下窄。牌面与 hash 写入 `golden_truth.json` v27、`golden-definition.json` v15，`role=training`、`confirmed=false`，未加入 `testcases_split.json`。
- **生成样本 9（case14 参考扇形）**：2026-11-02 新增 `testcases/auto_case10.jpg`：参考 `case14` 的三排左→右牌扇，右牌压左牌并露出左牌左上角点数/花色；左侧牌轻向左、右侧牌逐张向右倾斜，上排 9 + 中排 9 + 下排 9 = 27 张（seed `20261113`，绿色牌桌），下排覆盖上排约 60%、上宽下窄。牌面与 hash 写入 `golden_truth.json` v27、`golden-definition.json` v15，`role=training`、`confirmed=false`，未加入 `testcases_split.json`。
- **生成样本 10（case19 参考扇形）**：2026-11-02 新增 `testcases/auto_case11.jpg`：参考 `case19` 的红色桌布与三排左→右牌扇，右牌压左牌并露出左牌点数/花色；右侧牌逐张向右倾斜，上排 9 + 中排 9 + 下排 9 = 27 张（seed `20261120`），下排覆盖上排一多半、左手持牌。牌面与 hash 写入 `golden_truth.json` v28、`golden-definition.json` v16，`role=training`、`confirmed=false`，未加入 `testcases_split.json`。
- **生成样本 11（case19 参考扇形）**：2026-11-02 新增 `testcases/auto_case12.jpg`：参考 `case19` 的红色桌布与三排左→右牌扇，右牌压左牌并露出左牌点数/花色；右侧牌逐张向右倾斜，上排 9 + 中排 9 + 下排 9 = 27 张（seed `20261121`），下排覆盖上排一多半、左手持牌。牌面与 hash 写入 `golden_truth.json` v28、`golden-definition.json` v16，`role=training`、`confirmed=false`，未加入 `testcases_split.json`。
- **生成样本 12（case19 参考扇形）**：2026-11-02 新增 `testcases/auto_case13.jpg`：参考 `case19` 的红色桌布与三排左→右牌扇，右牌压左牌并露出左牌点数/花色；右侧牌逐张向右倾斜，上排 9 + 中排 9 + 下排 9 = 27 张（seed `20261122`），下排覆盖上排一多半、左手持牌。牌面与 hash 写入 `golden_truth.json` v28、`golden-definition.json` v16，`role=training`、`confirmed=false`，未加入 `testcases_split.json`。
- **生成样本 13（case19 参考扇形）**：2026-11-02 新增 `testcases/auto_case14.jpg`：参考 `case19` 的红色桌布与三排左→右牌扇，右牌压左牌并露出左牌点数/花色；右侧牌逐张向右倾斜，上排 9 + 中排 9 + 下排 9 = 27 张（seed `20261123`），下排覆盖上排一多半、左手持牌。牌面与 hash 写入 `golden_truth.json` v28、`golden-definition.json` v16，`role=training`、`confirmed=false`，未加入 `testcases_split.json`。
- **生成样本 14（case19 参考扇形）**：2026-11-02 新增 `testcases/auto_case15.jpg`：参考 `case19` 的红色桌布与三排左→右牌扇，右牌压左牌并露出左牌点数/花色；右侧牌逐张向右倾斜，上排 9 + 中排 9 + 下排 9 = 27 张（seed `20261124`），下排覆盖上排一多半、左手持牌。牌面与 hash 写入 `golden_truth.json` v28、`golden-definition.json` v16，`role=training`、`confirmed=false`，未加入 `testcases_split.json`。
- **生成样本 15（case19 参考扇形）**：2026-11-02 新增 `testcases/auto_case16.jpg`：参考 `case19` 的红色桌布与三排左→右牌扇，右牌压左牌并露出左牌点数/花色；右侧牌逐张向右倾斜，上排 9 + 中排 9 + 下排 9 = 27 张（seed `20261125`），下排覆盖上排一多半、左手持牌。牌面与 hash 写入 `golden_truth.json` v28、`golden-definition.json` v16，`role=training`、`confirmed=false`，未加入 `testcases_split.json`。
- **生成样本 16（case19 参考扇形）**：2026-11-02 新增 `testcases/auto_case17.jpg`：参考 `case19` 的红色桌布与三排左→右牌扇，右牌压左牌并露出左牌点数/花色；右侧牌逐张向右倾斜，上排 9 + 中排 9 + 下排 9 = 27 张（seed `20261126`），下排覆盖上排一多半、左手持牌。牌面与 hash 写入 `golden_truth.json` v28、`golden-definition.json` v16，`role=training`、`confirmed=false`，未加入 `testcases_split.json`。
- **生成样本 17（case19 参考扇形）**：2026-11-02 新增 `testcases/auto_case18.jpg`：参考 `case19` 的红色桌布与三排左→右牌扇，右牌压左牌并露出左牌点数/花色；右侧牌逐张向右倾斜，上排 9 + 中排 9 + 下排 9 = 27 张（seed `20261127`），下排覆盖上排一多半、左手持牌。牌面与 hash 写入 `golden_truth.json` v28、`golden-definition.json` v16，`role=training`、`confirmed=false`，未加入 `testcases_split.json`。
- **生成样本 18（case19 参考扇形）**：2026-11-02 新增 `testcases/auto_case19.jpg`：参考 `case19` 的红色桌布与三排左→右牌扇，右牌压左牌并露出左牌点数/花色；右侧牌逐张向右倾斜，上排 9 + 中排 9 + 下排 9 = 27 张（seed `20261128`），下排覆盖上排一多半、左手持牌。牌面与 hash 写入 `golden_truth.json` v28、`golden-definition.json` v16，`role=training`、`confirmed=false`，未加入 `testcases_split.json`。
- **生成样本 19（case19 参考扇形）**：2026-11-02 新增 `testcases/auto_case20.jpg`：参考 `case19` 的红色桌布与三排左→右牌扇，右牌压左牌并露出左牌点数/花色；右侧牌逐张向右倾斜，上排 9 + 中排 9 + 下排 9 = 27 张（seed `20261129`），下排覆盖上排一多半、左手持牌。牌面与 hash 写入 `golden_truth.json` v28、`golden-definition.json` v16，`role=training`、`confirmed=false`，未加入 `testcases_split.json`。
- **生成样本 20（case14 参考扇形 + 逐牌角）**：2026-11-02 新增 `testcases/auto_case21.jpg`：27 张三排左→右牌扇（seed `20261131`）；每张牌写入整牌 `quad` 与左上 35% `corner_quad` 四点，27/27 可见并带 `visible`/`occlusion`/`rotation`；hash 写入 `golden_truth.json` v29、`golden-definition.json` v17，`role=training`、`confirmed=false`，未加入 `testcases_split.json`。
- **生成样本 21（case18 随机参考扇形 + 逐牌角）**：2026-11-02 新增 `testcases/auto_case22.jpg`：随机参考 `case18` 的木质桌面、分层牌扇和左手持牌风格，三排左→右共 27 张（seed `20261132`）；每张写入整牌 `quad` 和左上 35% `corner_quad`，27/27 可见；hash 写入 `golden_truth.json` v30、`golden-definition.json` v18，`role=training`、`confirmed=false`，未加入 `testcases_split.json`。
- **生成样本 22–51（随机参考 case*.jpg + 逐牌角，批量 30 张）**：2026-11-02 新增 `testcases/auto_case23.jpg`～`auto_case52.jpg`；独立 seed `20261201`～`20261230`，每张随机参考 `case*.jpg`，27 张三排左→右牌扇，下方覆盖上方 58–64%；逐牌 `quad`/`corner_quad`、27/27 可见；写入 `golden_truth.json`（批次 v31–v60）与 `golden-definition.json` v48，`role=training`、`confirmed=false`，未加入 `testcases_split.json`。总览 `output/runs/20261102/auto23-52-batch/auto23-52-contact-sheet.jpg`。
- **生成样本 52–81（随机参考 case*.jpg + 逐牌角，批量 30 张）**：2026-11-03 新增 `testcases/auto_case53.jpg`～`auto_case82.jpg`；独立 seed `20261231`～`20261260`，每张随机参考 `case*.jpg`，27 张三排左→右牌扇，下方覆盖上方 58–64%；逐牌 `quad`/`corner_quad`、27/27 可见；写入 `golden_truth.json`（批次 v61–v90）与 `golden-definition.json` v78，`role=training`、`confirmed=false`，未加入 `testcases_split.json`。总览 `output/runs/20261103/auto53-82-batch/auto53-82-contact-sheet.jpg`。
- **伪框转换**：`draft_labels.py --pass-plan app` 以 T2.2v2@960 跑 8 张训练图，得到 344 个候选；`pseudo_from_testcases.py` 只保留检测分/分类置信度达标、且人工牌面清单中仍有剩余同类标签的框，并做 IoU 去重。最终 **55 个 crop / 29 类**，81 个框因标签不匹配被丢弃；`case13` 为 **0 条训练记录**。
- **训练**：合成 5,104 条（54 类）+ 55 个真实伪 crop 过采样 10× 组成 5,654 条记录；按 0° 训练后为 2,641 train / 461 val。T3.6 报告 PASS，合成 val exact **0.993492**；这是混合域回归，不是真实发布指标。
- **case13 对照**：同一个 T2.2v2 检测器/18-pass，只替换分类器。新模型最佳 F1 **0.5000**（recall 0.5185 / precision 0.4828，14/27）；旧模型 F1 **0.4643**（13/27）。但在 App 现行门控 `det=0.6 / cls=0.5 / margin=0.02` 下两者都是 **5/27**（新模型 7 框、旧模型 6 框），新模型合成 val 还从 0.995766 降到 0.993492。
- **边界**：伪框不是人工四点真值；`case13` 仅 1 个场景、27 张牌，远少于 T5.3 的 600 牌/30 场景；因此 T3.6 只能做真实域预检/候选诊断，**不得替换 App 资产、不得关闭 T5.3、不得计入 X3 门禁**。
- **证据**：`output/runs/20261102/T3.6-pseudo/`、`T3.6-real-mix/`、`T3.6-real-mix-export/`、`T3.6-case13-new/`、`T3.6-case13-old/`、`T0.5-case13-only/`。

### 8.3.3 2026-11-03 牌角候选自动排序回归（非门禁）

- **数据**：使用 `testcases/auto_case53~82` 的 30 张生成图 / 810 张牌及逐牌 `corner_quad`，并只用已签核 `case18/19/21`（82 框）做真实图护栏；不把生成图当真实 test。
- **诊断**：默认 `max_corners=80`、25px NMS、top-27 的几何命中是 **393/810**；候选点本身在 25px 内约占 75%，主要损失在候选排序，而不是 LSD 完全漏检。
- **规则**：保留 80 候选和 top-27；默认仍按 `ink, white, score`。仅当整张图至少有 **15** 个候选的顶边、左边都 `>=50px` 时，改用 `ink + 0.25*white + 0.5*normalized_edge`，否则完全退回默认排序。阈值由真实三图护栏确定，不对真实图启用边长奖励。
- **结果**：生成 30 张 **393/810 → 471/810（+78）**；已签核 `case18/19/21` 保持 **32/82（11/8/13）**。
- **负结果**：无条件边长排序把真实三图降到 **26/82**；80 候选下 `min>=50` 单候选门控降到 **29/82**，均未采用。
- **边界**：只改 Python 离线参考实现的候选排序和单测（不参与 Kotlin/Android App 运行时），不改 Android 资产、T5.3 或发布口径；默认引擎状态见 ADR-0044。证据：`output/runs/20261103/corner-ranking/report.json`、`auto_case53-overlay.jpg`；单测 28/28 PASS。

### 8.3.4 2026-11-03 桌面 learned 牌角 benchmark（非门禁）

- **目的**：在电脑上直接复用 Android 已部署的 `card_corner.tflite` + `classifier.tflite`，用 `auto_case23~82` 的逐牌 `corner_quad` 评估 learned 路径，不启动手机、不改 APK。
- **入口**：`PYTHONPATH=model_training python model_training/learned_benchmark.py --cases generated --adaptive --classify`；`--cases tune`（53–82）与 `--cases holdout`（23–52）可分开复测。
- **固定阈值 0.6**：几何命中 **1609/1620**，0 误检；最终策略保留 0.6 作为基础阈值，并在 manifest `20261102.4` 写入 `fallback_score_threshold=0.3`。
- **最终补漏策略**：已固化为 Kotlin `AdaptiveCornerPolicy`——仅当基础检测数少于 27 时执行 0.3 检测；低阈值候选先与现有框按 IoU 0.5 去重，再分类，只有 known 才接纳，最终候选上限 27。生成域 **1615/1620、0 误检**；留出 **807/810**、调参 **808/810**，分类 exact **1.0**，端到端 **99.63%/99.75%**。
- **边界**：全部是生成域；真实 case18/19/21 没有逐牌角 quad，不能用这份结果替代真实 test/T5.3。`auto_case34` 在 0.3 的额外检测是 unknown，因此被 known 过滤拒绝。本条记录的是 2026-11-03 benchmark 当时的默认状态；同日 ADR-0044 已把默认改为 `LEARNED` 并接入 `ModelAssets`。证据：`output/runs/20261103/learned-benchmark-adaptive/report.json`、`...-holdout-adaptive/report.json`、`...-tune-adaptive/report.json`。
- **2026-11-03 优化冻结**：生成域已饱和、真实域数据不足，停止继续调阈值/TTA/候选排序；本策略是最终策略。`model_training/annotations.json` 已清理 15 个无图片的旧 key，仅保留 9 图/242 框；旧 T3.1c/T3.5c、T5.3p 候选和临时 run 已清理。

### 8.4 可自动化执行的工作

| 优先级 | 工作 | 自动化范围与完成判定 |
| --- | --- | --- |
| P0-0 | 冻结唯一候选 | 用固定规则从评测结果选择候选；manifest/温度/模型 hash 自动校验。选择规则可自动执行，但最终发布仍需 review。 |
| P0-1 | 合成验收集（非 real-test） | `generate_synthetic.py` 生成 600 张牌 / 30 个完整 27 张场景，自动标签、seed、scene_id、hash、零泄漏检查；用于回归/鲁棒性，**不计入真实发布证据**。 |
| P0-2 | 真实域检测器 | 外部提供 `real-test` 后，自动跑分层评估、错误归因、训练、真实 calibration、导出；门槛 recall ≥99%、FP ≤0.1/图、IoU ≥0.8。无 `real-test` 时 `BLOCKED`。 |
| P0-3 | 真实域分类器 | 外部提供真实牌角 test 后，自动评估/温度/阈值/导出；门槛 exact ≥99.5%、Joker ≥99%、ECE ≤0.05。`T3.5r` 的整牌框预检只能诊断域差距，不能关闭本项。 |
| P0-4 | T4.7 桌面兼容 | 电脑加载 TFLite、shape/hash/尺寸、固定样本输出；手机不测，性能风险显式记录。 |
| P0-5 | T5.3 shadow | 有 `real-test` 后自动对比 heuristic/learned，出每张 exact、27 张全对、复核率、自信错误；`release-qa` 仍为外部签核。 |
| P0-6 | T5.1 闭环 | 队列/低 margin 排序/导入/重训/before-after 自动执行；人工修正与批准是外部输入。 |
| P1-1 | X2 静态离线 | 电脑端 `offline_audit.py` + 日志归档；运行期禁网不测，风险显式记录。 |
| P1-2 | 打包/回滚/文档 | T5.2 package/rollback、T5.4 validate_docs/gate 自动执行；最终 QA 签核外部。 |

**按“全部自动化”前提，当前可立即推进的是：P0-1 合成验收集、P0-4 T4.7 桌面兼容、P0-0 候选自动校验、T5.1 队列/导入链路、T5.2/T5.4 校验。P0-2/P0-3/P0-5 及发布候选必须等待 C 类外部数据/签核。**

**2026-11-02 T5.3p 自动真实图回归（非发布）**

- 新增 `public_benchmark.py`：确定性构建公开真实图 manifest，RF100 train/valid/test 分别映射到 train/calibration/test，TeogopK 全部作 test；记录图片/标注 hash、scene_id、license、整牌 bbox。
- 新增 `public_e2e.py`：部署版 TFLite 检测器 → 牌角四点半透视拉正 → 分类器 → 与公开整牌框的牌面多重集比较；不读取 `annotations.json`/`needs_review`，不做人工签核。
- 冻结结果：**221 个 test 场景 / 868 张牌**；test 与 train/calibration 图片 hash 无重叠；Joker 真值 0，因此 `joker_exact=null`。
- 全量基线：per-card exact **0.192483**、full-scene exact **0.00905**、review rate **0.27765**、confident error **0.451299**；报告 `output/runs/20261102/T5.3p-test/report.json`，判 **FAIL**。这是真实域差距，不是 T5.3 通过证据。
- 同时修正一个真实部署错误：App 旧 detector 资产是 640 训练版的陈旧导出，且 OBB 通道被按 `angle,score` 解码；实际 LiteRT 输出是 `cx,cy,w,h,score,angle`。已用 T2.2v2/960 重新导出并替换资产（`a17d8e10…`，10,869,952 B），manifest 升为 `20261102.3`，默认引擎仍为 `HEURISTIC`。
- 自动微调候选（**未发布**）：`public_crops.py` 从 RF100 train 的 320 图生成 **915 个**伪角 crop（检测框与整牌 bbox 一对一匹配），用 `train.py --init-weights` 从 `T3.6-real-mix/best.keras` 低学习率微调 12 epoch；默认门（0.5/0.02）calibration per-card **0.114078**、unknown 81.9%，旧分类器同 split **0.221698**、unknown 44.9%。随后仅在 calibration 扫描门控，选中 **0.1/0.005**：calibration per-card **0.273553**；同一个门原样应用到 test 得 per-card **0.242358**（旧模型 calibration 选门 0.3/0.02 的 test 为 **0.221239**）。但 full-scene、review **12.788%**、confident error **49.315%** 仍远未达 T5.3；**不跑 test 调参、不替换 App 分类器**，仅保留候选与 `public_gate.py` 工具。联合扫描 detector score 仍以 **0.6** 最优；`testcases/case13` 肉眼 sanity check 只有 **4 个检测 / 门控后 1 个 known**。进一步确认 T2.2v2 只训练于 **2,000 张合成图**；四向+翻转 TTA 在 0.6 仅 4→8，降到 0.1 才有 15 但伴随 4 个蓝底假框且顶部仍漏检。结论：阈值/TTA 不能弥补真实密集手牌域缺口，不接 App；下一步需要真实手牌训练数据。
- 边界：T5.3p 是自动回归/优化入口，不改变发布定义；正式 T5.3 仍需外部真实 27 张场景、逐牌角可见性真值与 `release-qa` 签核。

### 8.5 推荐执行顺序

1. **先冻结候选与 `real-test`**：没有独立真实 test，后面的调参和验收都会污染证据。
2. **并行做 T4.7 桌面兼容**：不依赖真实 test，但必须在最终模型冻结后重跑。
3. **检测器 → 分类器 → 真实 calibration**：每次只改一个变量；test 只评一次。
4. **T5.1 主动学习**：用真实失败样本补数据，直到真实 test 达标；不要用 test 反向调参。
5. **最终 T4.7 桌面兼容 + X2 静态审计 + T5.3 电脑端真实 test**：全部通过后，才生成 `release-qa` 和候选发布包。
6. **默认切换**：保留 heuristic 至少一个发布周期；任何指标回退立即回滚。

### 8.6 不能作为发布证据的材料

- 2,674 个合成 crop 的 exact 1.0（T3.5c）；它只证明遮挡标签修复后的合成域回归。
- 224 张合成 test 的 recall 0.9911（T2.4）；不能证明真实照片检测。
- 9 张 golden / 242 牌；样本量不足以证明 99.5%，且 golden 只用于固定回归。
- 放宽阈值后的 84/242；当前 App 阈值只有 35/242，不能把“最优阈值”当发布结果。
- 桌面推理、模拟器、旧模型报告、未绑定 hash 的耗时数据。
- 没有 `release-qa`、`photo-visibility` 或真实 test 的“PASS”。
- `T3.5r` 的 868 张整牌框预检（exact 0.104839）；它是 bbox 角候选，不是逐牌角真值。

### 8.7 发布候选检查清单

- [ ] 候选检测器/分类器/manifest 三者 hash 一致，T3.3 温度与 manifest 一致。
- [ ] `real-test` 冻结、逐牌角签核、与训练/校准/golden 零重叠。
- [ ] T2.4 真实 test 达标；T3.5 真实 test 达标；T5.1 before/after 可追溯。
- [ ] T4.7 桌面 TFLite 兼容报告通过；手机性能/内存风险已记录。
- [ ] X2 电脑静态离线审计通过；运行期禁网未验证风险已记录。
- [ ] T5.3 shadow 达到每张 ≥99.5%、27 张全对 ≥90%、复核 ≤5%、自信错误 ≤1%。
- [ ] T5.2 打包/回滚演练通过；T5.4 文档和许可材料更新。
- [ ] `release-qa` 签核完成；否则保持 heuristic，不生成发布候选。
