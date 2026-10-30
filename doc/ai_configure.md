# AI 源管理独立页面设计

> **状态：** 已实施（2026-10-30，P0–P4）。当前入口为全屏 `ui/AiSettingsActivity`；旧的 `ui/AiSettingsScreen.kt` / `ui/AiSettingsDialog.kt` 已删除。
>
> **验证：** `./gradlew testDebugUnitTest assembleDebug` 全绿（373 项，0 失败）；APK 清单确认 `.ui.AiSettingsActivity` 为 `exported=false`、竖屏、`adjustResize`。
>
> **真机（LIO-N29 / Android 10 / 1176×2400）：** 已验证入口/返回、五源字段显隐、dirty 切换与返回确认（取消保留输入）、保存并选用后主界面刷新、force-stop 重启后选择保留、非激活配置「未选用」、API Key 缺失错误落在字段并聚焦、删除激活配置的回退确认与回退结果；登录入口在 Ready 下仍可见，点击进入无诊断控件的全屏登录页；返回后自动重查并恢复「网页可用」。全程无 FATAL EXCEPTION。剩余：API 完整配置态、最后一份删除禁用、1.3× 字体/键盘/TalkBack、重建草稿、冷启动并发检查。
>
> **依据：** `doc/ui_design.md` §6（AI 源管理页面）、§7（网页登录页）、`doc/multi_ai_update.md` §4/§8、
> 现有 `AiSettingsStore` / `AiSourceId` / `AiSourceRegistry` / `WebViewHost`。
>
> **目标：** 把 AI 源配置从「长表单弹窗」改成**独立全屏管理页面**，让源选择、配置档管理、
> 字段校验、登录与健康状态各占明确位置，且不改变现有持久化格式与识牌路径。

---

## 1. 现状与问题

当前 `AiSettingsScreen` 用 `AlertDialog` 承载全部能力：

- 源下拉 + 配置档下拉 + 名称 + URL / endpoint / model / Key + 登录 + 健康态 + 删除。
- 弹窗高度受屏幕和键盘挤压，字段多时只能在小区域内滚动。
- 切换源会**静默替换**正在编辑的配置；没有未保存提示。
- 删除、保存、取消都在同一行按钮里，破坏性操作与主操作距离过近。
- 健康检查状态只存在于弹窗的一次性文本里，关闭即丢失；返回登录页后只能重新打开弹窗。
- 源、配置档、当前激活配置三者的关系只靠两个下拉表达，用户很难确认「我正在改哪一份、它是否已生效」。

`doc/ui_design.md` §2.2 已把该问题列为「AI 配置通过长对话框容纳多个字段 → 独立的 AI 源管理页面或全屏配置面板」。
本设计采用**独立 Activity**，不是全屏 Dialog，也不是 BottomSheet。

---

## 2. 目标与非目标

### 2.1 目标

1. AI 源管理是独立页面：有自己的标题、返回、滚动区域和固定底部操作栏。
2. 五种源使用同一套页面结构，字段按源类型显隐，网页源绝不出现 API 专属字段。
3. 配置档的「查看 / 新建 / 编辑 / 删除 / 设为当前」关系一眼可见。
4. 所有校验错误显示在对应字段下方；保存失败不关闭页面、不丢输入。
5. 网页源只在**当前激活配置**上做健康检查与登录，避免共享 WebView 被非激活配置带偏。
6. 未保存修改在返回、切换源、切换配置档前明确确认。
7. 不新增第三方依赖，不改变 `AiSettingsStore` 的存储格式。

### 2.2 非目标

- 不做多 WebView / 多会话并行；所有网页源仍共用一个 `WebViewHost` 与一份 Cookie。
- 不开放 CSS 选择器、脚本或站点适配参数给用户。
- 不在本页做 HTTP API 的真实网络请求测试；API 源只做配置完整性校验。
- 不改主工作台布局；主界面仍只显示当前源、健康态与「登录」入口。
- 不把删除/放弃修改的确认框也改成页面；确认类 `AlertDialog` 仍可保留。

---

## 3. 信息架构与路由

### 3.1 页面与入口

