# AI 识牌改造方案：从 Gemini API Key 迁移到 Gemini WebView

> 状态：改造方案，尚未执行代码迁移。
>
> 目标：将本项目在线 AI 识牌所使用的 Gemini API Key / HTTP API 调用，改为 Android WebView 驱动 Gemini 网页版。保留现有本地 OCR、模板匹配、图片编码校验、牌面结果解析和牌力评分。本文供后续实施与验收使用。
>
> 参考资料：
> - SMSProxy 本地参考：`/Users/onetreehill/Github/SMSProxy/doc/ai_support.md`。参考其已实现的 Android WebView 上游架构、窗口宿主生命周期、主线程 JS 桥、提交验证、回答基线、结束判定和健康检查隔离。DeepSeek 的选择器、stop 控件结论、EMUI 修复及 overlay 后台渲染结果属于站点或设备相关的实测经验，不能直接当作 Gemini 的既定行为。
> - GeminiBridge：`https://github.com/NZSpark/gemini-bridge`。已检查本地检出的代码和测试，重点参考 `gemini_web/config.py`、`gemini_web/chat_io.py`、`gemini_web/driver.py` 和 `tests/test_end_detection.py`。
> - DeepseekBridge 本地参考：`/Users/onetreehill/Github/DeepseekBridge/doc/image_upload.md`。参考其基于 `File`、`DataTransfer`、合成 `ClipboardEvent("paste")` 的图片注入设计、失败降级、多轮只发送本轮新增图片、分层测试和带图/不带图实测验证。该方案在 Chromium/Playwright 中验证，不代表 Android WebView/Gemini 已验证支持。
> - 本项目现状：`app/src/main/java/nz/org/aotearoa/guandanscore/recognition/`、`MainActivity.kt`、`AiRecognitionClientTest.kt`、`README.md`。

## 1. 目标与范围

当前应用采用“本地识牌 + 可选在线 AI 识牌”的组合：

1. 加载照片后，先尝试 `VisualCardCodec.decode()` 图片编码校验路径。
2. `CardRecognizer` 使用 ML Kit OCR、牌面模板匹配、图像旋转和裁剪等方式识别手牌。
3. 用户可点击“联网识图”，由 `AiRecognitionClient` 把图像编码成 JPEG/Base64，通过 HTTP API 向 Gemini 或其它配置的 Provider 提交图像与识牌 prompt。
4. AI 返回文本后，提取 JSON/文本并解析为 `List<Card>`，展示识别结果供用户校对与评分。

本次只更换在线 Gemini 的访问方式。首选目标是：用户在应用内的 Gemini WebView 中手动登录一次，然后应用把当前手牌图片提交给 Gemini 网页版，读取其回答并转换为现有 `Card` 模型。

**明确不在本次范围内：**

- 不重写 `CardRecognizer` 的本地 OCR、模板匹配与候选牌合并算法。
- 不改变 `Card`、`Rank`、`Suit` 的领域模型。
- 不改变 `HandScorer`、牌型分析与评分算法。
- 不把 Gemini 网页驱动代码塞进评分或图像处理代码中。
- 不把 GeminiBridge 的 Python / Playwright 代码直接移植到 Android；只借鉴其 DOM 驱动、会话保持、提交验证和回答完成判定的设计思想。

## 2. 当前实现与改造影响点

| 当前文件 | 当前职责 | 改造方向 |
| --- | --- | --- |
| `recognition/AiRecognitionClient.kt` | 图片压缩/Base64、Gemini 与 OpenAI API 请求、响应文本提取、牌面解析 | 将网页交互迁移到新的 WebView 上游；把响应解析和 Card JSON 解析提取为不依赖传输方式的组件 |
| `recognition/AiSettingsStore.kt` | Provider/endpoint/model/API Key 配置、多配置档持久化、Key 加密 | 增加上游类型或迁移为 WebView 模式；保存网站登录状态由 WebView Cookie/DOM Storage 管理，不再要求 Gemini API Key |
| `MainActivity.kt` 的 `recognizeOnline()` | 加载当前图片和配置，在单线程 worker 中调用 AI，更新 UI | 改成调用异步 WebView 识别服务，正确管理进度、取消、错误和 Activity 生命周期 |
| `MainActivity.kt` 的 `showAiSettings()` | Provider、端点、模型和 Key 的配置界面 | 对 Gemini WebView 模式显示登录/会话状态和打开登录页操作；不再要求填写 Gemini API Key |
| `recognition/CardRecognizer.kt` | 本地 OCR、旋转/裁剪、模板匹配、候选聚类与识别结果 | 保持不变；继续作为本地识别路径 |
| `imaging/VisualCardCodec.kt` | 识别图片中的校验码/编码数据 | 保持不变；继续优先使用准确的直接解码路径 |
| `recognition/AiRecognitionClientTest.kt` | API 请求体构造、响应提取、识牌解析等测试 | 将传输层测试替换/补充为 WebView 状态机和结果解析测试，保留有价值的解析回归用例 |
| `AndroidManifest.xml` / `app/build.gradle.kts` | Android 权限与依赖 | 检查网络权限、Activity 配置和 WebView 生命周期；一般不需要增加 Playwright 或 Python 依赖 |

`MainActivity.recognizeOnline()` 目前先检查 `settings.apiKey` 是否为空，再在 `worker` 中同步调用 `AiRecognitionClient.recognize()`。迁移后，这个 API Key 门槛必须删除，否则用户即使已经登录 Gemini 网页，也无法进入新识别流程。

## 3. 推荐总体架构

