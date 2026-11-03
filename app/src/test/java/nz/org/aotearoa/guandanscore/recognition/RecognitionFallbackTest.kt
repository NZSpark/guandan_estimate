package nz.org.aotearoa.guandanscore.recognition

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * T4.6: 模型侧的每一种故障都必须变成 [LearnedOutcome.Unavailable]，
 * 让 [CardRecognizer] 回退到启发式而不是把空结果当成识别完成。
 */
@RunWith(RobolectricTestRunner::class)
class RecognitionFallbackTest {

    private fun bitmap(width: Int = 1280, height: Int = 720): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    private fun uniform(size: Int, winner: Int, winnerValue: Float): FloatArray {
        val rest = (1f - winnerValue) / (size - 1)
        return FloatArray(size) { if (it == winner) winnerValue else rest }
    }

    /** 分类器固定输出：100% 置信的 ♠A，便于只看检测数量对结果的影响。 */
    private fun steadyClassifierRunner(): ModelRunner = ModelRunner {
        listOf(uniform(15, 12, 0.95f), uniform(5, 3, 0.95f))
    }

    private fun detectorRunner(vararg boxes: Pair<FloatArray, Float>): ModelRunner = ModelRunner {
        listOf(obbRaw(anchors = 8, boxes = boxes.toList()))
    }

    @Test
    fun missing_models_are_unavailable_and_never_throw() {
        val outcome = LearnedCardRecognizer(detector = null, classifier = null).recognize(bitmap())
        assertTrue(outcome is LearnedOutcome.Unavailable)
        assertEquals("模型资产不可用", (outcome as LearnedOutcome.Unavailable).reason)
    }

    @Test
    fun detector_exception_is_reported_as_unavailable() {
        val detector = CardCornerDetector(
            runner = { throw IllegalStateException("native crash") },
            manifest = detectorManifest(),
        )
        val classifier = CardCornerClassifier(steadyClassifierRunner(), classifierManifest(temperature = 1f))
        val outcome = LearnedCardRecognizer(detector, classifier).recognize(bitmap())
        assertTrue(outcome is LearnedOutcome.Unavailable)
        assertTrue((outcome as LearnedOutcome.Unavailable).reason.startsWith("模型推理失败"))
    }

    @Test
    fun zero_detections_is_unavailable() {
        val detector = CardCornerDetector(
            runner = { listOf(FloatArray(8 * 6)) },
            manifest = detectorManifest(anchors = 8),
        )
        val classifier = CardCornerClassifier(steadyClassifierRunner(), classifierManifest(temperature = 1f))
        val outcome = LearnedCardRecognizer(detector, classifier).recognize(bitmap())
        assertEquals("检测不到牌角", (outcome as LearnedOutcome.Unavailable).reason)
    }

    @Test
    fun too_few_cards_is_unavailable_with_counts() {
        val detector = CardCornerDetector(
            runner = detectorRunner(floatArrayOf(320f, 300f, 20f, 20f, 0f) to 0.9f),
            manifest = detectorManifest(anchors = 8),
        )
        val classifier = CardCornerClassifier(steadyClassifierRunner(), classifierManifest(temperature = 1f))
        val outcome = LearnedCardRecognizer(detector, classifier, minCards = 5).recognize(bitmap())
        val reason = (outcome as LearnedOutcome.Unavailable).reason
        assertTrue(reason.startsWith("可用牌角不足"))
        assertTrue(reason.contains("1 < 5"))
    }

    @Test
    fun enough_cards_produce_success_with_engine_statistics() {
        val detector = CardCornerDetector(
            runner = detectorRunner(
                floatArrayOf(100f, 300f, 20f, 20f, 0f) to 0.9f,
                floatArrayOf(200f, 300f, 20f, 20f, 0f) to 0.8f,
                floatArrayOf(350f, 300f, 20f, 20f, 0f) to 0.7f,
                floatArrayOf(450f, 300f, 20f, 20f, 0f) to 0.6f,
                floatArrayOf(520f, 300f, 20f, 20f, 0f) to 0.5f,
                floatArrayOf(300f, 200f, 20f, 20f, 0f) to 0.9f,
            ),
            manifest = detectorManifest(anchors = 8),
        )
        val classifier = CardCornerClassifier(steadyClassifierRunner(), classifierManifest(temperature = 1f))
        val outcome = LearnedCardRecognizer(detector, classifier).recognize(bitmap())
        assertTrue(outcome is LearnedOutcome.Success)
        val success = outcome as LearnedOutcome.Success
        assertEquals(6, success.cards.size)
        assertEquals(6, success.detections)
        assertEquals(0, success.unknown)
        assertTrue(success.elapsedMillis >= 0)
    }

