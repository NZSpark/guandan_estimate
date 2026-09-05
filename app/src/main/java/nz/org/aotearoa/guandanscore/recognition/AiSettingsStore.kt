package nz.org.aotearoa.guandanscore.recognition

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class AiSettings(
    val provider: String = "Google Gemini",
    val endpoint: String = "https://generativelanguage.googleapis.com/v1beta/models",
    val model: String = "gemini-3.6-flash",
    val apiKey: String = "",
    val id: String = UUID.randomUUID().toString()
)

object AiPresets {
    fun gemini() = AiSettings(
        provider = "Google Gemini",
        endpoint = "https://generativelanguage.googleapis.com/v1beta/models",
        model = "gemini-3.6-flash",
        apiKey = "",
        id = "preset_gemini"
    )

    fun openAi() = AiSettings(
        provider = "OpenAI",
        endpoint = "https://api.openai.com/v1/responses",
        model = "gpt-5.6-sol",
        apiKey = "",
        id = "preset_openai"
    )

    fun defaults(): List<AiSettings> = listOf(gemini(), openAi())
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
                listOf(legacy, AiPresets.gemini()).distinctBy { it.provider }
            } else {
                AiPresets.defaults()
            }
        }
        return try {
            val array = JSONArray(raw)
            val list = (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let(::decodeProfile) }
            list.ifEmpty { AiPresets.defaults() }
        } catch (_: Exception) { AiPresets.defaults() }
    }

    fun save(settings: AiSettings) {
        val profiles = loadAll().toMutableList()
        val index = profiles.indexOfFirst { it.id == settings.id }
        val clean = settings.copy(provider=settings.provider.trim(), endpoint=settings.endpoint.trim(), model=settings.model.trim(), apiKey=settings.apiKey.trim())
        if (index >= 0) profiles[index] = clean else profiles += clean
        persist(profiles, clean.id)
    }

    fun select(id: String) {
        if (loadAll().any { it.id == id }) prefs.edit().putString("active_profile", id).apply()
    }

    fun delete(id: String) {
        val remaining = loadAll().filterNot { it.id == id }.ifEmpty { AiPresets.defaults() }
        persist(remaining, remaining.first().id)
    }

    private fun persist(profiles: List<AiSettings>, activeId: String) {
        val array = JSONArray()
        profiles.forEach { s -> array.put(JSONObject().put("id",s.id).put("provider",s.provider)
            .put("endpoint",s.endpoint).put("model",s.model).put("api_key",encrypt(s.apiKey))) }
        prefs.edit().putString("profiles",array.toString()).putString("active_profile",activeId)
            .remove("provider").remove("endpoint").remove("model").remove("api_key").apply()
    }

    private fun decodeProfile(o: JSONObject) = AiSettings(
        provider=o.optString("provider","Google Gemini"), endpoint=o.optString("endpoint","https://generativelanguage.googleapis.com/v1beta/models"),
        model=o.optString("model","gemini-3.6-flash"), apiKey=decrypt(o.optString("api_key","")),
        id=o.optString("id").ifBlank { UUID.randomUUID().toString() }
    )

    private fun legacyProfile() = AiSettings(
        provider=prefs.getString("provider","Google Gemini") ?: "Google Gemini",
        endpoint=prefs.getString("endpoint","https://generativelanguage.googleapis.com/v1beta/models") ?: "https://generativelanguage.googleapis.com/v1beta/models",
        model=prefs.getString("model","gemini-3.6-flash") ?: "gemini-3.6-flash",
        apiKey=decrypt(prefs.getString("api_key","").orEmpty())
    )

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
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
    } catch (_: Exception) { "" }
}
