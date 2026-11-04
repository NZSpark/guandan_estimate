# 本地识图引擎分析与可自动化落地方案

> 分析对象：`CardRecognizer`、`CardTemplateMatcher`、`VisualCardCodec`、`HandImageRenderer`，以及 `MainActivity` 中的调用与图片解码。
>
> 方法：静态阅读源码 + 素材与 `testcases/` 图片尺寸测量；未修改识别代码，未做真机 heap dump 或准确率实测。文中标【已确认】的结论来自代码或文件事实，标【推断】的结论需要真机/语料验证。
>
> 数据策略：公开数据/预训练权重只做初始化，自有牌面用合成数据补足，真实照片只做失败样本校正；**不要求从零人工标注 300–500 个牌角**。
>
> 结论先行：当前引擎能完成「标准牌面 + 可读牌角」的离线识别，快速路径和三阶段拆分也合理；但 **18 个 pass 的位图全部同时驻留、构图发生在主线程、输入图未真正限制到 1800px**，在中低端设备上有明显的 OOM/ANR 风险。准确率方面，固定聚类半径、宽松的 OCR 别名和未标定的模板阈值是三个最值得先验证的点。短期先修管线安全与可测性；目标态是「条码快速路径 + OBB 牌角检测 + 点数/花色双头分类 + 规则校验」；ADR-0044 已把 learned 设为默认，模型加载/推理失败时仍以现有启发式路径回退。

## 1. 结论摘要

| 优先级 | 问题 | 影响 | 建议 |
| --- | --- | --- | --- |
| P0 | 18 个 pass 的 Bitmap 同时保留；构图和图片解码在主线程 | 峰值数百 MB，可能 OOM/ANR | 精确降采样、后台线程、串行/按需 pass、随用随回收 |
| P0 | 本地引擎没有回归测试，`ai_baseline.md` 的本地结果仍是「待测」 | 任何阈值/模板改动都无法证明变好 | 用 `case3/case4` 建 golden set，记录 exact accuracy、耗时、峰值内存 |
| P0 | 识别任务没有取消/序列号；换图、随机手牌、联网识图可能互相覆盖 | 旧结果覆盖新图，用户看到错牌 | 为每次识别加 job token，取消旧任务，只接受最新结果 |
| P0 | 数据策略假设必须从零人工标注 | 成本高、不可持续；公开数据已经可用 | 公开数据预训练 + 自有牌面合成 + 主动学习校正，不设 300–500 的硬指标 |
| P1 | 通用 OCR/模板对真实牌面的上限 | 换 Tesseract/PaddleOCR 不能解决旋转、花色和遮挡 | 目标态改为 OBB 牌角检测 + 15×5 分类器（§5） |
| P1 | 固定聚类半径 `min(w,h)*0.038`，且先 `take(27)` 再校验 | 扇形牌相邻牌角可能被合并；误检可能挤掉真牌 | 自适应半径/IoU 聚类、置信度门槛、两副牌合法性检查 |
| P1 | `O→Q`、`I→J`、`T→10` 等 OCR 别名过于宽松，缺少 ROI/上下文 | 背景文字可能变成牌 | 只在牌角区域接受别名；要求模板或花色 corroboration |
| P1 | 模板匹配依赖固定旋转 pass，阈值是常数 | 任意角度、真实牌背/字体下准确率不稳 | 按牌角角度拉正/多角度模板；用语料标定阈值 |
| P1 | 花色分类固定 RGB 阈值 + 宽度轮廓，最后可回退到黑桃 | 花色错、出现不合法的同花色重复 | 输出「不确定」、HSV/自适应阈值、保留模板投票 |
| P1 | 所有本地结果都带 warning，UI 的 `Success` 分支实际不可达 | 27/27 也显示「有警告」，准确解码也被标成部分成功 | 区分 info/warning，修正阶段文案与 `drop(3)` 索引 |
| P2 | `VisualCardCodec` 只做固定位置单行采样，无纠错 | 缩放/裁剪/JPEG 后可能读不到 | 增加定位/多行多数投票，或明确限定只用于本 App 原图 |
| P2 | `HandImageRenderer` 缓存全尺寸牌面（750×1044） | 多次随机/组牌图后内存累积 | 按目标尺寸解码，使用 `LruCache` 或复用池 |

## 2. 代码地图与调用链

### 2.1 调用链

```text
MainActivity.load(uri)                         // 主线程
  └─ decodeInput(uri)                          // ImageDecoder / BitmapFactory，主线程
      └─ CardRecognizer.recognize(bitmap)      // 主线程入口
          ├─ VisualCardCodec.decode(bitmap)    // 本 App 生成图：直接返回
          └─ buildPasses(bitmap)               // 同步创建 18 个 Bitmap
              ├─ 原图
              ├─ 8 个旋转图（-60/-40/-20/20/40/60/90/180°）
              └─ 9 个 55% 重叠切片，放大 1.8×/2.4×
          └─ 18 × ML Kit TextRecognizer.process()
              └─ 每个 OCR element：点数解析 + 模板匹配 + 花色判定
          └─ finalize(candidates, bitmap)      // 聚类、限张、花色投票、警告
```

### 2.2 相关文件

| 文件 | 职责 |
| --- | --- |
| `app/src/main/java/nz/org/aotearoa/guandanscore/recognition/CardRecognizer.kt` | 本地识别总控：快速路径、pass 构建、OCR 候选、聚类、点数/花色收敛 |
| `app/src/main/java/nz/org/aotearoa/guandanscore/recognition/CardTemplateMatcher.kt` | 54 张标准牌面的牌角模板签名与 Jaccard 匹配 |
| `app/src/main/java/nz/org/aotearoa/guandanscore/imaging/VisualCardCodec.kt` | 生成图底部的 191-bit 校验码，绘制/解码 |
| `app/src/main/java/nz/org/aotearoa/guandanscore/imaging/HandImageRenderer.kt` | 随机手牌/组牌图渲染，使用标准牌面素材 |
| `app/src/main/java/nz/org/aotearoa/guandanscore/MainActivity.kt` | 图片解码、调用识别、把结果映射到 `LocalRecognitionState` |
| `testcases/` | 现有照片素材；`doc/ai_baseline.md` 已给出 case3/case4 的人工牌面 |

## 3. 当前算法拆解

### 3.1 快速路径：生成图校验码

`CardRecognizer.recognize()` 先调用 `VisualCardCodec.decode()`（`CardRecognizer.kt:38-42`）。命中时直接返回 27 张牌，不启动 ML Kit。这是最可靠、最省资源的路径，适合「随机生成 27 张」和 App 自己保存的组牌图。

`VisualCardCodec` 的结构：

- `MAGIC` 16 bit + 张数 5 bit + 27 张 × 6 bit + 校验和 8 bit，共 **191 bit**（`VisualCardCodec.kt:13`）。
- 编码时 cell 宽度为 `width/225`，底部画 2 行黑白条（`VisualCardCodec.kt:22-30`）。
- 解码时按同一比例定位，固定取一行像素，按 RGB 和 `<384` 判黑（`VisualCardCodec.kt:33-47`）。

这条路径的优点是确定性强、零网络；限制是它只适用于**原样保留底部条码**的本 App 生成图，不能用于相机照片。注释里的“scale-tolerant”只在等比缩放时成立，裁剪、非等比拉伸或强 JPEG 压缩都可能失效。

