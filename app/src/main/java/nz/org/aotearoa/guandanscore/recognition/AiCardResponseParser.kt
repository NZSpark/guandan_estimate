package nz.org.aotearoa.guandanscore.recognition

import nz.org.aotearoa.guandanscore.model.Card
import nz.org.aotearoa.guandanscore.model.Rank
import nz.org.aotearoa.guandanscore.model.Suit
import org.json.JSONArray
import org.json.JSONObject

/**
 * 把模型回复文本解析为 [Card] 列表。与传输层（HTTP / WebView）完全无关。
 *
 * 解析策略：
 * - 先去掉 Markdown 围栏，再尝试严格 JSON。
 * - 严格模式校验字段合法性、数量上限、王的花色一致性；非法项被丢弃并记入 [ParseOutcome.warnings]。
 * - 严格解析得不到任何牌时，退回宽松正则提取（兼容被截断的回复）。
 * - 仍无结果则抛 [IllegalStateException]。
 */
class AiCardResponseParser {

    data class ParseOutcome(val cards: List<Card>, val warnings: List<String>)

    /** 宽松解析，失败抛异常。保持与旧 [AiRecognitionClient.parseCards] 一致的语义。 */
    fun parse(text: String): List<Card> = parseDetailed(text).cards.ifEmpty {
        error("AI 返回内容未能解析出有效的手牌数据")
    }

    /** 带告警的解析。调用方可用 warnings 提示用户校对。 */
    fun parseDetailed(text: String): ParseOutcome {
        val warnings = mutableListOf<String>()
        val strict = parseStrict(text, warnings)
        if (strict.isNotEmpty()) return ParseOutcome(strict, warnings)
        val resilient = parseResilient(text)
        if (resilient.isNotEmpty()) {
            warnings += "结构化 JSON 解析失败，已退回逐条提取，请重点核对"
            return ParseOutcome(resilient, warnings)
        }
        return ParseOutcome(emptyList(), warnings + "未能从回复中解析出任何有效牌")
    }

    internal fun parseStrict(text: String, warnings: MutableList<String>): List<Card> {
        // AI-134：既兼容 `{"cards":[...]}` 对象格式，也兼容模型直接返回的**顶层裸数组** `[...]`。
        val objStart = text.indexOf('{')
        if (objStart >= 0) {
            val objEnd = text.lastIndexOf('}')
            if (objEnd > objStart) {
                val obj = try {
                    JSONObject(text.substring(objStart, objEnd + 1))
                } catch (_: Exception) {
                    null
                }
                if (obj != null) {
                    val array = obj.optJSONArray("cards")
                    if (array != null) return collectCards(array, obj.optInt("count", -1), warnings)
                }
            }
        }
        // 顶层裸数组回退。
        val arrStart = text.indexOf('[')
        if (arrStart >= 0) {
            val arrEnd = text.lastIndexOf(']')
            if (arrEnd > arrStart) {
                val array = try {
                    JSONArray(text.substring(arrStart, arrEnd + 1))
                } catch (_: Exception) {
                    null
                }
                if (array != null) return collectCards(array, -1, warnings)
            }
        }
        return emptyList()
    }

    /** 把 JSON 数组按牌面规则转成 [Card] 列表，超限截断并写告警。 */
    private fun collectCards(array: JSONArray, declaredCount: Int, warnings: MutableList<String>): List<Card> {

        if (declaredCount >= 0 && declaredCount != array.length()) {
            warnings += "模型声明 $declaredCount 张，实际返回 ${array.length()} 张"
        }
        if (array.length() > MAX_CARDS) {
            warnings += "模型返回超过 $MAX_CARDS 张，已截断"
        }

        val cards = mutableListOf<Card>()
        for (i in 0 until minOf(array.length(), MAX_CARDS)) {
            val item = array.optJSONObject(i) ?: run {
                warnings += "第 ${i + 1} 项不是对象，已跳过"; null
            } ?: continue
            val card = toCard(cards.size, item.optString("rank"), item.optString("suit")) ?: run {
                warnings += "第 ${i + 1} 项牌面非法（rank=${item.optString("rank")}, suit=${item.optString("suit")}），已跳过"
                null
            } ?: continue
            cards += card
        }
        return cards
    }

    internal fun parseResilient(text: String): List<Card> {
        val pattern = Regex("""\{[^{}]*?"(?:rank|suit)"[^{}]*?\}""", RegexOption.DOT_MATCHES_ALL)
        val cards = mutableListOf<Card>()
        for (m in pattern.findAll(text)) {
            if (cards.size >= MAX_CARDS) break
            val obj = try { JSONObject(m.value) } catch (_: Exception) { continue }
            val card = toCard(cards.size, obj.optString("rank"), obj.optString("suit")) ?: continue
            cards += card
        }
        return cards
    }

    /** 把 rank/suit 字符串映射为合法 [Card]；王必须配 JOKER，普通牌不能配 JOKER。 */
    internal fun toCard(id: Int, rawRank: String, rawSuit: String): Card? {
        val rank = parseRank(rawRank) ?: return null
        val isJoker = rank == Rank.SMALL_JOKER || rank == Rank.BIG_JOKER
        val suit = parseSuit(rawSuit)
        return when {
            isJoker -> if (suit == null || suit == Suit.JOKER) Card(id, rank, Suit.JOKER) else null
            suit == null || suit == Suit.JOKER -> null
            else -> Card(id, rank, suit)
        }
    }

    internal fun parseRank(raw: String): Rank? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        Rank.parse(t)?.let { return it }
        return when (t.uppercase()) {
            "SMALL_JOKER", "SMALL JOKER", "BLACK_JOKER", "BLACK JOKER", "小丑", "小王" -> Rank.SMALL_JOKER
            "BIG_JOKER", "BIG JOKER", "RED_JOKER", "RED JOKER", "大王" -> Rank.BIG_JOKER
            else -> null
        }
    }

    internal fun parseSuit(raw: String): Suit? = when (raw.trim().uppercase()) {
        "C", "CLUB", "CLUBS", "♣" -> Suit.CLUB
        "D", "DIAMOND", "DIAMONDS", "♦" -> Suit.DIAMOND
        "H", "HEART", "HEARTS", "♥" -> Suit.HEART
        "S", "SPADE", "SPADES", "♠" -> Suit.SPADE
        "JOKER", "" -> Suit.JOKER
        else -> null
    }

    companion object {
        const val MAX_CARDS = 27

        /** 与 API 版共用的识牌指令，保证两种传输方式约束一致。 */
        const val PROMPT = """识别照片中的掼蛋手牌。第一步定位并统计可见扑克牌；第二步识别每张牌的点数；第三步重点观察点数正下方的小花色图形来识别花色。只返回JSON，不要Markdown：{"count":整数,"cards":[{"rank":"2|3|4|5|6|7|8|9|10|J|Q|K|A|SJ|BJ","suit":"C|D|H|S|JOKER"}]}。不要猜测被完全遮挡的牌，不要重复同一物理位置。最多27张。"""
    }
}
