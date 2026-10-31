package nz.org.aotearoa.guandanscore.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import nz.org.aotearoa.guandanscore.databinding.ActivityMeldAdjustBinding
import nz.org.aotearoa.guandanscore.databinding.ItemMeldGroupBinding
import nz.org.aotearoa.guandanscore.scoring.Meld
import nz.org.aotearoa.guandanscore.scoring.MeldGroup
import nz.org.aotearoa.guandanscore.scoring.MeldType
import java.util.Locale

/**
 * 手工拆牌页（doc/patterns_design.md）。
 *
 * 页面只编辑分区、只渲染 PartitionEvaluator 的结果；不搜索最优、不复制计分公式。
 * 左侧「最优拆牌」来自进入页面的只读快照，右侧「手工拆牌」随当前分区实时变化。
 */
class MeldAdjustActivity : AppCompatActivity() {
    private lateinit var ui: ActivityMeldAdjustBinding
    private lateinit var state: MeldAdjustState
    private lateinit var entryPartition: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityMeldAdjustBinding.inflate(layoutInflater)
        setContentView(ui.root)

        val partitionRaw = intent.getStringExtra(PartitionCodec.EXTRA_PARTITION)
        val baseRaw = intent.getStringExtra(PartitionCodec.EXTRA_BASE)
        if (partitionRaw.isNullOrBlank() || baseRaw.isNullOrBlank()) {
            toastAndFinish("手工拆牌数据缺失")
            return
        }
        val payload = runCatching { PartitionCodec.decodePartition(partitionRaw) }.getOrElse {
            toastAndFinish(it.message ?: "手工拆牌数据无效")
            return
        }
        entryPartition = partitionRaw
        val base = runCatching { PartitionCodec.decodeSnapshot(baseRaw) }.getOrElse {
            toastAndFinish(it.message ?: "最优摘要无效")
            return
        }
        state = runCatching {
            MeldAdjustEditor.initial(payload.hand, payload.level, base, payload.groups)
        }.getOrElse {
            toastAndFinish(it.message ?: "分区无效")
            return
        }