### 3.2 18 个 pass 的构造

`buildPasses()`（`CardRecognizer.kt:88-105`）创建：

1. 原图 1 个；
2. 整图旋转 8 个角度（`-60/-40/-20/20/40/60/90/180`，`CardRecognizer.kt:90`）；
3. 3×3 共 9 个切片，每个覆盖 55% 宽高，起点为 0、22.5%、45%，再放大 1.8×（小图 2.4×）（`CardRecognizer.kt:94-103`）。

合计 **18 个 pass**。旋转图通过逆矩阵把 OCR 坐标映射回原图（`CardRecognizer.kt:107-115`）；切片通过 `areaScale=zoom²` 还原面积（`CardRecognizer.kt:102`）。坐标映射本身是正确的。

设计的出发点是合理的：扇形牌角朝向不同，切片放大可以提升小字 OCR 的可读性。问题是这些图**一次性全部创建并保留**，而不是按需生成、用完即回收。

### 3.3 候选生成

每个 pass 的每个 OCR element 都走同一套逻辑（`CardRecognizer.kt:54-83`）：

1. 过滤高度不在 `10..passHeight*25%` 的框（`:58`）；
2. `normalize()` 去掉非字母数字，得到紧凑文本（`:172-175`）；
3. `Rank.parse()` 解析点数；
4. `CardTemplateMatcher.match(pass.bitmap, box)` 取模板点数/花色；
5. 点数选择：OCR 为空且模板置信度 ≥0.48 → 模板；OCR 非空且模板 ≥0.72 → **模板覆盖 OCR**；否则 OCR（`:62-66`）；
6. 花色：大小王直接 JOKER；否则 OCR 文本显式花色 → 模板花色（需点数一致且 ≥0.46）→ 下方像素颜色/轮廓分类（`:68-70`）；
7. 记录来源面积、`exact`（文本与点数标签完全一致）和启发式置信度（`:71-78`）。

这一步的关键特征是：**OCR 候选没有统一的置信度门槛**。只要点数能解析出来，就会进入候选池；模板只用于「覆盖」或「补花色」，并不是必须的确认。

### 3.4 聚类、限张与花色投票

`finalize()`（`CardRecognizer.kt:117-170`）分三步：

1. **聚类**：以 `min(bitmap.width, bitmap.height) * 0.038` 为半径，把坐标相近的候选合并（`:118-123`）。每个簇按点数分组，用置信度和、exact 数、面积选出胜者（`:126-132`）。
2. **限张**：按 exact、面积排序，每个点数最多 8 张（大小王最多 2 张），然后 `take(27)`（`:133-137`），再按 y/x 排序。
3. **花色**：对每个位置的点数，按花色票数排序，选择第一个「该点数+花色已用 <2」的花色；全部用满时回退黑桃（`:143-154`）。票数占比 <0.6 计入 `uncertainSuits`（`:149-150`）。

最后生成两组警告字符串（`:155-168`），返回 `RecognitionResult`。

### 3.5 UI 状态映射

`MainActivity.load()` 在识别完成回调里：

- `cards.isEmpty() && warnings.isNotEmpty()` → `Failed`；
- `warnings.isNotEmpty()` → `Partial`；
- 否则 → `Success`（`MainActivity.kt:594-604`）。

由于 `CardRecognizer` 的两条返回路径都至少带一条 warning，`Success` 分支实际上只在「随机生成 27 张」这种直接设置状态的路径出现，普通照片和生成图回读都不会进入 `Success`。这会让用户觉得「明明全对，为什么还说有警告」。

## 4. 主要问题与优化建议

### P0-1：18 个 Bitmap 同时驻留，主线程构图

【已确认】`recognize()` 直接从 `MainActivity.load()` 调用（`MainActivity.kt:594`），而 `buildPasses()` 是同步函数（`CardRecognizer.kt:43`、`:88-105`）。因此图片解码、8 次整图旋转、9 次切片放大、18 个 Bitmap 的创建全部发生在主线程；只有 ML Kit 的 OCR 是异步的。

更严重的是内存：18 个 pass 全部保留到最后一个完成（`CardRecognizer.kt:50` 才回收临时图）。按 ARGB_8888 每像素 4 字节做静态下界估算（不含 ML Kit 内部缓冲）：

| 测试图 | 原尺寸 | 实际解码尺寸 | 18 个 pass 的位图下界 |
| --- | --- | --- | --- |
| `case2.jpg` | 750×492 | 750×492 | 约 42 MB |
| `case3.png` | 1536×1024 | 1536×1024 | 约 139 MB |
| `case12.jpg` | 1440×1920 | 1440×1920 | 约 243 MB |
| `case4.jpg` | 2736×3648 | 1368×1824 | 约 219 MB |
| `case116.jpg` | 3648×2736 | 1824×1368 | 约 219 MB |

估算方法：原图 + 8 个旋转图的包围盒 + 9 个 55% 切片放大后的位图；旋转图按外接矩形计算，未计临时 crop 和 ML Kit 缓冲。即使按最保守的下界，1440×1920 的图也已经接近或超过常见中低端机的 Java heap；再叠加 ML Kit 的输入缓冲，OOM 风险很高。

输入图“已经限制到 1800px”这一假设也不成立：`decodeInput()` 用的是整数倍缩放（`MainActivity.kt:562`）：

```kotlin
val scale = maxOf(info.size.width / 1800, info.size.height / 1800, 1)
decoder.setTargetSize(info.size.width / scale, info.size.height / scale)
```

- 1440×1920：`max(0, 1, 1) = 1`，**完全不缩小**；
- 2000×3000：同样 `scale=1`，仍是 6MP；
- 2736×3648：`scale=2`，反而缩到 1368×1824。

因此会出现“更大的原图解码后比更小的原图还小”的不一致。API 26–27 的 `BitmapFactory` 回退也有同样的整数采样问题（`MainActivity.kt:568-578`）。

**建议（按顺序）**：

1. **精确限制长边**：按 `longEdge / 1800.0` 计算目标尺寸，使用 `roundToInt()`，而不是整数除法；`ImageDecoder.setTargetSize` 直接传目标宽高。低版本 `BitmapFactory` 先按 2 的幂采样，再用 `createScaledBitmap` 精修到目标尺寸。建议首版把长边限制在 1600px，给 ML Kit 留内存余量。
2. **把解码和构图移到工作线程**：`load()` 的 `decodeInput` 与 `recognizer.recognize` 都放到现有的 `worker`（`MainActivity.kt:81`）；UI 只接收最终结果。ML Kit 的 `Task` 回调默认在主线程，注意把「候选收集/聚类」留在工作线程，或明确指定 executor。
3. **串行/按需执行 pass**：不要一次创建 18 个。先做原图；不足 27 张或高置信候选不足时，再按缺口生成旋转/切片；每完成一个 pass 立即回收其临时 Bitmap。这样正常网格图只需 1 个 pass，扇形图也只需少数几个。
4. **利用 `InputImage` 的旋转参数**：90/180/270 度可以直接 `InputImage.fromBitmap(source, rotationDegrees)`，不必创建旋转位图；只对 ±20/40/60 等任意角度创建临时图。可先省掉 3 个整图 Bitmap。
5. **降低单 pass 内存**：切片只覆盖「有牌角的区域」，不要固定 55% × 55% 的 3×3；或先做原图 OCR，再对缺失区域做小切片。若颜色允许，OCR pass 可用 `RGB_565`（花色判定仍用原图/彩色切片）。