| 项 | 设计 |
| --- | --- |
| 页面 | `ui/AiSettingsActivity`，全屏、竖屏、`exported=false` |
| 标题 | 「AI 源管理」，副标题「选择联网识牌使用的 AI」 |
| 入口 1 | 主界面顶部 `AI 源` 按钮 |
| 入口 2 | `recognizeOnline()` 发现 API Key 缺失时，直接打开本页（替换当前 `showAiSettings()`） |
| 出口 | 系统返回 / 左上角返回；保存成功后自动返回主界面 |
| 返回刷新 | 不新增 `ActivityResult` 契约：主界面 `onResume` 已有健康检查（AI-184），返回即刷新，避免重复检查 |

### 3.2 页面布局（网页源）

```text
┌────────────────────────────────────────┐
│ ←   AI 源管理                          │
│     选择联网识牌使用的 AI               │
├────────────────────────────────────────┤
│ 当前使用                                │
│ DeepSeek 网页版 · 默认配置   [网页可用] │
│ 所有网页源共用同一个 WebView 与 Cookie  │
├────────────────────────────────────────┤
│ 1  选择 AI 源                           │
│  ● DeepSeek 网页版              首发    │
│  ○ Gemini 网页版                        │
│  ○ ChatGPT 网页版                       │
│  ○ 自定义网页源                         │
│  ○ HTTP API（API Key）                  │
├────────────────────────────────────────┤
│ 2  配置档                               │
│ [默认配置                        ▼]  ⋮  │
│ ＋ 新建配置档                           │
├────────────────────────────────────────┤
│ 3  配置                                 │
│ 名称     [DeepSeek 网页版            ]  │
│ 首页 URL [https://chat.deepseek.com  ]  │
│ 实际模型由网页会话决定，此处不能指定     │
│                                         │
│ 状态：网页可用                          │
│ [打开网页登录]  [重新检查]              │
├────────────────────────────────────────┤
│ [取消]                    [保存并选用]  │
└────────────────────────────────────────┘
```

### 3.3 页面布局（HTTP API 源）

```text
│ 3  配置                                 │
│ 名称     [Google Gemini              ]  │
│ 接口地址 [https://...                ]  │
│ 模型     [gemini-3.6-flash           ]  │
│ API Key  [••••••••••••            👁 ]  │
│ 配置完整（本页不验证网络连通性）         │
│                                         │
│ [取消]                    [保存并选用]  │
```

### 3.4 结构规则

- 页面主体是 `ScrollView`；底部操作栏固定在屏幕底部，键盘弹出时随 `adjustResize` 上移。
- 区块顺序固定：当前使用 → 选择源 → 配置档 → 配置表单 → 底部操作栏。
- 所有可点目标 ≥ 48dp；主按钮高度沿用 `@dimen/button_height`（52dp）。
- 页面配色、圆角、间距、文字层级全部复用 `colors.xml` / `dimens.xml` / `styles.xml`，
  不引入新色板；成功只用 `@color/success`，错误只用 `@color/error`。

---

## 4. 交互规格

### 4.1 打开页面

1. `loadAll()` 一次读出配置档；`load()` 读取当前激活配置。
2. `selectedSource = active.source`，`editing = active`，`activeId = active.id`。
3. 源列表按 `AiSourceId.entries` 顺序渲染（DeepSeek 首发），不硬编码中文名。
4. 配置档下拉只显示**当前源**的配置档；选中态与激活态分开标记。
5. 若当前激活源是网页源，自动发起一次该激活配置的健康检查。

### 4.2 选择 AI 源

| 条件 | 行为 |
| --- | --- |
| 无未保存修改 | 直接切换；加载该源的第一个配置档；若没有则创建未保存草稿 |
| 有未保存修改 | 先确认「放弃未保存的修改？」，确认后才切换；取消则停留原处 |

- 源切换**不写库**，只改变正在编辑的对象。
- 切换后表单字段立即按新源显隐；旧源的字段值留在草稿中，不串到新源。
- 每个源行显示一句能力说明：
  - DeepSeek / Gemini / ChatGPT：「网页登录 · 无需 API Key」。
  - 自定义网页源：「仅支持与内置聊天页结构相同的站点」。
  - HTTP API：「HTTPS 接口 · 需要 API Key」。

### 4.3 配置档管理

| 操作 | 规则 |
| --- | --- |
| 选择配置档 | 有未保存修改先确认放弃；选中后载入该 profile |
| 新建 | 以该源预设为模板生成新 `id`，进入「未保存」草稿，不写库 |
| 保存并选用 | 校验通过后 `store.save(settings)` + `store.select(id)`，返回主界面 |
| 删除 | 只对已保存 profile 可用；二次确认写明名称与回退结果 |
| 最后一份配置 | 全库只剩 1 份时禁用删除，提示「至少保留一个配置档」 |

