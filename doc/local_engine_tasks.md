# 本地识图引擎任务分解

> 依据：`doc/local_engine.md` §5–§7。
> 状态：规划，所有任务待开始。
> 原则：先做管线安全垫，再建数据和模型；模型未过 §5.10 门槛不得设为默认；始终保留人工核对和启发式回退。

## 1. 里程碑与依赖

| 里程碑 | 对应方案 | 目标 | 退出条件 |
| --- | --- | --- | --- |
| M0 管线安全与基线 | §5.3、§6、§7.2 | 降内存、去主线程、可取消、可回归 | case3 27/27；case4 可见牌 ≥95%；无旧结果覆盖；有性能基线 |
| M1 数据自动化 | §5.5 | 公开数据 + 合成数据 + manifest | 54 类覆盖；两副牌重复场景；无 unknown 许可进入 train |
| M2 牌角检测器 | §5.6.1 | 单类 OBB 检测 `card_corner` | recall ≥99%；误检 ≤0.1/图；角点 IoU ≥0.8 |
| M3 点数/花色分类器 | §5.6.2 | 15×5 双头分类，有效组合 54 | 真实留出集 exact ≥99.5%；大小王 ≥99%；ECE ≤0.05 |
| M4 Android 灰度 | §5.7、§5.8 | LiteRT 接入、shadow、fallback | 模型 ≤15MB；p95 ≤3s；峰值 <150MB；可回退 |
| M5 主动学习与发布 | §5.9、§5.10 | 真实校正闭环、版本化、灰度 | 端到端每张 ≥99.5%；27 张全对 ≥85%（发布 ≥90%）；复核率 ≤5% |

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

#### T1.1 公开数据登记与许可白名单

- **目标**：建立公开数据/权重的来源、许可、hash 和用途记录。
- **依赖**：无。
- **涉及**：新增 `model_training/datasets/README.md`、`licenses.json`。
- **步骤**：登记 Kaggle `andy8744`、Hugging Face `JackFurby/playing-cards`、TeogopK、Complete、PD-Mera；标注 CC0/MIT/Unknown；记录下载 URL、版本、hash；确认 Ultralytics AGPL 影响。
- **验收/证据**：每个来源都有许可字段；`Unknown` 明确禁止进入 train；发布清单中没有许可不明权重。

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
- **验收/证据**：门槛达标；失败样本进入主动学习队列；报告含样本数量和置信区间。

#### T2.5 阈值、NMS 与输入尺寸标定

- **目标**：只在 baseline 稳定后调整检测后处理。
- **依赖**：T2.4。
- **涉及**：manifest、`CardCornerDetector` 规划接口。
- **步骤**：网格搜索 score threshold、NMS IoU、640/960；每次只改一个变量；记录对 recall/FP/耗时的影响。
- **验收/证据**：选定参数写入 manifest；不出现“提高阈值导致大量缺牌”；性能仍满足 M4 门槛。

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

#### T4.7 真机性能与手工 QA

- **目标**：验证 API 29 真机的耗时、内存和交互。
- **依赖**：T4.5、T4.6。
- **涉及**：instrumentation、性能报告。
- **步骤**：固定图片重复 10 次；记录 p50/p95；用 `Debug.MemoryInfo`/`dumpsys meminfo` 记录峰值；连续 20 次导入；覆盖换图/随机/联网。
- **验收/证据**：p95 ≤3s；峰值 <150MB；模型资产 ≤15MB；无 OOM/ANR；连续 20 次无旧结果覆盖。

### M5 主动学习与发布

#### T5.1 主动学习闭环

- **目标**：只标注失败样本，不设固定的 300–500 张指标。
- **依赖**：T4.7。
- **涉及**：新增 `active_learning.py`、`annotate.py`、manifest。
- **步骤**：按低置信度、低 margin、贴边、张数不符、两副牌冲突排序；导出 crop；人工校正；合并回 manifest；下一轮训练。
- **验收/证据**：失败样本可追溯；成功样本不重复标注；case3/case4 不进入训练；每轮有 before/after 指标。

#### T5.2 模型版本化与回滚

