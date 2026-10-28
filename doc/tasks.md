# 任务总索引

> 本文件只做索引与状态汇总；详细任务条目、验收标准与执行记录见各专项清单。
> 最后更新：2026-10-29

## 专项清单

| 文件 | 内容 | 状态 |
|---|---|---|
| `doc/ai_tasks.md` | AI 识牌迁移与多源 WebView 任务（阶段 0–7、全局 G-01~G-21、执行记录） | 进行中 |
| `doc/ui_design.md` | UI 全面重设计规格（主工作台、手牌编辑、结果呈现、AI 源管理、网页登录页） | 规格待实施 |
| `doc/multi_ai_update.md` | 多 AI 源架构设计（Gemini / DeepSeek / 自定义网页 / HTTP API） | 设计依据 |
| `doc/ai_update.md` | 早期 Gemini 单源迁移设计 | 历史依据 |
| `doc/掼蛋牌型量化评分方案.md` | 单牌型分值公式、基础点数、炸弹/同花顺/火箭分值 | 权威 |
| `doc/手牌综合牌力评估方法.md` | SPI / NSPI / 动态评级 / 散牌惩罚 | 权威 |

## 当前进度

### 已完成

- 多源抽象层：`recognition/source`、`recognition/site`、`recognition/webview`（站点无关驱动）。
- DeepSeek 真机闭环：登录态跨进程保持；诊断 input=1/send=1/file=1；合成 paste 后附件预览出现。
- Gemini 真机闭环：填文本 → 合成 paste 注入图片 → 点 `Send message` → 收到回答。
- AI 设置页源选择与按源显隐字段；网页源免 API Key。
- `AiProbeActivity` 多站点探测页 + `--es auto` 无人值守入口。

### 进行中 / 下一步

1. 用真机实测值复核 `GeminiSelectors` 的 response / generating 候选。
2. AI-128：`FILE_CHOOSER` 原生触摸路径验收（Gemini 走 `Open mobile camera/gallery` 原生 chooser）。
3. AI-131~135：DeepSeek 提交闭环与连续请求回归。
4. 阶段 7 UI-001~022 按 P0→P1→P2 实施。

### 阻塞

无。真机可复测（`adb -s J7R0220110001216`）。

## 真机基线

| 项 | 值 |
|---|---|
| 设备 | HUAWEI LIO-N29 |
| Android | 10 |
| WebView | com.huawei.webview 114.0.5.302 |
| 构建 | `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew testDebugUnitTest assembleDebug` |
| 单元测试 | 159 项全绿 |

## 硬约束（易踩坑）

- `evaluateJavascript` 只返回同步值；Promise / `setTimeout` 结果拿不到，异步副作用必须由后续快照验证。
- JS 内禁止同步忙等；会阻塞事件循环，Angular/React 渲染停摆。
- 附件成功以预览节点出现为准，事件派发返回值不可信。
- 提交成功须确认 `submitted`，并以回答基线变化作最终判据。
- 登录判据需同时检查密码框与登录 UI，只看登录选择器会误判。