- 激活态用「当前」Chip 标识；未保存草稿在配置档位置显示「新建配置（未保存）」。
- 删除当前激活 configuration 时，回退顺序：同源剩余第一份 → 全库第一份；确认框必须写出目标名称。
- 删除只做持久化，不主动销毁 WebView；下一次健康检查或识牌会按新的激活源重新加载。

### 4.4 表单字段与显隐

| 字段 | 网页源 | HTTP API 源 | 说明 |
| --- | --- | --- | --- |
| 名称 | 显示 | 显示 | 可留空，保存时回落为源名称 |
| 首页 URL | 显示，必填 HTTPS | 隐藏 | 自定义源必须由用户填写 |
| 接口地址 | 隐藏（含标签） | 显示，必填 HTTPS | 沿用现有协议嗅探 |
| 模型 | 隐藏（含标签） | 显示，必填 | |
| API Key | 隐藏（含标签） | 显示，必填，密码态 | 默认掩码，支持显示/隐藏；不写日志、不进 Bundle |
| 网页模型提示 | 显示 | 隐藏 | 「实际模型由网页会话决定」 |

**硬规则：** 网页源不得出现 endpoint / model / API Key 的字段、标签、错误文案或空占位。
字段显隐必须整组处理，不能只隐藏输入框而留下标签（UI-015 真机回归过的缺陷）。

### 4.5 保存

1. 点击「保存并选用」后先做字段校验；失败时：
   - 对应字段下方显示错误；
   - 滚动并聚焦第一个错误字段；
   - 页面不关闭、输入不丢。
2. 校验通过后写入 store 并设为激活。
3. 保存成功 → `finish()` 返回主界面；主界面 `onResume` 刷新名称与健康态。
4. 保存过程中禁用主按钮，防止重复提交；保存是同步 SharedPreferences 写入，通常 < 50ms，
   不需要 loading 遮罩，但需防抖。

### 4.6 返回与未保存修改

- 左上角返回、系统返回、底部「取消」统一走同一路径。
- `dirty == false`：直接 `finish()`。
- `dirty == true`：确认框「放弃未保存的修改？」，按钮为「继续编辑 / 放弃」。
- 确认放弃后不写库、不改激活源。
- 已保存后返回时不再提示。

### 4.7 打开网页登录

- 仅当**正在编辑的 profile 就是当前激活 profile**且为网页源时启用。
- 登录入口始终显示，不随健康态隐藏：Ready 时文案为「网页登录 / 切换账号」，其余状态为「打开网页登录」。全新安装没有 Cookie，或页面被误判为 Ready 时，用户仍必须能主动登录。
- 点击后带 `EXTRA_URL` / `EXTRA_SITE` / `EXTRA_LOGIN=true` 打开 `AiProbeActivity` 的登录模式：隐藏站点切换、诊断按钮与日志，只保留返回、标题、重新加载和全屏网页；返回本页 `onResume` 自动重查一次。
- 非激活 profile 显示提示：「保存并选用后可打开网页登录」，按钮禁用。
  - 原因：共享 WebView 只有一个页面；为非激活源打开登录页会把页面导航到另一个站点，
    但主界面仍认为旧源是激活的，健康检查与识牌会互相打脸。
- 登录返回后只刷新健康态，不自动保存或切换源。

### 4.8 健康检查

- 自动检查时机：页面首次打开、从登录页返回、点击「重新检查」。
- 检查对象：**当前激活且已保存的网页配置**；未保存草稿不检查。
- API 源：只展示 `HttpApiSource.healthCheck()` 的配置完整性结论，文案为
  「配置完整（本页不验证网络连通性）」/「缺少 API Key」，不使用「已连接」。
- 非激活配置：状态位显示中性的「未选用」，不发起检查。
- 检查在 worker 线程执行，UI 用递增序号丢弃迟到结果，`isFinishing/isDestroyed` 后不回写。

状态映射（与 `WebViewAiSource.healthCheck` 一致）：