- **目标**：每次模型更新都可追踪、可回退。
- **依赖**：T5.1。
- **涉及**：manifest、assets、发布记录。
- **步骤**：记录数据版本、训练命令、随机种子、模型 hash、评估报告；保留上一版资产；设置版本切换开关。
- **验收/证据**：任意版本可定位来源；回滚流程演练通过；manifest 与资产一致。

#### T5.3 Shadow/A-B 与默认切换

- **目标**：用真实差异决定是否把 learned 设为默认。
- **依赖**：T5.2。
- **涉及**：设置/构建开关、评估报告。
- **步骤**：shadow 对比 heuristic/learned；统计每张 exact、27 张全对、复核率、自信错误；达标后灰度；保留启发式至少一个发布周期。
- **验收/证据**：端到端每张 ≥99.5%；27 张全对 ≥85% 起步、≥90% 发布；复核率 ≤5%；自信错误 ≤1%。

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
- **涉及**：`licenses.json`、NOTICE、发布清单。
- **验收/证据**：CC0/MIT/自有可发布；Unknown 不进入发布产物；Ultralytics AGPL 已确认；无许可不明权重。

#### X2 隐私与离线约束

- **目标**：图片不上传，模型不联网下载。
- **依赖**：贯穿 M4–M5。
- **涉及**：网络层、assets、隐私说明。
- **验收/证据**：识别路径无网络请求；模型只从 assets 加载；日志不记录图片内容。

#### X3 状态与决策记录

- **目标**：只记录恢复工作所需的信息。
- **依赖**：贯穿全部里程碑。
- **涉及**：`harness/state.md`、必要时 `harness/DECISIONS.md`。
- **验收/证据**：完成一个里程碑更新一次 state；只有架构/规则决策才追加 DECISIONS；不写流水账。

## 3. 验收门槛

| 指标 | 门槛 | 适用里程碑 |
| --- | --- | --- |
| 可见牌角检测 recall | ≥99% | M2 |
| 检测误检 | ≤0.1/图 | M2 |
| 角点多边形 IoU | ≥0.8 | M2 |
| 分类 exact card | ≥99.5% | M3 |
| 大小王分类 | ≥99% | M3 |
| ECE | ≤0.05 | M3 |
| 端到端每张 exact | ≥99.5% | M5 |
| 27 张全对 | ≥85% 起步；≥90% 发布 | M5 |
| 人工复核率 | ≤5% | M5 |
| 自信错误率 | ≤1% | M5 |
| 识别 p95 | ≤3s（API 29 真机） | M4 |
| 峰值内存 | <150MB | M4 |
| 模型资产 | ≤15MB | M4 |
| 稳定性 | 无 OOM/ANR；连续 20 次无旧结果覆盖 | M0/M4 |
| case3 | 27/27 | M0/M5 |
| case4 可见牌 | exact ≥95% | M0/M5 |
| 许可 | 发布产物中 unknown = 0 | X1 |

以上门槛是建议值，必须用 API 29 真机和独立真实照片标定；未达标时模型保持 shadow，默认仍走启发式路径。

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
- `NOT_RUN`：测试没有实际执行，例如没有 API 29 真机、runner 不可用或设备断连；必须说明原因。
- `BLOCKED`：存在尚未解决的前置条件，例如许可未知、Golden 标签未签核或人工修正未审批。

缺少必需字段、NaN/Infinity、空数据集、样本数不足、manifest 与模型不匹配、hash 不一致或报告缺字段，一律不得判为 `PASS`。父级报告必须保留全部子任务状态，不得只汇总成功项。

### 7.4 每项任务的自动化命令与证据

以下命令从仓库根目录执行。`RUN_DIR` 是本任务独立运行目录；`SEED` 必须由调用方显式提供。命令中尚未存在的脚本、测试类均为待实现交付物，不得视为当前已有功能。

#### M0 管线安全与基线

