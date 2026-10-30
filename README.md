# 掼蛋牌力评估 Android App

原生 Android（Kotlin）掼蛋手牌分析工具：**照片 → 识别牌面 → 人工核对 → 最优拆牌 → SPI / NSPI 牌力评估**。

- 应用名「掼蛋牌力」，包名 `nz.org.aotearoa.guandanscore`
- 评分规则以仓库内两份评分文档为唯一真源：[掼蛋牌型量化评分方案（V4.1）](./doc/掼蛋牌型量化评分方案.md)、[手牌综合牌力评估方法](./doc/手牌综合牌力评估方法.md)
- 识图支持「网页版 AI（复用登录态，免 API Key）」与「HTTP API（自带 Key）」两类源，可同时保存多套配置并切换

## 主要功能

- **取图**：拍照、系统相册，或用仓库内标准牌面素材随机合成 27 张手牌图。
- **本地识别**（离线，不上传）：设备端 ML Kit 文本识别 + 模板匹配，三阶段收敛——定位计数 → 点数 → 点数下方花色投票；整图 8 个旋转角度 + 9 块重叠放大切片，结果按坐标聚类去重，遵守两副牌的张数上限。
- **联网识图**（用户主动触发）：DeepSeek / Gemini / ChatGPT 网页版、自定义网页源、HTTP API（Gemini 与 OpenAI 协议）。失败按类别分别提示，不笼统报「网络错误」。
- **人工核对**：牌面 Chip 展示，点击校正、点 `×` 删除、`+ 补录`，上限 27 张；手牌或级牌变化后旧结果立即标记为「需重新计算」。
- **牌力计算**：任意 1～27 张均可计算，选择本局级牌（2～A）；输出评级、SPI、NSPI、评分分子、计划轮数、`T_max(N)`、散牌惩罚与按分值降序的逐组拆牌明细。
- **图片输出**：保存当前输入图；27 张手牌可生成「最优拆牌图」（按牌型分列、按分值降序）并写入相册。

## 使用流程

1. 点击「拍照」或「相册」导入手牌照片（也可「随机生成 27 张」体验完整流程）。
2. 等待本地识别完成；需要更高准确率时点击「联网识图」，由所选 AI 源再识别一次。
3. 逐张核对：点牌面校正点数/花色，点 `×` 删除，`+ 补录` 追加被遮挡的牌。
4. 选择「本局级牌」。
5. 点击「计算综合牌力」查看评级、指标与拆牌明细；27 张时可再生成拆牌图。

## 牌力指标

```text
SPI  = (Σ Fᵢ × αᵢ − P) ÷ T_plan
NSPI = SPI ÷ T_max(N) × 100
P    = 15 × N_waste        （N ≥ 10；N < 10 的残局不扣散牌惩罚）
```

- `Fᵢ`：V4.1 单牌型分值。普通牌型（单张、对子、三张、顺子、三带二、连对、钢板）由原始分线性映射，上限 **191**；4 张炸弹 **192～270**；5 张炸弹 **298～415**；同花顺 **430～502**；6 张及以上炸弹走对数平滑 **538～987**（9 炸上限 895）；火箭固定 **1000**。级牌作单张/对子/三张时按 15（含用于级牌自身的逢人配）；炸弹内的逢人配按替代点数计，与同点数自然炸弹同分（三张5+配 = 四张5 = 210 < 四张6 = 216）。牌理大小关系因此严格成立（最小炸弹也高于任何普通牌型）。
- `αᵢ`：控制力系数。炸弹、同花顺、火箭、单张大王、单张级牌取 `1.15`，其余取 `1.00`。
- `T_plan`：当前拆牌方案的出牌轮数；最优方案按 SPI 最大选取，SPI 相同则取轮数更少者。
- `N_waste`：点数 `<10` 且只能作为孤立单张/小对子出现的组数。
- `T_max(N)`：同张数手牌的理论最少轮数，用于把不同张数的 SPI 放到同一尺度（27 张时 NSPI ≈ SPI × 6.67）。

| `N` | 1～12 | 13 | 14 | 15 | 16 | 17 | 18 | 19～20 | 21～24 | 25～26 | 27 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `T_max` | = N | 12 | 13 | 14 | 15 | 14 | 14 | 15 | 16 | 17 | 15 |

评级阈值按手牌阶段切换（与规则文档一致）：