| 结果 | 展示 | 动作 |
| --- | --- | --- |
| Checking | 状态：检测中…（中性） | 禁用「重新检查」 |
| Ready | 状态：网页可用（成功色 + 文案） | 保留「网页登录 / 切换账号」入口 |
| NotLoggedIn | 状态：需要登录（金色 + 原因） | 显示「打开网页登录」 |
| Unavailable | 状态：页面暂不可用（错误色 + 原因） | 显示「重新检查」「打开网页登录」 |
| Unknown | 状态：未知（中性） | 允许「重新检查」 |
| NotActive | 状态：未选用（中性） | 不检查 |

### 4.9 键盘与滚动

- `windowSoftInputMode = adjustResize`；底部操作栏跟随键盘上移。
- API Key 获得焦点时，滚动到可见区域；错误文案不能被键盘遮住。
- 表单使用 `NestedScrollView` 或 `ScrollView` + `TextInputLayout`，字段间距 12dp。

---

## 5. 状态模型

### 5.1 UI 状态

建议把「编辑逻辑」从 Activity 中抽出为纯 Kotlin 类，便于 JVM 测试：

```kotlin
enum class AiField { NAME, HOME_URL, ENDPOINT, MODEL, API_KEY }

data class FieldErrors(val byField: Map<AiField, String> = emptyMap())

data class AiSettingsUiState(
    val profiles: List<AiSettings>,
    val activeId: String,
    val source: AiSourceId,
    val editing: AiSettings,
    val isNewDraft: Boolean,
    val dirty: Boolean,
    val errors: FieldErrors = FieldErrors()
) {
    val isActiveProfile: Boolean get() = editing.id == activeId
    val canDelete: Boolean get() = !isNewDraft && profiles.size > 1
}
```

`AiSettingsEditor` 负责：

- `selectSource(source)`：返回 `直接切换` / `需要确认放弃`。
- `selectProfile(id)`、`newProfile()`：同上。
- `update(field, value)`：改写草稿并重算 `dirty`。
- `validate()`：返回 `FieldErrors`；`buildForSave()`：trim + 默认名称 + transport。
- `deletePlan()`：返回 `可否删除 / 回退 profile 名称`。

Activity 只负责：渲染、确认框、启动登录页、健康检查、`finish()`。

### 5.2 事件转换

| 当前状态 | 事件 | 下一状态 |
| --- | --- | --- |
| 任意 | 打开页面 | 载入激活 profile，`dirty=false`，自动检查（网页） |
| 无修改 | 切换源 / 配置档 | 载入目标，`dirty=false` |
| 有修改 | 切换源 / 配置档 / 返回 | 弹确认；取消 → 原状态；确认 → 目标状态 |
| 编辑中 | 修改字段 | `dirty=true`，清除该字段错误 |
| 编辑中 | 保存且校验失败 | `errors` 更新，状态不变 |
| 编辑中 | 保存且校验通过 | 写库 + 选中 + `finish()` |
| 已保存 profile | 删除并确认 | 重新 `loadAll()`，激活态回退，`dirty=false` |

### 5.3 生命周期与草稿

- 页面竖屏锁定，旋转不重建。
- 字体缩放 / 语言切换等配置变更会重建 Activity：
  - `onSaveInstanceState` 只保存非敏感字段（sourceKey、provider、homeUrl、endpoint、model、
    是否新建、是否 dirty）；
  - **API Key 绝不写入 Bundle**；编辑已有 profile 时从 store 重新读取，新建 API 草稿则提示重新输入。
- 进程被回收：丢弃草稿，重新载入已保存配置并提示「未保存的修改已丢弃」。
- 不用 `configChanges` 吞掉配置变化。

---

## 6. 校验规则与错误文案

| 字段 | 规则 | 错误文案 |
| --- | --- | --- |
| 名称 | 可空；trim 后最长 40 字符 | 「名称过长（最多 40 字）」 |
| 首页 URL | 非空；`https://`；host 非空 | 「网页源必须填写首页地址」/「首页地址必须使用 HTTPS」/「首页地址格式不正确」 |
| 接口地址 | 非空；`https://`；host 非空 | 「接口地址不能为空」/「接口地址必须使用 HTTPS」/「接口地址格式不正确」 |
| 模型 | 非空 | 「模型名称不能为空」 |
| API Key | 非空 | 「API 源必须填写 API Key」 |
| 自定义网页源 | 仅提示兼容范围，不阻断保存 | 「自定义源只保证与内置聊天页结构相同的站点」 |