| 任务 | 自动化执行入口 | 必须归档的证据和判定 |
| --- | --- | --- |
| T0.1 | `./gradlew :app:testDebugUnitTest --tests '*ImagePreprocessorTest'` | 尺寸纯函数、EXIF、旋转、颜色测试；750×492、1440×1920、2736×3648 输入输出尺寸。长边不超过 1600px，小图不放大。 |
| T0.2 | `./gradlew :app:testDebugUnitTest --tests '*RecognitionWorkerTest' --tests '*ImageMemoryTest'` | 后台线程断言、Bitmap 生命周期、按需 pass 数、阶段耗时和内存报告。真实设备 OOM/内存门槛另由 T4.7 验证，JVM 测试不能替代真机证据。 |
| T0.3 | `./gradlew :app:testDebugUnitTest --tests '*RecognitionCoordinatorTest'` | 注入可控延迟，测试连续导入、随机、联网、取消、超时和 Activity 销毁；断言只有最新 jobToken 可以更新 UI。 |
| T0.4 | `./gradlew :app:testDebugUnitTest --tests '*VisualCardCodecTest'` | 生成 27 张图并往返 100 次；归档缩放、JPEG、裁切变体的命中率与误命中率。仅对明确支持的变体提出通过要求。 |
| T0.5 | `python model_training/golden.py prepare --cases case3,case4 --output-dir RUN_DIR`；`python model_training/evaluate.py --engine heuristic --split golden --manifest model_training/datasets/golden_manifest.json --output-dir RUN_DIR/evaluation` | expected JSON、逐样本预测、exact、张数、性能和失败差异报告。Golden 真值和可见牌角必须有人工签核；缺签核为 `BLOCKED`。case3/case4 不得进入训练或调参。 |

T0.5 的审核记录必须包含图片 SHA-256、scene_id、可见牌角清单、完全遮挡项、审核人、审核时间和审核版本。程序只能检查记录完整且 hash 一致，不得自动批准照片真值。

#### M1 数据自动化

| 任务 | 自动化执行入口 | 必须归档的证据和判定 |
| --- | --- | --- |
| T1.1 | `python model_training/fetch_public_data.py --sources model_training/datasets/sources.json --license-policy model_training/datasets/licenses.json --output-dir RUN_DIR`；`python model_training/validate_dataset.py --check-licenses --manifest RUN_DIR/dataset_manifest.json --output-dir RUN_DIR/audit` | 来源、URL、版本、下载文件 hash、许可和用途限制报告。Unknown 许可不得进入训练；法律许可结论必须人工签核。下载不完整或 hash 不匹配为 `FAIL`。 |
| T1.2 | `python model_training/convert_public_annotations.py --config model_training/configs/public_conversion.yaml --input RUN_DIR/raw --output-dir RUN_DIR/converted` | 标签转换统计、映射表、非法样本列表、四点/OBB 标签和抽样可视化。自动检查点序、越界、重复、54 个有效组合和 Joker 映射；需要人工视觉确认但未签核时为 `BLOCKED`。 |
| T1.3 | `python model_training/generate_synthetic.py --cards app/src/main/assets --backgrounds model_training/datasets/backgrounds --count 20000 --seed SEED --output-dir RUN_DIR/synthetic` | 合成图片、seed、scene_id、渲染参数、实例标签、四点/牌角标签及 hash。验证复现性、两副牌重复实例、1–27 张场景、负样本和大小王覆盖。背景许可不明为 `BLOCKED`。 |
| T1.4 | `python model_training/validate_dataset.py --manifest model_training/datasets/dataset_manifest.json --check-files --check-hashes --check-labels --check-classes --check-scene-splits --check-leakage --output-dir RUN_DIR` | split 统计、54 类覆盖、scene_id 交集、重复图片 hash、派生关系、许可统计、两副牌实例统计和错误样本列表。按 scene_id 分组后划分 train/val/calibration/test；数据泄漏为 `FAIL`，许可问题为 `BLOCKED`。 |
| T1.5 | `python model_training/validate_dataset.py --manifest model_training/datasets/dataset_manifest.json --audit --check-golden-exclusion --output-dir RUN_DIR`；`python model_training/gate.py --task T1.5 --require-approval dataset-license --require-approval golden-definition --report RUN_DIR/report.json` | 类别分布、遮挡比例、背景、重复图、标签越界、case3/case4 排除情况、manifest hash 和冻结数据版本。许可及 Golden 签核缺失或版本不一致为 `BLOCKED`。 |

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

#### M4 Android 灰度

