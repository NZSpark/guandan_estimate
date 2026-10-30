package nz.org.aotearoa.guandanscore.scoring

import nz.org.aotearoa.guandanscore.model.Card
import nz.org.aotearoa.guandanscore.model.Rank
import nz.org.aotearoa.guandanscore.model.Suit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** V4.0 单牌型评分与综合牌力评估的关键校验（对齐两份 2026-09 文档）。 */
class ScoringV4Test {
    private fun cards(vararg ranks: Rank): List<Card> = ranks.mapIndexed { index, rank ->
        Card(index, rank, if (rank.value > 14) Suit.JOKER else Suit.entries[index % 4])
    }

    /** 生成 count 张相同自然牌，花色循环，id 从 0 开始递增。 */
    private fun repeats(rank: Rank, count: Int): List<Card> {
        val suits = listOf(Suit.SPADE, Suit.HEART, Suit.CLUB, Suit.DIAMOND)
        return List(count) { i -> Card(i, rank, suits[i % suits.size]) }
    }

    @Test fun ordinaryMeldRangesMapExactlyToV4Bounds() {
        val ranges = listOf(
            intArrayOf(2, 10, 17),
            intArrayOf(6, 20, 51),
            intArrayOf(10, 30, 75),
            intArrayOf(20, 40, 74),
            intArrayOf(16, 50, 103),
            intArrayOf(18, 50, 106),
            intArrayOf(15, 60, 123)
        )
        ranges.forEach { (rawMin, finalMin, rawMax) ->
            assertEquals(finalMin, ordinaryMappedScore(rawMin, rawMin, finalMin, rawMax))
            assertEquals(191, ordinaryMappedScore(rawMax, rawMin, finalMin, rawMax))
        }
    }

    @Test fun ordinaryMeldMinimaUseV4Floors() {
        val level = Rank.SEVEN
        assertEquals(10, HandScorer(level).score(cards(Rank.TWO)).total)
        assertEquals(20, HandScorer(level).score(cards(Rank.TWO, Rank.TWO)).total)
        assertEquals(30, HandScorer(level).score(cards(Rank.TWO, Rank.TWO, Rank.TWO)).total)
    }

    @Test fun pairOfBigJokersSplitsIntoTwoControlSingles() {
        // V4.0 α 规则下“单张大王×1.15”：两张大王拆成两个单张大牌的平均 SPI 高于打成对子。
        val result = HandScorer(Rank.SEVEN).score(cards(Rank.BIG_JOKER, Rank.BIG_JOKER))
        assertEquals(2, result.rounds)
        assertEquals(382, result.total) // 191 + 191
        assertEquals(439.3, result.numerator, 0.001) // 191×1.15×2
        assertEquals(219.65, result.spi, 0.001)
        assertTrue(result.melds.all { it.type == MeldType.SINGLE })
    }

    @Test fun smallestBombStillBeatsEveryOrdinaryMeld() {
        val result = HandScorer(Rank.SEVEN).score(cards(Rank.TWO, Rank.TWO, Rank.TWO, Rank.TWO))
        assertEquals(192, result.total)
        assertTrue(result.melds.single().type == MeldType.BOMB)
    }

    @Test fun rocketHasFixedScoreOf1000() {
        val result = HandScorer(Rank.FIVE).score(
            cards(Rank.SMALL_JOKER, Rank.BIG_JOKER, Rank.SMALL_JOKER, Rank.BIG_JOKER)
        )
        assertEquals(1, result.rounds)
        assertTrue(result.melds.single().type == MeldType.ROCKET)
        assertEquals(1000, result.melds.single().score)
    }

    @Test fun sixCardBombMatchesLogScaleFormula() {
        // 6张2：Sum=12, Sum+5V=22 => 510+floor(100*log2(1.22)) = 538
        val result = HandScorer(Rank.THREE).score(repeats(Rank.TWO, 6))
        assertEquals(1, result.rounds)
        assertEquals(538, result.total)
        assertTrue(result.melds.any { it.type == MeldType.BOMB && it.score == 538 })
    }

