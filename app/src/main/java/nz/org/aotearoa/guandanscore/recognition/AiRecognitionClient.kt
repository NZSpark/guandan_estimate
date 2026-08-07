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
        val prompt = """识别照片中的掼蛋手牌。第一步定位并统计可见扑克牌；第二步识别每张牌的点数；第三步重点观察点数正下方的小花色图形来识别花色。只返回JSON，不要Markdown：{"count":整数,"cards":[{"rank":"2|3|4|5|6|7|8|9|10|J|Q|K|A|SJ|BJ","suit":"C|D|H|S|JOKER"}]}。不要猜测被完全遮挡的牌，不要重复同一物理位置。最多27张。"""
        val content = JSONArray()
            .put(JSONObject().put("type", "input_text").put("text", prompt))
            .put(JSONObject().put("type", "input_image")
                .put("image_url", "data:image/jpeg;base64,$image").put("detail", "high"))
        val input = JSONArray().put(JSONObject().put("role", "user").put("content", content))
        val body = JSONObject().put("model", settings.model).put("input", input)
            .put("max_output_tokens", 1800)
        val connection = (URL(settings.endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 30_000; readTimeout = 90_000; doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
        }
        connection.outputStream.use { it.write(body.toString().toByteArray()) }
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

    private fun extractText(response: JSONObject): String {
        response.optString("output_text").takeIf { it.isNotBlank() }?.let { return it }
        val output = response.optJSONArray("output") ?: error("AI 返回中没有 output")
        for (i in 0 until output.length()) {
            val content = output.optJSONObject(i)?.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) content.optJSONObject(j)?.optString("text")?.takeIf { it.isNotBlank() }?.let { return it }
        }
        error("AI 没有返回识别文本")
    }

    internal fun parseCards(text: String): List<Card> {
        val clean = text.substring(text.indexOf('{').coerceAtLeast(0), text.lastIndexOf('}').takeIf { it >= 0 }?.plus(1) ?: text.length)
        val array = JSONObject(clean).getJSONArray("cards")
        require(array.length() <= 27) { "AI 返回超过27张牌" }
        return (0 until array.length()).mapNotNull { id ->
            val item = array.optJSONObject(id) ?: return@mapNotNull null
            val rank = Rank.parse(item.optString("rank")) ?: return@mapNotNull null
            val suit = when (item.optString("suit").uppercase()) {
                "C", "CLUB" -> Suit.CLUB; "D", "DIAMOND" -> Suit.DIAMOND
                "H", "HEART" -> Suit.HEART; "S", "SPADE" -> Suit.SPADE
                "JOKER" -> Suit.JOKER; else -> return@mapNotNull null
            }
            Card(id, rank, if (rank.value > 14) Suit.JOKER else suit.takeUnless { it == Suit.JOKER } ?: Suit.SPADE)
        }
    }

    private fun errorMessage(response: String) = try { JSONObject(response).optJSONObject("error")?.optString("message") ?: response.take(300) } catch (_: Exception) { response.take(300) }
}
