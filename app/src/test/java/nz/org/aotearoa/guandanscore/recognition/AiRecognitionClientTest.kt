package nz.org.aotearoa.guandanscore.recognition

import nz.org.aotearoa.guandanscore.model.Rank
import nz.org.aotearoa.guandanscore.model.Suit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiRecognitionClientTest {
    private val client = AiRecognitionClient()

    @Test
    fun testIsGeminiDetection() {
        val geminiSettings1 = AiSettings(provider = "Google Gemini", endpoint = "https://generativelanguage.googleapis.com/v1beta/models", model = "gemini-2.5-flash")
        assertTrue(client.isGemini(geminiSettings1))

        val geminiSettings2 = AiSettings(provider = "Custom", endpoint = "https://generativelanguage.googleapis.com/v1beta", model = "gemini-3.8-flash")
        assertTrue(client.isGemini(geminiSettings2))

        val openAiSettings = AiSettings(provider = "OpenAI", endpoint = "https://api.openai.com/v1/responses", model = "gpt-5.6-sol")
        assertFalse(client.isGemini(openAiSettings))
    }

    @Test
    fun testBuildGeminiUrl() {
        val settings = AiSettings(
            provider = "Google Gemini",
            endpoint = "https://generativelanguage.googleapis.com/v1beta/models",
            model = "gemini-2.5-flash",
            apiKey = "test_gemini_api_key"
        )
        val url = client.buildGeminiUrl(settings)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=test_gemini_api_key", url)
    }

    @Test
    fun testBuildGeminiBody() {
        val prompt = "识别手牌"
        val imageBase64 = "fakeBase64Image"
        val body = client.buildGeminiBody(prompt, imageBase64)

        val contents = body.getJSONArray("contents")
        assertEquals(1, contents.length())
        val parts = contents.getJSONObject(0).getJSONArray("parts")
        assertEquals(2, parts.length())
        assertEquals(prompt, parts.getJSONObject(0).getString("text"))

        val inlineData = parts.getJSONObject(1).getJSONObject("inlineData")
        assertEquals("image/jpeg", inlineData.getString("mimeType"))
        assertEquals(imageBase64, inlineData.getString("data"))

        val genConfig = body.getJSONObject("generationConfig")
        assertEquals("application/json", genConfig.getString("responseMimeType"))
    }

    @Test
    fun testExtractTextGemini() {
        val geminiJson = JSONObject("""
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "text": "{\"count\":2,\"cards\":[{\"rank\":\"A\",\"suit\":\"S\"},{\"rank\":\"K\",\"suit\":\"H\"}]}"
                      }
                    ]
                  }
                }
              ]
            }
        """.trimIndent())
        val extracted = client.extractText(geminiJson)
        assertTrue(extracted.contains("\"rank\":\"A\""))
    }

    @Test
    fun testExtractTextOpenAiChat() {
        val chatJson = JSONObject("""
            {
              "choices": [
                {
                  "message": {
                    "content": "{\"count\":1,\"cards\":[{\"rank\":\"2\",\"suit\":\"C\"}]}"
                  }
                }
              ]
            }
        """.trimIndent())
        val extracted = client.extractText(chatJson)
        assertTrue(extracted.contains("\"rank\":\"2\""))
    }

    @Test
    fun testParseCards() {
        val sampleJson = """
            ```json
            {
              "count": 3,
              "cards": [
                {"rank": "2", "suit": "C"},
                {"rank": "SJ", "suit": "JOKER"},
                {"rank": "BJ", "suit": "JOKER"}
              ]
            }
            ```
        """.trimIndent()
        val cards = client.parseCards(sampleJson)
        assertEquals(3, cards.size)
        assertEquals(Rank.TWO, cards[0].rank)
        assertEquals(Suit.CLUB, cards[0].suit)
        assertEquals(Rank.SMALL_JOKER, cards[1].rank)
        assertEquals(Suit.JOKER, cards[1].suit)
        assertEquals(Rank.BIG_JOKER, cards[2].rank)
        assertEquals(Suit.JOKER, cards[2].suit)
    }

    @Test
    fun testParseCardsTruncatedJson() {
        val truncatedJson = """{"count":23,"cards":[{"rank":"SJ","suit":"JOKER"},{"rank":"SJ","suit":"JOKER"},{"rank":"A","suit":"H"},{"rank":"2","suit":"C"},{"rank":"K","suit":"S"},{"rank":"Q","suit":"H"},{"rank":"J","suit":"C"},{"rank":"10","suit":"C"}"""
        val cards = client.parseCards(truncatedJson)
        assertEquals(8, cards.size)
        assertEquals(Rank.SMALL_JOKER, cards[0].rank)
        assertEquals(Rank.SMALL_JOKER, cards[1].rank)
        assertEquals(Rank.ACE, cards[2].rank)
        assertEquals(Suit.HEART, cards[2].suit)
        assertEquals(Rank.TEN, cards[7].rank)
        assertEquals(Suit.CLUB, cards[7].suit)
    }
}