- 错误显示在字段下方，使用 `TextInputLayout.error`，不只 Toast。
- 输入变化时清除该字段错误；重新保存时重新校验全部字段。
- 网页源不参与 API 字段校验；API 源不参与首页 URL 校验。
- `http_api` 的 `transport` 由源类型决定，不信任草稿里的旧值。

---

## 7. 与 WebView 宿主的关系

- 全进程只有一个 `WebViewHost` / 一个 WebView；本页**不创建**自己的 WebView。
- 健康检查通过 `AiSourceRegistry.create(settings, host = WebViewHost.get(applicationContext), context)`。
- 冷启动时 `WebViewAiSource.healthCheck()` 会自动完成离屏挂接与首页导航（AI-184），
  因此本页首次打开即可得到真实状态，不需要用户先去主界面点「登录」。
- 只检查激活配置的原因：非激活源会把共享页面导航到另一个站点，造成「页面是 A、激活源是 B」。
- 切换激活源后不立即销毁页面；下一次健康检查/识牌会按新源导航。
- 会话隔离是已知限制（G-15）：同一 WebView 与 Cookie，多个网页源不能同时保持独立登录页；
  页面需在「当前使用」卡片下方明确写出这一点，避免用户误以为切换源等于隔离会话。

### 7.1 并发与重复挂接

`MainActivity` 与本页都可能触发健康检查。冷启动时若用户在预热完成前打开本页，
两个 `healthCheck()` 可能同时走 `ensureLoaded()`。实施时二选一：

1. **推荐：** 增加进程级 `AiHealthCheckCoordinator`，同一时刻只允许一个检查，
   后到者等待或直接复用最近结果；
2. 本页在 `host.status() == NotCreated` 且主界面刚启动的 2s 内不自动检查，只显示「检测中」，
   由用户点「重新检查」。

无论哪种，UI 必须用序号丢弃迟到结果，不能在旧 Activity 上回写。

---

## 8. 数据与持久化

- 复用 `AiSettingsStore`：`load()`、`loadAll()`、`save()`、`select()`、`delete()`。
- **不新增 SharedPreferences key，不改 JSON 字段**；`source_key`、`home_url`、`transport` 语义不变。
- `save()` 继续负责 trim；`persist()` 里的 `LegacyKeyCleanup` 保持原样；网页源的 `apiKey` 永远保存为空。
- `delete()` 之前由编辑器保证「至少保留一份」；不依赖 `AiSettingsStore` 的 defaults 兜底。
- 未知 `source_key` 仍按 `AiSourceId.fromKey` 回落 HTTP API；本页显示回落后的源，并在日志中告警，
  不新增第二套迁移逻辑。
- API Key：只走现有 Keystore 加密；页面不显示明文长度、不打印、不写 Bundle、不写截图。

---

## 9. 文件与接口变更

### 9.1 新增

| 文件 | 职责 |
| --- | --- |
| `app/src/main/res/layout/activity_ai_settings.xml` | 页面骨架：Toolbar、当前使用卡、源列表容器、配置档、表单、底部操作栏 |
| `app/src/main/res/layout/item_ai_source_option.xml` | 单个源选项：RadioButton + 标题 + 说明 + 当前标记 |
| `app/src/main/java/.../ui/AiSettingsActivity.kt` | 渲染（`ActivityAiSettingsBinding`）、确认框、登录跳转、健康检查、保存/删除 |
| `app/src/main/java/.../ui/AiSettingsEditor.kt` | 纯编辑状态：选择、草稿、dirty、删除计划 |
| `app/src/main/java/.../ui/AiSettingsValidation.kt` | 纯字段校验与错误文案 |
| `app/src/test/java/.../ui/AiSettingsEditorTest.kt` | 选择/草稿/dirty/删除回退 |
| `app/src/test/java/.../ui/AiSettingsValidationTest.kt` | 网页/API 校验矩阵、HTTPS、空值、名称默认值 |
| `app/src/main/java/.../recognition/source/HealthCheckGate.kt` | 进程级健康检查串行化，避免主界面与本页并发 `ensureLoaded()` |
| `app/src/test/java/.../recognition/source/HealthCheckGateTest.kt` | 并发检查不重叠 |

### 9.2 修改

