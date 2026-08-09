package nz.org.aotearoa.guandanscore.imaging

import nz.org.aotearoa.guandanscore.model.Card
import nz.org.aotearoa.guandanscore.model.Rank
import nz.org.aotearoa.guandanscore.model.Suit
import org.junit.Assert.assertEquals
import org.junit.Test

class CardAssetNamesTest {
    @Test fun standardCardsMapToProvidedPngNames() {
        assertEquals("SpadeA.png", Card(0, Rank.ACE, Suit.SPADE).assetFileName())
        assertEquals("Heart10.png", Card(1, Rank.TEN, Suit.HEART).assetFileName())
        assertEquals("Club2.png", Card(2, Rank.TWO, Suit.CLUB).assetFileName())
        assertEquals("DiamondQ.png", Card(3, Rank.QUEEN, Suit.DIAMOND).assetFileName())
    }

    @Test fun redAndBlackJokersMapToBigAndSmallJoker() {
        assertEquals("JOKER-A.png", Card(0, Rank.BIG_JOKER, Suit.JOKER).assetFileName())
        assertEquals("JOKER-B.png", Card(1, Rank.SMALL_JOKER, Suit.JOKER).assetFileName())
    }
}