### P0-2：识别任务没有取消与序列号，旧结果会覆盖新图

【已确认】`load()` 每次都会直接启动 `recognizer.recognize()`，没有保存 job id，也没有取消上一轮（`MainActivity.kt:581-613`）。`CardRecognizer` 内部的 `found`、`errors`、`remaining` 是普通可变集合/变量，依赖 ML Kit 默认主线程回调；没有显式 executor 或同步。

实际可触发的竞态：

- 用户连续导入两张图：第一张的 18 个 OCR 回调较晚返回，会把第二张的手牌覆盖；
- 本地识别尚未结束时点「随机生成 27 张」：随机结果被旧回调覆盖；
- 本地识别与联网识图同时进行：两边都写 `state.hand`，后完成者获胜。

**建议**：

- 在 `MainActivity` 增加 `recognitionSeq: Long`，每次 `load`/随机生成/联网识图递增；回调携带启动时的 seq，只有 `seq == current` 才写入 UI。
- 让 `CardRecognizer.recognize` 接受 `CancellationToken` 或返回可取消的 `Task`；取消后不再提交新的 ML Kit pass，回调直接丢弃。
- 把候选集合限制在单线程内；若未来使用多 executor，显式加锁或使用 `AtomicInteger`/并发集合，不能依赖“回调一定在主线程”。

### P0-3：本地引擎没有回归测试，现状无法量化

【已确认】`app/src/test/` 中没有 `CardRecognizerTest`、`CardTemplateMatcherTest` 或 `VisualCardCodecTest`；与识图相邻的只有 `CardAssetNamesTest` 和 `UploadImageEncoderTest`。`doc/ai_baseline.md` §4 的本地三阶段结果仍标为「待测」，`testcases/` 有 14 张图但没有 expected JSON。

这意味着：

- 无法回答「这次改模板阈值是变好还是变差」；
- 无法发现 `case3` 的 27 张是否全对、`case4` 的扇形缺几张；
- 无法证明快速路径/编码改动没有破坏生成图回读；
- 文档里的 `case1` 已不在 `testcases/` 中，测试清单本身也已过期。

**建议**：先建立最小可回归基线，再改算法。具体见 §6。

### P1-1：固定聚类半径可能合并相邻牌，`take(27)` 又会掩盖问题

【已确认】聚类半径只由图片短边决定（`CardRecognizer.kt:118`），与牌角框大小、牌间距、拍摄角度无关。以 1176 宽图为例，半径约 44.7px；27 张扇形牌在 1000px 宽度内展开时，相邻可见牌角的间距完全可能小于该值。

【推断】如果相邻牌角的 OCR 中心距离小于半径，它们会被合并成一个簇，导致少牌；反之，旋转/切片之间的坐标误差又可能需要较大半径来合并重复项。这是一个「一刀切」参数，无法同时适配网格图和扇形图。

此外，`located = hits...take(27)` 发生在任何置信度门槛之前（`:134-137`）。排序偏好 `exact` 和面积，因此背景里的大号字母（例如标题中的 `A`、`K`）可能挤掉真实牌角；`rankCount` 只限制每个点数最多 8 张，不检查「同一点数+花色最多 2 张」的两副牌约束。

**建议**：

- 用候选框的宽高/IoU 聚类，而不是固定像素半径：同一牌角在不同 pass 的框应高度重叠；相邻牌角的框通常只有部分重叠。
- 先按 `exact`/模板置信度/框的牌角形状做 NMS，再限制 27 张；不要把 `take(27)` 当作过滤器。
- 在 `finalize` 增加两副牌合法性检查：同一 `(rank, suit)` 超过 2 张时，标记为低置信并提示核对，而不是静默生成非法手牌。
- 用 `case3`（网格）和 `case4`（扇形）分别调半径，记录合并数、缺牌数、重复数，不要只凭单张图调参。

### P1-2：OCR 别名过宽，缺少牌角 ROI

【已确认】`normalize()` 会把单个 `O` 映射为 `Q`、`I` 映射为 `J`、`T` 映射为 `10`（`CardRecognizer.kt:172-175`）。随后 `exact` 只比较规范化文本与点数标签（`:72`），一个背景字母 `O` 就可能得到 `exact=true` 和较高的置信度。

当前唯一的几何过滤是「框高 10px 到 pass 高度的 25%」（`:58`），没有：

- 牌角区域的形状/长宽比约束；
- 点数下方是否存在花色 pip；
- 与相邻牌角的水平/垂直关系；
- OCR 元素的原始置信度（当前代码未使用）。

**建议**：

- 只在「牌角候选 ROI」内接受 OCR；ROI 可以由模板匹配的角点、边缘/轮廓检测，或 OCR 框与下方花色区域的组合得到。
- 对 `O/I/T` 这类单字母别名，要求模板点数一致或存在花色 pip，才允许 `exact`；否则只作为低置信候选。
- 增加框的宽高比/面积范围，过滤标题、按钮、水印等大文本。
- 将 `normalize` 抽成纯函数，用表驱动测试覆盖 `10/1O/IO/I0/T/T0/O/I/SJ/BJ` 等输入，防止后续改动回归。

### P1-3：模板匹配依赖固定旋转 pass，阈值没有语料标定

【已确认】`CardTemplateMatcher`：

- 懒加载 54 张 750×1044 的标准牌面，逐个解码、取固定比例牌角、生成 36×72 布尔签名，然后立即回收（`CardTemplateMatcher.kt:24-43`、`:65-72`）；
- 对 OCR 框扩出牌角区域，二次采样成签名（`:45-56`、`:74-86`）；
- 用 Jaccard 相似度排序，取 best/second，置信度为 `best*0.82 + margin*0.18`（`:57-62`）；
- 阈值 0.48/0.72/0.46 是硬编码常数（`CardRecognizer.kt:63-69`）。

这意味着模板匹配**本身不做旋转归一化**：它依赖外层 8 个固定角度的旋转 pass。牌角实际角度若不在这些角度附近，签名会错位；真实牌面的字体、花色图形、边框与标准素材不同，Jaccard 也会下降。当前没有仓库内证据表明这些阈值经过真实照片校准。

**建议**：

- 先做语料标定：对 `case3/case4` 及新增照片，统计每个阈值下的 rank/suit 准确率与覆盖率，选一个可解释的工作点，而不是继续拍脑袋调 `.48/.72/.46`。
- 按牌角角度拉正后再匹配（可从 OCR 框、轮廓或简单的 Hough/最小外接矩形估计角度），或对每个候选做 0°/±15°/±30° 的小角度模板搜索；不要只依赖整图 8 个旋转。
- 模板加载改为后台预计算/降采样：当前第一次 `match()` 会在 ML Kit 回调线程（通常是主线程）连续解码 54 张全尺寸 PNG，容易造成首轮卡顿。可以把签名预生成到 assets，或只解码牌角区域。
- 对「模板与 OCR 不一致」的情况保留 OCR 结果并标记低置信，不要用 ≥0.72 的模板直接覆盖一个可信的 OCR 点数；先比较两者的 margin。
- 若真实牌面差异大，考虑用 `model_training/` 的 OBB + 牌角分类器替换模板；默认 learned 已由 ADR-0044 启用，但仍必须保留 manifest/hash 校验、失败回退和性能风险记录。

