package nz.org.aotearoa.guandanscore.imaging

import nz.org.aotearoa.guandanscore.model.Card
import nz.org.aotearoa.guandanscore.model.Rank
import nz.org.aotearoa.guandanscore.model.Suit

internal fun Card.assetFileName(): String = when (rank) {
    Rank.BIG_JOKER -> "JOKER-A.png"
    Rank.SMALL_JOKER -> "JOKER-B.png"
    else -> "${suit.assetPrefix}${rank.label}.png"
}

private val Suit.assetPrefix: String
    get() = when (this) {
        Suit.CLUB -> "Club"
        Suit.DIAMOND -> "Diamond"
        Suit.HEART -> "Heart"
        Suit.SPADE -> "Spade"
        Suit.JOKER -> error("普通牌必须具有四种花色之一")
    }