        restoreDraft(savedInstanceState)
        bindActions()
        onBackPressedDispatcher.addCallback(this) { confirmExit() }
        render()
    }

    private fun bindActions() {
        ui.back.setOnClickListener { confirmExit() }
        ui.undo.setOnClickListener {
            state = MeldAdjustEditor.undo(state)
            render()
        }
        ui.reset.setOnClickListener { confirmReset() }
        ui.mergeSelected.setOnClickListener { showMergeCandidates() }
        ui.apply.setOnClickListener { applyAndFinish() }
    }

    private fun render() {
        val result = state.result
        val base = state.base
        ui.optimalTitle.text = ScoreText.optimalTitle(base.exhaustive)
        ui.optimalGrade.text = base.grade
        ui.optimalSpi.text = String.format(Locale.US, "%.2f", base.spi)
        ui.optimalNspi.text = "NSPI " + String.format(Locale.US, "%.1f", base.nspi)
        ui.optimalNumerator.text = "分子 " + String.format(Locale.US, "%.1f", base.numerator)
        ui.optimalRounds.text = "${base.rounds} 轮"

        ui.manualGrade.text = result.grade
        ui.manualSpi.text = String.format(Locale.US, "%.2f", result.spi)
        ui.manualNspi.text = "NSPI " + String.format(Locale.US, "%.1f", result.nspi)
        ui.manualNumerator.text = "分子 " + String.format(Locale.US, "%.1f", result.numerator)
        ui.manualRounds.text = "${result.rounds} 轮"
        ui.delta.text = ScoreText.delta(result.spi - base.spi, base.rounds, result.rounds)

        ui.groupList.removeAllViews()
        // 列表按分值降序展示，但选择/编辑仍使用 groups 的真实下标。
        state.groups.indices.sortedByDescending { result.melds.getOrNull(it)?.score ?: 0 }.forEach { index ->
            val row = ItemMeldGroupBinding.inflate(layoutInflater, ui.groupList, false)
            bindGroupRow(row, index, state.groups[index], result.melds.getOrNull(index))
            ui.groupList.addView(row.root)
        }
        updateFooter()
    }

    private fun bindGroupRow(
        row: ItemMeldGroupBinding,
        index: Int,
        group: MeldGroup,
        meld: Meld?,
    ) {
        row.groupTitle.text = "${group.type.title} · ${group.indices.size} 张"
        row.groupScore.text = meld?.score?.toString().orEmpty()
        val cards = group.indices.joinToString(" ") { state.hand.getOrNull(it)?.display ?: "?" }
        row.groupCards.text = cards
        // 勾选由 state.selection 唯一维护；关闭 View 自带状态恢复，
        // 否则 Activity 重建后框架会用旧视图状态覆盖刚恢复的勾选（onRestoreInstanceState 晚于 onCreate）。
        row.select.isSaveEnabled = false
        row.select.isChecked = index in state.selection
        row.select.setOnCheckedChangeListener { _, checked ->
            state = if (checked) {
                state.copy(selection = state.selection + index)
            } else {
                state.copy(selection = state.selection - index)
            }
            updateFooter()
        }
        row.root.setOnClickListener { showGroupActions(index) }
        row.split.isEnabled = group.indices.size >= 2
        row.split.setOnClickListener { showSplitDialog(index) }
    }

    private fun updateFooter() {

        ui.undo.isEnabled = state.undo.isNotEmpty()
        ui.mergeSelected.text = "合并所选 (${state.selection.size})"
        val candidates = MeldAdjustEditor.mergeCandidates(state)
        ui.mergeSelected.isEnabled = candidates.isNotEmpty()
        ui.mergeHint.visibility = when {
            state.selection.size >= 2 && candidates.isEmpty() -> View.VISIBLE
            state.selection.size == 1 -> View.VISIBLE
            else -> View.GONE
        }
        ui.mergeHint.text = when {
            state.selection.size >= 2 && candidates.isEmpty() -> "所选牌无法组成合法牌型"
            state.selection.size == 1 -> "至少选择两组才能合并"
            else -> ""
        }
        ui.apply.isEnabled = state.dirty
        ui.apply.text = if (state.dirty) "应用结果" else "尚未修改"
    }

    private fun showSplitDialog(index: Int) {
        val group = state.groups.getOrNull(index) ?: return
        if (group.indices.size < 2) {
            toast("单张无法继续拆分")
            return
        }
        val labels = mutableListOf("拆成单张")
        val actions = mutableListOf<() -> Unit>({ applyOutcome(MeldAdjustEditor.split(state, index, SplitMode.SINGLES)) })
        val preset = MeldAdjustEditor.presetPreview(state, index)
        if (preset != null && preset.any { it.type != MeldType.SINGLE }) {
            labels += "拆成基础组"
            actions += { applyOutcome(MeldAdjustEditor.split(state, index, SplitMode.PRESET)) }
        }

        AlertDialog.Builder(this)
            .setTitle("拆分 · ${group.type.title}")
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showGroupActions(index: Int) {
        val group = state.groups.getOrNull(index) ?: return
        val alternatives = MeldAdjustEditor.interpretationsFor(state, index)
            .filter { it.type != group.type }
            .distinctBy { it.type }
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        alternatives.forEach { meld ->
            labels += "改判为 ${meld.type.title} · ${meld.score}"
            actions += { applyOutcome(MeldAdjustEditor.reinterpret(state, index, meld.type)) }
        }
        if (group.indices.size >= 2) {
            labels += "拆成单张"
            actions += { applyOutcome(MeldAdjustEditor.split(state, index, SplitMode.SINGLES)) }
        }
        if (labels.isEmpty()) {
            toast("该组没有可用的改判或拆分")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("${group.type.title} · ${group.indices.size} 张")
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showMergeCandidates() {
        val candidates = MeldAdjustEditor.mergeCandidates(state)
        when {
            candidates.isEmpty() -> toast("所选牌无法组成合法牌型")
            candidates.size == 1 -> applyOutcome(MeldAdjustEditor.merge(state, candidates.single()))
            else -> AlertDialog.Builder(this)
                .setTitle("选择合并后的牌型")
                .setItems(candidates.map { "${it.type.title} · ${it.score}" }.toTypedArray()) { _, which ->
                    applyOutcome(MeldAdjustEditor.merge(state, candidates[which]))
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun applyOutcome(outcome: MeldEditOutcome) {
        when (outcome) {
            is MeldEditOutcome.Ok -> {
                state = outcome.state
                render()
            }
            is MeldEditOutcome.Rejected -> toast(outcome.message)
        }
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("重置手工拆牌？")
            .setMessage("将回到进入页面时的分区，当前修改与撤销记录会丢失。")
            .setPositiveButton("重置") { _, _ ->
                state = MeldAdjustEditor.reset(state)
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmExit() {
        if (!state.dirty) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("放弃本次手工拆牌？")
            .setPositiveButton("放弃") { _, _ -> finish() }
            // 「保留」在架构里只有一种落地方式：把当前分区作为结果交回主界面。
            .setNegativeButton("保留并返回") { _, _ -> applyAndFinish() }
            .show()
    }

    private fun applyAndFinish() {
        if (!state.dirty) {
            finish()
            return
        }
        val data = Intent()
            .putExtra(PartitionCodec.EXTRA_GROUPS, PartitionCodec.encodeGroups(state.groups))
            // 原样回传入口校验过的手牌/级牌，主界面据此拒绝张数或牌面被替换的结果。
            .putExtra(PartitionCodec.EXTRA_PARTITION, entryPartition)
        setResult(RESULT_OK, data)
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!::state.isInitialized) return
        outState.putString(KEY_GROUPS, PartitionCodec.encodeGroups(state.groups))
        outState.putIntegerArrayList(KEY_SELECTION, ArrayList(state.selection))
    }

    private fun restoreDraft(savedInstanceState: Bundle?) {
        val raw = savedInstanceState?.getString(KEY_GROUPS) ?: return
        val selection = savedInstanceState.getIntegerArrayList(KEY_SELECTION)?.toSet().orEmpty()
        when (val outcome = MeldAdjustEditor.restoreDraft(state, raw, selection)) {
            is MeldEditOutcome.Ok -> state = outcome.state
            is MeldEditOutcome.Rejected -> toast("手工拆牌草稿已失效，已恢复初始方案")
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun toastAndFinish(message: String) {
        toast(message)
        finish()
    }

    private companion object {
        const val KEY_GROUPS = "meld_adjust.groups"
        const val KEY_SELECTION = "meld_adjust.selection"
    }
}