```text
相机 / 相册
    |
    v
ImageDecoder -> currentBitmap
    |
    +--> VisualCardCodec.decode() 命中 ------> Card 列表
    |
    +--> CardRecognizer (本地 OCR/模板匹配) -> 本地识别结果
    |
    +--> 用户点击“Gemini 网页识牌”
             |
             v
       AiRecognitionService          业务统一入口/异步协调
             |
             v
       GeminiWebViewUpstream         网页 AI 上游
        |       |        |
        |       |        +--> GeminiSelectors / GeminiScripts
        |       +----------> GenerationWatcher / AnswerSnapshot
        +------------------> WebViewBridge / 生命周期管理器
             |
             v
        Android WebView
        - 用户手动登录 gemini.google.com
        - CookieManager / DOM Storage
        - 提交图片和结构化识牌 prompt
        - 等待新回答完成
             |
             v
       Gemini 回答文本
             |
             v
       AiCardResponseParser           JSON 提取、校验、Card 转换
             |
             v
       List<Card> -> MainActivity -> 用户校对 -> HandScorer
```

建议保持“业务接口 / WebView 驱动 / Gemini 站点适配 / 识牌结果解析”四个边界。这样后续网页 DOM 发生变化时，主要修改 Gemini 适配层，不必改动识牌和评分逻辑。

## 4. WebView 模式的登录与会话设计

### 4.1 手动登录一次

首次使用时，用户从 AI 设置或联网识牌入口进入 Gemini 登录页面，在应用内 WebView 完成 Google 账号登录。之后应用复用同一 WebView 实例和 Cookie/DOM Storage 状态。

建议初期采用应用级单一 WebView，不要每次识牌都新建 WebView。这样可以减少重复登录和 SPA 初始化带来的不确定性。WebView 的创建、导航和 JavaScript 执行都必须在主线程进行。

配置要点：

- 启用网页需要的 JavaScript 与 DOM Storage。
- 配置 `WebViewClient` 处理导航与页面就绪状态。
- 配置 `WebChromeClient` 处理网页弹窗和文件选择回调。
- `CookieManager` 保持必要的 Cookie 接受策略，不向日志输出 Cookie、密码或令牌。
- 不依赖 Activity 的短生命周期对象保存唯一的 WebView 状态；明确由应用级服务或长期存在的控制器持有 WebView。
- 登录状态应通过页面真实状态判断，而不是仅凭 Cookie 不为空。

登录页需要允许用户交互。若 WebView 长期藏在后台、没有有效窗口或渲染帧，Gemini 页面可能无法正常渲染或流式更新。因此本项目第一阶段推荐采用**专用 Gemini 页面/登录界面 + 明确的 WebView 宿主生命周期管理**，先在可见 WebView 下完成稳定验证。若要实现后台自动识牌，再独立评估窗口挂接与保活方式，不要假设一个 0×0 或完全未挂窗的 WebView 可以正常工作。

必须分别验证三个条件：**测量/布局尺寸有效、View 附着到有效窗口、页面仍能持续出帧**。WebView 对象存在、`isAttachedToWindow == true`、`innerHeight` 非零或 JavaScript 可执行，都不能单独证明后台流式渲染正常。SMSProxy 在 Mate 30 / API 29 / EMUI 上实测到 Activity `decorView` 按 Home 后停止出帧，而 overlay 仍持续出帧；这是风险证据，不代表 Gemini 在所有设备上必然如此。阶段 0 应先记录可见 WebView 的基线，再单独验证切后台后的页面更新。

### 4.2 登录状态和错误呈现

上游至少应返回如下状态：

- `Ready`：会话页面已加载、输入区域就绪。
- `NotLoggedIn`：页面要求登录、出现登录表单或确认会话已失效。
- `PageLoading`：页面导航或 SPA 初始化尚未完成。
- `SelectorMissing`：关键输入/发送/回答节点没有匹配到。
- `PageUnhealthy`：页面失活、渲染窗口缺失或导航处于无法判定状态。
- `Busy`：同一个 WebView 正在进行另一轮识牌。
- `Timeout`：等待页面就绪、提交或回答超过对应超时。
- `InvalidResponse`：网页回答已返回，但无法解析成合法的牌列表。

登录失效时显示“请在 Gemini 页面重新登录”，并提供打开登录页入口。不要把选择器失效或 WebView 被销毁伪装成“未登录”，否则会误导排查。

### 4.3 URL 与持久化

应保存稳定的 Gemini 入口 URL 和必要的页面状态。是否持久化具体对话 URL需要通过真机测试确定：如果识牌任务可以共用一个连续对话，需注意旧任务内容和上下文对新图片产生干扰；如果采用每次新建会话，则可减少跨图片串扰，但增加页面操作时间。

本项目优先推荐为识牌任务定义明确的会话策略：

1. 首轮先使用可控的新对话或清洁对话状态。
2. 每次提交前记录当前回答节点的基线。
3. 只接受确实属于本次图片识别的新回答。
4. 对话上下文中不得残留另一张图片对应的识牌结果，除非 prompt 明确要求做比较。

是否每次强制新建对话，应以 Gemini 当前网页的真实 DOM 和性能测试为依据，不要仅凭固定 URL 拼接来假设新对话已经创建成功。

## 5. Gemini 网页 DOM 适配设计

### 5.1 借鉴 GeminiBridge 的内容

本次检查的 GeminiBridge 是 Python + Playwright 驱动 Gemini 网页，再对外提供 OpenAI 兼容 API 的桥接项目。它不是 Android WebView 库，不能直接复制其驱动代码。不过下列工程原则适用于本项目：