### P1-4：花色分类的固定阈值与黑桃回退

【已确认】`classifySuit()` 在点数框下方取一个窄区域，统计红色/深色像素，并用上/中三分之一的宽度比较判断方块/红桃、黑桃/梅花（`CardRecognizer.kt:178-191`）。阈值是固定的 RGB 规则（`r > g*1.3 && r > b*1.3 && r > 65`，深色 `r+g+b < 300`）。

风险：

- 暖光、阴影、低对比度、彩色牌背会让固定阈值失效；
- 该区域可能混入牌面中心图形、相邻牌或手指；
- 旋转 pass 中花色 pip 的方向改变，宽度轮廓假设不再成立；
- 花色投票全部用满时回退 `Suit.SPADE`（`:148`），可能生成不合法的同花色重复，而不是「不确定」。

**建议**：

- 用 HSV/Lab 或自适应阈值替代固定 RGB；对红色/黑色分别建模，保留每个花色的得分而不是只取红色/深色计数。
- 花色分类输出 `Suit?` 或 `SuitVote(suit, confidence)`；不确定时不要强行回退黑桃，UI 明确标「花色待核对」。
- 优先使用模板花色；颜色分类只作为 fallback，并记录两者是否一致。
- 对 `(rank, suit)` 做两副牌上限校验，超出时把该张标为冲突，要求人工确认。

### P1-5：警告/状态语义混乱，`Success` 实际不可达

【已确认】

- 快速路径返回 `listOf("已通过图片校验码准确读取 27 张牌")`（`CardRecognizer.kt:39-41`），而 UI 把任何非空 warning 都映射成 `Partial`（`MainActivity.kt:596-600`）。因此**最准确的条码回读也显示「有 1 条警告」**。
- 普通 OCR 路径的 `stagedWarnings` 至少包含「第一步张数」「第二步点数」「第三步花色」三条（`:162-167`），所以普通照片也永远不会是 `Success`。
- `stagedWarnings` 的「第一步张数」用 `located.size`，「第二步点数」用 `cards.size`；由于 `cards` 就是 `located.map`，两个数字必然相同（`:141`、`:163-164`），并没有真正报告「点数识别失败了几张」。
- `warnings.drop(3)`（`:167`）在 `cards.size == 27` 时会把索引 2 的「已合并 N 个旋转/局部重复结果」丢掉；只有缺牌时这条提示才可能保留。合并提示恰好在最需要解释「为什么 27 张里有些位置不对」的时候消失。

**建议**：

- 把结果从 `List<String>` 改为带级别的 `RecognitionIssue(level, code, message)`，至少区分 `INFO`（条码命中、合并数）与 `WARNING`（缺牌、花色不确定、非法组合）。
- `Success` 的条件改为「27 张且没有 warning」；条码命中应返回 `INFO` 而不是 warning。
- 阶段报告使用真实计数：定位数、点数识别数、花色可信数、冲突数；不要用同一个 `cards.size` 冒充两个阶段。
- 删除 `drop(3)` 这种依赖条件索引的写法，按 code 显式组装要展示的 issue。

### P2-1：`VisualCardCodec` 的固定位置单行采样

【已确认】`decode()` 只按图片宽度计算 cell 和左边距，固定取 `height - cell*2.2` 这一行，逐 bit 读取（`VisualCardCodec.kt:33-40`）。它没有：

- 搜索条码位置（依赖固定底部位置）；
- 多行采样/多数投票；
- 纠错码（只有 8-bit 校验和，检测到错误就返回 null）；
- 对非等比缩放、裁剪、边框的容错。

**建议**：

- 如果该路径只服务本 App 原图，在注释和测试中明确约束（等比缩放、无裁剪、底部完整），不要宣称通用“scale-tolerant”。
- 若需要支持聊天软件转发后的图片，增加同步头搜索、多行采样、CRC/纠错，或改用标准 QR；这会增加复杂度，优先级低于 P0/P1。
- 增加 `render → encode → decode` 的往返测试，覆盖 1800×1200、1176×2400、等比缩小、底部裁切、JPEG 压缩等场景。

### P2-2：`HandImageRenderer` 的全尺寸牌面缓存

【已确认】`cardImages` 是 `MutableMap<String, Bitmap>`（`HandImageRenderer.kt:20`），`drawCard()` 用 `BitmapFactory.decodeStream` 解码完整 750×1044 PNG（`:93-99`），然后缩放到约 177×246 的目标矩形。按 ARGB_8888 计算，单张约 3.1MB；27 张约 84MB，最多 54 张约 169MB，且 Activity 存活期间不释放。

这虽然不属于 OCR 主链路，但会在「随机手牌 → 生成组牌图 → 再随机」时持续抬高进程内存，和 P0-1 的峰值叠加。

**建议**：

- 按目标尺寸解码（`inSampleSize` / `inScaled` / 先解码到约 2× 目标尺寸），或使用 `LruCache` 限制缓存字节数；
- 随机手牌和组牌图共用同一个受限缓存；
- 若只生成 1800×1200 图，没必要保留 750×1044 原图；需要高分辨率输出时再按需解码。

### P2-3：生命周期与死代码

- `CardRecognizer` 持有 ML Kit `TextRecognizer`，但没有 `close()`；`MainActivity` 销毁时未释放。建议增加 `close()` 并在 `onDestroy()` 调用。
- `recognizeGenerated()`（`CardRecognizer.kt:86`）当前没有调用方；保留会让“生成图回读”的职责出现两个入口。建议删除，或只在测试中使用。
- `RecognitionResult` 只有 `cards + warnings`，缺少阶段统计和候选质量信息，导致 UI 无法展示「哪一步失败」。建议增加轻量 `stats`（pass 数、候选数、合并数、不确定花色数、耗时），仅用于日志/调试展示，不包含图片内容。

## 5. 可自动化落地的目标方案

### 5.1 目标与边界

目标态不是“再换一个 OCR”，而是把相机照片的识别拆成两个可训练阶段：

1. **牌角检测**：找出每张可见牌的左上牌角，输出四点/旋转框；
2. **牌角分类**：把牌角透视拉正，输出点数与花色；
3. **规则装配**：去重、排序、两副牌上限、置信度与人工核对。

保留：

- `VisualCardCodec` 条码快速路径：本 App 生成图优先，不进入模型；
- 现有 ML Kit/模板路径作为模型缺失、加载/推理失败或低置信度时的 fallback；
- 人工核对；完全遮挡的牌不猜。

不做：

- 不训练 54 类检测器；检测器只学 `card_corner`，分类器单独学 15 个点数 × 5 个花色；
- 不把公开 52 类权重直接上线；
- 不引入云端识别，不上传图片。

### 5.2 目标架构