| 任务 | 自动化执行入口 | 必须归档的证据和判定 |
| --- | --- | --- |
| T4.1 | `python model_training/verify_models.py --assets app/src/main/assets/models --manifest app/src/main/assets/models/model_manifest.json --check-shape --check-labels --check-hash --output-dir RUN_DIR`；`./gradlew :app:testDebugUnitTest --tests '*ModelManifestTest'` | LiteRT 依赖锁定、APK asset 清单、manifest schema、SHA-256、缺失/损坏资产回退测试。安装脚本先 staging、hash 校验，再原子替换，并保留上一版资产。 |
| T4.2 | `./gradlew :app:testDebugUnitTest --tests '*CardGeometryTest' --tests '*CardCornerDetectorTest'`；`./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=nz.org.aotearoa.guandanscore.recognition.CardCornerDetectorTest` | letterbox、OBB 解码、NMS、四点排序、逆坐标映射、Python/Android 固定样本差异和 ByteBuffer 复用。JVM 测试不能替代设备推理证据。 |
| T4.3 | `./gradlew :app:testDebugUnitTest --tests '*CardCornerClassifierTest' --tests '*CardGeometryTest'`；`./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=nz.org.aotearoa.guandanscore.recognition.CardCornerClassifierTest` | 0°/180° 一致性、透视拉正、置信度/margin 阈值、UNKNOWN 输出、buffer 复用和 Python/Android 数值差异。无实际设备执行证据时，设备集成项不得标记 `PASS`。 |
| T4.4 | `./gradlew :app:testDebugUnitTest --tests '*RecognitionCoordinatorTest'`；`./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=nz.org.aotearoa.guandanscore.recognition.RecognitionShadowTest` | heuristic/learned/shadow 模式、默认开关、token 竞态、模型超时和异常回退；shadow 不得写正式 UI。记录运行模式及模型 hash。 |
| T4.5 | `./gradlew :app:testDebugUnitTest --tests '*RecognitionResultTest' --tests '*RecognitionStateTest'` | cards/issues/stats schema、INFO/WARNING/ERROR 映射、未知牌和冲突展示、实际阶段计数及 27 张/部分结果状态测试。 |
| T4.6 | `./gradlew :app:testDebugUnitTest --tests '*RecognitionFallbackTest'`；`./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=nz.org.aotearoa.guandanscore.recognition.ModelFailureInjectionTest` | 模型缺失、hash 错误、manifest 非法、加载异常、超时、检测数不足、回滚和旧资产恢复测试。任一模型故障导致崩溃或阻断 heuristic 为 `FAIL`。 |
| T4.7 | `python model_training/device_benchmark.py --serial DEVICE_SERIAL --api 29 --cases case3,case4 --repeat 10 --stress-sequence 20 --output-dir RUN_DIR`；`./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=nz.org.aotearoa.guandanscore.recognition.RealDeviceBenchmarkTest` | 设备型号、API level、build fingerprint、测试时间、APK/model/图片 hash、p50/p95、Java/native 峰值内存、模型大小、OOM/ANR 和连续 20 次竞态结果。必须确认 API=29 且是真机；无符合设备时为 `NOT_RUN`，模拟器不得代替。 |

T4.7 必须在执行前检查设备连接、授权、包名、应用版本、API level 和存储空间。采集失败不能使用旧报告或其他设备数据补齐。设备报告必须绑定测试时的 APK hash、模型 hash 和输入图片 hash。

#### M5 主动学习与发布

