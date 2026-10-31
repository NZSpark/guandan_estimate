package nz.org.aotearoa.guandanscore.ui

import nz.org.aotearoa.guandanscore.model.Card
import nz.org.aotearoa.guandanscore.model.Rank
import nz.org.aotearoa.guandanscore.scoring.HandResult
import nz.org.aotearoa.guandanscore.scoring.PartitionEvaluator

/**
 * 手工拆牌返回结果的唯一入口。
 *
 * 只接收子页面回传的 groups + 入口手牌/级牌；分值一律由 [PartitionEvaluator] 重算，
 * 不信任 Intent 里的 SPI/分子。Android Activity 只负责 Toast 与状态切换。
 */
sealed interface MeldAdjustApplyResult {
    data class Applied(val result: HandResult) : MeldAdjustApplyResult
    data class Rejected(val message: String) : MeldAdjustApplyResult
}

object MeldAdjustApply {
    const val INVALID = "手工拆牌结果无效，已保留原方案"
    const val HAND_CHANGED = "手牌已变化，请返回重新计算"
    const val LEVEL_CHANGED = "级牌已变化，请返回重新计算"

    fun apply(
        groupsRaw: String?,
        partitionRaw: String?,
        hand: List<Card>,
        level: Rank,
    ): MeldAdjustApplyResult {
        if (groupsRaw.isNullOrBlank() || partitionRaw.isNullOrBlank()) return rejected(INVALID)
        val payload = runCatching { PartitionCodec.decodePartition(partitionRaw) }
            .getOrElse { return rejected(INVALID) }
        when (PartitionCodec.mismatch(payload, hand, level)) {
            PartitionCodec.Mismatch.HAND -> return rejected(HAND_CHANGED)
            PartitionCodec.Mismatch.LEVEL -> return rejected(LEVEL_CHANGED)
            null -> Unit
        }
        val groups = runCatching { PartitionCodec.decodeGroups(groupsRaw, hand.size) }
            .getOrElse { return rejected(INVALID) }
        val result = runCatching { PartitionEvaluator.evaluate(hand, groups, level) }
            .getOrElse { return rejected(INVALID) }
        return MeldAdjustApplyResult.Applied(result)
    }

    private fun rejected(message: String) = MeldAdjustApplyResult.Rejected(message)
}