- 将 Gemini 页面选择器集中到配置/适配层，而不是散落在业务流程。
- 页面可用性要通过真实输入元素判断；GeminiBridge 的配置测试也专门检查可输入的 `textarea` / `[contenteditable="true"]` 选择器。
- 回复节点应当限定为 Gemini 的消息内容节点；项目代码中使用过 `message-content` 相关选择器，但必须在本应用实际使用的 Gemini 网页和设备上再次确认。
- 每轮提问前保存回答基线，发送后验证提交确实发生，不能点击了按钮就默认生成成功。
- 读取新回答时排除旧答案、用户 prompt 回显和末尾尚未填充的空容器。
- 在一定时间内连续读取 DOM，以内容变化、生成状态和稳定性组合判断回答结束。
- 区分页面已关闭/失活、页面还没准备好、元素暂时没有命中、真正未登录等不同错误。
- 设置有上限的重试、超时和明确诊断日志；不要无限等待或无条件重载。

参考文件：GeminiBridge 的 `gemini_web/config.py`、`chat_io.py`、`driver.py` 和 `tests/test_end_detection.py`。它的配置包含 `WEBSITE`、`READY_SELECTOR`、`RESPONSE_SELECTORS`、就绪/响应超时、轮询和稳定性参数；结束检测测试特别覆盖“只有旧答案”“回答节点末尾为空”“prompt 被错误命中为回复”等场景。

### 5.2 Android 适配层建议

新增建议文件：

```text
recognition/
  AiRecognitionService.kt       # UI 调用入口、互斥、错误和取消
  GeminiWebViewUpstream.kt      # Gemini 页面驱动和会话状态
  GeminiWebViewHost.kt          # WebView 持有、挂接和销毁
  GeminiSelectors.kt             # Gemini 专用 CSS 选择器
  GeminiScripts.kt               # 页面诊断、填充、轮询等 JS
  GenerationWatcher.kt           # 纯逻辑结束判断
  AiCardResponseParser.kt        # 模型回复转 Card
```

以上为建议布局，不是已经存在的文件。可以先将相关类放在现有 app 模块内，不必为首轮改造立即新建 Android Library；待职责稳定后再抽成独立模块。

选择器应作为单一事实来源，至少包括：

- `INPUT`：Gemini 输入框。
- `SEND`：发送按钮，作为 Enter/发送操作的备选路径。
- `RESPONSE`：AI 回答正文节点（只匹配助手消息，避免匹配用户问题）。
- `LOGIN`：登录页指示节点，仅作为证据之一。
- `FILE_INPUT`：图片上传的文件选择入口。
- 如网页支持且实测有效，再定义 `GENERATING`、`STOP` 或 `NEW_CHAT`。

**不得直接把 GeminiBridge 当前选择器当作永远正确的常量。** Gemini DOM 可能使用动态类名、Angular 组件或 Web Component；必须检查目标页面的实时 DOM，包括 Shadow DOM 边界、输入元素实际类型、上传控件的可用性以及回答节点的语义。每个选择器都要有一个诊断脚本，可以报告命中数量和节点基本属性，但不能输出账户凭据。

注入脚本也应遵守明确的安全与可诊断约束：脚本保持为可审计的静态字符串；动态 prompt 使用 `JSONObject.quote(question)` 或等效 JSON 编码，不能手工拼接 JavaScript 引号；脚本返回可解析的 JSON 结果并带 `ok` / `why` 等诊断字段，失败不能伪装成成功。若用 `MutationObserver` 监视或修复 DOM，应限制监听目标或增加自触发保护，避免自身修改形成无限循环。SMSProxy 的 stealth、CSS 和 EMUI 修复脚本是站点/设备专用内容，不应直接作为 Gemini 的默认实现。

### 5.3 诊断优先于盲目重试

建议通过只读 JavaScript 诊断返回结构化 JSON，例如：

```json
{
  "href": "https://gemini.google.com/app",
  "readyState": "complete",
  "innerHeight": 800,
  "inputCount": 1,
  "responseCount": 4,
  "fileInputCount": 1,
  "loginForm": false
}
```

上面是输出格式示例，不代表目标设备当前测量值。实际数字必须通过 WebView 实测得到。

页面还在 `about:blank`、导航未提交或 SPA 尚未渲染时应等待；只有确认页面失活或导航失败，才执行有次数上限的恢复。避免用 `document.readyState == complete` 单独判断 Gemini 已可操作，因为 SPA 加载完成不等于输入框、图片上传和回复组件已挂载。

## 6. 如何把当前图片发送给 Gemini 网页

这是本次改造最重要的技术验证点。现有 API 版本直接把 JPEG Base64 放在 HTTP 请求体；WebView 版本必须让图片以真实文件形式进入 Gemini 网页当前消息，而不是只在 prompt 中写入图片说明。DeepseekBridge 的 `doc/image_upload.md` 提供了一条值得优先验证的实现路径：针对 `contenteditable` 编辑器，在页面上下文中以 `File`、`DataTransfer` 和合成 `ClipboardEvent('paste')` 派发图片。该实现经过 Chromium/Playwright 项目验证，但不能据此保证 Gemini 或 Android WebView 一定接受该事件。

### 6.1 先探测真实上传能力

不要预先假定 Gemini 使用 `input[type=file]`，也不要假定它一定响应合成粘贴事件。阶段 0 应在实际 Android WebView 中检查：