| 任务 | 自动化执行入口 | 必须归档的证据和判定 |
| --- | --- | --- |
| T5.1 | `python model_training/active_learning.py --model RUN_DIR/model.tflite --manifest model_training/datasets/dataset_manifest.json --input model_training/inbox --sort-by confidence,margin,border,count,duplicate --output-dir RUN_DIR/queue`；审核后 `python model_training/import_corrections.py --annotations RUN_DIR/approved/annotations.json --manifest model_training/datasets/dataset_manifest.json --output-dir RUN_DIR/import` | 低置信候选、crop、源图片 hash、模型版本、修正前后标签和导入记录。人工修正必须带审核人、时间和样本 hash；未审批修正不得进入训练。case3/case4 永远不得导入 train。 |
| T5.2 | `python model_training/package_model.py --model RUN_DIR/model.tflite --manifest RUN_DIR/model_manifest.json --previous app/src/main/assets/models --output-dir RUN_DIR/package`；`python model_training/verify_models.py --assets RUN_DIR/package --check-hash --check-manifest --output-dir RUN_DIR/verify`；`python model_training/rollback_test.py --current RUN_DIR/package --previous app/src/main/assets/models/previous --output-dir RUN_DIR/rollback` | 版本清单、数据/模型 hash、训练命令、seed、依赖版本、评估报告引用、上一版本备份和回滚演练。manifest 与资产不一致为 `FAIL`。 |
| T5.3 | `python model_training/evaluate.py --stage e2e --mode shadow --model RUN_DIR/model.tflite --baseline heuristic --split real-test --manifest model_training/datasets/dataset_manifest.json --output-dir RUN_DIR/shadow`；`python model_training/gate.py --task T5.3 --milestone M5 --report RUN_DIR/shadow/report.json --require-device-api 29 --require-approval release-qa` | 逐张模型/启发式差异、每张 exact、27 张全对率、复核率、自信错误率、失败样本、真实 test 样本数、设备证据和发布 gate。test 不可用于调参；不达门槛时 heuristic 继续作为默认。 |
| T5.4 | `python model_training/validate_docs.py --check-commands --check-task-coverage --check-links --output-dir RUN_DIR`；`python model_training/gate.py --task T5.4 --milestone M5 --ci-report RUN_DIR/ci/report.json --output-dir RUN_DIR/gate`；`./gradlew test` | README/NOTICE、CI 配置、文档链接、任务覆盖率、训练/导出/评估复现命令、Gradle 与 Python 检查报告。每个任务 ID 必须出现在任务映射表中，入口必须可由 CLI `--help` 或静态检查验证。 |

#### 横切任务

| 任务 | 自动化执行入口 | 必须归档的证据和判定 |
| --- | --- | --- |
| X1 许可与来源审查 | `python model_training/license_audit.py --sources model_training/datasets/sources.json --licenses model_training/datasets/licenses.json --artifacts RUN_DIR/package --output-dir RUN_DIR/license-audit` | 数据、代码、预训练权重、最终模型逐层记录来源、版本、许可、NOTICE、分发限制和 hash。未知或不兼容许可阻断训练/发布；人工法律审查未签核为 `BLOCKED`。 |
| X2 隐私与离线约束 | `python model_training/offline_audit.py --apk app/build/outputs/apk/debug/app-debug.apk --source app/src/main --assets app/src/main/assets/models --output-dir RUN_DIR`；`./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=nz.org.aotearoa.guandanscore.OfflineRecognitionTest` | 静态扫描网络调用/模型下载；禁网条件下执行本地识别；检查图片未上传、日志未包含图片内容、模型只从 assets 加载。没有禁网设备测试时为 `NOT_RUN`。 |
| X3 状态与决策记录 | `python model_training/gate.py --milestone all --reports output/runs --state harness/state.md --decisions harness/DECISIONS.md --output-dir RUN_DIR` | 汇总任务状态、依赖链、报告/hash、未完成项和阻断原因；生成 state 更新建议。不得覆盖既有决策，不得把 `NOT_RUN`/`BLOCKED` 自动转成完成。架构决策须经人工批准后才追加 DECISIONS。 |

### 7.5 数据集划分、真实性与防泄漏

1. 正式 split 固定为 `train`、`val`、`calibration`、`test`；golden 使用独立 manifest 或独立标识。先按 `scene_id` 分组再划分，同一照片、连拍、增强图、裁剪图及同一合成场景的派生图不得跨集合。
2. 每个输入记录 SHA-256；同图异名、重复文件、裁剪派生关系和 scene_id 均纳入泄漏检查。来源关系不明的样本不得默认视为独立样本。
3. 训练只读 train；早停和模型选择只读 val；温度、阈值、NMS 和后处理标定只读 calibration；最终指标只读 test；golden 只用于固定回归，不得参与训练、校准和反复调参。
4. test/golden 真值文件必须与训练入口隔离。`evaluate.py` 记录阶段、split、manifest hash；检测到 test/golden 被用于训练或 calibration 时立即 `BLOCKED`。
5. `license=unknown`、来源缺失、许可不在白名单、图片 hash 缺失或签核失效的样本不得进入正式训练。下载脚本可以登记许可资料，不能自动批准许可证。
6. 合成数据必须记录 seed、scene_id、牌面素材版本、背景许可、布局和增强参数。同 seed、相同配置及素材版本应能复现图像标签；seed 缺失为 `FAIL`。
7. 评估报告必须保留逐样本预测和失败项，不能在统计前删除 UNKNOWN、漏检、无牌照片或困难样本。