| 文件 | 变更 |
| --- | --- |
| `AndroidManifest.xml` | 注册 `.ui.AiSettingsActivity`：`exported=false`、`screenOrientation=portrait`、`label="AI 源管理"`、`parentActivityName=.MainActivity` |
| `MainActivity.kt` | `showAiSettings()` 改为 `startActivity(Intent(this, AiSettingsActivity::class.java))`；移除 `AiSettingsScreen` import；`recognizeOnline()` 缺 Key 分支同样跳转 |
| `doc/ui_design.md` §6 | 增加「实施细节见 `doc/ai_configure.md`」指针，旧布局草图保留为方向 |
| `doc/tasks.md` | 文档索引加入本文件 |

### 9.3 删除（已完成）

| 文件 | 原因 |
| --- | --- |
| `ui/AiSettingsScreen.kt` | 弹窗实现被独立页面取代 |
| `ui/AiSettingsDialog.kt` | 旧版弹窗，已无引用（保留只会制造第二套配置逻辑） |

删除前用 `grep -R "AiSettingsScreen\\|AiSettingsDialog" app/src` 确认零引用；
不保留「弹窗回退开关」，避免两套入口长期并存。

---

## 10. 可访问性与适配

- 源选项使用 `RadioGroup` 语义；每行 `contentDescription` 包含源名称、是否当前选中、是否当前使用。
- 状态不能只靠颜色：全部带文字（「网页可用」「需要登录」「页面暂不可用」）。
- 错误通过 `TextInputLayout.error` 与字段关联，TalkBack 可读到。
- 所有按钮/输入框 ≥ 48dp；底部操作栏在 1.3× 字体下不重叠。
- 窄屏 360dp 单列；≥ 600dp 可把「源列表」和「配置表单」做双栏，但 v1 先单列，
  双栏作为后续增强，不引入第二套交互。
- 深色模式沿用现有 `DayNight` 主题；新增颜色先进 `colors.xml`，页面不写死色值。

---

## 11. 测试与验收

### 11.1 JVM 单测

**`AiSettingsValidationTest`**

- 网页源：空 URL、http URL、合法 https URL；API 字段不参与。
- API 源：空 endpoint / http endpoint / 空 model / 空 Key；首页 URL 不参与。
- 合法输入返回空错误；名称留空时保存值回落为源 label。
- 自定义网页源保留兼容性提示，但不阻断保存。

**`AiSettingsEditorTest`**

- 打开页面载入激活 profile，`dirty=false`。
- 切换源：有该源 profile 时载入第一份；没有时生成新草稿，`isNewDraft=true`。
- 切换源/配置档/返回在有修改时返回「需要确认」。
- 修改字段后 `dirty=true`；保存后 `dirty=false`。
- 删除：非激活、激活、最后一份三种计划；激活删除的回退名称正确。
- 新草稿不可删除、不可登录（由 Activity + `isActiveProfile` 共同保证）。

### 11.2 真机验收清单（LIO-N29 / Android 10）

1. 主界面「AI 源」打开全屏页面，无 AlertDialog 表单；返回键回主界面。
2. 五种源全部可见；网页源无 endpoint/model/Key 字段与标签；API 源无首页 URL 字段。
3. 切换源、切换配置档、返回：有修改时均出现确认；取消后编辑内容不丢。
4. 保存并选用后主界面名称/状态立即更新；杀进程重启后选择仍保留。
5. 网页源：打开页面自动显示真实健康态；点「网页登录 / 切换账号」→ 无诊断控件的全屏登录页 → 返回，状态自动重查；
   不出现「检测失败」或把未登录报成页面错误。
6. 非激活配置显示「未选用」，不会触发 WebView 导航；切回激活配置后状态恢复。
7. API 源：Key 缺失/非 HTTPS/模型为空时错误在字段下方；合法配置显示「配置完整」；
   全程不出现 Key 明文日志。
8. 删除当前激活配置：确认框写出回退 profile；删除后主界面与页面激活态一致。
9. 只剩一份配置时删除禁用并说明原因。
10. 字体 1.3×、TalkBack 下字段与底部按钮可读可点；状态有文字不只颜色。
11. 配置变更重建：非敏感草稿保留，API Key 不被 Bundle 持久化；进程死亡后草稿丢弃提示。
12. 冷启动直接进入本页：不因主界面预热与页面检查并发而重复挂接/崩溃。

**真机结果（2026-10-30，LIO-N29）：** 1✅、2✅、3✅（源切换与返回；配置档切换共用同一确认路径）、4✅（临时 profile 保存后主界面刷新，force-stop 重启仍保留）、5✅（自动健康态、登录入口、无诊断登录页、返回重查；未退出账号重登）、6✅、7部分（API Key 缺失错误✅；完整配置态未测）、8✅（含回退名称与回退结果）、9–12 未测。未把部分结果当作全清单通过。

