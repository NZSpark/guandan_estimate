# State —— 当前状态

> 只保留恢复工作所必需的信息。任务完成即就地改写，不追加历史流水。
> 最后更新：2026-10-30（AI 源管理独立页面）；旧版全文归档于 `harness/archive/state-2026-10-30.md`。

## 当前分支

`airecognize`

## 当前任务

执行 `doc/ai_tasks.md`：多 AI 源 WebView 迁移 + 阶段 7 UI + 阶段 8 宿主/提交协议。
清单共 235 项，**217 项已勾选**；剩余 18 项集中在 ChatGPT 额度恢复后的验收、Gemini 会话过期、横向验收。

**本轮（2026-10-30）**：按 `doc/ai_configure.md` 完成 AI 源管理独立页面（UI-023，P0–P4）。
旧 `ui/AiSettingsScreen.kt` / `AiSettingsDialog.kt` 已删除；主界面入口改为 `AiSettingsActivity`。
真机 LIO-N29 已跑通入口/返回、五源显隐、dirty 确认、保存并选用+重启保留、非激活「未选用」、API 错误聚焦、删除激活配置回退、登录页往返重查；剩余完整 API 配置态、最后一份删除禁用、字体/TalkBack、重建草稿与冷启动并发。

## 本轮完成（2026-10-30，AI 源管理独立页面）

- **页面**：`ui/AiSettingsActivity` 全屏承载当前使用卡、五源单选、配置档下拉、新建/删除、按源显隐表单、真实健康态、固定底部「保存并选用」。`AndroidManifest` 注册为 `exported=false`、竖屏、`adjustResize`。
- **纯逻辑**：`ui/AiSettingsEditor`（源/配置档切换、draft/dirty、删除回退、重建草稿恢复）、`ui/AiSettingsValidation`（网页只校验 homeUrl，API 校验 endpoint/model/key，HTTPS + host）、`recognition/source/HealthCheckGate`（主界面与本页健康检查串行化，避免并发 `ensureLoaded()` 重挂共享 WebView）。
- **交互**：有未保存修改时切换源/配置档/返回均确认；校验错误落在 `TextInputLayout` 并滚动到首个错误（含嵌套 `apiSection` 的坐标累加）；删除当前激活配置按「同源第一份 → 全库第一份」回退；仅激活网页配置可登录并自动重查，非激活配置显示「保存并选用后可打开网页登录」。
- **验证**：`./gradlew testDebugUnitTest assembleDebug` BUILD SUCCESSFUL（38 个测试类 / **373 项，0 失败**）；新增 28 项纯 JVM 测试覆盖编辑器、校验与串行化。APK 清单确认 Activity 导出/方向/输入模式。
- **真机（LIO-N29 / Android 10 / 1176×2400）**：页面完全替代弹窗；五源顺序与字段显隐正确；dirty 切换/返回确认，取消后输入保留；新建并保存临时 profile 后主界面立即刷新，force-stop 重启仍保留；非激活 API profile 显示「未选用」；API Key 缺失在字段下方报错并聚焦；删除激活 profile 的确认框写出回退名称，删除后回退 DeepSeek 且健康态刷新为「网页可用」；Ready 下登录入口仍可见，点击进入无诊断控件的全屏登录页，返回后自动重查并保持「网页可用」；全程无 FATAL EXCEPTION。临时 profile 已删除，prefs 结构与操作前一致。
- **兼容性**：`MainActivity` 图片解码为 `ImageDecoder` 增加 API 28 守卫，API 26–27 回退 `BitmapFactory` + `inSampleSize`；`lintDebug` 从 8 个 `NewApi` 错误变为通过。

## 同日上一轮完成（AI-184 宿主自动挂接）

