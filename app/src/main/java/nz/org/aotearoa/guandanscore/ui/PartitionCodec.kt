package nz.org.aotearoa.guandanscore.ui

import nz.org.aotearoa.guandanscore.model.Card
import nz.org.aotearoa.guandanscore.model.Rank
import nz.org.aotearoa.guandanscore.model.Suit
import nz.org.aotearoa.guandanscore.scoring.MeldGroup
import nz.org.aotearoa.guandanscore.scoring.MeldType
import nz.org.aotearoa.guandanscore.scoring.validatePartition
import org.json.JSONArray
import org.json.JSONObject

/** Intent 与 onSaveInstanceState 共用的分区 / 摘要编解码。 */
object PartitionCodec {
    const val EXTRA_PARTITION = "meld_adjust.partition"
    const val EXTRA_GROUPS = "meld_adjust.groups"
    const val EXTRA_BASE = "meld_adjust.base"

    data class Payload(val level: Rank, val hand: List<Card>, val groups: List<MeldGroup>)

    enum class Mismatch { HAND, LEVEL }

    /** 返回子页面回传的入口数据与主界面当前状态的差异；null 表示一致。 */
    fun mismatch(payload: Payload, hand: List<Card>, level: Rank): Mismatch? {
        if (payload.hand.map { it.rank to it.suit } != hand.map { it.rank to it.suit }) return Mismatch.HAND
        if (payload.level != level) return Mismatch.LEVEL
        return null
    }

    fun encodePartition(level: Rank, hand: List<Card>, groups: List<MeldGroup>): String {
        validatePartition(hand.size, groups)
        val obj = JSONObject()
            .put("level", level.name)
            .put("hand", JSONArray(hand.map { cardJson(it) }))
            .put("groups", groupsJson(groups))
        return obj.toString()
    }

    fun decodePartition(raw: String): Payload {
        val obj = JSONObject(raw)
        val level = enumValue<Rank>(obj.getString("level"))
        val handJson = obj.getJSONArray("hand")
        require(handJson.length() in 1..27) { "手牌张数必须为1至27" }
        val hand = List(handJson.length()) { i ->
            val card = handJson.getJSONObject(i)
            Card(
                id = i,
                rank = enumValue(card.getString("rank")),
                suit = enumValue(card.getString("suit")),
            )
        }
        val groups = parseGroups(obj.getJSONArray("groups"))
        require(hand.groupingBy { it.rank to it.suit }.eachCount().values.all { it <= 2 }) {
            "两副牌中同一张牌最多出现 2 次，请检查识别结果"
        }
        validatePartition(hand.size, groups)
        return Payload(level, hand, groups)
    }

    fun encodeGroups(groups: List<MeldGroup>): String = groupsJson(groups).toString()

    fun decodeGroups(raw: String, handSize: Int): List<MeldGroup> {
        val groups = parseGroups(JSONArray(raw))
        validatePartition(handSize, groups)
        return groups
    }

    fun encodeSnapshot(snapshot: ScoreSnapshot): String = JSONObject()
        .put("spi", snapshot.spi)
        .put("numerator", snapshot.numerator)
        .put("rounds", snapshot.rounds)
        .put("nspi", snapshot.nspi)
        .put("grade", snapshot.grade)
        .put("exhaustive", snapshot.exhaustive)
        .toString()

    fun decodeSnapshot(raw: String): ScoreSnapshot {
        val obj = JSONObject(raw)
        return ScoreSnapshot(
            spi = obj.getDouble("spi"),
            numerator = obj.getDouble("numerator"),
            rounds = obj.getInt("rounds"),
            nspi = obj.getDouble("nspi"),
            grade = obj.getString("grade"),
            exhaustive = obj.getBoolean("exhaustive"),
        )
    }

    private fun cardJson(card: Card): JSONObject = JSONObject()
        .put("rank", card.rank.name)
        .put("suit", card.suit.name)

    private fun groupsJson(groups: List<MeldGroup>): JSONArray = JSONArray(groups.map { group ->
        JSONObject()
            .put("type", group.type.name)
            .put("cards", JSONArray(group.indices))
    })

    private fun parseGroups(array: JSONArray): List<MeldGroup> = List(array.length()) { i ->
        val obj = array.getJSONObject(i)
        val type = enumValue<MeldType>(obj.getString("type"))
        val cards = obj.getJSONArray("cards")
        MeldGroup(List(cards.length()) { cards.getInt(it) }, type)
    }

    private inline fun <reified T : Enum<T>> enumValue(raw: String): T =
        enumValues<T>().firstOrNull { it.name == raw }
            ?: throw IllegalArgumentException("未知枚举值：$raw")
}