```text
MainActivity / UI
  └─ RecognitionCoordinator（worker、jobToken、timeout）
      ├─ 1. VisualCardCodec（生成图快速路径）
      ├─ 2. LearnedRecognitionEngine
      │      ├─ ImagePreprocessor（EXIF、精确限长、RGB、letterbox）
      │      ├─ CardCornerDetector（YOLO11n-OBB，单类 card_corner）
      │      ├─ CornerRectifier（四点透视拉正到 96×128；0°/180° TTA）
      │      ├─ CardCornerClassifier（MobileNetV3Small，15 ranks × 5 suits）
      │      └─ CardAssembler（NMS、排序、两副牌规则、置信度）
      └─ 3. HeuristicCardRecognizer（现有 ML Kit + 模板，fallback）
  → RecognitionResult(cards, issues, stats)
```

检测器只回答“牌角在哪里、朝向如何”；分类器只回答“这个牌角是什么”。两者分开后，新增牌面样式主要重训分类器，不必重训几何检测器。

### 5.3 运行时流程

1. **解码**：在 `Dispatchers.Default` 解码，按长边精确缩放到 `1600px`（不是整数除法）；先读 EXIF，再应用方向。只保留一张工作位图，禁止 18 个 pass 同时驻留。
2. **条码优先**：调用 `VisualCardCodec.decode()`。命中且校验通过时直接返回 27 张，状态为成功，不再跑模型。
3. **检测预处理**：RGB、letterbox 到 `640×640`（质量档可 `960×960`），记录缩放和平移矩阵；不做 8 个整图旋转。
4. **牌角检测**：YOLO-OBB 输出单类 `card_corner` 的四点/旋转框；按 manifest 中的分数阈值过滤，NMS 后映射回原图。
5. **几何过滤**：丢弃过小、贴边严重、宽高比异常的框；按多边形 IoU/中心距去重；最多保留 27 个候选，但**不要用 `take(27)` 代替置信度过滤**。
6. **拉正**：把每个四点框按左上、右上、右下、左下排序，透视变换到 `96×128`；分类器同时跑 `0°` 与 `180°`，取联合概率更高者，解决 OBB 角度 180° 歧义。
7. **分类**：输出 15 个点数 × 5 个花色的 softmax；要求 top-1 置信度和 top-1/top-2 margin 都过阈值 [`thresholds`]，否则标记 `UNKNOWN`，不猜。
8. **装配**：按牌的几何顺序排序；同一 `(rank, suit)` 最多 2 张，大小王各最多 2 张；超出时标记冲突，不静默改牌。少于 27 张时按实际数量返回，不补齐。
9. **输出**：`RecognitionResult(cards, issues, stats)`；`issues` 区分 `INFO/WARNING/ERROR`，并记录检测数、分类数、未知数、合并数、耗时。

分类器的 0°/180° TTA 只增加很小的计算量，但能避免把倒置牌角强行分类成另一个点数。若 margin 仍低，保留原图牌角供人工核对。

### 5.4 模块与接口

建议新增的 Kotlin 边界（名称可调整，职责不变）：

| 模块 | 职责 |
| --- | --- |
| `RecognitionEngine` | 统一接口：`suspend fun recognize(bitmap, token): RecognitionResult` |
| `RecognitionCoordinator` | 单飞、jobToken、超时、选择条码/模型/启发式路径 |
| `CardCornerDetector` | LiteRT 检测器加载、letterbox、OBB 解码、NMS |
| `CardCornerClassifier` | LiteRT 分类器加载、透视拉正、0°/180° TTA、阈值 |
| `CardGeometry` | 四点排序、多边形 IoU、透视矩阵、坐标映射；纯 Kotlin，可 JVM 单测 |
| `ModelManifest` | 读取/校验 assets 中的模型版本、输入尺寸、标签、阈值、SHA-256 |
| `RecognitionResult` | `cards + issues + stats`，替代裸 `List<String>` warning |

`CardGeometry` 和 `ModelManifest` 不依赖 Android `Bitmap` 或 LiteRT，便于在 JVM 上做几何/阈值回归；只有 detector/classifier 适配层接触 Tensor 和 Bitmap。

### 5.5 数据自动化

#### 5.5.1 公开数据：只做预训练，不做最终验收

公开数据可以显著减少人工标注，但没有找到可直接覆盖“掼蛋 54 类 + 大小王分开 + 两副牌 + 真实牌面”的成熟数据集。可用来源如下：

