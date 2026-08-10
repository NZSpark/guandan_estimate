# 独立牌角模型训练工具

该目录与 Android 构建环境解耦，用真实手牌照片训练离线“点数 + 花色”双输出分类模型，并导出 Android 可使用的 LiteRT/TensorFlow Lite 文件。训练不会调用联网识图 API；首次使用 ImageNet 初始化权重时，TensorFlow 可能下载公开预训练权重，可用 `--no-imagenet` 禁止。

## 1. 准备环境

建议使用 Python 3.11，并在本目录创建独立虚拟环境：

```powershell
py -3.11 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
```

如果启动标注器时报错 `cannot import name '_imaging' from PIL`，说明虚拟环境中混入了其他 Python 版本的 Pillow 二进制文件，可执行：

```powershell
python -m pip install --force-reinstall --no-cache-dir "Pillow>=11,<12"
```

训练依赖不会加入 Android APK。

## 2. 标注真实照片

```powershell
python annotate.py
```

首次打开后可点击“自动标注当前”或“自动标注全部未标注”。自动流程离线寻找候选牌角、估算四点边界和角度，并给出点数、花色与置信度。橙色 `⚠` 项必须逐一人工检查；选择项目、修正边界/角度/标签后，点击“应用标签/确认”变为绿色 `✓`。未经确认的自动标注不会进入检测或分类训练。

操作说明：

- 在每张照片中框选可见的左上牌角，框内应同时包含点数及其下方花色。
- 先选择点数和花色，再按住鼠标左键拖出标注框。
- 新建框会自动选中；也可在右侧列表单击选择，或在图片中使用左键/右键选择。
- 点击“删除选中框”或按键盘 `Delete` 删除选中标注。
- 选中框后，在框内按住鼠标左键拖拽，可整体移动标注框。
- 拖动四个黄色角点可沿标注框当前方向缩放，缩放时保持旋转矩形结构。
- 拖动标注框上方的蓝色圆形手柄，可围绕中心连续旋转标注框；手柄位于图片边缘外时仍可操作。
- 在“倾斜角°”输入牌角角度并点击“应用角度”，使四边形贴合牌角方向；正角度为顺时针。
- 修改下拉框中的点数或花色后，点击“应用标签/确认”保存修改并确认自动结果。
- “上一张”“下一张”和关闭窗口都会自动保存到 `annotations.json`。
- 大小王分别使用 `SJ/BJ`，花色必须选择 `JOKER`。

照片应按实际可见牌角标注；完全遮挡、无法判断的牌不要猜测。标注保存为四点边界，训练时先进行透视校正，再进入点数和花色分类。训练前建议至少标注80个牌角，正式模型建议覆盖数百至数千个牌角，并包含不同背景、角度、光照和遮挡。

## 3. 训练第一阶段：牌角边界与倾斜角度

四点标注可直接用于旋转目标检测：

```powershell
python export_obb.py
python train_detector.py --epochs 100
```

`export_obb.py` 按照片划分训练集和验证集并生成 YOLO OBB 数据；检测模型输出每个可见牌角的四点边界和倾斜角度。导出的 TFLite 检测模型负责识别张数、边界和角度，不负责猜测点数或花色。

## 4. 训练第二阶段：点数和花色

```powershell
python train.py --epochs 35
```

分类训练会先按检测框进行透视拉正，再识别点数和花色。程序按“照片”而非单个牌角划分训练集和验证集，避免同一照片的近似样本同时进入两边。输出位于 `output/`：

- `best.keras`：可继续训练的 Keras 模型；
- `saved_model/`：TensorFlow SavedModel；
- `card_corner_classifier.tflite`：手机端离线模型；
- `model_manifest.json`：输入尺寸、标签顺序、样本数和整牌准确率。

最重要的指标是 `validation_exact_card_accuracy`，只有点数和花色同时正确才计为正确。

## 5. 放入 Android 工程

```powershell
.\install_model.ps1
```

脚本会把模型及 manifest 复制到 `app/src/main/assets/models/`。模型进入 App 前应满足：

- 独立验证图片上的整牌准确率达到预定标准；
- 15种点数和5种花色均有足够样本；
- 在目标 API 29 手机上验证耗时、内存和稳定性。

当前 Android App 继续使用 ML Kit OCR、标准牌角模板及规则约束。训练并验收出第一个 `.tflite` 后，再启用 App 内的 LiteRT 推理路径，避免把未经验证的模型用于正式识别。