    @Test fun wildSubstitutedBombUsesSameLogScaleFormula() {
        // 5张2 + ♥7（级牌逢人配）：逢人配按替代点数 2 计，Sum=12，与6张2完全同分 538
        val result = HandScorer(Rank.SEVEN).score(repeats(Rank.TWO, 5) + Card(5, Rank.SEVEN, Suit.HEART))
        assertEquals(1, result.rounds)
        assertEquals(538, result.total)
    }

    @Test fun nineCardBombWithOneWildMatchesFormula() {
        // 8张2 + ♥7（级牌逢人配）：Sum=18, Sum+7V=32 => 800+floor(70*log2(1.32)) = 828
        val result = HandScorer(Rank.SEVEN).score(repeats(Rank.TWO, 8) + Card(8, Rank.SEVEN, Suit.HEART))
        assertEquals(1, result.rounds)
        assertEquals(828, result.total)
    }

    @Test fun tenCardBombWithTwoWildsMatchesFormula() {
        // 8张2 + 2张♥7（级牌逢人配）：Sum=20, Sum+5V=30 => 890+floor(60*log2(1.30)) = 912
        val hand = repeats(Rank.TWO, 8) + List(2) { Card(8 + it, Rank.SEVEN, Suit.HEART) }
        val result = HandScorer(Rank.SEVEN).score(hand)
        assertEquals(1, result.rounds)
        assertEquals(912, result.total)
    }

    @Test fun wildSubstitutedBombScoresAsItsRepresentedRank() {
        // 级牌 2：三张5 + ♥2 就是四张5（210）。旧实现把逢人配按 15 计，得到 220，
        // 反而压过四张6（216），与牌理矛盾。
        val scorer = HandScorer(Rank.TWO)
        val wildBomb = scorer.score(repeats(Rank.FIVE, 3) + Card(3, Rank.TWO, Suit.HEART))
        assertEquals(MeldType.BOMB, wildBomb.melds.single().type)
        assertEquals(210, wildBomb.total)
        assertEquals(210, scorer.score(repeats(Rank.FIVE, 4)).total)
        assertEquals(216, scorer.score(repeats(Rank.SIX, 4)).total)
        assertTrue(wildBomb.total < 216)
    }

    @Test fun residualBombPlusLowSingleIgnoresWastePenalty() {
        // 文档示例2：AAAA + 2（残局 5 张，P=0）→ SPI = (264×1.15 + 10×1.0)/2 = 156.8
        val suits = listOf(Suit.SPADE, Suit.HEART, Suit.CLUB, Suit.DIAMOND)
        val result = HandScorer(Rank.SEVEN).score(
            suits.mapIndexed { i, s -> Card(i, Rank.ACE, s) } + Card(4, Rank.TWO, Suit.SPADE)
        )
        assertEquals(2, result.rounds)
        assertEquals(0, result.waste)
        assertEquals(313.6, result.numerator, 0.001)
        assertEquals(156.8, result.spi, 0.001)
        assertEquals("强势", result.grade)
    }

    @Test fun openingHandPenalizesLeftoverLowPair() {
        // 10张：KKKK + QQQQ + 33（残局边界 N=10，散牌对子 33 计入惩罚）
        val suits = listOf(Suit.SPADE, Suit.HEART, Suit.CLUB, Suit.DIAMOND)
        val result = HandScorer(Rank.SEVEN).score(
            suits.mapIndexed { i, s -> Card(i, Rank.KING, s) } +
                suits.mapIndexed { i, s -> Card(i + 4, Rank.QUEEN, s) } +
                listOf(Card(8, Rank.THREE, Suit.SPADE), Card(9, Rank.THREE, Suit.CLUB))
        )
        assertEquals(3, result.rounds)
        assertEquals(1, result.waste)
        assertEquals(15.0, result.penalty, 0.001)
        assertEquals(602.5, result.numerator, 0.001)
        assertEquals(200.8333, result.spi, 0.001)
    }

