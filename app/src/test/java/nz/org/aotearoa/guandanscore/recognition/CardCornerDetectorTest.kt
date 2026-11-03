package nz.org.aotearoa.guandanscore.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM 部分只覆盖解码、逆映射、过滤和 NMS；真实推理证据需要设备上的 instrumentation 测试。 */
class CardCornerDetectorTest {

    private fun detector(
        minCornerPx: Float = 4f,
        edgeMarginPx: Int = 1,
        scoreThreshold: Float = 0.4f,
    ) = CardCornerDetector(
        runner = { emptyList() },
        manifest = detectorManifest(
            anchors = 4,
            minCornerPx = minCornerPx,
            edgeMarginPx = edgeMarginPx,
            scoreThreshold = scoreThreshold,
        ),
    )

    @Test
    fun decode_maps_model_space_back_to_source_pixels() {
        val box = CardGeometry.letterbox(1280, 720, 640, 640)
        val raw = obbRaw(anchors = 4, boxes = listOf(floatArrayOf(320f, 320f, 20f, 20f, 0f) to 0.9f))

        val detections = detector().decode(raw, box, sourceWidth = 1280, sourceHeight = 720)

        assertEquals(1, detections.size)
        assertEquals(620f, detections[0].quad.tl.x, 1e-3f)
        assertEquals(340f, detections[0].quad.tl.y, 1e-3f)
        assertEquals(660f, detections[0].quad.br.x, 1e-3f)
    }

    @Test
    fun decode_drops_boxes_smaller_than_manifest_threshold() {
        val box = CardGeometry.letterbox(1280, 720, 640, 640)
        val raw = obbRaw(anchors = 4, boxes = listOf(floatArrayOf(320f, 320f, 20f, 20f, 0f) to 0.9f))
        assertTrue(detector(minCornerPx = 100f).decode(raw, box, 1280, 720).isEmpty())
    }

    @Test
    fun decode_drops_boxes_touching_the_border() {
        val box = CardGeometry.letterbox(1280, 720, 640, 640)
        val raw = obbRaw(anchors = 4, boxes = listOf(floatArrayOf(3f, 3f, 6f, 6f, 0f) to 0.9f))
        assertTrue(detector(edgeMarginPx = 1).decode(raw, box, 1280, 720).isEmpty())
    }

    @Test
    fun decode_applies_nms_to_duplicate_detections() {
        val box = CardGeometry.letterbox(1280, 720, 640, 640)
        val raw = obbRaw(
            anchors = 4,
            boxes = listOf(
                floatArrayOf(320f, 320f, 30f, 30f, 0f) to 0.9f,
                floatArrayOf(320f, 320f, 30f, 30f, 0f) to 0.6f,
            ),
        )
        val detections = detector().decode(raw, box, 1280, 720)
        assertEquals(1, detections.size)
        assertEquals(0.9f, detections[0].score, 1e-6f)
    }

    @Test
    fun decode_accepts_an_explicit_fallback_threshold() {
        val box = CardGeometry.letterbox(1280, 720, 640, 640)
        val raw = obbRaw(anchors = 4, boxes = listOf(floatArrayOf(320f, 320f, 20f, 20f, 0f) to 0.4f))

        assertTrue(detector(scoreThreshold = 0.5f).decode(raw, box, 1280, 720).isEmpty())
        assertEquals(1, detector(scoreThreshold = 0.5f).decode(raw, box, 1280, 720, 0.3f).size)
    }

    @Test
    fun decode_is_empty_when_threshold_filters_everything() {
        val box = CardGeometry.letterbox(1280, 720, 640, 640)
        val raw = obbRaw(anchors = 4, boxes = listOf(floatArrayOf(320f, 320f, 20f, 20f, 0f) to 0.3f))
        assertTrue(detector(scoreThreshold = 0.5f).decode(raw, box, 1280, 720).isEmpty())
    }

    @Test
    fun decode_tolerates_malformed_raw_output() {
        val box = CardGeometry.letterbox(1280, 720, 640, 640)
        assertTrue(detector().decode(FloatArray(3), box, 1280, 720).isEmpty())
    }
}
