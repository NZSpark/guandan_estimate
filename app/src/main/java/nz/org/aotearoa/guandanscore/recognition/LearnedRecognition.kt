package nz.org.aotearoa.guandanscore.recognition

import android.graphics.Bitmap
import nz.org.aotearoa.guandanscore.model.Card

/**
 * T4.4: 三条识别路径的开关。默认是 [LEARNED]（ADR-0044）；模型加载或推理失败时
 * 由 [CardRecognizer] 回退到 [HEURISTIC]。
 */
enum class RecognitionEngine {
    HEURISTIC,
    LEARNED,
    SHADOW;

    companion object {
        /** 生产默认识别引擎；测试/诊断仍可显式覆盖。 */
        val DEFAULT = RecognitionEngine.LEARNED
    }
}

/**
 * T4.4: 引擎开关的唯一判定点。
 *
 * `runsModel` 决定是否执行模型；`usesModelResult` 决定模型结果能否进入 UI。
 * SHADOW 两者分开：跑但不采用，否则 shadow 会静默改变正式结果。
 */
object EnginePolicy {
    fun runsModel(engine: RecognitionEngine): Boolean = engine != RecognitionEngine.HEURISTIC

    fun usesModelResult(engine: RecognitionEngine): Boolean = engine == RecognitionEngine.LEARNED

    /** 仅当引擎本应采用模型结果而模型失败时返回原因。 */
    fun fallbackReason(engine: RecognitionEngine, outcome: LearnedOutcome): String? {
        if (!usesModelResult(engine)) return null
        return (outcome as? LearnedOutcome.Unavailable)?.reason
    }
}

sealed interface LearnedOutcome {
    data class Success(
        val cards: List<Card>,
        val detections: Int,
        val classified: Int,
        val unknown: Int,
        val merged: Int,
        val elapsedMillis: Long,
    ) : LearnedOutcome

    data class Unavailable(val reason: String) : LearnedOutcome
}

/**
 * T4.4/T4.6: 模型路径的编排。
 *
 * 只做三件事：检测牌角 → 逐角分类 → 合并去重。任何一步失败（资产缺失、推理异常、
 * 牌角太少）都返回 [LearnedOutcome.Unavailable]，由 [CardRecognizer] 回退到启发式，
 * 绝不把空结果当成“识别完成”。
 */
class LearnedCardRecognizer(
    private val detector: CardCornerDetector?,
    private val classifier: CardCornerClassifier?,
    private val minCards: Int = 5,
    private val total: Int = 27,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val ready: Boolean get() = detector != null && classifier != null

    fun recognize(bitmap: Bitmap): LearnedOutcome {
        val detector = detector
        val classifier = classifier
        if (detector == null || classifier == null) {
            return LearnedOutcome.Unavailable("模型资产不可用")
        }
        val started = clock()
        return try {
            val baseDetections = detector.detect(bitmap)
            val baseClassified = baseDetections.map { detection ->
                detection to classifier.classify(bitmap, detection.quad)
            }
            // 冻结策略：基础阈值不足 27 个候选时，才用 manifest 的低阈值补漏；
            // 补入框不得与已有框重叠，且必须被分类器判为 known。
            val classified = detector.fallbackScoreThreshold?.let { fallback ->
                if (baseDetections.size >= total) {
                    baseClassified
                } else {
                    val lowDetections = detector.detect(bitmap, fallback)
                    AdaptiveCornerPolicy.supplement(
                        base = baseClassified,
                        low = lowDetections,
                        total = total,
                        iouThreshold = MERGE_IOU,
                        classify = { candidate -> classifier.classify(bitmap, candidate.quad) },
                    )
                }
            } ?: baseClassified
            val detections = classified.map { it.first }
            if (detections.isEmpty()) {
                return LearnedOutcome.Unavailable("检测不到牌角")
            }
            val unknown = classified.count { it.second.unknown }
            val clusters = mutableListOf<Pair<Detection, Classification>>()
            var merged = 0
            for (entry in classified.sortedByDescending { it.second.confidence }) {
                val duplicate = clusters.any { CardGeometry.polygonIoU(it.first.quad, entry.first.quad) >= MERGE_IOU }
                if (duplicate) merged++ else clusters.add(entry)
            }
            val cards = clusters
                .filterNot { it.second.unknown }
                .sortedWith(compareBy({ it.first.quad.tl.y }, { it.first.quad.tl.x }))
                .take(total)
                .mapIndexed { index, entry -> Card(index, entry.second.rank, entry.second.suit) }
            if (cards.size < minCards) {
                return LearnedOutcome.Unavailable("可用牌角不足：${cards.size} < $minCards（检测 ${detections.size}）")
            }
            LearnedOutcome.Success(
                cards = cards,
                detections = detections.size,
                classified = classified.size,
                unknown = unknown,
                merged = merged,
                elapsedMillis = clock() - started,
            )
        } catch (error: Throwable) {
            LearnedOutcome.Unavailable("模型推理失败：${error.javaClass.simpleName}: ${error.message}")
        }
    }

    companion object {
        /** 两个检测框 IoU 超过该值即视为同一张牌（旋转/局部重复）。 */
        const val MERGE_IOU = 0.5f
    }
}

/**
 * 冻结的短场景补漏策略（与 `learned_benchmark.py --adaptive` 一致）：
 *
 * - 只在基础阈值候选少于 27 时启用；
 * - 只补与现有框 IoU < [iouThreshold] 的新框；
 * - 低阈值框先分类，unknown 直接丢弃，不进入 UI；
 * - 补入后仍最多保留 [total] 个候选。
 *
 * 这个对象不依赖 Android，JVM 测试可以直接验证去重、known 过滤和数量上限。
 */
internal object AdaptiveCornerPolicy {
    fun supplement(
        base: List<Pair<Detection, Classification>>,
        low: List<Detection>,
        total: Int,
        iouThreshold: Float,
        classify: (Detection) -> Classification,
    ): List<Pair<Detection, Classification>> {
        if (base.size >= total) return base
        val combined = base.toMutableList()
        for (candidate in low.sortedByDescending { it.score }) {
            if (combined.any { (existing, _) ->
                    CardGeometry.polygonIoU(existing.quad, candidate.quad) >= iouThreshold
                }
            ) {
                continue
            }
            val classification = classify(candidate)
            if (classification.unknown) continue
            combined += candidate to classification
        }
        // 与桌面 benchmark 一致：补漏完成后按 detector score 取前 27 个。
        return combined.sortedByDescending { it.first.score }.take(total)
    }
}
