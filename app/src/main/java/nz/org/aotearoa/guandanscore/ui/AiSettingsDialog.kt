package nz.org.aotearoa.guandanscore.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import nz.org.aotearoa.guandanscore.probe.AiProbeActivity
import nz.org.aotearoa.guandanscore.recognition.AiSettings
import nz.org.aotearoa.guandanscore.recognition.AiSettingsStore
import nz.org.aotearoa.guandanscore.recognition.AiTransport
import nz.org.aotearoa.guandanscore.recognition.source.AiSourceId
import nz.org.aotearoa.guandanscore.recognition.source.AiSourcePresets
import java.util.UUID

/**
 * AI 源配置对话框。
 *
 * 设计（`doc/multi_ai_update.md` §8）：
 * 1. 先选 **AI 源**（DeepSeek / Gemini / 自定义 / HTTP API）。
 * 2. 再按源的属性显隐字段：网页源显示首页 + 登录入口；API 源显示 endpoint/model/key。
 * 3. 保存后立即选用该 profile。
 *
 * 关键修复（AI-141）：网页源**不再要求 API Key**。旧实现在 `MainActivity` 里无条件检查
 * `settings.apiKey.isBlank()`，导致选了网页版仍弹“请先填写 API Key”。
 */
class AiSettingsDialog(
    private val activity: Activity,
    private val store: AiSettingsStore,
    /**
     * AI-033：健康检查必须是异步的。
     *
     * WebView 源的健康检查要读页面诊断，会走 `evaluateJavascript`；若在弹窗回调里
     * 同步阻塞主线程等待结果，就是 AI-023 明令禁止的死锁路径。因此契约改为
     * 「先显示检测中，结果通过回调送回」。
     */
    private val healthProvider: (AiSettings, (String) -> Unit) -> Unit = { _, done -> done("") },
    private val onSaved: (AiSettings) -> Unit = {}
) {

    fun show() {
        val profiles = store.loadAll().toMutableList()
        val current = store.load()

        val sourceSpinner = Spinner(activity).apply {
            adapter = ArrayAdapter(
                activity,
                android.R.layout.simple_spinner_dropdown_item,
                AiSourceId.entries.map { it.label }
            )
        }
        val profileSpinner = Spinner(activity).apply {
            adapter = ArrayAdapter(
                activity,
                android.R.layout.simple_spinner_dropdown_item,
                mutableListOf<String>()
            )
        }

        fun field(hint: String, secret: Boolean = false) = EditText(activity).apply {
            this.hint = hint
            setSingleLine(true)
            if (secret) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        }

        val name = field("显示名称")
        val homeUrl = field("网页地址（https://）")
        val endpoint = field("HTTPS API 地址")
        val model = field("模型")
        val apiKey = field("API Key（仅本机加密保存）", secret = true)
        val health = TextView(activity).apply {
            textSize = 12f
            setPadding(12, 12, 12, 4)
            setTextColor(0xff444444.toInt())
        }

        var editing: AiSettings = current

        fun refreshProfileList() {
            val source = AiSourceId.entries[sourceSpinner.selectedItemPosition]
            val matching = profiles.filter { AiSourceId.fromKey(it.sourceKey) == source }
            val labels = matching.map { it.provider.ifBlank { source.label } }.toMutableList()
            labels += "＋ 新增${source.label}"
            @Suppress("UNCHECKED_CAST")
            (profileSpinner.adapter as ArrayAdapter<String>).let {
                it.clear()
                it.addAll(labels)
                it.notifyDataSetChanged()
            }
            val idx = matching.indexOfFirst { it.id == editing.id }
            profileSpinner.setSelection(if (idx >= 0) idx else matching.size)
        }

        fun applyVisibility(source: AiSourceId) {
            val web = source.isWebView
            homeUrl.visibility = if (web) View.VISIBLE else View.GONE
            endpoint.visibility = if (web) View.GONE else View.VISIBLE
            model.visibility = if (web) View.GONE else View.VISIBLE
            apiKey.visibility = if (web) View.GONE else View.VISIBLE
            health.visibility = if (web) View.VISIBLE else View.GONE
            if (web) {
                health.text = "登录状态：检测中…"
                val snapshot = editing
                healthProvider(snapshot) { text ->
                    // 结果回来时用户可能已切到别的 profile，只更新仍然匹配的那次。
                    if (editing === snapshot || editing == snapshot) {
                        health.text = "登录状态：$text"
                    }
                }
            } else {
                health.text = ""
            }
        }

        fun showProfile(p: AiSettings) {
            editing = p
            name.setText(p.provider)
            homeUrl.setText(p.homeUrl)
            endpoint.setText(p.endpoint)
            model.setText(p.model)
            apiKey.setText(p.apiKey)
            applyVisibility(AiSourceId.fromKey(p.sourceKey))
        }

        sourceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                val source = AiSourceId.entries[position]
                val existing = profiles.firstOrNull { AiSourceId.fromKey(it.sourceKey) == source }
                editing = existing ?: newProfileFor(source)
                refreshProfileList()
                showProfile(editing)
            }

            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }

        profileSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                val source = AiSourceId.entries[sourceSpinner.selectedItemPosition]
                val matching = profiles.filter { AiSourceId.fromKey(it.sourceKey) == source }
                if (position < matching.size) showProfile(matching[position])
                else showProfile(newProfileFor(source).copy(id = UUID.randomUUID().toString()))
            }

            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }

        val openLogin = android.widget.Button(activity).apply {
            text = "打开网页登录"
            setOnClickListener {
                val target = editing.homeUrl.ifBlank {
                    AiSourcePresets.homeUrlFor(AiSourceId.fromKey(editing.sourceKey)).orEmpty()
                }
                if (target.isBlank()) {
                    toast("请先填写网页地址")
                    return@setOnClickListener
                }
                activity.startActivity(
                    Intent(activity, AiProbeActivity::class.java)
                        .putExtra(AiProbeActivity.EXTRA_URL, target)
                )
            }
        }

        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 8, 40, 0)
            addView(label("AI 源"))
            addView(sourceSpinner)
            addView(label("已保存的配置"))
            addView(profileSpinner)
            addView(name)
            addView(homeUrl)
            addView(openLogin)
            addView(health)
            addView(endpoint)
            addView(model)
            addView(apiKey)
        }

        // 初始定位到当前 profile
        val currentSource = AiSourceId.fromKey(current.sourceKey)
        sourceSpinner.setSelection(AiSourceId.entries.indexOf(currentSource))
        editing = current
        refreshProfileList()
        showProfile(current)

        AlertDialog.Builder(activity)
            .setTitle("AI 源设置")
            .setView(android.widget.ScrollView(activity).apply { addView(form) })
            .setNegativeButton("取消", null)
            .setNeutralButton("删除所选") { _, _ ->
                val source = AiSourceId.entries[sourceSpinner.selectedItemPosition]
                val matching = profiles.filter { AiSourceId.fromKey(it.sourceKey) == source }
                val pos = profileSpinner.selectedItemPosition
                if (pos < matching.size) {
                    store.delete(matching[pos].id)
                    toast("已删除 ${matching[pos].provider}")
                    profiles.removeAll { it.id == matching[pos].id }
                    refreshProfileList()
                }
            }
            .setPositiveButton("保存并选用") { _, _ -> save(editing, name, homeUrl, endpoint, model, apiKey) }
            .show()
    }

    private fun save(
        base: AiSettings,
        name: EditText,
        homeUrl: EditText,
        endpoint: EditText,
        model: EditText,
        apiKey: EditText
    ) {
        val source = AiSourceId.fromKey(base.sourceKey)
        val settings = base.copy(
            provider = name.text.toString().ifBlank { source.label },
            homeUrl = homeUrl.text.toString(),
            endpoint = endpoint.text.toString(),
            model = model.text.toString(),
            apiKey = apiKey.text.toString(),
            transport = if (source.isWebView) AiTransport.WEBVIEW else AiTransport.API
        )
        if (source.isWebView) {
            if (settings.homeUrl.isBlank()) return toast("网页源必须填写网页地址")
            if (!settings.homeUrl.startsWith("https://")) return toast("网页地址必须使用 HTTPS")
        } else {
            if (settings.endpoint.isBlank() || settings.model.isBlank()) {
                return toast("接口地址与模型不能为空")
            }
            if (!settings.endpoint.startsWith("https://")) return toast("接口地址必须使用 HTTPS")
            if (settings.apiKey.isBlank()) return toast("API 源必须填写 API Key")
        }
        store.save(settings)
        store.select(settings.id)
        toast("已保存并选用 ${settings.provider}")
        onSaved(settings)
    }

    private fun newProfileFor(source: AiSourceId): AiSettings = when (source) {
        AiSourceId.DEEPSEEK_WEBVIEW -> AiSourcePresets.deepseekWebView().copy(id = UUID.randomUUID().toString())
        AiSourceId.GEMINI_WEBVIEW -> AiSourcePresets.geminiWebView().copy(id = UUID.randomUUID().toString())
        AiSourceId.CUSTOM_WEBVIEW -> AiSourcePresets.customWebView().copy(id = UUID.randomUUID().toString())
        AiSourceId.HTTP_API -> AiSourcePresets.httpApiGemini().copy(id = UUID.randomUUID().toString())
    }

    private fun label(text: String) = TextView(activity).apply {
        this.text = text
        textSize = 12f
        setPadding(12, 10, 12, 2)
        setTextColor(0xff666666.toInt())
    }

    private fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
}