- `WebViewAiSource.healthCheck()` 遇 `HOST_NOT_ATTACHED` 时在后台复用就绪链路（创建 → 离屏挂接 → 首页导航 → 等待可操作），再重新只读诊断；已有页面保持只读。
- `MainActivity` 首次检查改由 `onResume` post 触发，避免 decorView 尚未挂窗口时误报宿主故障。
- 真机冷启动日志：`pageState: webView is null` → `warm-up finished state=READY`，主界面直接显示「状态：网页可用」。

## 更早完成（2026-10-30，图片上传收口）

- 上传与提交拆分：填文本 → 上传并确认附件 → 点发送；附件未确认绝不提交，发送延迟时只重试点击。
- Gemini 改走原生文件选择（`Upload and tools` → `Files`）；DeepSeek paste + FILE_CHOOSER；ChatGPT PASTE。
- 真机闭环：Gemini 单图 9 张、连续两图 10/27 张；DeepSeek 27 张。详见 `doc/ai_tasks.md` AI-182。

## 验证基线

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew testDebugUnitTest assembleDebug
```

真机：HUAWEI LIO-N29 / Android 10 / WebView 114.0.5.302（WiFi adb `192.168.3.158:5555`，当前锁屏需人工解锁）。
测试图：`/data/local/tmp/hand_test.png`（Gemini 9/10 张）、`/data/local/tmp/case14.jpg`（27 张）。

## 剩余未完成（18 项）

- **AI 源管理页真机清单**：`doc/ai_configure.md` §11.2 剩余 7 的完整 API 配置态与 9–12（最后一份删除禁用、字体缩放/键盘、重建草稿、冷启动并发）。
- **ChatGPT**：AI-158（未登录侧）、159~163、G-23~25。额度恢复后重跑 `source_e2e` / `source_e2e_twice`。
- **Gemini**：AI-089 还差「会话过期及恢复」；单图与连续两图已通过。
- **横向**：AI-148/149/154/155、G-15、G-10。
- **其他**：G-21（TalkBack）。

## 已知缺口 / 阻塞

- **真机 UI 取证**：主路径已通过（见上），但未把 §11.2 全清单冒充完成；剩余项见「剩余未完成」。
- **ChatGPT 附件额度**：测试账号额度用尽，页面提示等待约 10-31 12:36；当前无法取得附件成功证据。
- **ChatGPT 新 DOM**：composer 为 `div.ProseMirror[aria-label='Ask ChatGPT']`，`#prompt-textarea` 已不存在；`ChatGPTSelectors.INPUT` 靠 `div.ProseMirror` 兜底命中。
- **Gemini 合成 paste**：只在 `document.hasFocus()` 为 true 时生效；生产识牌走离屏 1×1 锚点，故只用 FILE_CHOOSER。
- **TalkBack**：设备未安装读屏服务，G-21 无法取证。
- **HTTP API**：仍无可用 Key，AI-154 未回归。

## 下一步

1. 按 `doc/ai_configure.md` §11.2 补剩余项：API 完整配置态、最后一份删除禁用、1.3× 字体/键盘、重建草稿、冷启动并发。
2. 2026-10-31 12:36 后复测 ChatGPT `source_e2e` 与 `source_e2e_twice`，跑 AI-159/160/162、G-23/G-24。
3. 补 Gemini 会话过期与恢复取证（AI-089）。
4. 三源稳定后跑 AI-148/149/154/155、G-15/G-25，回写 §5.6。

## 已知问题（踩坑备忘）

- `evaluateJavascript` 只返回同步值；Promise / `setTimeout` 结果拿不到，异步副作用必须用后续快照验证。
- **JS 里禁止同步忙等**：会卡死事件循环，Angular/React 渲染全部停摆。
- **WebView/Host 的所有读取必须在主线程**：在 worker 线程调用 `host.currentWebView()` 会直接 `IllegalStateException`。
- **共享 WebView 的健康检查必须串行**：主界面与本页同时 `ensureLoaded()` 会互抢页面；统一走 `HealthCheckGate.run`，且调用方必须在后台线程。