1. 当前页面 URL、`readyState`、登录状态和输入区域是否已就绪。
2. 输入区域的真实元素类型，例如 `textarea`、`[contenteditable="true"]`，以及实际使用的 Shadow DOM 边界。
3. `input[type=file]` 的数量、可见性、关联按钮和触发行为；点击上传按钮后 `WebChromeClient.onShowFileChooser()` 是否被调用。
4. 输入元素聚焦后，合成 `paste` 事件是否触发网页处理程序；事件派发后附件预览是否真的出现。
5. 上传成功与失败时页面给出的反馈、等待时间以及附件是否进入当前待发送消息。

诊断只返回必要的节点特征和计数，不记录 Cookie、账号令牌、完整 Base64 或照片内容。DOM 诊断成功不代表图片上传成功，必须继续验证真实附件与模型回答。

### 6.2 候选方案 A：页面内合成图片粘贴

如果 Gemini 的实际输入区域是 `contenteditable`，优先制作一个最小验证原型，评估 DeepseekBridge 已采用的 `File` + `DataTransfer` + `ClipboardEvent` 方式。这条路径更贴近“把图片粘贴进富文本编辑器”的行为，不需要把 `FileList` 强行赋给隐藏输入框，也不需要先假设网页一定采用文件选择控件。

建议流程：

1. 将当前 `Bitmap` 规范化为 JPEG，初始可测试长边不超过约 1600–2000 像素；确认图片方向、画质和文件大小满足识牌需要。
2. 在 Android 侧将 JPEG 编码为 Base64。通过主线程的 `evaluateJavascript()` 将经过 JSON 安全编码的数据传入脚本；页面侧使用 `atob()`、`Uint8Array` 还原字节，再构造 `File` 和 `DataTransfer`。
3. 先聚焦实际 Gemini 输入元素，再尝试派发携带文件的粘贴事件。概念性脚本如下；上线前需结合 Gemini 真实 DOM、WebView 版本和设备实测调整：

```javascript
(el, name, mime, base64) => {
  if (!el) return { ok: false, why: 'composer-not-found' };
  try {
    const binary = atob(base64);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) {
      bytes[i] = binary.charCodeAt(i);
    }
    const file = new File([bytes], name, { type: mime });
    const dt = new DataTransfer();
    dt.items.add(file);
    el.dispatchEvent(new ClipboardEvent('paste', {
      clipboardData: dt,
      bubbles: true,
      cancelable: true,
      composed: true
    }));
    return { ok: true, dispatched: true };
  } catch (e) {
    return { ok: false, why: 'paste-dispatch-failed' };
  }
}
```

以上是表达流程的示例，不是可以直接传入 `evaluateJavascript()` 的完整调用代码；Android 实际注入时应正确序列化参数并按 WebView API 的要求处理返回值。不要直接拼接未经编码的 prompt、文件名或其他动态字符串。

4. 脚本返回 `dispatched: true` 只表示事件派发代码执行成功，不代表网页接受了文件。随后必须通过有超时的 DOM 轮询或受控的页面变化观察，确认附件预览出现、上传状态完成且当前消息确实包含图片。
5. 先验证单张图片的上传和识牌闭环，再测试连续上传不同图片时的清理及隔离行为。不要把完整 Base64 写入日志，也不要因注入失败而循环派发事件。

需要注意：合成事件可能被页面忽略，`ClipboardEvent` 的 `clipboardData`、编辑器事件监听和浏览器安全机制也可能导致行为不同。不能仅凭事件构造成功或 `dispatchEvent()` 返回值认定 Gemini 已收到图片。如果此路径无法在目标 WebView 中稳定工作，应停止扩展并评估下一条路径。

### 6.3 候选方案 B：缓存文件 + WebChromeClient 文件选择桥接

如果实测确认 Gemini 通过兼容的 HTML 文件选择流程上传图片，则评估以下方案：

1. 将当前 `Bitmap` 规范化为 JPEG，写入应用私有 cache 目录。
2. 如使用 `content://` URI，通过适当的 `ContentProvider` / `FileProvider` 暴露文件，并确保 URI 可由 WebView 对应的文件读取流程访问。
3. 通过网页真实上传按钮触发文件选择流程，在 `WebChromeClient.onShowFileChooser()` 中接收回调。
4. 对已经由应用选定的当前图片，可尝试把对应 URI 交给文件选择回调，而不是再打开系统选择器；这必须先在 Gemini 页面与实际设备上验证权限、回调和读取行为。
5. 等待网页附件预览及上传完成后，才填入或提交识牌 prompt。

此方案的前提是目标页面确实使用兼容的文件选择机制，而且 WebView 能可靠读取返回的 URI。不要假定隐藏 `input[type=file]` 可以任意赋值，也不要把直接设置文件输入框的 `FileList` 当作可靠的通用方案。若网页没有触发文件选择回调，此方案不应作为已经可用的路径。

### 6.4 降级与边界

如果合成粘贴与文件选择都无法可靠工作，应记录为核心技术验证未通过，暂停整套迁移的后续集成。可依据 Gemini 页面真实支持的交互评估其他方案，例如经过实测的系统剪贴板粘贴或拖放；必须验证图片 MIME 类型、用户权限提示、网页处理逻辑和实际附件状态。不要通过 Gemini 私有内部 API、隐藏网络接口或站点内部 RPC 绕过网页交互。

上传失败时，不能把只含图片占位说明的文本 prompt 当作成功的识牌请求继续处理，也不能把空回答或旧回答转换成有效牌列表。应返回明确的图片上传失败错误，保留原有本地 OCR/识牌结果供用户继续使用。

### 6.5 上传与真实性验收

