package nz.org.aotearoa.guandanscore.model

enum class Suit(val symbol: String) { CLUB("♣"), DIAMOND("♦"), HEART("♥"), SPADE("♠"), JOKER("") }

enum class Rank(val label: String, val value: Int) {
    TWO("2", 2), THREE("3", 3), FOUR("4", 4), FIVE("5", 5), SIX("6", 6),
    SEVEN("7", 7), EIGHT("8", 8), NINE("9", 9), TEN("10", 10), JACK("J", 11),
    QUEEN("Q", 12), KING("K", 13), ACE("A", 14), SMALL_JOKER("小王", 16), BIG_JOKER("大王", 17);

    companion object {
        fun parse(raw: String): Rank? = entries.firstOrNull {
            it.label.equals(raw.trim(), true) || (it == TEN && raw.trim() == "T")
        } ?: when (raw.trim().uppercase()) { "SJ" -> SMALL_JOKER; "BJ" -> BIG_JOKER; else -> null }
    }
}

data class Card(val id: Int, val rank: Rank, val suit: Suit) {
    val display: String get() = if (suit == Suit.JOKER) rank.label else "${suit.symbol}${rank.label}"
    fun isWild(level: Rank) = suit == Suit.HEART && rank == level
}
