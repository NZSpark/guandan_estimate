# 任务总索引

> 本文件只做索引与状态汇总；详细任务条目、验收标准与执行记录见各专项清单。
> 最后更新：2026-10-29

## 专项清单

| 文件 | 内容 | 状态 |
|---|---|---|
| `doc/ai_tasks.md` | AI 识牌迁移与多源 WebView 任务（阶段 0–8、全局 G-01~G-25、执行记录） | 209/231 完成 |
| `doc/ui_design.md` | UI 全面重设计规格（主工作台、手牌编辑、结果呈现、AI 源管理、网页登录页） | 已实施（UI-001~022） |
| `doc/multi_ai_update.md` | 多 AI 源架构设计（ChatGPT / DeepSeek / Gemini / 自定义网页 / HTTP API） | 设计依据 + §5.6 实测记录 |
| `doc/ai_update.md` | 早期 Gemini 单源迁移设计 | 历史依据 |
| `doc/掼蛋牌型量化评分方案.md` | 单牌型分值公式、基础点数、炸弹/同花顺/火箭分值 | 权威 |
| `doc/手牌综合牌力评估方法.md` | SPI / NSPI / 动态评级 / 散牌惩罚 | 权威 |

## 当前进度

### 已完成

- 多源抽象层：`recognition/source`、`recognition/site`、`recognition/webview`（站点无关驱动）。
- DeepSeek 真机闭环：登录态跨进程保持；诊断 input=1/send=1/file=1；合成 paste 后附件预览出现；离屏识牌 `source_e2e` 27 张、连续两图/追问 `source_e2e_twice` 互不串线。
- 共享宿主：登录/诊断页与主界面共用同一 WebView，可见 ↔ 离屏切换无崩溃；冷启动「未挂接」提示后打开 AI 页面即恢复。
- Gemini 真机闭环：填文本 → 合成 paste 注入图片 → 点 `Send message` → 收到回答。
- AI 设置页源选择与按源显隐字段；网页源免 API Key。
- `AiProbeActivity` 多站点探测页 + `--es auto` 无人值守入口（e2e / source_e2e / source_e2e_twice / offscreen 等）。
- 阶段 7 UI 重设计（UI-001~022）与主工作台、手牌核对、评分结果、AI 源管理、登录/诊断页。

### 进行中 / 下一步

1. ChatGPT 真机登录 → 只读诊断回填选择器 → PASTE 附件验证与带图闭环（AI-158~163、G-23~25）。
2. 旋转 / Activity 重建 / 切后台（AI-077/078）与迁移后登录态（AI-095、G-10）。
3. Gemini 设备端单图/连续图/过期恢复（AI-089/090）与三源横向切换（AI-148/149/154）。
4. G-21：窄屏、横屏、字体缩放、TalkBack 人工取证。

### 阻塞

无。真机可复测（`adb -s 192.168.3.158:5555`，WiFi adb）。

## 真机基线

| 项 | 值 |
|---|---|
| 设备 | HUAWEI LIO-N29 |
| Android | 10 |
| WebView | com.huawei.webview 114.0.5.302 |
| 构建 | `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew testDebugUnitTest assembleDebug` |
| 单元测试 | 34 个测试类 / 321 项全绿 |

## 硬约束（易踩坑）

- `evaluateJavascript` 只返回同步值；Promise / `setTimeout` 结果拿不到，异步副作用必须由后续快照验证。
- JS 内禁止同步忙等；会阻塞事件循环，Angular/React 渲染停摆。
- 附件成功以预览节点出现为准，事件派发返回值不可信。
- 提交成功须确认 `submitted`，并以回答基线变化作最终判据。
- 登录判据需同时检查密码框与登录 UI，只看登录选择器会误判。