    @Test fun dynamicGradeThresholdsFollowV4Table() {
        fun result(spi: Double, n: Int) = HandResult(spi, 0, 1, emptyList(), n)
        // 残局 1~12：<60 / 60~120 / 120~180 / ≥180
        assertEquals("弱势", result(59.9, 5).grade)
        assertEquals("均衡", result(60.0, 5).grade)
        assertEquals("强势", result(120.0, 5).grade)
        assertEquals("碾压级", result(180.0, 5).grade)
        // 中局 13~18：<70 / 70~130 / 130~200 / ≥200
        assertEquals("弱势", result(69.9, 13).grade)
        assertEquals("均衡", result(70.0, 13).grade)
        assertEquals("强势", result(130.0, 13).grade)
        assertEquals("碾压级", result(200.0, 13).grade)
        // 开局（≥19，含19~26过渡）：<80 / 80~140 / 140~220 / ≥220
        assertEquals("弱势", result(79.9, 27).grade)
        assertEquals("均衡", result(80.0, 27).grade)
        assertEquals("强势", result(140.0, 27).grade)
        assertEquals("碾压级", result(220.0, 27).grade)
    }

    @Test fun fullHouseCountsNaturalLevelCardsAsFifteen() {
        // 级牌 2：222+33 raw = (3×15 + 2×3) + 2×15 = 81 → 155（旧实现是 16 → 50）
        val result = HandScorer(Rank.TWO).score(repeats(Rank.TWO, 3) + repeats(Rank.THREE, 2))
        assertEquals(1, result.rounds)
        assertEquals(MeldType.FULL_HOUSE, result.melds.single().type)
        assertEquals(155, result.total)
    }

    @Test fun fullHouseLevelTripleOutranksAceTriple() {
        // 222+33 = 155 必须高于 AAA+33 = 147；旧实现是 50 < 156。
        val levelTriple = HandScorer(Rank.TWO).score(repeats(Rank.TWO, 3) + repeats(Rank.THREE, 2)).total
        val aceTriple = HandScorer(Rank.TWO).score(repeats(Rank.ACE, 3) + repeats(Rank.THREE, 2)).total
        assertEquals(155, levelTriple)
        assertEquals(147, aceTriple)
    }

    @Test fun fullHouseCountsLevelPairAsFifteen() {
        // 级牌 2 作对子（候选分，直接校验公式）：
        // KKK+22 raw = 5×13 + 2×15 = 95 → 178；AAA+22 raw = 5×14 + 2×15 = 100 → 186。
        // 注：整手评分会拆成三张 K + 两张级牌单张（单张级牌 α=1.15），
        // 这里的候选分用于确认级牌对子不再按面值 2 计。
        val scorer = HandScorer(Rank.TWO)
        assertEquals(178, scorer.fullHouseScore(13, 2))
        assertEquals(186, scorer.fullHouseScore(14, 2))
    }

    @Test fun fullHouseMaximumStaysBelowSmallestBomb() {
        // 级牌 2 的最大三带二：222+AA raw = 103 → 191，仍小于最小 4 炸 192。
        val result = HandScorer(Rank.TWO).score(repeats(Rank.TWO, 3) + repeats(Rank.ACE, 2))
        assertEquals(MeldType.FULL_HOUSE, result.melds.single().type)
        assertEquals(191, result.total)
        assertTrue(result.total < 192)
    }

    @Test fun fullHouseWithoutLevelCardsUsesExtendedMapping() {
        // AAAKK raw = 96 在新映射 16..103 下为 180；旧映射会得到 191。
        val result = HandScorer(Rank.SEVEN).score(repeats(Rank.ACE, 3) + repeats(Rank.KING, 2))
        assertEquals(MeldType.FULL_HOUSE, result.melds.single().type)
        assertEquals(180, result.total)
    }

    @Test fun fullHouseWildSubstitutionUsesRepresentedRank() {
        // 级牌 2：10,10,♥2(代10),5,5 raw = 5×10 + 2×5 = 60 → 121
        val hand = listOf(
            Card(0, Rank.TEN, Suit.SPADE),
            Card(1, Rank.TEN, Suit.CLUB),
            Card(2, Rank.TWO, Suit.HEART),
            Card(3, Rank.FIVE, Suit.SPADE),
            Card(4, Rank.FIVE, Suit.CLUB),
        )
        val result = HandScorer(Rank.TWO).score(hand)
        assertEquals(1, result.rounds)
        assertEquals(MeldType.FULL_HOUSE, result.melds.single().type)
        assertEquals(121, result.total)
    }
}