### 7.6 人工签核接口与阻断条件

以下工作不能由程序代签，但必须转换为结构化记录供门禁校验：

| 签核类型 | 对应任务 | 必需字段 | 缺失时状态 |
| --- | --- | --- | --- |
| 数据许可审核 | T1.1、T1.5、X1 | source_id、许可结论、用途范围、审核人、时间、记录版本 | `BLOCKED` |
| Golden 真值确认 | T0.5、T1.5 | 图片 hash、scene_id、可见牌角及真值、遮挡说明、审核人、时间 | `BLOCKED` |
| 真实照片可见性确认 | T1.2、T3.1、T5.1 | 原图 hash、crop hash、四点坐标、可见性、审核结论、审核人、时间 | `BLOCKED` |
| 人工修正批准 | T5.1 | 修正前后标签、来源样本 hash、审核人、审核时间 | `BLOCKED` |
| 真实设备证据 | T4.7、T5.3 | 设备型号、API level、build fingerprint、测试时间、APK/model hash、报告 hash | 未执行为 `NOT_RUN` |
| 最终发布 QA | T5.3、T5.4 | 候选版本、test 报告、设备报告、许可报告、回滚结果、批准人、时间 | `BLOCKED` |

签核文件统一放在 `model_training/approvals/`，采用 JSON schema 校验。每条签核绑定数据/模型/样本 hash 与版本；签核撤销、过期或 hash 不一致时门禁失效。自动化只检查签核存在、格式和绑定关系，不伪造签名、不自动勾选批准。

### 7.7 CI 与设备 Runner 分层

| 流水线 | 自动执行 | 不能代替的内容 |
| --- | --- | --- |
| Pull Request | Python lint/type check、CLI/schema、标签与几何单测、manifest 校验、数据泄漏检查、模型形状冒烟、Gradle JVM 单测、离线静态扫描 | 真实照片最终准确率、API 29 真机性能、许可批准、golden 真值签核 |
| Nightly | 固定 seed 合成小数据、训练冒烟、完整 golden 回归、模型导出、Python/Android 固定样本一致性、故障注入 | 无真实设备时不能产生真机证据；合成验证不能代替真实 test |
| Manual/Release | 完整训练和导出、独立真实 test、shadow/A-B、发布 gate、许可和签核核验 | 人工签核须由指定审核人完成 |
| API 29 Device Runner | 真机 instrumentation、连续导入/换图/随机竞态、性能/内存、OOM/ANR、离线网络测试 | runner 不可用或设备不符合要求时为 `NOT_RUN`，不得用模拟器冒充 |

每个 CI job 必须上传 `report.json`、原始日志、测试结果、输入/输出 hash 和 runner 环境。Nightly 失败不能被后续成功 job 覆盖；Release gate 必须引用确定的 run_id 和报告 hash。

### 7.8 自动化完成定义与总门禁

任务只有同时满足以下条件才能标记完成：

- 对应 CLI 入口已实现，`--help` 可运行，参数和退出码符合 §7.2；
- 自动化测试/评估真实执行，原始结果、`report.json` 和输入输出 hash 已归档；
- 适用指标达到原任务验收门槛，样本数足够且 split 正确；
- 依赖任务为 `PASS`，适用许可、golden、真实照片和发布签核有效；
- 需要 API 29 证据的任务引用真实设备报告，设备型号、系统版本、APK/model hash 一致；
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
  --require-device-api 29 \
  --output-dir output/runs/release-gate
```

该命令必须按依赖关系汇总 T0.1–T5.4 和 X1–X3，检查每项任务是否有可执行入口、执行报告、样本数、指标、版本/hash、设备证据和签核。没有真实 API 29 证据时不得通过 M4/M5 发布门禁；许可或人工批准缺失时为 `BLOCKED`；测试未实际运行时为 `NOT_RUN`；指标失败时为 `FAIL`。全部必需门禁通过后才允许生成发布候选，不能自动改变 learned/heuristic 默认开关。
