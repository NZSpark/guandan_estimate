package nz.org.aotearoa.guandanscore.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T4.4: heuristic / learned / shadow 的开关语义。
 *
 * shadow 必须“跑模型但不采用结果”，否则调试对照会静默改变正式 UI；这条语义由
 * [EnginePolicy] 单点决定，[CardRecognizer] 只做调用，因此可以脱离 ML Kit 断言。
 */
class RecognitionShadowTest {

    @Test
    fun heuristic_engine_never_runs_the_model() {
        assertFalse(EnginePolicy.runsModel(RecognitionEngine.HEURISTIC))
        assertFalse(EnginePolicy.usesModelResult(RecognitionEngine.HEURISTIC))
    }

    @Test
    fun shadow_runs_the_model_but_never_publishes_its_result() {
        assertTrue(EnginePolicy.runsModel(RecognitionEngine.SHADOW))
        assertFalse(EnginePolicy.usesModelResult(RecognitionEngine.SHADOW))
        assertNull(
            EnginePolicy.fallbackReason(
                RecognitionEngine.SHADOW,
                LearnedOutcome.Unavailable("检测不到牌角"),
            ),
        )
    }

    @Test
    fun learned_engine_adopts_the_model_result() {
        assertTrue(EnginePolicy.runsModel(RecognitionEngine.LEARNED))
        assertTrue(EnginePolicy.usesModelResult(RecognitionEngine.LEARNED))
        assertNull(
            EnginePolicy.fallbackReason(
                RecognitionEngine.LEARNED,
                LearnedOutcome.Success(
                    cards = emptyList(),
                    detections = 0,
                    classified = 0,
                    unknown = 0,
                    merged = 0,
                    elapsedMillis = 1,
                ),
            ),
        )
    }

    @Test
    fun learned_engine_falls_back_with_the_model_reason() {
        assertEquals(
            "推理超时 3000ms",
            EnginePolicy.fallbackReason(
                RecognitionEngine.LEARNED,
                LearnedOutcome.Unavailable("推理超时 3000ms"),
            ),
        )
    }

    @Test
    fun default_engine_stays_heuristic_until_the_model_passes_its_gate() {
        assertEquals(RecognitionEngine.HEURISTIC, RecognitionEngine.entries.first())
    }
}
