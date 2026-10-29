# State —— 当前状态

> 只保留恢复工作所必需的信息。任务完成即就地改写，不追加历史流水。
> 最后更新：2026-10-30；旧版全文归档于 `harness/archive/state-2026-10-30.md`。

## 当前分支

`airecognize`

## 当前任务

执行 `doc/ai_tasks.md`：多 AI 源 WebView 迁移 + 阶段 7 UI + 阶段 8 宿主/提交协议。
清单共 231 项，**212 项已勾选**；剩余 19 项依赖 ChatGPT 真机登录、Gemini 重新登录、三源横向验收与 TalkBack 取证。

## 本轮完成（2026-10-30 下午第二轮，真机）

- **AI-077**：旋转探针页 → `onDestroy → onCreate(restored=true)`，同一 pid、无重复 `WebView created`，重挂 `2328x679` / `1176x1801`；离开/返回 Gemini 页面无崩溃；`force-stop`+`kill` 后新 pid 启动，DeepSeek 登录态保留。主界面固定 portrait。
- **AI-078**：`source_e2e` 提交后 5 秒 Home 切后台（`detached`），8 秒后返回（`Attached 1176x1801`），识牌继续并完成 `cards=27`；证明不能只凭挂接状态判断健康。
- **AI-095**：写入旧格式 prefs（仅 provider/endpoint/model/api_key）→ 启动迁移到 DeepSeek 默认 profile，AI 页面往返后仍「网页可用」，登录态保留；代码无 Cookie/WebStorage 清理调用；测试后已还原原配置。
- **G-21（部分）**：窄屏 720×1280/360dpi 正常；字体 1.3× 完整显示；9 个可点击控件实测 48~52dp。横屏不适用（主界面竖屏锁定）；TalkBack 设备未安装 → 保持未勾选。
- **G-07**：Activity 重建已真机验证；上传 20s 超时仍缺直接证据 → 保持未勾选。
- **探针**：新增 `LIFECYCLE` 日志（onCreate/onDestroy，含 pid 与 restored），用于区分重建与新建实例。
- **验证**：`./gradlew testDebugUnitTest assembleDebug` 全绿；34 类 / 321 项。

## 验证基线

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew testDebugUnitTest assembleDebug
```

真机：HUAWEI LIO-N29 / Android 10 / WebView 114.0.5.302（WiFi adb `192.168.3.158:5555`）。
探针真实图：先 `adb push` 到 `/data/local/tmp`，再 `run-as` 拷进 `cache/probe/`。

## 剩余未完成（19 项）

- **AI-158 ~ AI-163 / G-15 / G-23 ~ G-25**：ChatGPT 真机登录、选择器核对、PASTE 附件验证、带图闭环、连续两图与三源横向验收。
- **AI-089 / AI-090**：Gemini 手动登录后的单图/连续图/过期恢复；本地链路迁移前后设备对照。
- **AI-148 / AI-149 / AI-154 / AI-155**：三源闭环与切换隔离、HTTP API Key 回归、结论回写。
- **G-10**：三源真实会话记录（缺 ChatGPT）。
- **G-07**：仅缺上传超时的直接证据（真机或假时钟单测）。
- **G-21**：仅缺 TalkBack（设备未安装读屏服务）。

## 已知缺口 / 阻塞

- **ChatGPT**：适配器/脚本/注册/单测齐全，但设备未登录、选择器未核对；不得写成已验证。
- **Gemini 登录态**：设备上的 Google 登录已失效，AI-089 需人工重新登录。
- **TalkBack**：目标设备 `pm list packages` 无任何 TalkBack/读屏服务，G-21 无法取证。
- **上传超时**：`PageDriver.UPLOAD_TIMEOUT_MILLIS = 20s` 有实现，但无假时钟单测；补一个注入时钟的测试即可闭合 G-07。
- **横屏**：`MainActivity` 固定 `portrait`，无横屏布局，属设计选择而非缺陷。

## 下一步

1. 补 `PageDriver` 上传超时的假时钟单测 → 可勾 G-07。
2. 设备登录 ChatGPT，按 `--es auto` 只读诊断回填选择器，跑 AI-158~163 与 G-23~25。
3. 人工重新登录 Gemini，跑 AI-089/090。
4. 三源横向与 HTTP API 回归（AI-148/149/154/155），结论回写 §5.6。

## 已知问题（踩坑备忘）

- `evaluateJavascript` 只返回同步值；Promise / `setTimeout` 结果拿不到，异步副作用必须用后续快照验证。
- **JS 里禁止同步忙等**：会卡死事件循环，Angular/React 渲染全部停摆。
- `FileProvider` 已声明 `cache/photos/`、`cache/ai-upload/`、`cache/probe/`；其它目录会抛 `Failed to find configured root`。
- 拖放/粘贴 `dispatchEvent` 返回 false **不等于失败**，唯一判据是 composer 作用域内的附件预览计数。
- DeepSeek 回答可能带 UI 前缀（如 `jsonCopyDownload[`），解析会退回逐条提取并告警；牌面仍正确。
- 本机无系统 JDK，需 `JAVA_HOME=/opt/homebrew/opt/openjdk@17`；adb 在 `~/Library/Android/sdk/platform-tools/adb`。
- 本机 `am force-stop` 不生效，需 `am force-stop` + `am kill` 并用 `pidof` 确认；`logcat -c` 不清主缓冲，须按 pid 过滤。
- 探针 Activity 已在前台时 `am start` 只会投递 intent，不触发 `runAuto`；重跑前必须 force-stop + kill，确认 `pidof` 为空。
- 旋转测试需 `settings put system accelerometer_rotation 0` 后再设 `user_rotation`，否则设置被传感器覆盖；结束后恢复原值。
- uiautomator 的 bounds 会被 ScrollView 底边裁切，触控目标必须滚动后再测量。
- 轮询类单测的时钟必须递增，否则稳定判定永不满足。
