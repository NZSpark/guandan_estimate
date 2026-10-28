package nz.org.aotearoa.guandanscore.recognition

import nz.org.aotearoa.guandanscore.model.Rank
import nz.org.aotearoa.guandanscore.model.Suit
import org.junit.Assert.*
import org.junit.Test

class AiCardResponseParserTest {
    private val parser = AiCardResponseParser()

    @Test
    fun parsesPlainJson() {
        val cards = parser.parse("""{"count":2,"cards":[{"rank":"A","suit":"S"},{"rank":"10","suit":"H"}]}""")
        assertEquals(2, cards.size)
        assertEquals(Rank.ACE, cards[0].rank)
        assertEquals(Suit.SPADE, cards[0].suit)
        assertEquals(Rank.TEN, cards[1].rank)
        assertEquals(Suit.HEART, cards[1].suit)
    }

    @Test
    fun stripsMarkdownFence() {
        val text = """
            ```json
            {"count":1,"cards":[{"rank":"2","suit":"C"}]}
            ```
        """.trimIndent()
        val cards = parser.parse(text)
        assertEquals(1, cards.size)
        assertEquals(Suit.CLUB, cards[0].suit)
    }

    @Test
    fun ignoresSurroundingProse() {
        val text = "我识别到以下牌：\n{\"count\":1,\"cards\":[{\"rank\":\"K\",\"suit\":\"D\"}]}\n希望有帮助。"
        val cards = parser.parse(text)
        assertEquals(1, cards.size)
        assertEquals(Rank.KING, cards[0].rank)
        assertEquals(Suit.DIAMOND, cards[0].suit)
    }

    @Test
    fun parsesJokers() {
        val cards = parser.parse("""{"count":2,"cards":[{"rank":"SJ","suit":"JOKER"},{"rank":"BJ","suit":"JOKER"}]}""")
        assertEquals(2, cards.size)
        assertEquals(Rank.SMALL_JOKER, cards[0].rank)
        assertEquals(Suit.JOKER, cards[0].suit)
        assertEquals(Rank.BIG_JOKER, cards[1].rank)
        assertEquals(Suit.JOKER, cards[1].suit)
    }

    @Test
    fun acceptsJokerWithoutSuit() {
        val cards = parser.parse("""{"cards":[{"rank":"SJ","suit":""}]}""")
        assertEquals(1, cards.size)
        assertEquals(Suit.JOKER, cards[0].suit)
    }

    @Test
    fun rejectsJokerWithNormalSuit() {
        val outcome = parser.parseDetailed("""{"cards":[{"rank":"BJ","suit":"S"}]}""")
        assertTrue(outcome.cards.isEmpty())
        assertTrue(outcome.warnings.isNotEmpty())
    }

    @Test
    fun rejectsNormalCardWithJokerSuit() {
        val outcome = parser.parseDetailed("""{"cards":[{"rank":"A","suit":"JOKER"}]}""")
        assertTrue(outcome.cards.isEmpty())
    }

    @Test
    fun reportsInvalidRankAndSuit() {
        val outcome = parser.parseDetailed(
            """{"cards":[{"rank":"A","suit":"S"},{"rank":"ZZ","suit":"S"},{"rank":"K","suit":"XX"}]}"""
        )
        assertEquals(1, outcome.cards.size)
        assertEquals(2, outcome.warnings.size)
    }

    @Test
    fun reportsMissingFields() {
        val outcome = parser.parseDetailed("""{"cards":[{"rank":"A"},{"suit":"S"},{"rank":"K","suit":"H"}]}""")
        assertEquals(1, outcome.cards.size)
        assertEquals(Rank.KING, outcome.cards[0].rank)
        assertEquals(2, outcome.warnings.size)
    }

    @Test
    fun emptyCardsArrayIsNotSuccess() {
        val outcome = parser.parseDetailed("""{"count":0,"cards":[]}""")
        assertTrue(outcome.cards.isEmpty())
        assertTrue(outcome.warnings.any { it.contains("未能") })
    }