| 阶段 | 弱势 | 均衡 | 强势 | 碾压级 |
| --- | --- | --- | --- | --- |
| 残局 1～12 张 | SPI < 60 | 60 ≤ SPI < 120 | 120 ≤ SPI < 180 | SPI ≥ 180 |
| 中局 13～18 张 | SPI < 70 | 70 ≤ SPI < 130 | 130 ≤ SPI < 200 | SPI ≥ 200 |
| 开局 ≥19 张 | SPI < 80 | 80 ≤ SPI < 140 | 140 ≤ SPI < 220 | SPI ≥ 220 |

拆牌搜索是带记忆化的**精确覆盖**（同一点数+花色最多 2 张，级牌按「逢人配」参与组合），优先展开候选最少的牌并先试高分牌型；为保持界面可交互，搜索设 3 秒 / 12 万状态上限，超限后以剩余单张收尾（结果仍然合法，但可能不是全局最优）。

## AI 识图与 AI 源

| 源 | 传输 | 需要 API Key | 说明 |
| --- | --- | --- | --- |
| DeepSeek 网页版 | WebView | 否 | 首发源，默认选中；真机端到端闭环已验证 |
| Gemini 网页版 | WebView | 否 | 在应用内用 Google 账号登录后识图；离屏单图与连续两图已跑通 |
| ChatGPT 网页版 | WebView | 否 | 已真机登录；测试账号附件额度已用尽，上传闭环待额度恢复后复测 |
| 自定义网页源 | WebView | 否 | 只需填 `https://` 网页地址，选择器复用通用聊天页结构 |
| HTTP API | HTTPS | 是 | Gemini `generateContent` / OpenAI Responses / OpenAI Chat Completions |

**网页源的运行方式**

1. **登录**：AI 源管理页「网页登录 / 切换账号」进入无诊断控件的全屏登录页完成登录，Cookie 由 `CookieManager` 在应用内全局保留，之后主界面识图复用同一登录态。全新安装没有 Cookie 时，健康检查会显示「需要登录」，登录入口始终可用。
2. **上传**：按适配器声明的 `supportedUploads` 尝试（每个源只启用真机验证过的路径，不再无条件回退）——DeepSeek 支持合成 `paste` 与原生触摸文件选择器；Gemini 使用原生触摸文件选择（`Upload and tools` → `Files`）；ChatGPT 使用合成 `paste`；自定义源走文件选择器。所有路径都必须以页面出现附件预览为成功判据，**附件未确认绝不提交**。
3. **提交与等待**：先记录回答基线 → 填 prompt → 上传并在提交前确认附件 → 点发送（必须同时确认已填写与已点击）→ 轮询页面新增回答，文本连续稳定才判定生成结束，避免追问拿到上一轮回答。
4. **健康检查**：已挂接页面只做只读诊断，不导航、不 reload；页面被分为 READY / LOADING / COMPOSER_MISSING / VIEWPORT_UNHEALTHY / LOGIN_REQUIRED / HOST_NOT_ATTACHED，分别对应「继续 / 重试 / 重载 / 需人处理（视口异常）/ 请登录 / 宿主待恢复」。冷启动（进程内还没有 WebView）时，健康检查会在后台自动完成创建 → 离屏挂接 → 首页导航，再重新只读诊断，因此**不需要用户先手动打开 AI 页面**；只有自动挂接仍失败时才提示「请先打开一次 AI 页面」。选择器缺失与视口异常不会被误报成未登录。
5. **不占界面**：主界面不插入可见 WebView，页面挂在 1×1 离屏锚点上按全屏像素布局，`innerHeight` 等于屏高，页面照常出帧、文件选择器照常触发。
6. **共享宿主**：主界面与「网页登录 / 切换账号」页共用同一个 WebView 实例；在可见容器与离屏锚点之间切换前会先解除旧父容器。冷启动由首个健康检查自动离屏挂接并加载首页，健康态从「检测中」直接变为「网页可用」或「需要登录」；只有锚点不可用时才提示「请先打开一次 AI 页面」，而不是误报未登录。

**HTTP API 源**

- 按接口地址与模型名自动选择协议：Gemini `:generateContent`、OpenAI Responses（默认）、OpenAI Chat Completions（地址以 `/chat/completions` 结尾）。
- 只接受 `https://` 地址；配置页会做字段级校验，错误显示在字段下方而不是只弹 Toast。
- 预设只填入示例接口与模型名，请按所用服务商的当前文档核对后修改。

**配置管理**：每个源可保存多套 profile，AI 源管理页按源类型显隐字段——网页源完全隐藏 endpoint / model / API Key 及其标签；删除 profile 需二次确认并显示将删除的名称。

## 架构