- Gemini 页面显示的附件预览对应本次 `Bitmap`，并且上传状态已结束。
- 发送 prompt 前确认图片已附加；超时、预览缺失或上传报错时不发送。
- 一次识别只提交当前图片一次；连续识别图片 A、图片 B 时，不遗留 A 的待发送附件，也不把 A 的回答返回给 B。
- 通过带图与不带图的对照测试验证模型确实使用了图像像素信息；要求模型指出测试图片中只有看图才能获取的具体细节，而不是只检查事件是否派发。
- 重复识别、上传超时、页面刷新、任务取消和登录失效均能得到明确结果，不会永久卡住 UI。
- 明确临时文件清理时机，避免缓存长期堆积；日志不含完整图片 Base64、Cookie 或令牌。

如果上传机制尚未在真实 Gemini WebView 中证明可靠，应将其保留为阶段 0 的独立技术验证门槛，不要在未经实测的情况下宣称图片直传已经可用。

## 7. 识牌 Prompt 与响应解析

### 7.1 固定输出格式

现有 `AiRecognitionClient.kt` 已经包含 prompt 和响应解析逻辑。迁移时应将业务 prompt 从 HTTP 客户端中抽出，确保 API 版与 WebView 版（在过渡期需要并存时）使用相同的识牌约束。

建议要求模型只输出 JSON，不输出 Markdown 围栏和额外解释。例如：

```json
{
  "cards": [
    {"rank": "A", "suit": "Spade"},
    {"rank": "10", "suit": "Heart"}
  ]
}
```

这只是结构示例。必须沿用项目 `Rank`、`Suit` 中真正定义的牌面标记，确定大小王表达、花色名称、重复牌、最多 27 张的规则，并更新 prompt 和测试。不要直接依赖模型输出数组长度一定为 27，因为图片可能没有完整展示全部牌。

### 7.2 独立解析模块

将现有 `AiRecognitionClient` 内的 `extractText()`、`parseCards()` 和牌面字符串归一化逻辑抽取为 `AiCardResponseParser`。解析器接收纯文本，输出经过校验的 `List<Card>` 或明确错误，与文本来自 API 还是 WebView 无关。

解析步骤：

1. 去掉前后的 Markdown 代码围栏（若模型违反 prompt 输出了围栏）。
2. 尝试解析标准 JSON；必要时只做有边界、可测试的 JSON 提取，不要贪婪匹配任意花括号。
3. 校验字段、rank 和 suit 的合法性。
4. 将字符串映射到项目真实的 `Rank` / `Suit` 枚举。
5. 处理未知牌面、重复记录、超量结果与空数组，不能静默把坏数据转换为有效牌。
6. 对输出牌数、未知字段和解析失败情况给出警告或错误，交由 UI 提示用户校验。

测试必须覆盖 Gemini 回答中的解释文字、Markdown JSON、非法 JSON、缺字段、无效花色/点数、空结果和超过可用牌数量等情况。

## 8. 提交、生成和结束判定状态机

不要将“执行了 JS 点击”当作请求成功。推荐使用以下状态机：

```text
Idle
  -> EnsurePageReady
  -> EnsureLoggedIn
  -> CaptureReplyBaseline
  -> AttachImage
  -> VerifyAttachment
  -> FillPrompt
  -> Submit
  -> VerifySubmission
  -> WaitForNewAnswer
  -> CollectAnswer
  -> VerifyStableEnd
  -> ParseCards
  -> Success

任意阶段失败 -> 分类错误 -> 恢复/有限重试/显示错误
```

### 8.1 保存发送前基线

点击提交之前记录：

- 最后一个非空助手回答的文本（或稳定摘要）。
- 当前助手回答节点数量。
- 输入框当前内容与上传附件状态。
- 当前 URL/会话标识（可读取时）。

提交后确认输入框已清空、用户消息已添加、页面产生新的回答节点或回答正文发生真实变化。GeminiBridge 的结束检测用例明确保护了旧回答和 prompt 回显：末尾空节点不能让旧内容绕过基线检查，用户自己的问题不能误当成 AI 回答。

在本项目中，这一点尤其重要，因为 AI 识牌连续输入的是相似指令，且每次图片结构很接近；如果把上一张图的稳定回答当成下一张图结果，UI 看起来可能正常，但牌力评分完全错误。

### 8.2 回答结束判定

采用周期轮询，例如初始可用 500–1000 ms 作为试验参数，根据 Gemini 网页速度和设备表现实测调整。结束判据应组合考虑：

- 本轮确实观察到了新回答，而不是只有旧答案或 prompt 回显。
- 新回答非空。
- 连续多次采样内容不再变化，并经过最小静默时间。
- 若实时 DOM 确认有生成中控件，可将其状态作为辅助信号，但不能依赖一个未验证的按钮选择器。
- 检查页面是否出现登录、验证码、上传失败、模型报错或会话到顶等明确错误。

建议将 `GenerationWatcher` 做成纯 Kotlin 逻辑类，输入每次采样的 `text`、回答节点数量、生成信号和时间戳，输出 `WAITING`、`FINISHED` 或 `TIMEOUT`。稳定轮询次数不得低于 3 次；可先用约 1.5–2 秒的最小静默时间作为试验起点，再按 Gemini 实测调整。判定前必须保存本轮发送前的 `baselineCount` 与 `baselineText`，只有确认出现本轮新回答或回答文本相对基线真实变化，才将新文本交给结束判定。stop/generating 控件只作辅助信号，不能成为唯一完成条件。SMSProxy 已验证 DeepSeek 没有可依赖的 stop 元素；Gemini 的控件及语义仍须单独实测。

超时应区分页面就绪、图片上传、提交确认和回答生成，避免只设一个很长的总超时使 UI 长时间无反馈。超时后可以基于错误类型做有限恢复，但不能不加判断地重复上传或重复发送同一问题。

