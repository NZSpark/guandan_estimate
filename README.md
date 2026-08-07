# 掼蛋牌力评估 Android App

一款面向掼蛋手牌分析的原生 Android 应用，提供照片识别、联网 AI 识图、人工校正、最优拆牌与综合牌力计算。

评分依据仓库内的：

- [掼蛋牌型量化评分方案.md](./掼蛋牌型量化评分方案.md)
- [手牌综合牌力评估方法.md](./手牌综合牌力评估方法.md)

## 主要功能

- 拍照或从系统相册选择手牌照片。
- 三阶段本地识别：牌张定位与计数、点数识别、点数下方花色图形识别。
- 可配置联网 AI 识图，支持保存和切换多个 OpenAI Responses API 兼容 Provider。
- 默认 Provider 为 OpenAI，默认模型为 `gpt-5.6-sol`。
- 点选识别结果进行校正、删除或补录。
- 支持对任意 `1～27` 张已确认手牌计算最优拆牌。
- 显示总分、计划出牌轮数、SPI、NSPI、动态等级及逐组拆牌明细。
- 随机生成27张掼蛋牌图片，保存后可重新读入并准确识别。
- 完整27张手牌可按最优拆牌结果生成排序图片。

## 牌力指标

```text
SPI  = 最优拆牌总分 ÷ 计划出牌轮数
NSPI = SPI ÷ T_max(N) × 100
```

应用会根据当前手牌张数采用动态 `T_max(N)` 和评级阈值，覆盖开局、中局与残局。

## 使用方法

1. 点击“拍照”或“相册”载入照片。
2. 等待本地三阶段识别完成；需要时点击“联网识图”获取 AI 识别结果。
3. 点击牌面标签校正，点击 `×` 删除，或通过“+ 补录”添加牌。
4. 选择“本局打”的级牌。
5. 点击“计算综合牌力”查看最优拆牌和评估结果。

被相邻牌或手指完全遮挡的牌角无法从单张照片可靠恢复，计算前应核对当前牌张。

## AI Provider 设置

“AI 设置”支持保存多个 Provider 配置：

- Provider 名称
- HTTPS Responses API 地址
- 模型名称
- API Key

API Key 不会写入源码或 APK 资源，而是使用 Android Keystore 加密后保存在设备本地。不要在 Git、截图、Issue 或聊天记录中提交真实密钥。生产应用更推荐通过自有后端代理 API 请求，避免在客户端持有长期密钥。

## 构建环境

- Android Studio / Android Gradle Plugin 8.7.3
- Gradle 8.9
- JDK 17
- `compileSdk 35`
- `minSdk 26`
- 已在 Android API 29 真机验证

配置本机 Android SDK 后执行：

```powershell
./gradlew.bat testDebugUnitTest assembleDebug
```

生成的调试 APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

安装到已连接设备：

```powershell
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
```

## 项目结构

```text
app/src/main/java/nz/org/aotearoa/guandanscore/
├── imaging/       # 随机手牌、排序图片与图片校验码
├── model/         # 扑克牌数据模型
├── recognition/   # 本地 OCR、AI 识图与 Provider 配置
├── scoring/       # 牌型候选生成、最优拆牌和 SPI/NSPI
└── MainActivity.kt
testcases/         # 手工验证图片
```

## 测试

单元测试覆盖：

- 炸弹、火箭、顺子、同花顺等牌型分值。
- 级牌作为逢人配时的组合计算。
- 1～27张部分或完整手牌的最优拆牌。
- 动态 `T_max(N)`、SPI、NSPI 和评级。
- 规则文档中的关键计算示例。

## 隐私与网络

- 本地识别不上传图片。
- 只有用户主动点击“联网识图”时，当前照片才会发送到所选 AI Provider。
- 图片和 API 请求受相应 Provider 的隐私政策与计费规则约束。

## License

当前仓库尚未指定开源许可证。未经许可，不代表授予复制、修改或分发权利。
