package nz.org.aotearoa.guandanscore.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.textfield.TextInputLayout
import nz.org.aotearoa.guandanscore.R
import nz.org.aotearoa.guandanscore.databinding.ActivityAiSettingsBinding
import nz.org.aotearoa.guandanscore.databinding.ItemAiSourceOptionBinding
import nz.org.aotearoa.guandanscore.probe.AiProbeActivity
import nz.org.aotearoa.guandanscore.recognition.AiSettings
import nz.org.aotearoa.guandanscore.recognition.AiSettingsStore
import nz.org.aotearoa.guandanscore.recognition.AiTransport
import nz.org.aotearoa.guandanscore.recognition.source.AiSourceId
import nz.org.aotearoa.guandanscore.recognition.source.AiSourcePresets
import nz.org.aotearoa.guandanscore.recognition.source.AiSourceRegistry
import nz.org.aotearoa.guandanscore.recognition.source.HealthCheckGate
import nz.org.aotearoa.guandanscore.recognition.source.HttpApiSource
import nz.org.aotearoa.guandanscore.recognition.source.SourceHealth
import nz.org.aotearoa.guandanscore.recognition.webview.WebViewHost

/**
 * AI 源管理独立页面（doc/ai_configure.md）。
 *
 * 与旧弹窗实现的差别：
 * - 全屏页面，配置表单不再塞进 AlertDialog；
 * - 源 / 配置档切换与未保存修改解耦，切换前必须确认；
 * - 校验错误固定在字段下方，保存失败不关闭页面；
 * - 网页健康检查只针对**当前激活配置**，避免共享 WebView 被非激活配置带偏。
 *
 * 持久化仍由 [AiSettingsStore] 负责；健康检查仍走 [AiSourceRegistry] 与共享 [WebViewHost]。
 */
class AiSettingsActivity : AppCompatActivity() {

    private lateinit var ui: ActivityAiSettingsBinding
    private lateinit var store: AiSettingsStore
    private lateinit var editor: AiSettingsEditor

    private data class SourceRow(val source: AiSourceId, val binding: ItemAiSourceOptionBinding)

    private val sourceRows = mutableListOf<SourceRow>()

    /** 字段回填时抑制 TextWatcher，避免把载入配置误判成用户修改。 */
    private var suppressFieldCallbacks = false

    /** 健康检查序号：切换配置或迟到回调时丢弃旧结果。 */
    private var healthSeq = 0
    private var healthChecking = false
    private var health: SourceHealth? = null

    /** 从登录页返回后重查一次，不在每次 onResume 都重跑页面检查。 */
    private var pendingLoginCheck = false

    /** 同步写入很快，但仍需挡住快速连点造成的重复提交。 */
    private var saving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityAiSettingsBinding.inflate(layoutInflater)
        setContentView(ui.root)

        store = AiSettingsStore(this)
        editor = AiSettingsEditor(store.loadAll(), store.load())
        restoreDraftIfAny(savedInstanceState)

        buildSourceRows()
        bindFields()
        bindActions()
        renderAll()

