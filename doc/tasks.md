# 任务总索引

> 本文件只做索引与状态汇总；详细任务条目、验收标准与执行记录见各专项清单。
> 最后更新：2026-10-31

## 专项清单

| 文件 | 内容 | 状态 |
|---|---|---|
| `doc/ai_tasks.md` | AI 识牌迁移与多源 WebView 任务（阶段 0–8、全局 G-01~G-25、执行记录） | 217/235 完成 |
| `doc/ui_design.md` | UI 全面重设计规格（主工作台、手牌编辑、结果呈现、AI 源管理、网页登录页） | 已实施（UI-001~023） |
| `doc/patterns_design.md` | 手工拆牌页面设计（入口、最优/手工分值对照、分区编辑、评分复用、状态机、测试与实施顺序） | 设计完成（任务分解见 `patterns_tasks.md`） |
| `doc/patterns_tasks.md` | 手工拆牌任务分解（P0–P5、37 项、测试与真机门禁） | **37/37 完成** |
| `doc/ai_configure.md` | AI 源管理独立页面设计（信息架构、交互、状态、校验、健康检查、测试与实施顺序） | 已实施（P0–P4；真机主路径通过，剩余项见 `ai_configure.md` §11.2） |
| `doc/multi_ai_update.md` | 多 AI 源架构设计（ChatGPT / DeepSeek / Gemini / 自定义网页 / HTTP API） | 设计依据 + §5.6 实测记录 |
| `doc/ai_update.md` | 早期 Gemini 单源迁移设计 | 历史依据 |
| `doc/掼蛋牌型量化评分方案.md` | 单牌型分值公式、基础点数、炸弹/同花顺/火箭分值 | 权威 |
| `doc/手牌综合牌力评估方法.md` | SPI / NSPI / 动态评级 / 散牌惩罚 | 权威 |

## 当前进度

### 已完成

- 多源抽象层：`recognition/source`、`recognition/site`、`recognition/webview`（站点无关驱动）。
- DeepSeek 真机闭环：登录态跨进程保持；诊断 input=1/send=1/file=1；合成 paste 后附件预览出现；离屏识牌 `source_e2e` 27 张、连续两图/追问 `source_e2e_twice` 互不串线。
- 共享宿主：登录/诊断页与主界面共用同一 WebView，可见 ↔ 离屏切换无崩溃；冷启动「未挂接」提示后打开 AI 页面即恢复。
- 生命周期与迁移：旋转触发 Activity 重建但复用同一 WebView；识牌中切后台 8 秒返回仍完成 27 张；旧格式设置迁移后登录态保留。
- Gemini 真机闭环：填文本 → 合成 paste 注入图片 → 点 `Send message` → 收到回答。
- AI 设置页源选择与按源显隐字段；网页源免 API Key。
- AI 源配置迁移为独立全屏页面：源/配置档/表单/健康态分区、未保存修改确认、字段级校验、删除回退；旧弹窗已删除。
- `AiProbeActivity` 多站点探测页 + `--es auto` 无人值守入口（e2e / source_e2e / source_e2e_twice / offscreen 等）。
- 阶段 7 UI 重设计（UI-001~023）与主工作台、手牌核对、评分结果、AI 源管理、登录/诊断页。
- 手工拆牌 P0–P4 + 真机：共享评分层/分区模型/编解码/编辑器/独立页面/主界面对照已落地；JVM 457 项全绿，27 张 50 次编辑 233.1 ms；LIO-N29 上 MP-035 六项全通过，MP-036 ⑦⑧⑨⑩ 通过；补验 MP-019（单张隐藏、`Failed` 分支）与 MP-022（连对/火箭拆分+撤销）；清单 37/37。真机发现并修复：重建丢勾选、非 Success 状态残留手工入口、`保留并返回` 丢弃修改。

### 进行中 / 下一步

1. AI 源管理独立页面剩余真机项：API 完整配置态、最后一份删除禁用、字体缩放/键盘、重建草稿、冷启动并发。
2. ChatGPT 真机登录 → 只读诊断回填选择器 → PASTE 附件验证与带图闭环（AI-158~163、G-23~25）。
3. 旋转 / Activity 重建 / 切后台（AI-077/078）与迁移后登录态（AI-095、G-10）。
4. Gemini 设备端单图/连续图/过期恢复（AI-089/090）与三源横向切换（AI-148/149/154）。
5. G-21：窄屏、横屏、字体缩放人工取证。

### 阻塞

无。手工拆牌 37/37 已完成；AI 侧剩余项见 `doc/ai_tasks.md`。

## 真机基线

| 项 | 值 |
|---|---|
| 设备 | HUAWEI LIO-N29 |
| Android | 10 |
| WebView | com.huawei.webview 114.0.5.302 |
| 构建 | `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew testDebugUnitTest assembleDebug` |
| 单元测试 | 49 个测试类 / 457 项全绿（改造前基线 39 / 395） |

## 硬约束（易踩坑）

- `evaluateJavascript` 只返回同步值；Promise / `setTimeout` 结果拿不到，异步副作用必须由后续快照验证。
- JS 内禁止同步忙等；会阻塞事件循环，Angular/React 渲染停摆。
- 附件成功以预览节点出现为准，事件派发返回值不可信。
- 提交成功须确认 `submitted`，并以回答基线变化作最终判据。
- 登录判据需同时检查密码框与登录 UI，只看登录选择器会误判。
