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

    internal fun parseCards(text: String): List<Card> {
        val start = text.indexOf('{')
        if (start >= 0) {
            val end = text.lastIndexOf('}')
            if (end > start) {
                try {
                    val clean = text.substring(start, end + 1)
                    val array = JSONObject(clean).optJSONArray("cards")
                    if (array != null) {
                        val parsed = parseCardsFromArray(array)
                        if (parsed.isNotEmpty()) return parsed
                    }
                } catch (_: Exception) {
                    // Truncated or slightly invalid JSON, fall back to resilient extractor
                }
            }
        }
        val fallback = parseCardsResilient(text)
        if (fallback.isNotEmpty()) return fallback
        error("AI 返回内容未能解析出有效的手牌数据")
    }

    private fun parseCardsFromArray(array: JSONArray): List<Card> {
        require(array.length() <= 27) { "AI 返回超过27张牌" }
        return (0 until array.length()).mapNotNull { id ->
            val item = array.optJSONObject(id) ?: return@mapNotNull null
            val rawRank = item.optString("rank")
            val rank = Rank.parse(rawRank) ?: parseFallbackRank(rawRank) ?: return@mapNotNull null
            val suit = parseSuit(item.optString("suit")) ?: return@mapNotNull null
            Card(id, rank, if (rank.value > 14) Suit.JOKER else suit.takeUnless { it == Suit.JOKER } ?: Suit.SPADE)
        }
    }

    internal fun parseCardsResilient(text: String): List<Card> {
        val pattern = Regex("""\{[^{}]*?"(?:rank|suit)"[^{}]*?\}""", RegexOption.DOT_MATCHES_ALL)
        val matches = pattern.findAll(text)
        val cards = mutableListOf<Card>()
        for (m in matches) {
            if (cards.size >= 27) break
            try {
                val obj = JSONObject(m.value)
                val rawRank = obj.optString("rank")
                val rank = Rank.parse(rawRank) ?: parseFallbackRank(rawRank) ?: continue
                val suit = parseSuit(obj.optString("suit")) ?: continue
                cards += Card(cards.size, rank, if (rank.value > 14) Suit.JOKER else suit.takeUnless { it == Suit.JOKER } ?: Suit.SPADE)
            } catch (_: Exception) {
                continue
            }
        }
        return cards
    }

    private fun parseSuit(raw: String): Suit? = when (raw.trim().uppercase()) {
        "C", "CLUB", "CLUBS" -> Suit.CLUB
        "D", "DIAMOND", "DIAMONDS" -> Suit.DIAMOND
        "H", "HEART", "HEARTS" -> Suit.HEART
        "S", "SPADE", "SPADES" -> Suit.SPADE
        "JOKER" -> Suit.JOKER
        else -> null
    }

    private fun parseFallbackRank(raw: String): Rank? = when (raw.trim().uppercase()) {
        "SMALL_JOKER", "SMALL JOKER", "BLACK_JOKER", "BLACK JOKER" -> Rank.SMALL_JOKER
        "BIG_JOKER", "BIG JOKER", "RED_JOKER", "RED JOKER" -> Rank.BIG_JOKER
        else -> null
    }

    private fun errorMessage(response: String) = try {
        val json = JSONObject(response)
        json.optJSONObject("error")?.optString("message") ?: json.optString("error").takeIf { it.isNotBlank() } ?: response.take(300)
    } catch (_: Exception) {
        response.take(300)
    }

    companion object {
        const val PROMPT = """识别照片中的掼蛋手牌。第一步定位并统计可见扑克牌；第二步识别每张牌的点数；第三步重点观察点数正下方的小花色图形来识别花色。只返回JSON，不要Markdown：{"count":整数,"cards":[{"rank":"2|3|4|5|6|7|8|9|10|J|Q|K|A|SJ|BJ","suit":"C|D|H|S|JOKER"}]}。不要猜测被完全遮挡的牌，不要重复同一物理位置。最多27张。"""
    }
}
