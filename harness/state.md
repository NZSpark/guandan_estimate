# State —— 当前状态

> 只保留恢复工作所必需的信息。任务完成即就地改写，不追加历史流水。
> 最后更新：2026-10-30；旧版全文归档于 `harness/archive/state-2026-10-30.md`。

## 当前分支

`airecognize`

## 当前任务

执行 `doc/ai_tasks.md`：多 AI 源 WebView 迁移 + 阶段 7 UI + 阶段 8 宿主/提交协议。
清单共 231 项，**209 项已勾选**；剩余 22 项全部依赖 ChatGPT 真机登录、多端横向验收或旋转/无障碍人工取证。

## 本轮完成（2026-10-30 下午，真机复测）

- **AI-169 / UI-019 / G-20**：`AiProbeActivity` 改挂进程级共享 `WebViewHost`，不再自建 WebView；`MainActivity.onDestroy` 不再 `destroy()` 共享实例。真机（HUAWEI LIO-N29）冷启动主界面显示「AI 页面未挂接（请先打开一次 AI 页面）」→ 打开 AI 页面 `Attached 1176x1801` → 返回主界面「状态：网页可用」，同 pid 15623，登录态保持。
- **AI-168 / AI-170**：可见 `1176x1801` → 离屏切换日志 `webview still parented by FrameLayout, removing` → `OFFSCREEN_ATTACH attached=true status=Offscreen layout=1176x2328`，页面 `innerHeight=776`（DPR 3）；返回可见重新 `Attached`，无 `IllegalStateException`。
- **AI-178**：生产驱动 `source_e2e` 真机通过：`SOURCE_E2E_OK cards=27 status=Offscreen`（约 10 秒）。
- **AI-133 / AI-134**：同一 `WebViewAiSource` 连续两图/追问 `source_e2e_twice`：第一轮 27 张、第二轮 22 张，牌面列表不同、无回答串线、无附件残留。case14 真实照片出现重复牌（识别质量，已写入 README 已知限制）。
- **G-17**：阶段 7 UI 全部任务（UI-001~022）完成，勾选。
- **文档**：`doc/multi_ai_update.md` 新增 §5.6.5（共享宿主/三态/离屏提交）与附件作用域、发送按钮门控两行；`harness/DECISIONS.md` 新增 ADR-0015；README 补「共享宿主」与真实照片重复牌限制；`harness/memory/recognition-pipeline.md` 补四条不变量。
- **验证**：`./gradlew testDebugUnitTest assembleDebug` BUILD SUCCESSFUL；34 个测试类 / 321 项，0 失败 0 跳过。

## 验证基线

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew testDebugUnitTest assembleDebug
```

真机：HUAWEI LIO-N29 / Android 10 / WebView 114.0.5.302（WiFi adb `192.168.3.158:5555`）。
探针真实图：先 `adb push` 到 `/data/local/tmp`，再 `run-as` 拷进 `cache/probe/`。

## 剩余未完成

- **AI-158 ~ AI-163 / G-15 / G-23 ~ G-25**：ChatGPT 真机登录、选择器核对、PASTE 附件验证、带图闭环与三源横向验收。
- **AI-077 / AI-078**：旋转与 Activity 重建、切后台尺寸/出帧。
- **AI-089 / AI-090**：Gemini 设备端单图/连续图/过期恢复；迁移前后本地链路对照。
- **AI-095 / G-10**：迁移后 Cookie / DOM Storage 保留。
- **AI-148 / AI-149 / AI-154 / AI-155**：跨源切换隔离、HTTP API Key 回归、结论回写。
- **G-07 / G-21**：生命周期与窄屏/横屏/字体缩放/TalkBack 人工验收。

## 已知缺口 / 阻塞

- **ChatGPT**：适配器/脚本/注册/单测齐全，但设备未登录、选择器未核对；不得写成已验证。
- **Gemini FILE_CHOOSER**：`Upload and tools` 只弹网页自渲染面板，二级入口（Photos/Files）未自动化；当前仅 PASTE。
- **VIEWPORT_UNHEALTHY**：无真机构造手段，仅单测覆盖。
- **AI-168**：视图切换依赖真机，无 JVM 测试（已有真机日志证据）。

## 下一步

1. 设备登录 ChatGPT，用只读诊断回填选择器，再跑 AI-158~162 与 G-23~25。
2. 跑 AI-077/078（旋转、重建）与 AI-095/G-10（迁移后登录态）。
3. 三源交叉与 HTTP API 回归（AI-148/149/154），结论回写 §5.6。
4. G-21：横屏、字体缩放、TalkBack 人工取证。

## 已知问题（踩坑备忘）

- `evaluateJavascript` 只返回同步值；Promise / `setTimeout` 结果拿不到，异步副作用必须用后续快照验证。
- **JS 里禁止同步忙等**：会卡死事件循环，Angular/React 渲染全部停摆。
- `FileProvider` 已声明 `cache/photos/`、`cache/ai-upload/`、`cache/probe/`；其它目录会抛 `Failed to find configured root`。
- 拖放/粘贴 `dispatchEvent` 返回 false **不等于失败**，唯一判据是 composer 作用域内的附件预览计数。
- DeepSeek 回答可能带 UI 前缀（如 `jsonCopyDownload[`），解析会退回逐条提取并告警；牌面仍正确。
- 本机无系统 JDK，需 `JAVA_HOME=/opt/homebrew/opt/openjdk@17`；adb 在 `~/Library/Android/sdk/platform-tools/adb`。
- 本机 `am force-stop` 不生效，需 `am force-stop` + `am kill` 并用 `pidof` 确认；`logcat -c` 不清主缓冲，须按 pid 过滤。
- 探针 Activity 已在前台时 `am start` 只会投递 intent，不触发 `runAuto`；重跑前必须 force-stop + kill，确认 `pidof` 为空。
- 轮询类单测的时钟必须递增，否则稳定判定永不满足。
