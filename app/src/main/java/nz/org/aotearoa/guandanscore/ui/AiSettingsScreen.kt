package nz.org.aotearoa.guandanscore.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import nz.org.aotearoa.guandanscore.probe.AiProbeActivity
import nz.org.aotearoa.guandanscore.recognition.AiSettings
import nz.org.aotearoa.guandanscore.recognition.AiSettingsStore
import nz.org.aotearoa.guandanscore.recognition.AiTransport
import nz.org.aotearoa.guandanscore.recognition.source.AiSourceId
import nz.org.aotearoa.guandanscore.recognition.source.AiSourcePresets
import nz.org.aotearoa.guandanscore.recognition.source.AiSourceRegistry
import nz.org.aotearoa.guandanscore.recognition.source.SourceHealth
import java.util.UUID

/**
 * AI 源管理（doc/ui_design.md §6，UI-015 / UI-016 / UI-017）。
 *
 * 与旧 `AiSettingsDialog` 的差别：
 * 1. 字段显隐按源类型，网页源绝不出现 API Key 字段（UI-015）。
 * 2. 校验错误显示在字段下方，不只靠 Toast（§6.5）。
 * 3. 删除需要二次确认，并写出将删除的 profile 名称（UI-016）。
 * 4. 健康状态来自真实 healthCheck（UI-017）。
 *
 * 复用 `AiSettingsStore` / `AiSourceId` / `AiSourcePresets`，不复制持久化逻辑。
 */
