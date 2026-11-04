package nz.org.aotearoa.guandanscore.scoring

import nz.org.aotearoa.guandanscore.model.Card
import nz.org.aotearoa.guandanscore.model.Rank
import nz.org.aotearoa.guandanscore.model.Suit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** V4.2 单牌型评分与综合牌力评估的关键校验（对齐两份评分文档）。 */
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
            intArrayOf(16, 50, 109),
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
        // 6张2：Sum=12, Sum+5V=22 => 523+floor(55*log2(1.22)) = 538
        val result = HandScorer(Rank.THREE).score(repeats(Rank.TWO, 6))
        assertEquals(1, result.rounds)
        assertEquals(538, result.total)
        assertTrue(result.melds.any { it.type == MeldType.BOMB && it.score == 538 })
    }

    @Test fun sixCardKingBombMatchesV41Example() {
        // 文档 §六 示例4：Sum=78, Sum+5V=143 => 523+floor(55*log2(2.43)) = 593
        val result = HandScorer(Rank.THREE).score(repeats(Rank.KING, 6))
        assertEquals(1, result.rounds)
        assertEquals(593, result.total)
    }

    @Test fun sevenAndEightCardBombsMatchV41Formulas() {
        // 7张2：577+floor(73*log2(1.26)) = 601；8张2：672+floor(75*log2(1.30)) = 700
        assertEquals(601, HandScorer(Rank.THREE).score(repeats(Rank.TWO, 7)).total)
        assertEquals(700, HandScorer(Rank.THREE).score(repeats(Rank.TWO, 8)).total)
    }

    @Test fun wildSubstitutedBombUsesSameLogScaleFormula() {
        // 5张2 + ♥7（级牌红心配）：红心配按替代点数 2 计，Sum=12，与6张2完全同分 538
        val result = HandScorer(Rank.SEVEN).score(repeats(Rank.TWO, 5) + Card(5, Rank.SEVEN, Suit.HEART))
        assertEquals(1, result.rounds)
        assertEquals(538, result.total)
    }

    @Test fun nineCardBombWithOneWildMatchesFormula() {
        // 8张2 + ♥7（级牌红心配）：Sum=18, Sum+7V=32 => 784+floor(66*log2(1.32)) = 810
        val result = HandScorer(Rank.SEVEN).score(repeats(Rank.TWO, 8) + Card(8, Rank.SEVEN, Suit.HEART))
        assertEquals(1, result.rounds)
        assertEquals(810, result.total)
    }

    @Test fun tenCardBombWithTwoWildsMatchesFormula() {
        // 8张2 + 2张♥7（级牌红心配）：Sum=20, Sum+5V=30 => 890+floor(60*log2(1.30)) = 912
        val hand = repeats(Rank.TWO, 8) + List(2) { Card(8 + it, Rank.SEVEN, Suit.HEART) }
        val result = HandScorer(Rank.SEVEN).score(hand)
        assertEquals(1, result.rounds)
        assertEquals(912, result.total)
    }

    @Test fun bombTiersMatchV41RangesAndStayStrictlyIncreasing() {
        // 9/10 炸不存在 V=15 的级牌炸弹，可实现上限取 8 张 A + 配。
        val ranges = mapOf(
            4 to 192..270, 5 to 298..415, 6 to 538..600, 7 to 601..690,
            8 to 700..799, 9 to 810..895, 10 to 912..987,
        )
        val mins = mutableMapOf<Int, Int>()
        val maxs = mutableMapOf<Int, Int>()
        for (n in 4..10) {
            val values = if (n <= 8) 2..15 else 2..14
            val scores = values.map { bombScore(n, it) }
            assertEquals("${n}炸下限", ranges.getValue(n).first, scores.min())
            assertEquals("${n}炸上限", ranges.getValue(n).last, scores.max())
            mins[n] = scores.min()
            maxs[n] = scores.max()
        }
        for (n in 4..9) {
            assertTrue("${n}炸上限 ${maxs.getValue(n)} 必须低于 ${n + 1}炸下限 ${mins.getValue(n + 1)}", maxs.getValue(n) < mins.getValue(n + 1))
        }
        assertTrue("10炸上限 ${maxs.getValue(10)} 必须低于火箭 1000", maxs.getValue(10) < 1000)
    }

    @Test fun wildSubstitutedBombScoresAsItsRepresentedRank() {
        // 级牌 2：三张5 + ♥2 就是四张5（210）。旧实现把红心配按 15 计，得到 220，
        // 反而压过四张6（216），与牌理矛盾。
        val scorer = HandScorer(Rank.TWO)
        val wildBomb = scorer.score(repeats(Rank.FIVE, 3) + Card(3, Rank.TWO, Suit.HEART))
        assertEquals(MeldType.BOMB, wildBomb.melds.single().type)
        assertEquals(210, wildBomb.total)
        assertEquals(210, scorer.score(repeats(Rank.FIVE, 4)).total)
        assertEquals(216, scorer.score(repeats(Rank.SIX, 4)).total)
        assertTrue(wildBomb.total < 216)
    }

    @Test fun residualBombPlusLowSingleAppliesWastePenaltyPerExample2() {
        // 综合评估 §八 示例 2：AAAA + 2（残局 5 张，N_waste=1、P=15）
        // SPI = (264×1.15 + 10 − 15)/2 = 149.30；N_control=1，R_control = 1/(2−1) = 1.00。
        val suits = listOf(Suit.SPADE, Suit.HEART, Suit.CLUB, Suit.DIAMOND)
        val result = HandScorer(Rank.SEVEN).score(
            suits.mapIndexed { i, s -> Card(i, Rank.ACE, s) } + Card(4, Rank.TWO, Suit.SPADE)
        )
        assertEquals(2, result.rounds)
        assertEquals(1, result.waste)
        assertEquals(298.6, result.numerator, 0.001)
        assertEquals(149.3, result.spi, 0.001)
        assertEquals(1, result.control)
        assertEquals(1.0, result.controlRatio!!, 0.001)
        assertEquals("残局", result.stage)
        assertEquals("强势", result.grade)
    }

    @Test fun singleUnitPlanHasNoControlRatio() {
        // §6.4：T_plan=1 时分母为零，控制覆盖比不计算。
        val result = HandScorer(Rank.SEVEN).score(cards(Rank.BIG_JOKER))
        assertEquals(1, result.rounds)
        assertEquals(1, result.control)
        assertNull(result.controlRatio)
        assertEquals("残局", result.stage)
        assertEquals("碾压级", result.grade)
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
        // 残局 1~9（辅助参考）：<60 / 60~120 / 120~180 / ≥180
        assertEquals("弱势", result(59.9, 5).grade)
        assertEquals("均衡", result(60.0, 5).grade)
        assertEquals("强势", result(120.0, 5).grade)
        assertEquals("碾压级", result(180.0, 5).grade)
        assertEquals("残局", result(9.0, 9).stage)
        // 中局 13~18：<70 / 70~130 / 130~200 / ≥200
        assertEquals("弱势", result(69.9, 13).grade)
        assertEquals("均衡", result(70.0, 13).grade)
        assertEquals("强势", result(130.0, 13).grade)
        assertEquals("碾压级", result(200.0, 13).grade)
        assertEquals("中局", result(100.0, 10).stage)
        // 27 张：<80 / 80~140 / 140~220 / ≥220
        assertEquals("弱势", result(79.9, 27).grade)
        assertEquals("均衡", result(80.0, 27).grade)
        assertEquals("强势", result(140.0, 27).grade)
        assertEquals("碾压级", result(220.0, 27).grade)
        assertEquals("开局/前中局", result(100.0, 19).stage)
        // §5.2 / T13：19~26 张与 10~12 张无阈值依据，不输出正式评级，也不外推相邻区间。
        assertEquals("待标定", result(220.0, 20).grade)
        assertEquals("待标定", result(220.0, 26).grade)
        assertEquals("待标定", result(220.0, 10).grade)
        assertEquals("待标定", result(220.0, 12).grade)
    }

    @Test fun fullHouseCountsNaturalLevelCardsAsFifteen() {
        // 级牌 2：222+33 raw = (3×15 + 2×3) + 2×15 = 81 → 50 + 65×141/93 = 149（V4.2 分母 93）
        val result = HandScorer(Rank.TWO).score(repeats(Rank.TWO, 3) + repeats(Rank.THREE, 2))
        assertEquals(1, result.rounds)
        assertEquals(MeldType.FULL_HOUSE, result.melds.single().type)
        assertEquals(149, result.total)
    }

    @Test fun fullHouseLevelTripleOutranksAceTriple() {
        // 222+33 = 149 必须高于 AAA+33 = 141（raw 76）。
        val levelTriple = HandScorer(Rank.TWO).score(repeats(Rank.TWO, 3) + repeats(Rank.THREE, 2)).total
        val aceTriple = HandScorer(Rank.TWO).score(repeats(Rank.ACE, 3) + repeats(Rank.THREE, 2)).total
        assertEquals(149, levelTriple)
        assertEquals(141, aceTriple)
    }

    @Test fun levelPairAndTripleCandidatesUseFifteen() {
        // 级牌 2：对子 3×15=45 → 168；三张 5×15=75 → 191。
        // 非级牌 A：对子 3×14=42 → 157；三张 5×14=70 → 179。
        val scorer = HandScorer(Rank.TWO)
        assertEquals(168, scorer.pairScore(Rank.TWO.value))
        assertEquals(157, scorer.pairScore(Rank.ACE.value))
        assertEquals(191, scorer.tripleScore(Rank.TWO.value))
        assertEquals(179, scorer.tripleScore(Rank.ACE.value))
    }

    @Test fun levelTripleStaysOptimalWhenBombsCarryTheHand() {
        // 4张2 + 4张3 + 4张7（级牌，其中♥7为红心配）。最优：4张3+配的5炸(307)
        // + 4张2炸(192) + 三张自然7(191)；若三张7被按 5×7 计，整体会改选别的拆法。
        val hand = repeats(Rank.TWO, 4) + repeats(Rank.THREE, 4) + repeats(Rank.SEVEN, 4)
        val result = HandScorer(Rank.SEVEN).score(hand)
        assertEquals(3, result.rounds)
        assertEquals(764.85, result.numerator, 0.001)
        assertTrue(result.melds.any { it.type == MeldType.TRIPLE && it.score == 191 })
    }

    @Test fun fullHouseCountsLevelPairAsFifteen() {
        // 级牌 2 作对子（候选分，直接校验公式）：
        // KKK+22 raw = 5×13 + 2×15 = 95 → 178；AAA+22 raw = 5×14 + 2×15 = 100 → 186。
        // 注：整手评分会拆成三张 K + 两张级牌单张（单张级牌 α=1.15），
        // 这里的候选分用于确认级牌对子不再按面值 2 计。
        val scorer = HandScorer(Rank.TWO)
        assertEquals(170, scorer.fullHouseScore(13, 2))
        assertEquals(177, scorer.fullHouseScore(14, 2))
    }

    @Test fun fullHouseMaximumStaysBelowSmallestBomb() {
        // V4.2 §六 示例 8：级牌 2 的 222+AA raw = 103 → 50 + 87×141/93 = 182，仍小于最小 4 炸 192。
        // 分母扩大后整手最优会改拆「三张级牌单张（α=1.15）+ 对 A」，所以校验牌型候选分而非最优分区。
        val scorer = HandScorer(Rank.TWO)
        assertEquals(182, scorer.fullHouseScore(Rank.TWO.value, Rank.ACE.value))
        val hand = repeats(Rank.TWO, 3) + repeats(Rank.ACE, 2)
        assertTrue(
            MeldEvaluator.interpretations(hand, hand.indices.toList(), Rank.TWO)
                .any { it.type == MeldType.FULL_HOUSE && it.score == 182 }
        )
        assertTrue(182 < 192)
    }

    @Test fun fullHouseWithJokerPairReachesMaxAndStaysLegal() {
        // V4.2 §三 / §六 示例 7：三张级牌 + 一对大王 raw = 79 + 30 = 109 → 191。
        // 综合评估 T06/T07：王对必须能通过合法性检查（王不可由红心配替代）。
        val scorer = HandScorer(Rank.TWO)
        assertEquals(191, scorer.fullHouseScore(Rank.TWO.value, Rank.BIG_JOKER.value))
        assertEquals(188, scorer.fullHouseScore(Rank.TWO.value, Rank.SMALL_JOKER.value))
        val hand = repeats(Rank.TWO, 3) + listOf(
            Card(3, Rank.BIG_JOKER, Suit.JOKER),
            Card(4, Rank.BIG_JOKER, Suit.JOKER),
        )
        val fullHouses = MeldEvaluator.interpretations(hand, hand.indices.toList(), Rank.TWO)
            .filter { it.type == MeldType.FULL_HOUSE }
        assertTrue(fullHouses.any { it.score == 191 })
    }

    @Test fun fullHouseWithoutLevelCardsUsesExtendedMapping() {
        // AAAKK raw = 96 在 V4.2 映射 16..109 下为 171。
        val result = HandScorer(Rank.SEVEN).score(repeats(Rank.ACE, 3) + repeats(Rank.KING, 2))
        assertEquals(MeldType.FULL_HOUSE, result.melds.single().type)
        assertEquals(171, result.total)
    }

    @Test fun fullHouseWildSubstitutionUsesRepresentedRank() {
        // 级牌 2：10,10,♥2(代10),5,5 raw = 5×10 + 2×5 = 60 → 50 + 44×141/93 = 117
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
        assertEquals(117, result.total)
    }

    @Test fun nineAndTenCardBombMinimaDependOnLevel() {
        // §4.3：810 / 912 是跨级牌局的全局最小值；级牌为 2 时红心配只能按 15 计，
        // 有效最低构成退为「8 张 3 + 配」，即 821 / 922。
        assertEquals(810, bombScore(9, 2))
        assertEquals(912, bombScore(10, 2))
        assertEquals(821, bombScore(9, 3))
        assertEquals(922, bombScore(10, 3))
    }
}