        // AI-184：等视图真正 attached 后再检查；离屏锚点由主界面注册，
        // 直接检查可能在窗口挂接前被误判成宿主故障。
        ui.root.post { checkHealthIfApplicable() }
    }

    override fun onResume() {
        super.onResume()
        if (pendingLoginCheck) {
            pendingLoginCheck = false
            ui.root.post { checkHealthIfApplicable() }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 只在需要保留草稿时写入。API Key 绝不进 Bundle；重建时已有配置从 store 重读。
        if (!editor.hasUnsavedChanges && editor.editing.id == editor.activeId) return
        outState.putBoolean(STATE_PRESENT, true)
        outState.putString(STATE_ID, editor.editing.id)
        outState.putString(STATE_SOURCE, editor.source.key)
        outState.putString(STATE_NAME, editor.editing.provider)
        outState.putString(STATE_HOME_URL, editor.editing.homeUrl)
        outState.putString(STATE_ENDPOINT, editor.editing.endpoint)
        outState.putString(STATE_MODEL, editor.editing.model)
        outState.putBoolean(STATE_NEW, editor.isNewDraft)
        outState.putBoolean(STATE_DIRTY, editor.dirty)
    }

    private fun restoreDraftIfAny(saved: Bundle?) {
        if (saved?.getBoolean(STATE_PRESENT) != true) return
        val id = saved.getString(STATE_ID).orEmpty()
        val source = AiSourceId.fromKey(saved.getString(STATE_SOURCE))
        val isNew = saved.getBoolean(STATE_NEW)
        val existing = editor.profiles.firstOrNull { it.id == id }
        if (!isNew && existing == null) return

        val draft = AiSettings(
            id = id.ifBlank { java.util.UUID.randomUUID().toString() },
            sourceKey = source.key,
            provider = saved.getString(STATE_NAME).orEmpty(),
            endpoint = if (source.isWebView) "" else saved.getString(STATE_ENDPOINT).orEmpty(),
            model = if (source.isWebView) "" else saved.getString(STATE_MODEL).orEmpty(),
            // 非敏感字段从 Bundle 恢复；Key 只能从已保存配置重新读取，绝不落 Bundle。
            apiKey = existing?.apiKey.orEmpty(),
            homeUrl = if (source.isWebView) saved.getString(STATE_HOME_URL).orEmpty() else "",
            transport = if (source.isWebView) AiTransport.WEBVIEW else AiTransport.API
        )
        editor.restoreDraft(draft, isNew, saved.getBoolean(STATE_DIRTY))
        if (isNew && !source.isWebView) {
            toast("页面已重建，请重新输入 API Key")
        }
    }

    // ------------------------------------------------------------ 初始化

    private fun buildSourceRows() {
        AiSourceId.entries.forEach { source ->
            val binding = ItemAiSourceOptionBinding.inflate(layoutInflater, ui.sourceList, false)
            binding.title.text = source.label
            binding.description.text = descriptionFor(source)
            binding.radio.isClickable = false
            binding.radio.isFocusable = false
            binding.root.setOnClickListener { chooseSource(source) }
            ui.sourceList.addView(binding.root)
            sourceRows += SourceRow(source, binding)
        }
    }

    private fun bindFields() {
        ui.nameEdit.doAfterTextChanged { onFieldChanged(AiField.NAME, it) }
        ui.homeUrlEdit.doAfterTextChanged { onFieldChanged(AiField.HOME_URL, it) }
        ui.endpointEdit.doAfterTextChanged { onFieldChanged(AiField.ENDPOINT, it) }
        ui.modelEdit.doAfterTextChanged { onFieldChanged(AiField.MODEL, it) }
        ui.apiKeyEdit.doAfterTextChanged { onFieldChanged(AiField.API_KEY, it) }
    }

    private fun bindActions() {
        ui.back.setOnClickListener { attemptExit() }
        ui.cancel.setOnClickListener { attemptExit() }
        ui.save.setOnClickListener { save() }
        ui.newProfile.setOnClickListener {
            when (val outcome = editor.newProfile()) {
                EditOutcome.Applied -> refreshAfterEditorChange()
                EditOutcome.NeedsConfirmation -> confirmDiscard {
                    if (editor.newProfile(force = true) is EditOutcome.Applied) refreshAfterEditorChange()
                }
                is EditOutcome.Failed -> toast(outcome.reason)
            }
        }
        ui.deleteProfile.setOnClickListener { deleteSelected() }
        ui.openLogin.setOnClickListener { openLogin() }
        ui.recheck.setOnClickListener { checkHealthIfApplicable() }
        ui.profileDropdown.setOnItemClickListener { _, _, position, _ ->
            val profile = editor.profilesForSource().getOrNull(position) ?: return@setOnItemClickListener
            chooseProfile(profile)
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = attemptExit()
        })
    }

    // ------------------------------------------------------------ 选择

    private fun chooseSource(target: AiSourceId) {
        if (target == editor.source) return
        when (val outcome = editor.selectSource(target)) {
            EditOutcome.Applied -> refreshAfterEditorChange()
            EditOutcome.NeedsConfirmation -> confirmDiscard {
                if (editor.selectSource(target, force = true) is EditOutcome.Applied) refreshAfterEditorChange()
            }
            is EditOutcome.Failed -> toast(outcome.reason)
        }
    }

    private fun chooseProfile(profile: AiSettings) {
        when (val outcome = editor.selectProfile(profile.id)) {
            EditOutcome.Applied -> refreshAfterEditorChange()
            EditOutcome.NeedsConfirmation -> confirmDiscard {
                if (editor.selectProfile(profile.id, force = true) is EditOutcome.Applied) {
                    refreshAfterEditorChange()
                }
            }
            is EditOutcome.Failed -> {
                toast(outcome.reason)
                renderAll()
            }
        }
    }

    private fun refreshAfterEditorChange() {
        // 让正在跑的旧检查结果失效；否则切换配置后可能把上一个页面的状态写回来。
        healthSeq++
        healthChecking = false
        health = null
        renderAll()
        checkHealthIfApplicable()
    }

    // ------------------------------------------------------------ 保存 / 删除

    private fun save() {
        if (saving) return
        val errors = editor.validate()
        renderErrors(errors)
        if (!errors.isEmpty) {
            focusFirstError(errors)
            return
        }
        saving = true
        ui.save.isEnabled = false
        try {
            val settings = editor.buildForSave()
            store.save(settings)
            store.select(settings.id)
            toast("已保存并选用 ${labelOf(settings)}")
            finish()
        } finally {
            saving = false
            ui.save.isEnabled = true
        }
    }

    private fun deleteSelected() {
        val plan = editor.deletePlan()
        if (!plan.canDelete) {
            toast(plan.reason ?: "当前配置档不能删除")
            return
        }
        val target = editor.editing
        val keepActive = editor.activeId
        val wasActive = target.id == keepActive
        val fallback = plan.fallback
        val message = buildString {
            append("将删除「")
            append(labelOf(target))
            append("」。")
            if (wasActive && fallback != null) {
                append("当前使用将回退到「")
                append(labelOf(fallback))
                append("」。")
            }
        }
        AlertDialog.Builder(this)
            .setTitle("删除配置档")
            .setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                store.delete(target.id)
                // store.delete 会把激活项改成剩余第一份；删除非激活配置时必须恢复原激活项，
                // 删除激活配置时按页面计算出的同源回退执行，而不是依赖列表顺序。
                if (wasActive) {
                    fallback?.let { store.select(it.id) }
                } else {
                    store.select(keepActive)
                }
                editor.reload(store.loadAll(), store.load())
                healthSeq++
                healthChecking = false
                health = null
                renderAll()
                checkHealthIfApplicable()
                toast("已删除 ${labelOf(target)}")
            }
            .show()
    }

    // ------------------------------------------------------------ 退出

    private fun attemptExit() {
        if (!editor.hasUnsavedChanges) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("放弃未保存的修改？")
            .setMessage("返回后将丢失当前编辑的内容。")
            .setNegativeButton("继续编辑", null)
            .setPositiveButton("放弃") { _, _ -> finish() }
            .show()
    }

    private fun confirmDiscard(onDiscard: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("放弃未保存的修改？")
            .setMessage("切换后将丢失当前未保存的内容。")
            .setNegativeButton("继续编辑") { _, _ -> renderAll() }
            .setPositiveButton("放弃") { _, _ -> onDiscard() }
            .show()
    }

    // ------------------------------------------------------------ 登录 / 健康

    private fun openLogin() {
        if (!editor.isEditingActive()) {
            toast("保存并选用后可打开网页登录")
            return
        }
        val target = editor.editing.homeUrl.ifBlank {
            AiSourcePresets.homeUrlFor(editor.source).orEmpty()
        }
        if (target.isBlank()) {
            ui.homeUrlLayout.error = "请先填写首页地址"
            return
        }
        pendingLoginCheck = true
        startActivity(
            Intent(this, AiProbeActivity::class.java)
                .putExtra(AiProbeActivity.EXTRA_URL, target)
                .putExtra(AiProbeActivity.EXTRA_SITE, editor.source.key)
                // 正式登录页隐藏诊断按钮与日志；adb 直启探针仍保持原有调试界面。
                .putExtra(AiProbeActivity.EXTRA_LOGIN, true)
        )
    }

    private fun checkHealthIfApplicable() {
        val active = editor.activeProfile() ?: return
        if (!editor.isEditingActive()) {
            renderHealth()
            return
        }
        if (!active.source.isWebView) {
            renderHealth()
            return
        }

        val seq = ++healthSeq
        healthChecking = true
        health = null
        renderHealth()
        Thread({
            val result = try {
                // 主界面也可能在冷启动检查；串行化避免两边同时销毁/重挂共享 WebView。
                HealthCheckGate.run {
                    AiSourceRegistry.create(
                        active,
                        host = WebViewHost.get(applicationContext),
                        context = applicationContext
                    ).healthCheck()
                }
            } catch (e: Exception) {
                SourceHealth.Unavailable(e.localizedMessage ?: "检测失败")
            }
            runOnUiThread {
                if (isFinishing || isDestroyed || seq != healthSeq) return@runOnUiThread
                healthChecking = false
                health = result
                renderHealth()
            }
        }, "ai-settings-health").start()
    }

    // ------------------------------------------------------------ 渲染

    private fun renderAll() {
        suppressFieldCallbacks = true
        ui.nameEdit.setText(editor.editing.provider)
        ui.homeUrlEdit.setText(editor.editing.homeUrl)
        ui.endpointEdit.setText(editor.editing.endpoint)
        ui.modelEdit.setText(editor.editing.model)
        ui.apiKeyEdit.setText(editor.editing.apiKey)
        suppressFieldCallbacks = false

        renderActive()
        renderSources()
        renderProfile()
        renderFields()
        renderErrors(editor.errors)
        renderFormState()
        renderHealth()
    }

    private fun renderActive() {
        val active = editor.activeProfile()
        if (active == null) {
            ui.activeSourceName.text = "未配置"
            ui.activeSourceDetail.text = ""
            return
        }
        val name = labelOf(active)
        ui.activeSourceName.text = if (name == active.source.label) name else "${active.source.label} · $name"
        ui.activeSourceDetail.text = if (active.source.isWebView) {
            "类型：网页登录"
        } else {
            "类型：HTTP API"
        }
    }

    private fun renderSources() {
        val activeSource = editor.activeSource()
        sourceRows.forEach { row ->
            row.binding.radio.isChecked = row.source == editor.source
            row.binding.activeBadge.visibility =
                if (row.source == activeSource) View.VISIBLE else View.GONE
            row.binding.root.contentDescription = buildString {
                append(row.source.label)
                append("，")
                append(descriptionFor(row.source))
                if (row.source == editor.source) append("，正在编辑")
                if (row.source == activeSource) append("，当前使用")
            }
        }
    }

    private fun renderProfile() {
        val labels = editor.profilesForSource().map(::labelOf)
        ui.profileDropdown.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        )
        ui.profileDropdown.setText(
            if (editor.isNewDraft) "新建配置（未保存）" else labelOf(editor.editing),
            false
        )

        val plan = editor.deletePlan()
        ui.deleteProfile.isEnabled = plan.canDelete
        ui.deleteProfile.alpha = if (plan.canDelete) 1f else 0.4f
        ui.profileHint.text = when {
            editor.isNewDraft -> "新建配置尚未保存"
            !plan.canDelete -> plan.reason.orEmpty()
            editor.isEditingActive() -> "正在使用此配置"
            editor.source.isWebView ->
                "未选用（当前使用「${editor.activeProfile()?.let(::labelOf) ?: "—"}」）；保存并选用后可打开网页登录"
            else -> "未选用（当前使用「${editor.activeProfile()?.let(::labelOf) ?: "—"}」）"
        }
    }

    private fun renderFields() {
        val web = editor.source.isWebView
        ui.homeUrlLayout.visibility = if (web) View.VISIBLE else View.GONE
        ui.webHint.visibility = if (web) View.VISIBLE else View.GONE
        ui.customWarning.visibility =
            if (editor.source == AiSourceId.CUSTOM_WEBVIEW) View.VISIBLE else View.GONE
        ui.apiSection.visibility = if (web) View.GONE else View.VISIBLE
        ui.healthActions.visibility = if (web) View.VISIBLE else View.GONE
    }

    private fun renderErrors(errors: AiFieldErrors) {
        ui.nameLayout.error = errors.message(AiField.NAME)
        ui.homeUrlLayout.error = errors.message(AiField.HOME_URL)
        ui.endpointLayout.error = errors.message(AiField.ENDPOINT)
        ui.modelLayout.error = errors.message(AiField.MODEL)
        ui.apiKeyLayout.error = errors.message(AiField.API_KEY)
    }

    private fun focusFirstError(errors: AiFieldErrors) {
        val field = AiField.entries.firstOrNull { errors.message(it) != null } ?: return
        val (layout, input) = when (field) {
            AiField.NAME -> ui.nameLayout to ui.nameEdit
            AiField.HOME_URL -> ui.homeUrlLayout to ui.homeUrlEdit
            AiField.ENDPOINT -> ui.endpointLayout to ui.endpointEdit
            AiField.MODEL -> ui.modelLayout to ui.modelEdit
            AiField.API_KEY -> ui.apiKeyLayout to ui.apiKeyEdit
        }
        input.requestFocus()
        ui.scroll.post {
            // API 字段嵌在 apiSection 里，layout.top 只相对直接父容器；
            // 逐层累加才能得到 ScrollView 内容坐标，否则报错字段可能滚不到可见区。
            var offset = 0
            var current: View? = layout
            while (current != null && current !== ui.scroll) {
                offset += current.top
                current = current.parent as? View
            }
            ui.scroll.smoothScrollTo(0, offset)
        }
    }

    private fun renderFormState() {
        ui.formState.visibility = if (editor.hasUnsavedChanges) View.VISIBLE else View.GONE
        ui.formState.text = if (editor.isNewDraft) "新建配置尚未保存" else "有未保存的修改"
    }

    private fun renderHealth() {
        val web = editor.source.isWebView
        ui.healthActions.visibility = if (web) View.VISIBLE else View.GONE

        if (!web) {
            renderApiHealth()
            return
        }
        when {
            editor.isNewDraft -> setHealth(
                "状态：未保存，保存并选用后可检查",
                R.color.text_secondary,
                canLogin = false,
                canRecheck = false
            )
            !editor.isEditingActive() -> setHealth(
                "状态：未选用（当前使用「${editor.activeProfile()?.let(::labelOf) ?: "—"}」）",
                R.color.text_secondary,
                canLogin = false,
                canRecheck = false
            )
            healthChecking -> setHealth(
                "状态：检测中…",
                R.color.text_secondary,
                canLogin = false,
                canRecheck = false
            )
            health == null -> setHealth(
                "状态：未知",
                R.color.text_secondary,
                canLogin = true,
                canRecheck = true
            )
            health is SourceHealth.Ready -> setHealth(
                "状态：网页可用",
                R.color.success,
                // Ready 只说明页面可用，不代表当前账号不会再被要求登录；
                // 全新安装或切换账号时必须仍能进入网页登录。
                canLogin = true,
                canRecheck = true
            )
            health is SourceHealth.NotLoggedIn -> setHealth(
                "状态：需要登录${hintSuffix((health as SourceHealth.NotLoggedIn).hint)}",
                R.color.gold,
                canLogin = true,
                canRecheck = true
            )
            health is SourceHealth.Unavailable -> setHealth(
                "状态：页面暂不可用（${(health as SourceHealth.Unavailable).reason}）",
                R.color.error,
                canLogin = true,
                canRecheck = true
            )
        }
    }

    private fun renderApiHealth() {
        when {
            editor.isNewDraft -> setHealth(
                "状态：未保存，保存并选用后可检查配置",
                R.color.text_secondary,
                canLogin = false,
                canRecheck = false
            )
            !editor.isEditingActive() -> setHealth(
                "状态：未选用（当前使用「${editor.activeProfile()?.let(::labelOf) ?: "—"}」）",
                R.color.text_secondary,
                canLogin = false,
                canRecheck = false
            )
            else -> {
                // 展示当前草稿的配置完整性：字段刚改完就能看到「缺少 API Key」等变化；
                // 这里只做本地配置校验，不发起网络请求。
                when (val result = HttpApiSource(editor.editing).healthCheck()) {
                    SourceHealth.Ready -> setHealth(
                        "状态：配置完整（本页不验证网络连通性）",
                        R.color.success,
                        canLogin = false,
                        canRecheck = false
                    )
                    is SourceHealth.NotLoggedIn -> setHealth(
                        "状态：缺少 API Key${hintSuffix(result.hint)}",
                        R.color.error,
                        canLogin = false,
                        canRecheck = false
                    )
                    is SourceHealth.Unavailable -> setHealth(
                        "状态：配置不完整（${result.reason}）",
                        R.color.error,
                        canLogin = false,
                        canRecheck = false
                    )
                }
            }
        }
    }

    private fun setHealth(
        text: String,
        colorRes: Int,
        canLogin: Boolean,
        canRecheck: Boolean
    ) {
        ui.healthText.text = text
        ui.healthText.setTextColor(getColor(colorRes))
        // 网页登录入口不依赖健康态：全量安装没有 Cookie、或页面被误判为 Ready 时，
        // 用户仍必须能主动登录/切换账号。网页源父容器控制显隐，API 源整组隐藏。
        ui.openLogin.visibility = View.VISIBLE
        ui.openLogin.text =
            if (health is SourceHealth.Ready) "网页登录 / 切换账号" else "打开网页登录"
        ui.openLogin.isEnabled = canLogin
        ui.openLogin.alpha = if (canLogin) 1f else 0.4f
        ui.recheck.isEnabled = canRecheck
        ui.recheck.alpha = if (canRecheck) 1f else 0.4f
    }

    // ------------------------------------------------------------ 小工具

    private fun onFieldChanged(field: AiField, text: CharSequence?) {
        if (suppressFieldCallbacks) return
        editor.update(field, text?.toString().orEmpty())
        layoutFor(field).error = null
        renderFormState()
        renderHealth()
    }

    private fun layoutFor(field: AiField): TextInputLayout = when (field) {
        AiField.NAME -> ui.nameLayout
        AiField.HOME_URL -> ui.homeUrlLayout
        AiField.ENDPOINT -> ui.endpointLayout
        AiField.MODEL -> ui.modelLayout
        AiField.API_KEY -> ui.apiKeyLayout
    }

    private fun labelOf(settings: AiSettings): String =
        settings.provider.ifBlank { settings.source.label }

    private fun descriptionFor(source: AiSourceId): String = when (source) {
        AiSourceId.DEEPSEEK_WEBVIEW -> "网页登录 · 无需 API Key · 首发"
        AiSourceId.GEMINI_WEBVIEW -> "网页登录 · 无需 API Key"
        AiSourceId.CHATGPT_WEBVIEW -> "网页登录 · 无需 API Key"
        AiSourceId.CUSTOM_WEBVIEW -> "仅支持与内置聊天页结构相同的站点"
        AiSourceId.HTTP_API -> "HTTPS 接口 · 需要 API Key"
    }

    private fun hintSuffix(hint: String): String = if (hint.isBlank()) "" else "（$hint）"

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private companion object {
        const val STATE_PRESENT = "ai_settings_draft_present"
        const val STATE_ID = "ai_settings_draft_id"
        const val STATE_SOURCE = "ai_settings_draft_source"
        const val STATE_NAME = "ai_settings_draft_name"
        const val STATE_HOME_URL = "ai_settings_draft_home_url"
        const val STATE_ENDPOINT = "ai_settings_draft_endpoint"
        const val STATE_MODEL = "ai_settings_draft_model"
        const val STATE_NEW = "ai_settings_draft_new"
        const val STATE_DIRTY = "ai_settings_draft_dirty"
    }
}