| 来源 | 内容 | 许可 | 用法 | 缺口 |
| --- | --- | --- | --- | --- |
| [Kaggle Playing Cards Object Detection](https://www.kaggle.com/datasets/andy8744/playing-cards-object-detection-dataset) | 约 2 万张合成图，52 类，416×416，牌角框 | **CC0** | 预训练牌角检测器 | 无大小王；合成域 |
| [Hugging Face JackFurby/playing-cards](https://huggingface.co/datasets/JackFurby/playing-cards) | 4 组 × 1 万张，52 类，提供每张牌四个角点 | **MIT** | 四点转 OBB，预训练牌角/整牌几何 | 无大小王；合成域 |
| [TeogopK Playing-Cards-Object-Detection](https://github.com/TeogopK/Playing-Cards-Object-Detection) | 2 万合成 + 100 张真实 + 增强数据，YOLOv8 模型 | **CC0**（数据集） | 预训练 + 小真实集微调参考 | 真实集仅 13 类；不含完整 54 类 |
| [Kaggle Complete Playing Card Dataset](https://www.kaggle.com/datasets/jaypradipshah/the-complete-playing-card-dataset) | 53 类，约每类 50 张，含一个 Joker 类 | **Unknown** | 仅调研/补 Joker 分类 | 只有一个 Joker 类；许可不明，不能直接进发布产物 |
| [PD-Mera Playing-Cards-Detection](https://github.com/PD-Mera/Playing-Cards-Detection) | YOLOv8s，52 类，22MB 权重，合成测试 mAP50 0.995 | 仓库未明确许可 | 只做 baseline/初始化 | 52 类、整牌框、许可需核对 |

使用规则：

- 只把 **CC0、MIT、公共领域或明确书面许可** 的数据纳入发布训练集；`Unknown` 只做调研，不进入最终模型。
- 公开权重只用于 baseline，不直接随 APK 分发；权重许可和数据许可分开审查。
- 公开数据主要用于学习“牌角/几何/基础点数花色特征”，最终验收必须在自有牌面 + 真实照片上完成。
- 没有找到 54 类且大小王分开的成熟检测数据；大小王、两副牌和真实牌面必须用自有数据补足。

#### 5.5.2 合成数据：项目自己的主训练集

仓库已有 54 张有效牌面（15 个点数、5 个花色，含 `SJ/BJ` 与 `JOKER`）和 `HandImageRenderer` 的牌面布局。建议新增 Python 合成器，不依赖 Android 运行时：

- 从两副 108 张牌中无放回抽 1–27 张；允许同一 `(rank, suit)` 出现两次；
- 扇形/网格/散牌三种布局；随机角度、间距、重叠 0–80%、缩放、透视；
- 随机背景、光照色温、阴影、模糊、噪声、JPEG 压缩、局部遮挡；背景只用自有照片或明确可商用的 CC0 纹理，不要默认把 DTD 等研究数据用于发布训练；
- 负样本：无牌、牌背、半张牌、文字/按钮、其他物体；
- 大小王单独采样，保证两类都有足够样本；
- 标签由渲染参数直接得到：整牌四点、牌角四点、点数、花色、是否可见；
- 牌角标签定义为牌面坐标 `x∈[0, 0.35]`、`y∈[0, 0.35]` 的左上区域，经牌面单应变换回图像；这与 `96×128` 的输入比例接近。

首轮建议生成约 2 万张场景（可按机器调整），按 **场景/种子** 划分 train/val，不按裁剪图随机划分。留出未出现过的背景、牌面样式和光照作为验证集，避免“合成验证集很好看、真实照片失效”。

#### 5.5.3 真实照片：主动学习，不做固定 300–500

真实照片只解决合成域和真实牌面之间的差距，不承担从零训练：

1. 用公开数据 + 合成数据训练第一版；
2. 在真实照片上跑推理，按低置信度、top-1/top-2 margin 小、检测框贴边、张数不符、两副牌冲突排序；
3. 只把失败样本送进现有 `annotate.py` 校正；成功样本不重复标注；
4. 校正结果写入 `annotations.json`，下次训练自动合并；
5. 用固定 golden set 评估，不用训练样本证明自己。

TeogopK 用 100 张真实图 + 增强做微调，说明小真实集可以作为起点；但这不是“100 张一定够”的承诺。真实样本数量由错误切片决定：某一类/某一光照持续错，就补那一类，而不是凑一个固定总数。

`case3` / `case4` 作为 golden set 时不得进入训练；如果必须用于微调，另留一组完全未参与训练的真实照片做最终验收。

#### 5.5.4 数据集清单与防泄漏

所有数据统一进入规划路径 `model_training/datasets/dataset_manifest.json`（图片本体可 gitignore，目录随实现创建）：

```json
{
  "source": "synthetic|public|real",
  "license": "CC0|MIT|own|unknown",
  "split": "train|val|test",
  "image": "relative/path.jpg",
  "scene_id": "unique-photo-or-seed",
  "cards": [
    {"quad": [[0, 0], [1, 0], [1, 1], [0, 1]], "rank": "A", "suit": "SPADE", "visible": true}
  ]
}
```

硬性规则：

- 按 `scene_id` 划分，同一张照片/同一个合成场景不能同时出现在 train 和 val；
- `license=unknown` 默认不进 train；
- 记录图片/模型 SHA-256 和生成参数，保证可复现；
- 训练、验证、golden 三组互斥；任何调参都不能看 golden 结果反复拟合。

### 5.6 训练、校准与导出

#### 5.6.1 牌角检测器

- 模型：`YOLO11n-OBB`（`model_training/train_detector.py` 当前默认）或锁定版本支持的更新 OBB 模型；单类 `card_corner`。
- 输入：默认 `640`；若真实照片小牌角召回不足，再评估 `960` 或对长图分块，不要先上更大模型。
- 初始化：公开数据/公开权重预训练 → 自有合成数据 → 真实校正集微调。
- 增强：旋转 ±15°、透视、缩放、平移、亮度/对比度/模糊/JPEG；不做水平翻转（会破坏牌角朝向语义）。
- 导出：在锁定的 Ultralytics 版本上验证 `format="litert"` 或 `format="tflite"` 的 OBB 输出；官方 LiteRT 导出已列 OBB 支持，但不能假定当前脚本的 `half=True` 在所有设备上可用。导出后必须用固定图片核对输出形状、四点顺序和分数。

#### 5.6.2 点数/花色分类器

- 模型：MobileNetV3Small，输入 `96×128`，RGB /255；沿用 `model_training/train.py` 的双头结构。
- 标签：严格使用 `labels.py` 的 15 个点数 × 5 个花色（输出头 75 个位置，但只接受 54 个有效组合）；`SJ/BJ` 只能配 `JOKER`；标签顺序写进 manifest，Android 不另写一份。
- 训练：公开 52 类做初始化 → 自有合成牌角 → 真实校正；类别不均衡时用 class weight，不用过采样把某一类刷到过拟合。
- 增强：小角度旋转、透视、亮度/对比度、模糊、JPEG、轻度遮挡；不做水平翻转。
- 校准：在验证集上做 temperature scaling；输出 top-1 概率和 margin，阈值由 manifest 提供。
- 导出：float16 优先；INT8 必须用真实校正集做代表样本校准，并单独比较 6/9、10/J、红黑花色和大小王。

#### 5.6.3 模型 manifest

```json
{
  "version": 1,
  "detector": {"file": "card_corner_obb.tflite", "input": [1, 640, 640, 3], "scoreThreshold": 0.30, "nmsIou": 0.50},
  "classifier": {"file": "card_corner_classifier.tflite", "input": [1, 128, 96, 3], "ranks": ["2", "3", "...", "BJ"], "suits": ["CLUB", "DIAMOND", "HEART", "SPADE", "JOKER"]},
  "thresholds": {"cardConfidence": 0.90, "margin": 0.15},
  "sha256": {"detector": "...", "classifier": "..."}
}
```

阈值、输入尺寸、标签顺序和校验和都由 manifest 驱动；Kotlin 不硬编码。manifest 不合法或校验失败时，整个模型路径禁用并回退。

### 5.7 Android 接入

1. **依赖**：加入 `com.google.ai.edge.litert:litert:2.3.0`（官方要求 API 24+，项目 `minSdk 26` 满足）；GPU delegate 作为可选优化，CPU/XNNPACK 必须可用。
2. **资产**（规划目录）：`app/src/main/assets/models/` 放 detector、classifier、manifest；不联网下载，不把 `model_training/` 的 Python 依赖带进 APK。
3. **线程**：`RecognitionCoordinator` 在 `Dispatchers.Default` 执行解码/推理；同一时刻只允许一个识别 job；新 job 递增 token，旧回调直接丢弃；设置总超时（建议 6s），超时取消并回退。
4. **内存**：只保留一张限长工作位图和必要的 96×128 牌角；检测输入用直接 `ByteBuffer`，复用缓冲；不要为每个候选创建全尺寸 Bitmap；模型 `close()` 放在 `onDestroy`。
5. **特征开关**：提供 `heuristic | learned` 显式覆盖；生产默认 `learned`（ADR-0044），模型加载/推理失败回退 `heuristic`。`shadow` 仍只跑模型做差异记录，不把模型结果写入正式 UI。
6. **结果映射**：`CardRecognizer` 的 `List<String>` 改为 `RecognitionResult(cards, issues, stats)`；条码命中是 `INFO`，低置信度是 `WARNING`，冲突/无法识别是 `ERROR`；UI 显示待核对数量，不显示“全对”除非确实无 warning。
7. **回退**：模型文件缺失/校验失败/加载异常/超时/有效牌角过少 → 现有 ML Kit 路径；模型低置信度 → 返回部分结果并进入人工核对，不静默补齐。

### 5.8 失败与回退矩阵

| 条件 | 行为 |
| --- | --- |
| 条码命中且校验通过 | 直接成功，标记 `INFO`，不跑模型 |
| 模型缺失、manifest 非法、SHA-256 不匹配 | 禁用模型，回退启发式路径 |
| 检测超时/推理异常 | 取消当前 job，回退并提示；不能卡住 UI |
| 可见牌角少于预期 | 返回实际数量 + `WARNING`，不补牌 |
| 同一 `(rank, suit)` >2 或大小王 >2/类 | 标记冲突，要求人工核对 |
| 分类 top-1 或 margin 低于阈值 | 该张 `UNKNOWN`，保留牌角图供核对 |
| 连续换图/随机/联网识图 | 只有最新 jobToken 能写 UI |

### 5.9 自动化脚本与 CI

| 脚本/任务 | 输入 | 输出 |
| --- | --- | --- |
| `fetch_public_data.py` | 数据源 URL/API、许可白名单 | 下载文件、hash、license 记录 |
| `convert_public_annotations.py` | 公开四点/牌角框 | 统一 `dataset_manifest.json`、OBB 标签 |
| `generate_synthetic.py` | 54 张牌面、背景、随机种子 | 合成图、四点/牌角标签、scene_id |
| `train_detector.py` | manifest、OBB 数据集 | `best.pt`、LiteRT/TFLite、检测指标 |
| `train.py` | manifest、牌角裁剪 | Keras/SavedModel、classifier、manifest |
| `evaluate.py` | 模型、golden set | 检测/分类/端到端指标、混淆矩阵 |
| `active_learning.py` | 未标注照片、当前模型 | 低置信度队列、待校正 crop |
| `install_model.ps1`（已有） | `output/` | Android assets |
| `verify_models.py` | assets + manifest | 形状/标签/SHA-256/阈值校验 |

CI 分层执行：Pull request 只做 Python lint/类型检查、标签与几何单测、manifest schema、数据泄漏检查、模型文件形状冒烟和 Gradle JVM 单测；Nightly/manual 执行固定 seed 的小规模训练冒烟、完整 golden 评估、模型导出及 Python/Android 输出一致性检查；API 29 真机 instrumentation、性能/内存/竞态测试由设备 runner 或人工执行。没有设备证据时必须标记 `NOT_RUN`，不能用模拟器结果代替。每次模型更新都记录数据版本、训练命令、随机种子、依赖版本、模型 hash 和评估结果。

**自动化执行契约**：所有脚本必须提供稳定 CLI，路径可显式传入且相对路径以仓库根目录解析；不得依赖交互式输入或当前工作目录。成功退出码为 `0`，参数/数据错误为 `2`，训练/评估失败为 `1`，外部依赖不可用为 `3`。每次运行写入独立的 `output/runs/<run_id>/`，不得覆盖已有 run；至少输出机器可读 `report.json`，包含 `schema_version`、`status`（`PASS/FAIL/NOT_RUN/BLOCKED`）、时间、完整参数、输入文件 SHA-256、数据集版本、随机种子、依赖版本、输出文件 hash、指标、警告和错误。缺少必需字段、非有限数值、空数据集或样本数不足不得判为 PASS。

建议新增 `validate_dataset.py`（检查文件/hash、标签范围、类别覆盖、许可白名单、scene 分组和数据泄漏）、`calibrate.py`（只用独立 calibration split 计算温度参数/阈值并报告 ECE/Brier）、`import_corrections.py`（只导入带审核人、来源样本 hash 和审核时间的人工修正）。`generate_synthetic.py` 必须接收显式随机种子并写入 scene_id；`evaluate.py` 必须输出逐样本结果、混淆矩阵和评估报告；`install_model.ps1` 必须在安装后校验文件 hash，并原子替换 assets。所有脚本失败必须返回非零退出码，错误写入 stderr。

数据集按 `scene_id` 分组后再划分 train/val/calibration/test；同一场景、连拍及其裁剪/增强版本不得跨集合。golden/test 不参与训练、调参或阈值选择。`license=unknown`、来源不明或不在许可白名单内的数据必须阻止训练。许可审查、golden 标签确认、真实照片可见性确认和最终发布 QA 属于人工签核边界；自动化只能检查签核记录是否存在，不得自行批准。阶段门禁必须同时核对报告、样本数、数据版本、模型 hash、设备证据和人工签核，缺失时标记 `BLOCKED` 或 `NOT_RUN`。

### 5.10 分阶段实施与验收门槛

| 阶段 | 交付物 | 通过条件 |
| --- | --- | --- |
| 0. 安全垫 | 精确限长、worker、jobToken、条码回归、golden JSON | 现有启发式路径不回归；无旧结果覆盖；有基线数据 |
| 1. 数据自动化 | 公开数据转换、合成器、manifest、许可表 | 54 类覆盖；两副牌重复场景；无 unknown 许可进入 train |
| 2. 检测器 | 单类 OBB 模型、LiteRT 导出、检测评估 | 可见牌角 recall ≥99%；误检 ≤0.1/图；角点 IoU ≥0.8 |
| 3. 分类器 | 15×5 双头模型、校准、混淆矩阵 | 真实留出集 exact card ≥99.5%；大小王 ≥99%；ECE ≤0.05 |
| 4. Android 灰度 | manifest 驱动推理、fallback、shadow | 模型资产 ≤15MB；真机 p95 ≤3s；峰值 <150MB；无 OOM/ANR；可回退 |
| 5. 主动学习/发布 | 真实校正集、版本化模型、A/B 报告 | 端到端每张 ≥99.5%；27 张全对 ≥85% 起步、≥90% 发布；复核率 ≤5%；自信错误 ≤1% |

以上是建议门槛，不是已测结果；必须用 API 29 真机和独立真实照片校准。任何一项不达标，模型保持 shadow，默认仍走启发式路径。`case3` 27/27 与 `case4` 可见牌 ≥95% 继续作为两种引擎的共同回归门槛。

## 6. 测试与验收基线

本节保留现有启发式引擎的回归基线；学习模型另见 §5.10，两种引擎共用同一套 golden set。

### 6.1 先固定一组「可判定」的样本

| 图片 | 场景 | 期望 |
| --- | --- | --- |
| `case3.png` | 27 张网格、无遮挡、逐张可读 | 27/27，点数和花色全对 |
| `case4.jpg` | 桌面扇形、含 Joker、有重叠 | 所有可见牌角的 exact card 正确；被完全遮挡的牌不猜 |
| `case2.jpg` | 750×492 小图、扇形、干扰文字 | 只用于鲁棒性，不作为精确基准 |
| 新增 `IMG_*` | 不同牌面/光照/角度 | 每张照片人工确认可见牌角，写入 expected JSON |

`doc/ai_baseline.md` 已有 case3/case4 的人工牌面，直接把本地结果补进 §4；同时修正文档里已不存在的 `case1` 引用，避免测试清单继续漂移。

### 6.2 指标

- **张数**：27 张基准上的 count error；缺牌/多牌数量。
- **点数准确率**：rank accuracy；区分 OCR 错、模板覆盖错、聚类合并。
- **花色准确率**：suit accuracy；单独统计「点数对但花色错」。
- **整牌准确率**：rank + suit 同时正确的 exact card accuracy，这是主指标。
- **性能**：冷启动/热启动识别 p50/p95；每阶段耗时；峰值 Java/native 内存；OOM/ANR 次数。
- **稳定性**：连续导入 20 张、连续随机 20 次、识别中换图/点随机/联网识图，无旧结果覆盖、无崩溃。
- **快速路径**：`VisualCardCodec` 往返 100 次、缩放/JPEG/裁切变体，命中率与误命中率。
- **检测**：可见牌角 recall、误检/图、角点多边形 IoU。
- **分类**：15×5 exact、混淆矩阵、ECE、未知率。
- **端到端**：每张 exact、27 张全对率、复核率、自信错误率。

初始门槛建议（实测后校准，不要直接当产品承诺）：

- `case3` 27/27 exact；`case4` 可见牌 exact ≥95%；
- 学习模型：检测 recall ≥99%，分类 exact ≥99.5%，端到端每张 ≥99.5%，27 张全对 ≥85%（发布 ≥90%）；详见 §5.10；
- 识别 p95 ≤3s（中端 API 29 设备），峰值内存 <150MB；
- 连续换图/随机 20 次无旧结果覆盖、无 OOM；
- 所有结果仍进入人工核对，不允许跳过确认直接计算。

### 6.3 测试层次

1. **JVM 纯函数**：`normalize`、`Rank.parse`、花色投票、聚类距离/IoU、`VisualCardCodec` 的 bit 打包（把 Android Bitmap 采样与 bit 编解码拆开）。
2. **Robolectric/ instrumentation**：`CardTemplateMatcher` 的 54 张素材签名、`HandImageRenderer → VisualCardCodec` 往返、缺失素材检测。
3. **真机 instrumentation**：`CardRecognizer` 对 case3/case4 的完整输出；记录耗时、内存、pass 数；同一测试可复用于回归。
4. **竞态测试**：快速连续 load 两张图、识别中点随机/联网；断言最终状态属于最新操作。
5. **性能测试**：固定图片重复 10 次，取 p50/p95；用 `Debug.MemoryInfo` 或 `dumpsys meminfo` 记录峰值；在 API 29 真机上跑，不只 JVM。

## 7. 迁移、回退与许可

### 7.1 迁移顺序

`model_training/` 已有 OBB 旋转检测 + 透视拉正 + 点数/花色分类的训练框架，但仓库里还没有已验收的 `.tflite` / `model_manifest.json`，Android 依赖中也没有 LiteRT。因此当前正式路径仍是 ML Kit OCR + 模板；§5 是目标态，不是已实现功能。

迁移必须按下面的顺序，不能跳步：

1. 完成 §5.10 阶段 0 的管线安全垫和 golden baseline；
2. 完成阶段 1–3，产出检测器、分类器和独立评估报告；
3. Android 接入 `heuristic | learned` 开关；默认 learned，失败回退 heuristic，shadow 仅作差异对照；
4. shadow 对比差异，按 `case3` / `case4` 和真实照片定位错误；
5. 全部门槛通过后，才把 learned 设为默认；保留启发式路径至少一个发布周期，确认可回退后再考虑移除。

### 7.2 回退原则

- 模型加载失败、manifest 校验失败、推理超时或有效牌角过少 → 立即回退 ML Kit + 模板；
- 模型结果低置信度 → 部分结果 + 人工核对，不静默补齐；
- 每次模型更新都保留旧版本资产和 manifest，出问题可切回；
- 条码快速路径永远优先，不能被模型路径替代。

### 7.3 许可与来源

| 对象 | 许可/风险 | 处理 |
| --- | --- | --- |
| Kaggle `andy8744`、TeogopK 数据集 | CC0 | 可用于训练；保留来源记录 |
| Hugging Face `JackFurby/playing-cards` | MIT | 可用于训练；保留版权声明 |
| Kaggle `Complete Playing Card Dataset` | Unknown | 只调研，不进入发布训练集 |
| 公开 YOLO 权重（如 `playingCards.pt`、`yolov8s_playing_cards.pt`） | 仓库许可常不明确 | 只做 baseline，不随 APK 分发 |
| Ultralytics YOLO 代码/权重 | 默认 AGPL-3.0 | 闭源商用分发前确认商业许可；不能默认“训练出来就是自己的” |
| LiteRT / MediaPipe | Apache-2.0 | 可随 App 使用；保留 NOTICE |
| 自有牌面素材 | 项目自有/已授权 | 作为合成训练和发布的唯一牌面来源 |

许可审查要同时覆盖 **数据、代码、预训练权重、最终模型** 四层；任何一层不明确，都不进入发布产物。模型和数据集都记录来源、版本、hash 和许可，避免以后无法追溯。

## 8. 不建议的做法

- **不要只调高模板阈值**：会减少错牌，但也会增加缺牌；缺牌对用户更不可恢复。先建立语料和混淆矩阵。
- **不要用规则猜被遮挡的牌**：物理上不可见的信息不能从单张照片恢复；继续交给用户补录/校正。
- **不要直接把 18 个 pass 改成更多角度**：当前瓶颈已经是内存和 OCR 次数，增加 pass 只会放大问题。
- **默认启用模型时必须保留回退和体积/冷启动校验**：ADR-0044 已明确默认 learned；不要删除 heuristic，也不要在没有回退和证据的情况下继续扩大模型。
- **不要从零人工标 300–500 个牌角**：先用公开数据/合成数据预训练，真实照片只做失败样本校正；数量由错误切片决定，不设固定总数。
- **不要直接把公开 52 类权重上线**：它们没有大小王、两副牌重复实例和掼蛋真实牌面，且许可常不明确。
- **不要把检测器训练成 54 类**：检测器只学 `card_corner`，点数/花色交给分类器；否则小数据下类别竞争会让检测更差。
- **不要用 `take(27)` 当作置信度过滤**：它只会按 exact/面积截断，无法区分真牌和背景大字。
- **不要把条码路径当成通用识别**：它只适用于本 App 生成且底部完整的图片。

## 9. 关键代码索引

| 位置 | 内容 |
| --- | --- |
| `CardRecognizer.kt:38-42` | 条码快速路径 |
| `CardRecognizer.kt:43`、`:88-105` | 18 个 pass 的同步构建 |
| `CardRecognizer.kt:107-115` | 旋转图与逆坐标映射 |
| `CardRecognizer.kt:54-83` | ML Kit 候选生成、模板覆盖、花色 fallback |
| `CardRecognizer.kt:117-137` | 固定半径聚类、限张、`take(27)` |
| `CardRecognizer.kt:143-154` | 花色投票与黑桃回退 |
| `CardRecognizer.kt:155-168` | 警告文案、阶段计数、`drop(3)` |
| `CardRecognizer.kt:172-191` | OCR 归一化与花色像素分类 |
| `CardTemplateMatcher.kt:24-63` | 54 张模板的懒加载、匹配、置信度 |
| `CardTemplateMatcher.kt:74-96` | 36×72 签名与 Jaccard |
| `VisualCardCodec.kt:13-47` | 191-bit 编码/固定位置解码 |
| `HandImageRenderer.kt:20`、`:93-99` | 全尺寸牌面缓存与解码 |
| `MainActivity.kt:558-579` | 输入图整数倍降采样 |
| `MainActivity.kt:581-613` | 主线程调用识别、UI 状态映射 |

---

**一句话建议**：短期先把「输入图精确限长 + 后台构图 + 按需 pass + 及时回收」做完，并用 case3/case4 建立可复现基线；中期按 §5 建「公开数据/合成数据 → OBB 牌角检测 → 15×5 分类器 → 主动学习校正」的自动化管线；当前已由 ADR-0044 将模型设为默认并保留启发式回退；§5.10/T5.3 仍是未完成的质量证据，不得写成验收通过。