### 11.3 回归

- `./gradlew testDebugUnitTest assembleDebug` 全绿。
- 主界面联网识牌：DeepSeek / Gemini 至少各跑一次 `source_e2e`（或手工单图）确认选源仍生效。
- 网页登录返回后主界面健康态仍能刷新（UI-019）。
- `grep -R "AiSettingsScreen\\|AiSettingsDialog" app/src` 为空。

---

## 12. 实施顺序

| 阶段 | 内容 | 出口条件 |
| --- | --- | --- |
| P0 | `AiSettingsValidation` + `AiSettingsEditor` + 单测 | 纯 JVM 测试覆盖选择/校验/删除计划，不碰 Android |
| P1 | `activity_ai_settings.xml` + `item_ai_source_option.xml` + `AiSettingsActivity` 骨架 + Manifest + 主界面入口 | 能从主界面进入全屏页面，五源显隐正确，能返回 |
| P2 | 配置档 CRUD、dirty 确认、字段校验、保存并选用、删除回退 | 真机清单 1–4、8–9 通过 |
| P3 | 健康检查、登录跳转与返回重查、生命周期草稿策略 | 真机清单 5–7、11–12 通过 |
| P4 | 删除旧弹窗类、更新 `ui_design` / `tasks` / `README`（如涉及入口文案）、真机回归 | 清单全通过，单测与构建全绿 |

**实施记录（2026-10-30）：** P0–P4 已完成。新增 `AiSettingsValidation` / `AiSettingsEditor` / `HealthCheckGate` 及 28 项纯 JVM 单测（编辑器 16、校验 11、串行化 1）；`AiSettingsActivity` 替换弹窗并接入主界面入口；旧弹窗类删除后 `grep -R "AiSettingsScreen\\|AiSettingsDialog" app/src` 无代码引用。`testDebugUnitTest assembleDebug` 373 项测试全通过。真机 LIO-N29 已完成清单 1–6、8 及 7 的错误路径；7 的完整配置态与 9–12 仍待补证。

---

## 13. 风险与取舍

| 风险 | 影响 | 处置 |
| --- | --- | --- |
| 共享 WebView / Cookie | 多个网页源不能同时登录，切换后页面被重新导航 | 页面明示限制；只检查激活配置；不承诺会话隔离 |
| 主界面与本页并发健康检查 | 冷启动重复 `ensureLoaded`、页面互抢 | `AiHealthCheckCoordinator` 或延后自动检查；序号丢弃迟到结果 |
| 非激活源禁止登录 | 用户想先登录再决定是否选用 | 文案说明「保存并选用后可登录」；保存即返回主界面，可立刻点登录 |
| API Key 进入 Bundle | 可能随系统状态落盘 | `onSaveInstanceState` 明确排除 Key；重建后从 store 重新读取 |
| 删除激活配置 | 主界面状态突变 | 确认框写出回退名称；删除后重新载入并刷新当前使用卡 |
| 页面重建丢草稿 | 用户输入丢失 | 非敏感字段恢复；进程死亡提示；竖屏锁定降低触发概率 |
| 表单变长 | 小屏滚动与键盘遮挡 | 固定底部操作栏 + `adjustResize` + 字段分组 |

---

## 14. 已定决策与待确认项

**已定（本设计默认执行）**

1. 主编辑面必须是独立 Activity，不使用 Dialog / BottomSheet。
2. 保存动作沿用「保存并选用」单一主操作，保存后返回主界面。
3. 网页源只在激活配置上做健康检查与登录。
4. 删除最后一份配置禁用，不触发 store 的 defaults 兜底。
5. 不新增依赖、不改存储格式、不保留旧弹窗入口。

**待确认（如无异议按推荐执行）**

1. 是否需要在保存后停留本页并显示「已保存」？
   - 推荐：否，保存即返回；配置管理以「选定并生效」为完成。
2. 编辑已有 API profile 时，Key 输入框是否预填掩码？
   - 推荐：预填掩码（沿用现有行为），支持显示/隐藏；重建后从 store 重读，不写 Bundle。
3. 是否在页面内提供「仅保存不选用」？
   - 推荐：v1 不提供，避免「已保存但未生效」的二义状态；配置档的价值在于快速切换。
