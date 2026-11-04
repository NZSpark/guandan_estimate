package nz.org.aotearoa.guandanscore.recognition

import android.graphics.Bitmap
import android.util.Base64
import nz.org.aotearoa.guandanscore.model.Card
import nz.org.aotearoa.guandanscore.model.Rank
import nz.org.aotearoa.guandanscore.model.Suit
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

class AiRecognitionClient {
    fun recognize(bitmap: Bitmap, settings: AiSettings): List<Card> {
        require(settings.apiKey.isNotBlank()) { "请先在 AI 设置中填写 API Key" }
        require(settings.endpoint.startsWith("https://")) { "AI 接口必须使用 HTTPS" }
        val image = encode(bitmap)
        val prompt = PROMPT

        val connection: HttpURLConnection
        val body: String

        if (isGemini(settings)) {
            val url = buildGeminiUrl(settings)
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = 30_000; readTimeout = 90_000; doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", settings.apiKey)
            }
            body = buildGeminiBody(prompt, image).toString()
        } else if (isOpenAiChat(settings)) {
            connection = (URL(settings.endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = 30_000; readTimeout = 90_000; doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
            }
            body = buildOpenAiChatBody(prompt, image, settings.model).toString()
        } else {
            // Default: OpenAI Responses API
            connection = (URL(settings.endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = 30_000; readTimeout = 90_000; doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
            }
            body = buildOpenAiResponsesBody(prompt, image, settings.model).toString()
        }

        connection.outputStream.use { it.write(body.toByteArray()) }
        val code = connection.responseCode
        val response = (if (code in 200..299) connection.inputStream else connection.errorStream).bufferedReader().use { it.readText() }
        if (code !in 200..299) throw IllegalStateException("${settings.provider} 请求失败 ($code)：${errorMessage(response)}")
        return parseCards(extractText(JSONObject(response)))
    }

    private fun encode(source: Bitmap): String {
        val max = 1600
        val scale = minOf(1f, max.toFloat() / maxOf(source.width, source.height))
        val bitmap = if (scale < 1f) Bitmap.createScaledBitmap(source, (source.width*scale).toInt(), (source.height*scale).toInt(), true) else source
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        if (bitmap !== source) bitmap.recycle()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    internal fun isGemini(settings: AiSettings): Boolean {
        val p = settings.provider.lowercase()
        val e = settings.endpoint.lowercase()
        val m = settings.model.lowercase()
        return p.contains("gemini") || p.contains("google") ||
               e.contains("generativelanguage.googleapis.com") ||
               m.contains("gemini")
    }

    internal fun isOpenAiChat(settings: AiSettings): Boolean {
        val e = settings.endpoint.lowercase()
        return e.endsWith("/chat/completions") || e.contains("/chat/completions?")
    }

    internal fun buildGeminiUrl(settings: AiSettings): String {
        var base = settings.endpoint.trim().trimEnd('/')
        if (!base.contains(":generateContent")) {
            if (base.endsWith("/models")) {
                base = "$base/${settings.model}:generateContent"
            } else if (base.endsWith("/models/${settings.model}")) {
                base = "$base:generateContent"
            } else if (base.contains("/v1beta") || base.contains("/v1")) {
                base = "$base/models/${settings.model}:generateContent"
            } else {
                base = "$base/v1beta/models/${settings.model}:generateContent"
            }
        }
        return if (base.contains("key=")) base else {
            val delimiter = if (base.contains("?")) "&" else "?"
            "$base${delimiter}key=${settings.apiKey}"
        }
    }

    internal fun buildGeminiBody(prompt: String, base64Image: String): JSONObject {
        val parts = JSONArray()
            .put(JSONObject().put("text", prompt))
            .put(JSONObject().put("inlineData", JSONObject()
                .put("mimeType", "image/jpeg")
                .put("data", base64Image)))
        val content = JSONObject().put("role", "user").put("parts", parts)
        val genConfig = JSONObject()
            .put("responseMimeType", "application/json")
            .put("maxOutputTokens", 8192)
            .put("thinkingConfig", JSONObject().put("thinkingBudget", 1024))
        return JSONObject()
            .put("contents", JSONArray().put(content))
            .put("generationConfig", genConfig)
    }

    internal fun buildOpenAiChatBody(prompt: String, base64Image: String, model: String): JSONObject {
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(JSONObject().put("type", "image_url")
                .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$base64Image")))
        val message = JSONObject().put("role", "user").put("content", content)
        return JSONObject()
            .put("model", model)
            .put("messages", JSONArray().put(message))
            .put("max_tokens", 4096)
    }

    internal fun buildOpenAiResponsesBody(prompt: String, base64Image: String, model: String): JSONObject {
        val content = JSONArray()
            .put(JSONObject().put("type", "input_text").put("text", prompt))
            .put(JSONObject().put("type", "input_image")
                .put("image_url", "data:image/jpeg;base64,$base64Image").put("detail", "high"))
        val input = JSONArray().put(JSONObject().put("role", "user").put("content", content))
        return JSONObject()
            .put("model", model)
            .put("input", input)
            .put("max_output_tokens", 4096)
    }

    internal fun extractText(response: JSONObject): String {
        response.optString("output_text").takeIf { it.isNotBlank() }?.let { return it }

        val candidates = response.optJSONArray("candidates")
        if (candidates != null && candidates.length() > 0) {
            val parts = candidates.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            if (parts != null) {
                val sb = StringBuilder()
                for (i in 0 until parts.length()) {
                    parts.optJSONObject(i)?.optString("text")?.let { sb.append(it) }
                }
                if (sb.isNotBlank()) return sb.toString()
            }
        }

        val output = response.optJSONArray("output")
        if (output != null) {
            for (i in 0 until output.length()) {
                val content = output.optJSONObject(i)?.optJSONArray("content") ?: continue
                for (j in 0 until content.length()) {
                    content.optJSONObject(j)?.optString("text")?.takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        }

        val choices = response.optJSONArray("choices")
        if (choices != null && choices.length() > 0) {
            val text = choices.optJSONObject(0)?.optJSONObject("message")?.optString("content")
            if (!text.isNullOrBlank()) return text
        }

        error("AI 没有返回识别文本")
    }

    // 解析逻辑已抽取到 AiCardResponseParser，此处保留委托以维持既有调用点与测试。
    private val parser = AiCardResponseParser()

    internal fun parseCards(text: String): List<Card> = parser.parse(text)

    internal fun parseCardsResilient(text: String): List<Card> = parser.parseResilient(text)

    private fun errorMessage(response: String) = try {
        val json = JSONObject(response)
        json.optJSONObject("error")?.optString("message") ?: json.optString("error").takeIf { it.isNotBlank() } ?: response.take(300)
    } catch (_: Exception) {
        response.take(300)
    }

    companion object {
        const val PROMPT = AiCardResponseParser.PROMPT
    }
}