```text
MainActivity（工作台，状态驱动渲染）
   └── AiSource（业务层唯一依赖）
        ├── HttpApiSource ── AiRecognitionClient（Gemini / OpenAI 协议）
        └── WebViewAiSource（状态机、超时、取消、回答基线）
             ├── SiteAdapter（选择器 / 脚本 / 解析 / 上传策略）
             ├── PageDriver（站点无关驱动，实现 PageOps）
             └── WebViewHost（主线程宿主、生命周期、安全配置）
```

| 包 | 关键类 | 职责 |
| --- | --- | --- |
| `model/` | `Card`、`Rank`、`Suit` | 牌面模型；级牌是评分器参数，不是牌的属性 |
| `scoring/` | `HandScorer`、`HandResult`、`Meld` | 生成牌型候选、精确覆盖搜索、SPI/NSPI 与动态评级 |
| `recognition/` | `CardRecognizer`、`CardTemplateMatcher` | 本地三阶段识别 |
| | `AiCardResponseParser`、`AiRecognitionClient` | 回答解析、HTTP API 协议适配 |
| `recognition/source/` | `AiSource`、`AiSourceId`、`AiSourceRegistry`、`HttpApiSource`、`AiSourcePresets`、`HealthCheckGate` | 业务侧唯一依赖的源抽象、注册表、站点首页常量与健康检查串行化 |
| `recognition/site/` | `SiteAdapter`、`DeepSeek/Gemini/ChatGPT/Custom Adapter` + `Scripts` + `Selectors` | 站点知识：选择器、注入脚本、结构化解析、上传首选策略 |
| `recognition/webview/` | `WebViewHost`、`PageDriver`、`PageOps`、`WebViewAiSource` | 宿主、站点无关驱动、识牌状态机 |
| | `PageState`、`ReadyRetryPolicy`、`AttachGate`、`BlockingBridge` | 页面状态分类、就绪退避、挂接体检、主线程死锁防线 |
| | `ViewportTapMapper`、`FileChooserSlot`、`UrlRedactor`、`WebViewSecurityConfig` | 视口坐标换算、文件选择回调所有权、日志脱敏、安全开关契约 |
| `imaging/` | `HandImageRenderer`、`VisualCardCodec`、`CardAssetNames`、`UploadImageEncoder` | 手牌图/拆牌图渲染、内嵌校验码、素材名映射、上传压缩 |
| `ui/` | `WorkbenchState`、`AiSettingsActivity`、`AiSettingsEditor`、`AiSettingsValidation` | 分域显示状态、独立 AI 源管理页及其纯 JVM 编辑/校验逻辑 |
| `probe/` | `AiProbeActivity`、`GeminiProbeActivity` | 真机诊断入口（开发者工具，非用户功能） |
| 根 | `MainActivity` | 单 Activity 工作台：操作 → 业务层 → 状态渲染 |

必须遵守的工程约束：

1. **站点知识单点**：`PageDriver` 中不允许出现任何 CSS 选择器、站点 URL 或站点名，站点差异只能落在 `SiteAdapter`；新增站点 = 新增一个适配器 + 在注册表加一行，驱动层不动。
2. **主线程契约**：`WebViewHost` 全部方法断言主线程；`PageOps` 可被 worker 线程调用，由实现切主线程；`BlockingBridge` 在主线程直接抛异常，禁止 `runBlocking` 等 JS 回调。
3. **禁止同步忙等**：页面元素由前端框架异步渲染，`while (Date.now() < …)` 会冻结事件循环导致元素永远渲染不出来，一律用 `setTimeout` 重试。
4. **证据优先**：附件以页面预览节点为准、提交以 `filled && submitted` 为准、回答以基线之后的新增文本为准；不以事件派发的返回值当成功判据。
5. **健康检查只读**：AI 源管理页的「重新检查」不得变成一次真实导航；识牌生成过程中暂停自动 reload。

## 构建与运行

| 项 | 值 |
| --- | --- |
| JDK | 17 |
| Gradle | 8.9（仓库 wrapper） |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.0.21 |
| compileSdk / targetSdk | 35 / 35 |
| minSdk | 26 |
| 依赖 | AndroidX / Material 3、ML Kit 端侧文本识别；`org.json` 运行期用系统内置实现，单测由 `testImplementation` 提供 |

先在 `local.properties` 指定 SDK：`sdk.dir=/path/to/Android/sdk`。若默认 `java` 不是 17，用 `JAVA_HOME` 显式指定：

