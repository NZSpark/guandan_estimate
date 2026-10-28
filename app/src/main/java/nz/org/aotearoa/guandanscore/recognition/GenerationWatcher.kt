package nz.org.aotearoa.guandanscore.recognition

/**
 * 纯逻辑的“生成结束”判定器，不依赖 WebView / Android 框架，便于单元测试。
 *
 * 使用方式：发送前用 [AiCardResponseParser] 的调用方记录 [Baseline]；提交后每次轮询把
 * 当前页面快照喂给 [onSample]，根据返回的 [Decision] 决定继续等待、完成还是超时。
 */
class GenerationWatcher(
    private val minStableSamples: Int = 3,
    private val minQuietMillis: Long = 1500,
    private val hardTimeoutMillis: Long = 120_000
) {

    /** 发送前基线。 */
    data class Baseline(
        val responseCount: Int,
        val lastAnswerText: String
    )

    /** 一次页面采样。 */
    data class Sample(
        val responseCount: Int,
        val answerText: String,
        val generating: Boolean = false,
        val pageError: String? = null,
        val timestampMillis: Long
    )

    sealed interface Decision {
        data object Waiting : Decision
        data object Finished : Decision
        data class Failed(val reason: String) : Decision
    }

    private var baseline: Baseline? = null
    private var startedAt: Long = 0
    private var lastText: String? = null
    private var lastChangeAt: Long = 0
    private var stableCount = 0

    fun reset(baseline: Baseline, nowMillis: Long) {
        this.baseline = baseline
        startedAt = nowMillis
        lastText = null
        lastChangeAt = nowMillis
        stableCount = 0
    }

    fun onSample(sample: Sample): Decision {
        val base = baseline ?: return Decision.Failed("尚未设置基线")

        sample.pageError?.let { return Decision.Failed("页面错误：$it") }
        if (sample.timestampMillis - startedAt >= hardTimeoutMillis) {
            return Decision.Failed("等待回答超时")
        }

        val text = sample.answerText.trim()

        // 本轮新回答判定：节点数增加，或文本相对基线发生变化。
        val hasNewNode = sample.responseCount > base.responseCount
        val textChanged = text.isNotEmpty() && text != base.lastAnswerText.trim()
        if (!hasNewNode && !textChanged) {
            // 只有旧答案或 prompt 回显，或回答节点仍为空。
            return Decision.Waiting
        }
        if (text.isEmpty()) {
            // 新节点已出现但正文为空，尚未开始输出。
            return Decision.Waiting
        }

        if (text != lastText) {
            lastText = text
            lastChangeAt = sample.timestampMillis
            stableCount = 1
            return Decision.Waiting
        }

        stableCount++
        val quiet = sample.timestampMillis - lastChangeAt >= minQuietMillis
        // 生成中信号只作辅助：若明确仍在生成且已足够稳定，也继续等待。
        if (sample.generating && !quiet) return Decision.Waiting
        if (stableCount >= minStableSamples && quiet) {
            return Decision.Finished
        }
        return Decision.Waiting
    }
}