## 9. 并发、UI 与资源生命周期

目前在线识牌用单线程 `worker` 同步执行 HTTP 请求。WebView 版本需要改为可等待、可取消的异步操作，所有 WebView API 与视图层级调用都转到 Android 主线程。

建议新增单一 `AiRecognitionService` 协调类：

- 对每个 WebView 实例进行互斥，避免两次识牌同时改写输入框或互相读取回答。
- 持有 `GeminiWebViewUpstream` 和一个正在运行的任务句柄。
- 将页面操作通过 `Handler(Looper.getMainLooper())` 或等效的主线程桥接执行，并通过协程等待异步 JavaScript 回调。
- UI 线程只更新状态，不能执行轮询 sleep 或阻塞等待。
- Activity 销毁时取消与当前页面绑定的 UI 工作，不能在过时 Activity 上更新 UI；应用级 WebView 本身是否继续存在，由明确的生命周期控制器决定。
- 图片文件、Bitmap 和 JS 回调的资源所有权要清楚；失败或取消时清理临时文件，并正确释放不再使用的 Bitmap。
- 不要在回答生成时用健康探针不断执行 JS，避免探针与实际生成争用同一页面。

WebView 的渲染窗口问题应分阶段处理。初版先在可见 WebView 路径下验通，避免同时引入悬浮窗、后台保活和图片上传三类问题。由 `GeminiWebViewHost` 统一负责宿主、父容器与生命周期；同一个 WebView 同时只能有一个父容器。Activity 重建或在页面与离屏宿主之间切换时，应先安全移除旧父容器再重新挂接，并区分“移除父容器但保留 host”和“彻底拆除 host 并清空状态”。重试挂接不能只凭 `parent != null` 判定成功，还要检查有效窗口和页面实际更新。后台识牌若成为明确需求，再参考 SMSProxy 的视口测量、窗口挂接、生命周期重挂和低干扰健康监测经验独立实现；overlay 权限、前台服务、通知和电池优化引导属于宿主应用职责，不应混入 Gemini 站点适配器。

## 10. 配置界面与旧 API Key 迁移

### 10.1 新配置模型

目前 `AiSettings` 是以 Provider、endpoint、model、apiKey 为核心的通用 API 配置。WebView 不需要 Gemini API endpoint 或 Key。建议引入明确的上游模式，例如：

```kotlin
enum class AiTransport {
    GEMINI_WEBVIEW,
    API
}
```

此枚举仅为建议设计。若未来仍希望保留 OpenAI API 或自定义 Provider，可保留 API 模式；但 Gemini 网页模式不应因为一个空 `apiKey` 而被拒绝。

Gemini WebView 配置 UI 建议展示：

- 上游：Gemini 网页版（WebView）。
- 登录状态：已登录 / 需要登录 / 页面未就绪 / 检测失败。
- 操作：打开 Gemini、重新登录、检查连接。
- 可选项：回答超时、是否新建对话、调试诊断（默认关闭）。

默认不显示 Gemini API Key、模型 API endpoint 的编辑框。WebView 网页使用的实际模型由网页会话决定，不能声称可以像 API 一样通过 `model` 字段可靠指定模型。

### 10.2 向后兼容与 Key 清理

不要在第一步就破坏现有的设置持久化格式。建议：

1. 为现有 profile 增加 `transport` 或以明确的 provider 类型识别 WebView profile。
2. 对旧 `Google Gemini` profile 做一次迁移，切换为 `GEMINI_WEBVIEW`，保留其它不相关配置并避免启动时重置所有 AI 设置。
3. UI 默认选择 Gemini WebView profile，并把旧 Gemini API profile 标识为旧配置或在确认迁移完成后移除。
4. 继续保留 OpenAI/custom API profile 的兼容性，除非产品决定同时删除全部 API Provider。
5. 不在迁移脚本、日志或异常中输出 Key。确认 WebView 模式已使用成功后，再清除旧 Gemini Key 的持久化密文和无用的 Keystore alias；不要仅隐藏 UI 而留下被误用的旧凭据。
6. 更新 README，说明不再需要 Gemini API Key，而是需要在应用的 Gemini WebView 中登录 Google 账号。

如果产品最终决定彻底取消所有客户端 API Key 支持，可以在上述迁移完成并确认没有其他调用方后，再单独删除 API 传输代码。此次需求只明确要求 Gemini 识牌改用 WebView，不应未经确认就把 OpenAI/custom Provider 也一并破坏。

## 11. 建议的实施阶段

### 阶段 0：冻结基线与验证核心可行性

1. 记录当前 `./gradlew testDebugUnitTest assembleDebug` 结果。
2. 保存现有 AI 识牌测试图片和输出结果，建立同一批输入的对照基线。
3. 制作一个只负责打开 Gemini 的最小 WebView 页面，手动登录并确认 Cookie/DOM Storage 跨 Activity 重建或应用重启的表现。
4. 实测实际 DOM：输入框、发送操作、助手回答节点、文件输入、附件预览、生成状态和登录错误。
5. **优先验证真实图片上传闭环**：先检查输入元素与上传控件，再最小化测试页面内合成粘贴（`File` + `DataTransfer` + `ClipboardEvent`）；若真实 DOM 使用文件选择流程，再测试 `WebChromeClient.onShowFileChooser()` 与 cache URI。必须确认 Gemini 附件预览确实出现，并通过模型回答验证像素内容。这是进入后续阶段的准入条件；不要预设任一路径可用。
6. 验证 WebView 的视口尺寸、窗口附着和页面真实更新；先以可见页面作为准入基线。若后续要求后台识牌，再单独测试按 Home、返回前台、Activity 重建及窗口重挂，不要把“有 URL / JS 能执行 / View 已 attached”当成渲染成功。