```bash
# macOS / Linux
JAVA_HOME=/path/to/jdk17 ./gradlew testDebugUnitTest assembleDebug

# Windows
gradlew.bat testDebugUnitTest assembleDebug
```

产物与安装：

```bash
app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
```

真机验收基线：HUAWEI LIO-N29 / Android 10 / WebView 114.0.5.302（网页源的上传、提交与轮询都在该环境实测）。改动识别或 UI 路径时，除单测外应加跑 `assembleDebug`。

## 测试

`./gradlew testDebugUnitTest` 是提交前基线。当前 39 个测试类、379 项单元测试全绿，覆盖：

- 牌型分值：普通牌型映射上下界（191）、最小四炸（192）、高阶炸弹对数分、火箭（1000）、级牌逢人配在单/对/三、炸弹、顺子、连对/钢板、三带二中的取值语义。
- 拆牌与指标：1～27 张部分/完整手牌、`T_max(N)`、SPI/NSPI、散牌惩罚、动态评级、并列方案的取舍。
- 本地识别与图片：素材名映射、上传压缩目标尺寸、生成图校验码回读、回答解析（对象 / 顶层裸数组 / 正则兜底）。
- 网页驱动：页面状态分类、就绪退避上限、生成中暂停 reload、文件选择回调所有权、视口坐标换算、安全配置、URL 脱敏、主线程死锁防线、上传与提交状态机、离屏挂接等价可用。

单测跑在 JVM 上（`unitTests.isReturnDefaultValues = true`），不依赖模拟器；真机行为用「真机探针」一节取证。

## 项目结构

```text
.
├── app/                                  # Android 应用（Kotlin，View Binding）
│   └── src/main/java/nz/org/aotearoa/guandanscore/
│       ├── MainActivity.kt               # 单 Activity 工作台
│       ├── model/                        # 牌面模型
│       ├── scoring/                      # 牌型候选、最优拆牌、SPI/NSPI
│       ├── recognition/                  # 本地识别 + AI 源（source / site / webview）
│       ├── imaging/                      # 手牌图渲染、校验码、素材名、上传压缩
│       ├── ui/                           # 工作台状态域、AI 源设置页
│       └── probe/                        # 真机诊断 Activity（开发者用）
├── card_picture/
│   ├── card_png/                         # 54 张标准牌面 + 背景（打包为 assets）
│   └── card_gif/                         # 同套牌面 GIF 版（备用）
├── testcases/                            # 真实手牌照片验证集
├── model_training/                       # 独立标注 / 训练 / TFLite 导出工具链
├── doc/                                  # 评分规则、AI 迁移、UI 设计、任务清单
├── harness/  skills/  AGENTS.md          # AI 协作 Harness（见文末）
├── statistic.py                          # 蒙特卡洛牌型分布统计（V3.0 时期，独立，不参与构建）
└── AotearoaGuandanLogo.png
```

`model_training/` 与 Android 构建完全解耦，其依赖不会进入 APK；未验收的 `.tflite` 模型不得接入正式识别路径。详见 [model_training/README.md](./model_training/README.md)。

## 真机探针（开发者工具）

`AiProbeActivity` 是识牌迁移期的验证入口，可在真机上切换站点、执行只读诊断并跑无人值守脚本：

```bash
adb shell am start -n nz.org.aotearoa.guandanscore/.probe.AiProbeActivity \
  --es site deepseek_web
```

- `--es site`：`deepseek_web` / `gemini_web` / `chatgpt_web` / `custom_web`（必须与 `AiSourceId.key` 完全一致；拼错不会报错，会静默加载默认地址并回落到 DeepSeek 适配器）。
- `--es url`：直接指定页面，优先级高于 `site`。
- `--es test_image`：真实测试图的绝对路径；需先复制到应用可读目录（如 `cache/probe/`）。
- `--es auto`：`e2e`（上传→提交→轮询→解析）、`textonly`（不带图对照）、`baseline`、`pagestate`、`selector_probe`、`file_probe`、`fschooser`、`upload_alt`、`stop_probe`、`offscreen`，以及早期 Gemini 专项探针 `gemini_*`。

注意：该 Activity 在 manifest 中为 `exported="true"`，仅供开发调试，正式发布前应移除或加保护。

## 隐私与安全