    @Test
    fun short_scene_uses_manifest_fallback_and_keeps_known_cards() {
        val detector = CardCornerDetector(
            runner = detectorRunner(
                floatArrayOf(100f, 300f, 20f, 20f, 0f) to 0.9f,
                floatArrayOf(500f, 300f, 20f, 20f, 0f) to 0.4f,
            ),
            manifest = detectorManifest(
                anchors = 8,
                scoreThreshold = 0.6f,
                fallbackScoreThreshold = 0.3f,
            ),
        )
        val classifier = CardCornerClassifier(steadyClassifierRunner(), classifierManifest(temperature = 1f))
        val outcome = LearnedCardRecognizer(detector, classifier, minCards = 1).recognize(bitmap())

        val success = outcome as LearnedOutcome.Success
        assertEquals(2, success.cards.size)
        assertEquals(2, success.detections)
    }

    @Test
    fun adaptive_policy_rejects_duplicate_and_unknown_low_detections() {
        fun classification(unknown: Boolean) = Classification(
            rankIndex = 12,
            suitIndex = 3,
            rank = nz.org.aotearoa.guandanscore.model.Rank.ACE,
            suit = nz.org.aotearoa.guandanscore.model.Suit.SPADE,
            confidence = if (unknown) 0.2f else 0.9f,
            margin = if (unknown) 0.01f else 0.5f,
            unknown = unknown,
        )
        val baseDetection = Detection(CardGeometry.obbQuad(100f, 100f, 20f, 20f, 0f), 0.9f)
        val duplicate = Detection(CardGeometry.obbQuad(100f, 100f, 20f, 20f, 0f), 0.4f)
        val added = Detection(CardGeometry.obbQuad(300f, 100f, 20f, 20f, 0f), 0.35f)
        val rejected = Detection(CardGeometry.obbQuad(500f, 100f, 20f, 20f, 0f), 0.3f)

        val selected = AdaptiveCornerPolicy.supplement(
            base = listOf(baseDetection to classification(unknown = false)),
            low = listOf(duplicate, added, rejected),
            total = 27,
            iouThreshold = LearnedCardRecognizer.MERGE_IOU,
            classify = { candidate -> classification(unknown = candidate === rejected) },
        )

        assertEquals(2, selected.size)
        assertEquals(0.9f, selected[0].first.score, 1e-6f)
        assertEquals(0.35f, selected[1].first.score, 1e-6f)
    }

    @Test
    fun duplicate_detections_are_merged_instead_of_double_counted() {
        val detector = CardCornerDetector(
            runner = detectorRunner(
                floatArrayOf(320f, 300f, 24f, 24f, 0f) to 0.9f,
                floatArrayOf(321f, 300f, 24f, 24f, 0f) to 0.7f,
                floatArrayOf(200f, 300f, 20f, 20f, 0f) to 0.8f,
                floatArrayOf(450f, 300f, 20f, 20f, 0f) to 0.6f,
                floatArrayOf(520f, 250f, 20f, 20f, 0f) to 0.6f,
                floatArrayOf(100f, 200f, 20f, 20f, 0f) to 0.6f,
            ),
            manifest = detectorManifest(anchors = 8, nmsIou = 0.99f),
        )
        val classifier = CardCornerClassifier(steadyClassifierRunner(), classifierManifest(temperature = 1f))
        val outcome = LearnedCardRecognizer(detector, classifier).recognize(bitmap())
        val success = outcome as LearnedOutcome.Success
        assertEquals(1, success.merged)
        assertTrue(success.cards.size < success.detections)
    }
}
