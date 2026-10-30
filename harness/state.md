# State —— 当前状态

> 只保留恢复工作所必需的信息。任务完成即就地改写，不追加历史流水。
> 最后更新：2026-10-30；旧版全文归档于 `harness/archive/state-2026-10-30.md`。

## 当前分支

`airecognize`

## 当前任务

执行 `doc/ai_tasks.md`：多 AI 源 WebView 迁移 + 阶段 7 UI + 阶段 8 宿主/提交协议。
清单共 233 项，**214 项已勾选**；剩余 19 项集中在 ChatGPT 额度恢复后的验收、Gemini 会话过期、横向验收。

## 本轮完成（2026-10-30 下午第四轮，图片上传收口）

- **拆分上传与提交（AI-182）**：新增 `SiteAdapter.uploadsAfterFill` / `fillComposerScript` / `submitComposerScript`；驱动层固定「填文本 → 上传并确认 → 点发送」，附件确认严格在提交前。发送按钮延迟时只重试点击，不重填文本（避免覆盖附件）。
- **修复 FILE_CHOOSER 从未执行**：`WebViewAiSource.tryUpload` 之前只调 `verifyAttachment`，从未调 `prepareFileChooserUpload`，Gemini 切到文件选择后立即暴露；已修复并补单测。
- **Gemini 上传改走原生文件选择**：CDP 实测离屏 WebView `document.hasFocus()=false`，Gemini 对合成 paste `preventDefault` 但不挂附件；改为原生触摸 `Upload and tools`（约 56,725）→ 工具箱 `Files`（约 87,456）→ `onShowFileChooser` → FileProvider URI 回填。工具箱有 ~1.5s 动画，菜单轮询 8 次；填文本后工具栏重排（652→725），取坐标前等 400ms。
- **修正 Gemini 附件假阳性**：`[data-test-id*="upload"]` 命中 6 个上传工具栏按钮，`verifyAttachment` 恒 true；现只认 `uploader-file-preview` / `.file-preview-chip` / `gem-media-attachment` / 预览缩略图。上传前 sample `previewCount=0`，回填后 `previewCount=3 thumbCount=1 attached=true`。
- **真机结果（离屏，LIO-N29 / Android 10 / WebView 114.0.5.302）**：
  - Gemini 单图：`SOURCE_E2E_OK cards=9 status=Offscreen`，`verifyAttachment confirmed` 在 `submitComposer` 之前；
  - Gemini 连续两图：`SOURCE_E2E_TWICE_OK first=10 second=27 status=Offscreen`，第二轮基线 `inputChars=0 attached=false`；
  - DeepSeek 回归：`cards=27 status=Offscreen`；
  - ChatGPT：`UploadFailed: … 站点上传额度已用尽，需等待额度恢复或升级账号`，首次采样即失败、未提交纯文本。
- **ChatGPT 额度阻塞（AI-183）**：页面明确提示 `You've reached the limit for file attachments`，约 **2026-10-31 12:36** 恢复；换账号或等待前无法验收 PASTE/连续两图。额度/焦点原因已透传到 `UploadFailed`。
- **验证**：`./gradlew testDebugUnitTest assembleDebug` 全绿（333 项单测，0 失败）。

## 验证基线

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew testDebugUnitTest assembleDebug
```

真机：HUAWEI LIO-N29 / Android 10 / WebView 114.0.5.302（WiFi adb `192.168.3.158:5555`）。
测试图：`/data/local/tmp/hand_test.png`（Gemini 9/10 张）、`/data/local/tmp/case14.jpg`（27 张）。

## 剩余未完成（19 项）

- **ChatGPT**：AI-158（未登录侧）、159~163、G-23~25。额度恢复后重跑 `source_e2e` / `source_e2e_twice`。
- **Gemini**：AI-089 还差「会话过期及恢复」；单图与连续两图已通过。
- **横向**：AI-148/149/154/155、G-15、G-10。
- **其他**：G-07（上传超时证据）、G-21（TalkBack）。

## 已知缺口 / 阻塞

- **ChatGPT 附件额度**：测试账号额度用尽，页面提示等待约 10-31 12:36；这不是代码路径问题，当前无法取得附件成功证据。
- **ChatGPT 新 DOM**：composer 为 `div.ProseMirror[aria-label='Ask ChatGPT']`，`#prompt-textarea` 已不存在；`ChatGPTSelectors.INPUT` 靠 `div.ProseMirror` 兜底命中。
- **Gemini 合成 paste**：只在 `document.hasFocus()` 为 true 时生效；生产识牌走离屏 1×1 锚点，不能依赖焦点，故只用 FILE_CHOOSER。
- **未登录侧**：ChatGPT/Gemini 均处于已登录态，不能为取证退出账号。
- **TalkBack**：设备未安装读屏服务，G-21 无法取证。
- **HTTP API**：仍无可用 Key，AI-154 未回归。

## 下一步

1. 2026-10-31 12:36 后复测 ChatGPT `source_e2e` 与 `source_e2e_twice`，跑 AI-159/160/162、G-23/G-24。
2. 补 Gemini 会话过期与恢复取证（AI-089）。
3. 三源稳定后跑 AI-148/149/154/155、G-15/G-25，回写 §5.6。
4. 注意 Gemini 同一张图两次识别的张数会波动（9/10），验收时以“能解析出合法牌面”为主，同时保留张数记录供人工核对。

## 已知问题（踩坑备忘）

- `evaluateJavascript` 只返回同步值；Promise / `setTimeout` 结果拿不到，异步副作用必须用后续快照验证。
- **JS 里禁止同步忙等**：会卡死事件循环，Angular/React 渲染全部停摆。
- **WebView/Host 的所有读取必须在主线程**：在 worker 线程调用 `host.currentWebView()` 会直接 `IllegalStateException`。
- 离屏宿主 `document.hasFocus()` 为 false；依赖焦点的站点（Gemini）会丢弃合成 paste。派发前在主线程调用 `webView.requestFocus()` 可让同一离屏 WebView 的 `document.hasFocus()` 变为 true（ChatGPT 真机不再返回 `document-not-focused`）；Gemini 仍选择文件选择路径，避免依赖焦点。
- 附件判据不能包含 `data-test-id*="upload"`：工具栏按钮也带该字段，会恒判已挂附件。
- 上传入口坐标会随文本填入/动画变化（Gemini 652→725）；取坐标后先等布局稳定，菜单打不开时重新定位再点。
- 本机 `am force-stop` 需配合 `am kill` 并用 `pidof` 确认；`logcat` 默认缓冲偏小，长任务可 `logcat -G 8M`。
- 轮询类单测的时钟必须递增，否则稳定判定永不满足。
