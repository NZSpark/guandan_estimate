# 构建与测试（build-and-test）

## 环境基线

| 项 | 值 |
| --- | --- |
| JDK | 17 |
| Gradle | 8.9（wrapper） |
| AGP | 8.7.3 |
| compileSdk / minSdk | 35 / 26 |
| 验证设备 | Android API 29 真机 |

`local.properties` 含本机 `sdk.dir`，已被 `.gitignore` 忽略，不要提交。

## 常用命令

```bash
# 单元测试（提交前必跑）
./gradlew testDebugUnitTest

# 出调试 APK
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# 安装
adb install -r -t app/build/outputs/apk/debug/app-debug.apk

# 只跑评分相关测试
./gradlew testDebugUnitTest --tests 'nz.org.aotearoa.guandanscore.scoring.*'
```

Windows 下把 `./gradlew` 换成 `./gradlew.bat`（README 原文即 PowerShell 写法）。

## 目录排除

`.gradle/`、`**/build/`、`.kotlin/`、`local.properties` 等已在 `.gitignore`；排查“为什么没提交”时先看这里。

## 离线模型工具链（独立环境）

```bash
cd model_training
python -m venv .venv && source .venv/bin/activate   # Windows: .\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
python annotate.py                 # 标注
python export_obb.py && python train_detector.py --epochs 100
python train.py --epochs 35        # 输出到 output/
```

- 训练依赖**不得**加入 APK。
- `install_model.ps1` 仅适用于 PowerShell；macOS/Linux 手动把 `output/card_corner_classifier.tflite` 与 `model_manifest.json` 复制到 `app/src/main/assets/models/`。
- 常见错误 `cannot import name '_imaging' from PIL`：`python -m pip install --force-reinstall --no-cache-dir "Pillow>=11,<12"`。

## 排障速查

| 现象 | 排查方向 |
| --- | --- |
| `Unable to locate a Java Runtime` | macOS 本机无系统 JDK；用 Homebrew JDK 17：`export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && export PATH="$JAVA_HOME/bin:$PATH"` |
| `SDK location not found` | 检查 `local.properties` 的 `sdk.dir` |
| Kotlin 编译缓存异常 | `./gradlew clean testDebugUnitTest` |
| 测试通过但 UI 行为不符 | 跑 `assembleDebug` 装真机，核对 `MainActivity` 状态机 |
| 评分断言失败 | 先确认文档版本与 `ScoringV4Test` 预期，再判断是代码回归还是规则变更 |