class AiSettingsScreen(
    private val activity: Activity,
    private val store: AiSettingsStore,
    private val onSaved: (AiSettings) -> Unit = {}
) {

    fun show() {
        val profiles = store.loadAll().toMutableList()
        val current = store.load()
        var editing: AiSettings = current
        var dialog: AlertDialog? = null

        val sourceSpinner = Spinner(activity).apply {
            adapter = ArrayAdapter(
                activity,
                android.R.layout.simple_spinner_dropdown_item,
                AiSourceId.entries.map { it.label }
            )
        }
        val profileSpinner = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, mutableListOf<String>())
        }

        val name = field("显示名称")
        val homeUrl = field("网页地址（https://）")
        val endpoint = field("接口地址（https://）")
        val model = field("模型名称")
        val apiKey = field("API Key（仅本机加密保存）", secret = true)

        val nameError = errorText()
        val homeError = errorText()
        val endpointError = errorText()
        val modelError = errorText()
        val apiKeyError = errorText()

        // UI-015：网页源必须整体隐藏 API 专属字段（含标签），因此提前收集。
        val apiSection = mutableListOf<View>()

        val health = TextView(activity).apply {
            textSize = 13f
            setPadding(0, 12, 0, 4)
            setTextColor(0xff59645E.toInt())
        }
        val healthAction = Button(activity).apply {
            text = "重新检查"
            visibility = View.GONE
            setOnClickListener { checkHealth(editing) { text -> health.text = text } }
        }

        fun refreshProfileList() {
            val source = AiSourceId.entries[sourceSpinner.selectedItemPosition]
            val matching = profiles.filter { AiSourceId.fromKey(it.sourceKey) == source }
            val labels = matching.map { it.provider.ifBlank { source.label } }.toMutableList()
            labels += "＋ 新建${source.label}"
            @Suppress("UNCHECKED_CAST")
            (profileSpinner.adapter as ArrayAdapter<String>).let {
                it.clear()
                it.addAll(labels)
                it.notifyDataSetChanged()
            }
            val idx = matching.indexOfFirst { it.id == editing.id }
            profileSpinner.setSelection(if (idx >= 0) idx else matching.size)
        }

        fun clearErrors() {
            listOf(nameError, homeError, endpointError, modelError, apiKeyError).forEach {
                it.visibility = View.GONE
                it.text = ""
            }
        }

        fun applyVisibility(source: AiSourceId) {
            val web = source.isWebView
            homeUrl.visibility = if (web) View.VISIBLE else View.GONE
            endpoint.visibility = if (web) View.GONE else View.VISIBLE
            model.visibility = if (web) View.GONE else View.VISIBLE
            apiKey.visibility = if (web) View.GONE else View.VISIBLE
            health.visibility = if (web) View.VISIBLE else View.GONE
            healthAction.visibility = if (web) View.VISIBLE else View.GONE
            // UI-015：网页源不得出现 endpoint / model / key 的任何字段或标签。
            apiSection.forEach { it.visibility = if (web) View.GONE else View.VISIBLE }
            if (web) {
                health.text = "状态：检测中…"
                checkHealth(editing) { text -> health.text = text }
            } else {
                health.text = ""
            }
        }

        fun showProfile(p: AiSettings) {
            editing = p
            clearErrors()
            name.setText(p.provider)
            homeUrl.setText(p.homeUrl)
            endpoint.setText(p.endpoint)
            model.setText(p.model)
            apiKey.setText(p.apiKey)
            applyVisibility(AiSourceId.fromKey(p.sourceKey))
        }

        sourceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                val source = AiSourceId.entries[position]
                val existing = profiles.firstOrNull { AiSourceId.fromKey(it.sourceKey) == source }
                editing = existing ?: newProfileFor(source)
                refreshProfileList()
                showProfile(editing)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        profileSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                val source = AiSourceId.entries[sourceSpinner.selectedItemPosition]
                val matching = profiles.filter { AiSourceId.fromKey(it.sourceKey) == source }
                if (position < matching.size) showProfile(matching[position])
                else showProfile(newProfileFor(source).copy(id = UUID.randomUUID().toString()))
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        val openLogin = Button(activity).apply {
            text = "打开网页登录"
            setOnClickListener {
                val target = editing.homeUrl.ifBlank {
                    AiSourcePresets.homeUrlFor(AiSourceId.fromKey(editing.sourceKey)).orEmpty()
                }
                if (target.isBlank()) {
                    homeError.text = "请先填写网页地址"
                    homeError.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                activity.startActivity(
                    Intent(activity, AiProbeActivity::class.java)
                        .putExtra(AiProbeActivity.EXTRA_URL, target)
                        .putExtra(AiProbeActivity.EXTRA_SITE, AiSourceId.fromKey(editing.sourceKey).key)
                )
            }
        }

        fun save() {
            clearErrors()
            val source = AiSourceId.fromKey(editing.sourceKey)
            val settings = editing.copy(
                provider = name.text.toString().ifBlank { source.label },
                homeUrl = homeUrl.text.toString(),
                endpoint = endpoint.text.toString(),
                model = model.text.toString(),
                apiKey = apiKey.text.toString(),
                transport = if (source.isWebView) AiTransport.WEBVIEW else AiTransport.API
            )
            var ok = true
            if (source.isWebView) {
                if (settings.homeUrl.isBlank()) {
                    homeError.text = "网页源必须填写网页地址"
                    homeError.visibility = View.VISIBLE
                    ok = false
                } else if (!settings.homeUrl.startsWith("https://")) {
                    homeError.text = "网页地址必须使用 HTTPS"
                    homeError.visibility = View.VISIBLE
                    ok = false
                }
            } else {
                if (settings.endpoint.isBlank()) {
                    endpointError.text = "接口地址不能为空"
                    endpointError.visibility = View.VISIBLE
                    ok = false
                } else if (!settings.endpoint.startsWith("https://")) {
                    endpointError.text = "接口地址必须使用 HTTPS"
                    endpointError.visibility = View.VISIBLE
                    ok = false
                }
                if (settings.model.isBlank()) {
                    modelError.text = "模型名称不能为空"
                    modelError.visibility = View.VISIBLE
                    ok = false
                }
                if (settings.apiKey.isBlank()) {
                    apiKeyError.text = "API 源必须填写 API Key"
                    apiKeyError.visibility = View.VISIBLE
                    ok = false
                }
            }
            if (!ok) return
            store.save(settings)
            store.select(settings.id)
            toast("已保存并选用 ${settings.provider}")
            onSaved(settings)
            dialog?.dismiss()
        }

        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 8, 40, 0)
            addView(label("AI 源"))
            addView(sourceSpinner)
            addView(label("配置档"))
            addView(profileSpinner)
            addView(label("名称"))
            addView(name)
            addView(nameError)
            addView(label("首页 URL"))
            addView(homeUrl)
            addView(homeError)
            apiSection.addAll(listOf(
                label("接口地址"), endpoint, endpointError,
                label("模型"), model, modelError,
                label("API Key"), apiKey
            ))
            apiSection.forEach { addView(it) }
            addView(apiKeyError)
            addView(openLogin)
            addView(health)
            addView(healthAction)
        }

        val currentSource = AiSourceId.fromKey(current.sourceKey)
        sourceSpinner.setSelection(AiSourceId.entries.indexOf(currentSource))
        editing = current
        refreshProfileList()
        showProfile(current)

        dialog = AlertDialog.Builder(activity)
            .setTitle("AI 源管理")
            .setView(ScrollView(activity).apply { addView(form) })
            .setNegativeButton("取消", null)
            .setNeutralButton("删除配置") { _, _ ->
                val source = AiSourceId.entries[sourceSpinner.selectedItemPosition]
                val matching = profiles.filter { AiSourceId.fromKey(it.sourceKey) == source }
                val pos = profileSpinner.selectedItemPosition
                if (pos >= matching.size) return@setNeutralButton
                val target = matching[pos]
                AlertDialog.Builder(activity)
                    .setTitle("删除配置档")
                    .setMessage("将删除「${target.provider.ifBlank { source.label }}」。删除当前激活配置会回退到该源的首个配置。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("删除") { _, _ ->
                        store.delete(target.id)
                        profiles.removeAll { it.id == target.id }
                        refreshProfileList()
                        toast("已删除 ${target.provider}")
                    }
                    .show()
            }
            .setPositiveButton("保存并选用") { _, _ -> save() }
            .create()
        dialog?.show()
    }

    private fun checkHealth(settings: AiSettings, done: (String) -> Unit) {
        Thread({
            val text = try {
                val source = AiSourceRegistry.create(
                    settings,
                    host = null,
                    context = activity.applicationContext
                )
                when (val h = source.healthCheck()) {
                    SourceHealth.Ready -> "状态：网页可用"
                    is SourceHealth.NotLoggedIn -> "状态：需要登录${if (h.hint.isBlank()) "" else "（${h.hint}）"}"
                    is SourceHealth.Unavailable -> "状态：不可用（${h.reason}）"
                }
            } catch (e: Exception) {
                "状态：检测失败（${e.localizedMessage ?: "未知错误"}）"
            }
            activity.runOnUiThread { done(text) }
        }, "ai-settings-health").start()
    }

    private fun field(hint: String, secret: Boolean = false) = EditText(activity).apply {
        this.hint = hint
        setSingleLine(true)
        if (secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    private fun errorText() = TextView(activity).apply {
        textSize = 12f
        setTextColor(0xffB3261E.toInt())
        visibility = View.GONE
    }

    private fun label(text: String) = TextView(activity).apply {
        this.text = text
        textSize = 12f
        setPadding(0, 10, 0, 2)
        setTextColor(0xff666666.toInt())
    }

    private fun newProfileFor(source: AiSourceId): AiSettings = when (source) {
        AiSourceId.DEEPSEEK_WEBVIEW -> AiSourcePresets.deepseekWebView().copy(id = UUID.randomUUID().toString())
        AiSourceId.GEMINI_WEBVIEW -> AiSourcePresets.geminiWebView().copy(id = UUID.randomUUID().toString())
        AiSourceId.CHATGPT_WEBVIEW -> AiSourcePresets.chatgptWebView().copy(id = UUID.randomUUID().toString())
        AiSourceId.CUSTOM_WEBVIEW -> AiSourcePresets.customWebView().copy(id = UUID.randomUUID().toString())
        AiSourceId.HTTP_API -> AiSourcePresets.httpApiGemini().copy(id = UUID.randomUUID().toString())
    }

    private fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
}