    @Test
    fun emptyArrayThrowsInStrictParse() {
        try {
            parser.parse("""{"count":0,"cards":[]}""")
            fail("应当抛异常")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("未能解析"))
        }
    }

    @Test
    fun illegalJsonFallsBackToResilient() {
        val text = """{"cards":[{"rank":"SJ","suit":"JOKER"},{"rank":"A","suit":"H"},"""
        val cards = parser.parse(text)
        assertEquals(2, cards.size)
        assertEquals(Rank.SMALL_JOKER, cards[0].rank)
        assertEquals(Rank.ACE, cards[1].rank)
    }

    @Test
    fun truncatedJsonKeepsCompleteEntries() {
        val text = """{"count":23,"cards":[{"rank":"SJ","suit":"JOKER"},{"rank":"A","suit":"H"},{"rank":"2","suit":"C"},{"rank":"K","suit":"S"},{"rank":"Q","suit":"H"},{"rank":"J","suit":"C"},{"rank":"10","suit":"C"}"""
        val cards = parser.parse(text)
        assertEquals(7, cards.size)
    }

    @Test
    fun truncatesOverLimit() {
        val items = (1..30).joinToString(",") { "{\"rank\":\"2\",\"suit\":\"C\"}" }
        val outcome = parser.parseDetailed("{\"cards\":[$items]}")
        assertEquals(AiCardResponseParser.MAX_CARDS, outcome.cards.size)
        assertTrue(outcome.warnings.any { it.contains("超过") })
    }

    @Test
    fun warnsOnCountMismatch() {
        val outcome = parser.parseDetailed("""{"count":5,"cards":[{"rank":"A","suit":"S"}]}""")
        assertEquals(1, outcome.cards.size)
        assertTrue(outcome.warnings.any { it.contains("声明 5 张") })
    }

    @Test
    fun assignsSequentialIds() {
        val cards = parser.parse("""{"cards":[{"rank":"2","suit":"C"},{"rank":"3","suit":"D"},{"rank":"4","suit":"H"}]}""")
        assertEquals(listOf(0, 1, 2), cards.map { it.id })
    }

    @Test
    fun acceptsFullSuitNamesAndSymbols() {
        val cards = parser.parse(
            """{"cards":[{"rank":"A","suit":"Spades"},{"rank":"K","suit":"♥"},{"rank":"Q","suit":"diamonds"},{"rank":"J","suit":"c"}]}"""
        )
        assertEquals(listOf(Suit.SPADE, Suit.HEART, Suit.DIAMOND, Suit.CLUB), cards.map { it.suit })
    }

    @Test
    fun acceptsAlternativeJokerNaming() {
        val cards = parser.parse("""{"cards":[{"rank":"BLACK_JOKER","suit":"JOKER"},{"rank":"RED JOKER","suit":"JOKER"}]}""")
        assertEquals(Rank.SMALL_JOKER, cards[0].rank)
        assertEquals(Rank.BIG_JOKER, cards[1].rank)
    }

    @Test
    fun parsesBareTopLevelArray() {
        val text = """[{"rank":"A","suit":"SPADE"},{"rank":"Q","suit":"SPADE"},{"rank":"K","suit":"HEART"}]"""
        val outcome = parser.parseDetailed(text)
        assertEquals(3, outcome.cards.size)
        assertTrue("不应退回兜底提取", outcome.warnings.none { it.contains("退回逐条") })
        assertEquals(Suit.SPADE, outcome.cards[0].suit)
        assertEquals(Rank.QUEEN, outcome.cards[1].rank)
        assertEquals(Suit.HEART, outcome.cards[2].suit)
    }

    @Test
    fun parsesBareArrayWithUiNoisePrefix() {
        // DeepSeek 代码块把 “json / Copy / Download” 按钮文字也拼进了 textContent。
        val text = """jsonCopyDownload[
            {"rank":"9","suit":"DIAMOND"},
            {"rank":"2","suit":"SPADE"}
        ]"""
        val outcome = parser.parseDetailed(text)
        assertEquals(2, outcome.cards.size)
        assertTrue(outcome.warnings.none { it.contains("退回逐条") })
        assertEquals(Rank.NINE, outcome.cards[0].rank)
    }

    @Test
    fun promptDeclaresRequiredFields() {
        assertTrue(AiCardResponseParser.PROMPT.contains("\"cards\""))
        assertTrue(AiCardResponseParser.PROMPT.contains("最多27张"))
    }
}