### 阶段 1：建立上游接口与 DOM 诊断

1. 新建 `GeminiWebViewUpstream` 和 `GeminiSelectors`。
2. 实现创建 WebView、打开首页、页面就绪、登录状态和能力报告。
3. 写只读的 DOM 诊断脚本，检查每个选择器的命中数量与元素特征。
4. 添加手动登录入口及 UI 状态显示。
5. 不接入识牌业务，先确保登录、页面恢复和导航可诊断。

### 阶段 2：完成单次图片问答闭环

1. 实现阶段 0 已证明可靠的图片上传路径（页面内合成粘贴，或缓存 URI + 文件选择桥接），并加入上传完成确认；不要未经验证就同时启用多条路径。
2. 实现 prompt 填充与提交验证。
3. 保存回答基线，确认本轮新回答开始。
4. 使用 `GenerationWatcher` 判定生成结束。
5. 新增 `AiCardResponseParser`，把结果转换为 `List<Card>`。
6. 编写端到端手工测试：单张图片、重复识牌同一图片、连续识牌不同图片、登录过期和页面异常。

### 阶段 3：接回现有 UI，API 模式与网页模式分离

1. 在 `MainActivity.recognizeOnline()` 中改为调用 `AiRecognitionService`。
2. 删除 `settings.apiKey.isBlank()` 对 WebView 模式的限制。
3. 在线识牌按钮采用可观察的忙碌状态，任务完成或失败都恢复按钮。
4. 保证在线失败不会覆盖原有本地识别结果，保留当前“已保留原有识别结果”的语义。
5. 设置 UI 根据 `transport` 切换显示内容，而不把 WebView 伪装为拥有 API endpoint/model 参数的 Provider。
6. 暂时保留 API 实现用于回归或切换；不要让两个后端同时执行同一个识牌请求。

### 阶段 4：完善错误恢复与生命周期

1. 区分登录失效、上传失败、页面未就绪、选择器变化、提交失败、生成超时和结果解析失败。
2. 添加有限的重试、页面恢复和任务取消。
3. 防止旧回答、用户 prompt 回显和前一张图片的结果被识别为当前回答。
4. 检查 Activity 重建、屏幕旋转、应用切后台、返回前台及进程重启后的行为。
5. 检查长时间使用的页面是否需要重开会话、如何清除旧附件与上下文。

### 阶段 5：移除 Gemini API Key 路径并收尾

1. 确认 WebView 路径已通过功能和设备验收。
2. 完成旧 Gemini profile 的迁移与 Key 清理。
3. 若 OpenAI/custom API 仍是产品能力，保留其单独配置与实现；如不再支持，再作独立变更。
4. 更新 README、设置说明、故障排查文档和测试用例。
5. 运行完整测试与构建，检查依赖、Manifest 和 R8/Release 构建行为。

## 12. 测试计划与验收标准

### 12.1 单元测试

- `AiCardResponseParser`：标准 JSON、Markdown 围栏、前后说明、错误 JSON、非法 rank/suit、空结果、重复项和数量上限。
- `GenerationWatcher`：文本持续增长、生成中暂停、稳定后完成、未出现新回答、旧回答不变、prompt 回显、页面错误和超时。
- `WebViewHost` / 生命周期：WebView 创建与 Activity 重建后不出现多重 parent 或僵尸宿主；窗口未就绪时等待而不陷入 reload 循环；生成期间健康检查不导航、不 reload、不打断任务。
- 并发与回答隔离：同一个 WebView 同时最多一个识牌请求；旧回答、末尾空节点和 prompt 回显均不能通过本轮基线检查。
- `GeminiSelectors` / 诊断结果：页面选择器配置集中，命中失败能转换为明确错误。
- 图片上传编排：验证 JPEG/字节数据传递、动态参数安全编码、粘贴脚本返回值和异常处理；若采用文件选择路径，还要覆盖 URI 交接、回调取消及重复回调。
- 上传确认与降级：事件派发成功但附件预览未出现时必须判失败；开关关闭、超时、页面忽略事件或文件读取失败均不得误报上传成功，也不得继续提交 prompt。
- `AiSettingsStore`：旧 profile 迁移、WebView profile 不需要 API Key、OpenAI/custom profile 不被破坏。
- 异步服务：Busy、任务取消、错误后允许再次识牌，单实例互斥不发生死锁。

### 12.2 真机验收

- 新安装时打开 Gemini WebView，可以手动登录，不需要填写 Gemini API Key。
- 应用重启后登录态按预期恢复；登录已过期时有明确提示。
- 通过阶段 0 选定并验证的网页交互路径（合成粘贴或 `WebChromeClient` 文件选择桥接）上传牌图；确认附件预览及上传完成后再提交，并返回结构化牌面结果。
- 使用带图与不带图的对照请求验证模型确实读取图像像素，而不是仅根据 prompt 猜测；测试图片应包含可核对的具体牌面细节。
- 连续上传不同牌图时，回答不会串到上一次图片。
- 上传尚未完成时不会提交 prompt；上传失败时能快速报错。
- 回答正文只取助手新输出，不将用户问题、旧回答或空容器误识别为响应。
- 回答较慢或出现生成暂停时，不会过早截断；超时也不会永久卡住 UI。
- WebView 页面重新挂载、Activity 重建、取消任务后都能正常恢复。
- 本地 `VisualCardCodec`、`CardRecognizer`、手牌编辑和评分功能与改造前保持一致。
- 在线识牌失败时保留已有本地识别结果，不破坏用户手动修改的牌。
- 设置迁移后 Gemini WebView 工作不依赖旧 API Key；若保留 OpenAI API，其功能仍可回归验证。

