package nz.org.aotearoa.guandanscore.recognition

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import nz.org.aotearoa.guandanscore.recognition.source.AiSourceId
import nz.org.aotearoa.guandanscore.recognition.source.AiSourcePresets
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 上游传输方式。WEBVIEW 复用网页登录态，API 使用 endpoint + model + apiKey。 */
enum class AiTransport { WEBVIEW, API }

data class AiSettings(
    val id: String = UUID.randomUUID().toString(),
    /** [AiSourceId.key]；取代靠 provider 字符串嗅探调用路径。 */
    val sourceKey: String = AiSourceId.HTTP_API.key,
    val provider: String = "Google Gemini",
    val endpoint: String = "https://generativelanguage.googleapis.com/v1beta/models",
    val model: String = "gemini-3.6-flash",
    val apiKey: String = "",
    /** WEBVIEW 模式必填（自定义源由用户填写）。 */
    val homeUrl: String = "",
    val transport: AiTransport = AiTransport.API
) {
    /** 网页模式不需要 API Key 与 endpoint。 */
    val requiresApiKey: Boolean get() = transport == AiTransport.API

    val source: AiSourceId get() = AiSourceId.fromKey(sourceKey)

    /** 位置参数兼容：旧的 `AiSettings(provider, endpoint, model, apiKey, id)` 写法。 */
    constructor(
        provider: String,
        endpoint: String,
        model: String,
        apiKey: String,
        id: String
    ) : this(
        id = id,
        sourceKey = AiSourceId.HTTP_API.key,
        provider = provider,
        endpoint = endpoint,
        model = model,
        apiKey = apiKey,
        transport = AiTransport.API
    )
}

object AiPresets {
    fun gemini() = AiSettings(
        id = "preset_gemini",
        sourceKey = AiSourceId.HTTP_API.key,
        provider = "Google Gemini",
        endpoint = "https://generativelanguage.googleapis.com/v1beta/models",
        model = "gemini-3.6-flash",
        apiKey = "",
        transport = AiTransport.API
    )

    fun openAi() = AiSettings(
        id = "preset_openai",
        sourceKey = AiSourceId.HTTP_API.key,
        provider = "OpenAI",
        endpoint = "https://api.openai.com/v1/responses",
        model = "gpt-5.6-sol",
        apiKey = "",
        transport = AiTransport.API
    )

    /** Gemini 网页版（WebView）配置：无 endpoint / model / apiKey 语义。 */
    fun geminiWebView(): AiSettings = AiSourcePresets.geminiWebView()

    /** DeepSeek 网页版（WebView）配置：首发源。 */
    fun deepseekWebView(): AiSettings = AiSourcePresets.deepseekWebView()

    /** 自定义网页源：homeUrl 由用户填写。 */
    fun customWebView(): AiSettings = AiSourcePresets.customWebView()

    /** 默认 profile 列表：DeepSeek 首发在前。 */
    fun defaults(): List<AiSettings> = AiSourcePresets.defaultProfiles()
}

class AiSettingsStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("ai_provider_settings", Context.MODE_PRIVATE)
    private val alias = "guandan_ai_api_key"

    fun load(): AiSettings {
        val profiles = loadAll()
        val selected = prefs.getString("active_profile", null)
        return profiles.firstOrNull { it.id == selected } ?: profiles.first()
    }

    fun loadAll(): List<AiSettings> {
        val raw = prefs.getString("profiles", null)
        if (raw.isNullOrBlank()) {
            val legacy = legacyProfile()
            return if (legacy.apiKey.isNotBlank() || legacy.provider != "Google Gemini") {
                listOf(legacy) + AiPresets.defaults()
            } else {
                AiPresets.defaults()
            }
        }
        return try {
            val array = JSONArray(raw)
            val list = (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.let { decodeProfile(it) }
            }
            list.ifEmpty { AiPresets.defaults() }
        } catch (_: Exception) {
            AiPresets.defaults()
        }
    }

    fun save(settings: AiSettings) {
        val profiles = loadAll().toMutableList()
        val index = profiles.indexOfFirst { it.id == settings.id }
        val clean = settings.copy(
            provider = settings.provider.trim(),
            endpoint = settings.endpoint.trim(),
            model = settings.model.trim(),
            apiKey = settings.apiKey.trim(),
            homeUrl = settings.homeUrl.trim()
        )
        if (index >= 0) profiles[index] = clean else profiles += clean
        persist(profiles, clean.id)
    }

    fun select(id: String) {
        if (loadAll().any { it.id == id }) prefs.edit().putString("active_profile", id).apply()
    }

    /**
     * AI-094：删除不再使用的 Keystore alias。
     *
     * 只在整个配置里已经没有任何 Key 时调用；否则现存密文会永久无法解密。
     * 返回是否真的删了。不记录任何 Key 内容。
     */
    fun dropKeystoreAliasIfUnused(): Boolean {
        if (!LegacyKeyCleanup.canDropKeystoreAlias(loadAll())) return false
        return try {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!store.containsAlias(alias)) return false
            store.deleteEntry(alias)
            Log.i(TAG, "dropped legacy keystore alias (no profile retains a key)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "drop alias failed: ${e.javaClass.simpleName}")
            false
        }
    }

    fun delete(id: String) {
        val remaining = loadAll().filterNot { it.id == id }.ifEmpty { AiPresets.defaults() }
        persist(remaining, remaining.first().id)
    }

    private fun persist(profiles: List<AiSettings>, activeId: String) {
        // AI-094：写入前清掉网页源上残留的旧 Key，避免它被重新加密落盘。
        val sanitized = LegacyKeyCleanup.plan(profiles).profiles
        val array = JSONArray()
        sanitized.forEach { s ->
            array.put(
                JSONObject()
                    .put("id", s.id)
                    .put("source_key", s.sourceKey)
                    .put("provider", s.provider)
                    .put("endpoint", s.endpoint)
                    .put("model", s.model)
                    .put("api_key", encrypt(s.apiKey))
                    .put("home_url", s.homeUrl)
                    .put("transport", s.transport.name)
            )
        }
        prefs.edit()
            .putString("profiles", array.toString())
            .putString("active_profile", activeId)
            .remove("provider").remove("endpoint").remove("model").remove("api_key")
            .apply()
    }

    companion object {
        private const val TAG = "AiSettingsStore"

        /**
         * 解码单个 profile，含旧数据迁移。抽成伴生函数以便单测直接覆盖迁移规则（无需 Context）。
         *
         * 迁移规则（doc/multi_ai_update.md §4.1）：
         * - 有 transport=WEBVIEW 或旧名 GEMINI_WEBVIEW → WEBVIEW + 相应 source_key
         * - 无 transport → API + http_api（与迁移前行为一致）
         * - source_key 缺失时按 transport 兜底推断，**不**用 provider 字符串推断
         */
        fun decodeProfile(o: JSONObject, decrypt: (String) -> String = { "" }): AiSettings {
            val rawTransport = o.optString("transport").takeIf { it.isNotBlank() }
            val transport = when (rawTransport) {
                "WEBVIEW", "GEMINI_WEBVIEW" -> AiTransport.WEBVIEW
                else -> AiTransport.API
            }
            val rawSourceKey = o.optString("source_key").takeIf { it.isNotBlank() }
            val sourceKey = rawSourceKey ?: when {
                rawTransport == "GEMINI_WEBVIEW" -> AiSourceId.GEMINI_WEBVIEW.key
                transport == AiTransport.WEBVIEW -> AiSourceId.CUSTOM_WEBVIEW.key
                else -> AiSourceId.HTTP_API.key
            }
            val source = AiSourceId.fromKey(sourceKey)
            val homeUrl = o.optString("home_url").takeIf { it.isNotBlank() }
                ?: AiSourcePresets.homeUrlFor(source).orEmpty()
            return AiSettings(
                id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                sourceKey = source.key,
                provider = o.optString("provider", "Google Gemini"),
                endpoint = o.optString("endpoint", "https://generativelanguage.googleapis.com/v1beta/models"),
                model = o.optString("model", "gemini-3.6-flash"),
                apiKey = decrypt(o.optString("api_key", "")),
                homeUrl = homeUrl,
                transport = transport
            )
        }
    }

    private fun decodeProfile(o: JSONObject) = decodeProfile(o) { decrypt(it) }

    private fun legacyProfile() = AiSettings(
        provider = prefs.getString("provider", "Google Gemini") ?: "Google Gemini",
        endpoint = prefs.getString("endpoint", "https://generativelanguage.googleapis.com/v1beta/models")
            ?: "https://generativelanguage.googleapis.com/v1beta/models",
        model = prefs.getString("model", "gemini-3.6-flash") ?: "gemini-3.6-flash",
        apiKey = decrypt(prefs.getString("api_key", "").orEmpty())
    )

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }

    private fun encrypt(value: String): String {
        if (value.isEmpty()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String = try {
        if (value.isEmpty()) "" else {
            val bytes = Base64.decode(value, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            }
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)))
        }
    } catch (_: Exception) {
        ""
    }
}