- 本地识别完全离线，不上传图片。
- 只有用户点击「联网识图」时，当前图片才会发送到所选 AI 源；网页源即所选服务商的网页版，受其服务条款与隐私政策约束，登录态仅保存在应用内的 WebView Cookie 中。
- HTTP API Key 用 Android Keystore（AES/GCM）加密后仅存本机，不写入源码、资源或构建产物；网页源不保存任何 Key。
- API 地址必须是 `https://`；WebView 关闭文件/内容访问与表单保存，混合内容策略为 `NEVER_ALLOW`；URL 日志会把 query 与 fragment 的**值**替换为 `<redacted>`。
- 仓库内不含真实密钥。不要在 Issue、截图、提交信息或聊天记录中附带 Key；生产场景更推荐由自有后端代理 API 请求。

## 已知限制

- 被相邻牌或手指完全遮挡的牌角无法从单张照片恢复，必须人工补录。
- 本地识别是 OCR + 模板匹配的工程方案（非训练模型），花色依赖点数下方小图形投票，低置信度会在警告里点名，需要核对。
- Gemini 网页版的上传入口是网页自渲染的二级面板（`Upload and tools` → `Files`），驱动层用两次原生触摸完成；离屏宿主没有 `document` 焦点，合成 `paste` 会被页面静默丢弃，因此不作为上传路径。DeepSeek 的完整闭环（离屏、连续两图、追问）已真机验收。
- 模型对真实拍摄照片仍可能读错或输出重复牌（实测 `case14.jpg` 22 张中出现重复的黑桃 A/K），识牌结果需要人工核对；含重复牌的输入请在核对页修正后再评分。
- 最优拆牌图要求 27 张；不足 27 张只能查看文字明细。
- 精确覆盖是 NP-hard，极端手牌会触发 3 秒 / 12 万状态上限，退化为「剩余单张」收尾。
- 主界面锁定竖屏，无横屏布局；TalkBack 未在目标设备取证（系统未安装读屏服务）。窄屏 720×1280、1.3× 字体缩放与 48dp 触控目标已在真机核对。
- ChatGPT 网页源已真机登录；测试账号的附件额度已用尽（页面提示 `You've reached the limit for file attachments`，约 2026-10-31 12:36 恢复），附件上传闭环暂时无法复测。额度用尽时应用会明确报「上传额度已用尽」，**不会**提交纯文本识牌。

## 规则与设计文档

| 文件 | 内容 |
| --- | --- |
| [doc/掼蛋牌型量化评分方案.md](./doc/掼蛋牌型量化评分方案.md) | V4.1 单牌型分值、炸弹/同花顺/火箭公式（权威） |
| [doc/手牌综合牌力评估方法.md](./doc/手牌综合牌力评估方法.md) | SPI / NSPI / 动态评级 / 散牌惩罚（权威） |
| [doc/tasks.md](./doc/tasks.md) | 任务总索引、当前进度、真机基线 |
| [doc/ai_tasks.md](./doc/ai_tasks.md) | AI 识牌迁移与多源 WebView 任务（阶段 0～8、验收清单） |
| [doc/ui_design.md](./doc/ui_design.md) | 主工作台与 AI 源管理的 UI 规格 |
| [doc/multi_ai_update.md](./doc/multi_ai_update.md) | 多 AI 源架构设计 |
| [model_training/README.md](./model_training/README.md) | 标注、训练、导出与接入流程 |

代码与规则文档冲突时以文档为唯一真源：先确认哪边是需求，不允许静默修改公式；改动分值或系数必须同步文档、单测与本文件的指标说明。

## Agent Harness（AI 协作框架）

仓库内置一套文件驱动、跨工具兼容的 Agent Harness，供 AI 编码工具（pi / Claude Code / Codex 等）跨会话连续工作，知识只存一份：

- [AGENTS.md](./AGENTS.md) —— 唯一入口：启动协议、工具适配、写入规则。
- [harness/CRITICAL.md](./harness/CRITICAL.md) —— 不可违反的硬约束。
- [harness/state.md](./harness/state.md) —— 当前任务、进度与阻塞。
- [harness/index.md](./harness/index.md) —— 任务路由表与 Token 预算。
- [harness/DECISIONS.md](./harness/DECISIONS.md) —— 架构与规则决策记录。
- [harness/memory/](./harness/memory) —— 模块地图、评分改动指南、识别管线、构建排障。
- [skills/](./skills) —— 可复用工作流：`add-scoring-test`、`ai-recognition-debug`、`harness-cleanup`。

新会话按 `AGENTS.md` → `CRITICAL.md` → `state.md` → `index.md` 的顺序加载，其余文档按路由表按需读取；任务收尾必须就地更新 `state.md`。

## License

当前仓库尚未指定开源许可证。未经许可，不代表授予复制、修改或分发权利。