### 12.3 构建与回归

每一阶段至少执行：

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

在最后阶段再运行项目全部相关测试。测试通过不能替代 Gemini 实际登录和文件上传的真机验收；网页 DOM 和登录行为必须在实际目标 WebView 中验证。

## 13. 风险与注意事项

| 风险 | 影响 | 对策 |
| --- | --- | --- |
| Gemini DOM 更新 | 找不到输入框、回答或上传控件 | 集中选择器，实时诊断，低耦合站点适配层与 DOM 回归用例 |
| 文件上传链路不可控 | 图片没有真正附加，模型回答错误 | 阶段 0 分别探测页面内合成粘贴和文件选择回调；只有附件预览与带图实测通过才进入后续集成 |
| 登录态过期或出现额外验证 | 识牌无法继续 | 识别 NotLoggedIn/页面异常并提示用户在 WebView 处理，不伪装成空结果 |
| 旧回答被当成新回答 | 返回上一张图片的牌，导致后续评分错误 | 发送前基线、提交确认、新节点/文本变化和输出校验 |
| 共享 WebView 并发冲突 | 图片、prompt 与回答交叉 | 单实例互斥、明确任务状态和取消语义 |
| 后台 WebView 没有有效窗口或持续出帧 | 流式内容不更新、轮询超时；仅检查 attached 或视口高度可能漏报 | 首版验证可见 WebView；同时记录尺寸、窗口状态与真实回答更新；后台渲染作为独立阶段验证宿主与重挂生命周期 |
| 上游输出不稳定 | JSON 错误或牌数不完整 | 严格解析、校验与 UI 警告，不把坏结果静默当成成功 |
| API Key 迁移遗留 | UI 已切换但旧 Key 仍存储或被调用 | 迁移后验证 profile 与调用路径，再有序清除过期凭据 |
| Gemini 网页版条款或行为改变 | 方案可能失效或受到限制 | 核实目标网站规则，准备维护和回退策略，不把网页自动化视为稳定官方 API |

## 14. 最终建议

采用“**Gemini WebView 上游 + 独立识牌响应解析器 + 原有本地识别兜底**”的架构。改造不应该是简单地把 HTTP 调用改成 `evaluateJavascript()`：浏览器方式必须新增登录态、文件上传、提交确认、回答基线、结束判定和页面恢复等能力。

最高优先级不是先重写 `MainActivity`，而是进行阶段 0 的最小实证：在实际 Android WebView 中登录 Gemini，验证视口尺寸、窗口附着和页面实际更新；先检查真实输入框及上传控件，再尝试页面内合成粘贴（`File` + `DataTransfer` + `ClipboardEvent`），或在确认页面采用文件选择流程后测试 cache URI 与 `WebChromeClient.onShowFileChooser()`。必须确认附件上传完成，并通过带图/不带图对照证明模型确实读取了图像像素，再提交 prompt 并通过发送前基线可靠地读取本轮新回答。先跑通可见 WebView 闭环，再测试 Activity 重建、切后台与返回前台后的恢复；不要在图片上传尚未验证时同时引入 overlay 和保活机制。闭环成功后，再逐层接入 `AiCardResponseParser`、原有 UI 和配置迁移。

最终目标是移除 Gemini 在线识牌对 API Key 的依赖，同时保留本地识别与评分能力。除非另有产品决定，不应顺带删除仍然需要的 OpenAI/custom API Provider。

## 15. 实施状态回填（2026-10-30）

本设计中的“待确认”问题已由 `doc/multi_ai_update.md` §5.6 与 `doc/ai_tasks.md` 的实测记录回答，摘要如下：

- **架构已泛化**：业务层只依赖 `AiSource`；站点知识集中在 `SiteAdapter`；页面编排在站点无关的 `PageDriver` / `WebViewAiSource`；HTTP API 由独立的 `HttpApiSource` 保留。Gemini 专用的旧 `AiRecognitionService` / `GeminiUpstream` 已删除。
- **Gemini**：登录判据、输入区、发送按钮与合成 paste 闭环有真机记录；`Upload and tools` 只弹网页面板，`FILE_CHOOSER` 的二级入口尚未自动化。
- **DeepSeek（首发）**：真实手牌图上传 → 提交 → 新回答 → 解析已通过；2026-10-30 复测：生产驱动离屏识牌 27 张，同一实例连续两图/追问 27 → 22 张且互不串线；无 stop 控件，结束判定只靠文本增长 + 连续稳定；`FILE_CHOOSER` 必须原生触摸，JS click 不满足 user activation。
- **ChatGPT**：适配器、脚本、五源注册与单测已落地，但**未真机登录验证**，不得标为可用。
- **上传成功判据**：只有附件预览节点；`supportedUploads` 限制回退范围；提交前记录 `inputText` / `attached` / `sessionId`，脏输入区先建立经 DOM 确认的新会话。
- **共享宿主**：登录/诊断页与主界面共用同一 WebView；冷启动未挂接会提示先打开 AI 页面，可见 ↔ 离屏切换无崩溃，登录态保留。
- **遗留的真机项**：旋转/重建、切后台返回、Gemini 文件选择器与设备端连续图、ChatGPT 登录与闭环、三源横向切换、HTTP API Key 回归、无障碍与横屏验收。
