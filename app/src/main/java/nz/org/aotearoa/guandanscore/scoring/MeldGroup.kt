package nz.org.aotearoa.guandanscore.scoring

/**
 * 一次手工拆牌中的一组：引用手牌下标 + 选定的牌型解释。
 * 不携带分值：分值由 MeldEvaluator 按牌型规则重新解析，避免 UI 侧伪造分数。
 */
data class MeldGroup(val indices: List<Int>, val type: MeldType)

/**
 * 校验一个分区是否覆盖手牌恰好一次。
 * 只检查结构，不解析牌型；牌型合法性由 [PartitionEvaluator.resolve] 负责。
 */
internal fun validatePartition(handSize: Int, groups: List<MeldGroup>) {
    require(groups.isNotEmpty()) { "分区不完整，请重置后重试" }
    var used = 0
    groups.forEach { group ->
        require(group.indices.isNotEmpty()) { "空的分组不能应用" }
        require(group.indices.all { it in 0 until handSize }) { "分区引用无效，请重置后重试" }
        val bits = group.indices.fold(0) { acc, i -> acc or (1 shl i) }
        require(Integer.bitCount(bits) == group.indices.size) { "分区引用无效，请重置后重试" }
        require(used and bits == 0) { "分区引用无效，请重置后重试" }
        used = used or bits
    }
    require(used == (1 shl handSize) - 1) { "分区不完整，请重置后重试" }
}
