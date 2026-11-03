package nz.org.aotearoa.guandanscore.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class CardGeometryTest {

    @Test
    fun letterbox_centers_wide_image() {
        val box = CardGeometry.letterbox(1440, 1920, 640, 640)
        assertEquals(640f / 1920f, box.scale, 1e-6f)
        assertEquals(80f, box.padX, 1e-3f)
        assertEquals(0f, box.padY, 1e-3f)
    }

    @Test
    fun letterbox_maps_model_and_source_coordinates_both_ways() {
        val box = CardGeometry.letterbox(1280, 720, 640, 640)
        assertEquals(0.5f, box.scale, 1e-6f)
        assertEquals(0f, box.padX, 1e-3f)
        assertEquals(140f, box.padY, 1e-3f)

        val source = box.toSource(box.padX, box.padY)
        assertEquals(0f, source.x, 1e-4f)
        assertEquals(0f, source.y, 1e-4f)

        val target = box.toTarget(640f, 360f)
        assertEquals(320f, target.x, 1e-4f)
        assertEquals(320f, target.y, 1e-4f)
    }

    @Test
    fun obbQuad_returns_tl_tr_br_bl_at_zero_angle() {
        val quad = CardGeometry.obbQuad(cx = 100f, cy = 50f, width = 20f, height = 10f, angle = 0f)
        assertEquals(90f, quad.tl.x, 1e-4f)
        assertEquals(45f, quad.tl.y, 1e-4f)
        assertEquals(110f, quad.tr.x, 1e-4f)
        assertEquals(55f, quad.br.y, 1e-4f)
        assertEquals(90f, quad.bl.x, 1e-4f)
    }

    @Test
    fun obbQuad_rotates_quarter_turn() {
        val quad = CardGeometry.obbQuad(cx = 100f, cy = 50f, width = 20f, height = 10f, angle = (PI / 2).toFloat())
        assertEquals(105f, quad.tl.x, 1e-3f)
        assertEquals(40f, quad.tl.y, 1e-3f)
        assertEquals(60f, quad.tr.y, 1e-3f)
        assertEquals(95f, quad.br.x, 1e-3f)
    }

    @Test
    fun orderQuad_sorts_rows_then_columns() {
        val quad = CardGeometry.orderQuad(
            listOf(Offset(20f, 20f), Offset(10f, 10f), Offset(10f, 20f), Offset(20f, 10f)),
        )
        assertEquals(Offset(10f, 10f), quad.tl)
        assertEquals(Offset(20f, 10f), quad.tr)
        assertEquals(Offset(20f, 20f), quad.br)
        assertEquals(Offset(10f, 20f), quad.bl)
    }

    @Test
    fun minSide_reports_shortest_edge() {
        assertEquals(10f, CardGeometry.minSide(CardGeometry.obbQuad(0f, 0f, 40f, 10f, 0f)), 1e-4f)
    }

    @Test
    fun polygonIoU_handles_identical_disjoint_and_half_overlap() {
        val a = CardGeometry.obbQuad(10f, 10f, 20f, 20f, 0f)
        val identical = CardGeometry.obbQuad(10f, 10f, 20f, 20f, 0f)
        val disjoint = CardGeometry.obbQuad(100f, 100f, 20f, 20f, 0f)
        val halfShift = CardGeometry.obbQuad(20f, 10f, 20f, 20f, 0f)

        assertEquals(1f, CardGeometry.polygonIoU(a, identical), 1e-4f)
        assertEquals(0f, CardGeometry.polygonIoU(a, disjoint), 1e-4f)
        assertEquals(1f / 3f, CardGeometry.polygonIoU(a, halfShift), 1e-3f)
    }

    @Test
    fun nms_keeps_highest_scoring_overlapping_box() {
        val high = Detection(CardGeometry.obbQuad(10f, 10f, 20f, 20f, 0f), 0.9f)
        val low = Detection(CardGeometry.obbQuad(11f, 10f, 20f, 20f, 0f), 0.5f)
        val apart = Detection(CardGeometry.obbQuad(200f, 200f, 20f, 20f, 0f), 0.3f)

        val kept = CardGeometry.nms(listOf(low, apart, high), 0.5f)
        assertEquals(2, kept.size)
        assertEquals(0.9f, kept[0].score, 1e-6f)
        assertEquals(0.3f, kept[1].score, 1e-6f)
    }

    @Test
    fun decodeObb_reads_channel_first_layout_and_drops_low_scores() {
        val raw = obbRaw(
            anchors = 3,
            boxes = listOf(
                floatArrayOf(320f, 320f, 20f, 20f, 0f) to 0.9f,
                floatArrayOf(100f, 100f, 10f, 10f, 0f) to 0.2f,
            ),
        )
        val detections = CardGeometry.decodeObb(raw, anchors = 3, stride = 6, scoreThreshold = 0.4f)
        assertEquals(1, detections.size)
        assertEquals(320f, detections[0].quad.tl.x + 10f, 1e-3f)
        assertEquals(0.9f, detections[0].score, 1e-6f)
    }

    @Test
    fun decodeObb_reads_angle_after_score_channel() {
        val raw = obbRaw(
            anchors = 1,
            boxes = listOf(floatArrayOf(100f, 50f, 20f, 10f, (PI / 2).toFloat()) to 0.9f),
        )
        val detections = CardGeometry.decodeObb(raw, anchors = 1, stride = 6, scoreThreshold = 0.4f)
        assertEquals(1, detections.size)
        assertEquals(0.9f, detections[0].score, 1e-6f)
        assertTrue(
            detections[0].quad.points.any { kotlin.math.abs(it.x - 105f) < 1e-3f && kotlin.math.abs(it.y - 40f) < 1e-3f },
        )
    }

    @Test
    fun decodeObb_ignores_non_positive_extent() {
        val raw = obbRaw(anchors = 1, boxes = listOf(floatArrayOf(10f, 10f, 0f, 0f, 0f) to 0.9f))
        assertTrue(CardGeometry.decodeObb(raw, anchors = 1, stride = 6, scoreThreshold = 0.1f).isEmpty())
    }

    @Test
    fun toInputTensor_matches_requested_layout() {
        val red = 0xFFFF0000.toInt()
        val nhwc = CardGeometry.toInputTensor(intArrayOf(red), TensorLayout.NHWC)
        assertEquals(1f, nhwc[0], 1e-6f)
        assertEquals(0f, nhwc[1], 1e-6f)
        assertEquals(0f, nhwc[2], 1e-6f)

        // 像素 0 是纯红、像素 1 是纯绿：通道平面依次为 R=[1,0]、G=[0,1]、B=[0,0]。
        val two = intArrayOf(red, 0xFF00FF00.toInt())
        val nchw = CardGeometry.toInputTensor(two, TensorLayout.NCHW)
        assertEquals(1f, nchw[0], 1e-6f)
        assertEquals(0f, nchw[1], 1e-6f)
        assertEquals(0f, nchw[2], 1e-6f)
        assertEquals(1f, nchw[3], 1e-6f)
        assertEquals(0f, nchw[4], 1e-6f)
        assertEquals(0f, nchw[5], 1e-6f)
    }

    @Test
    fun touchesEdge_flags_boxes_on_the_border() {
        val edge = CardGeometry.obbQuad(1f, 1f, 2f, 2f, 0f)
        val inside = CardGeometry.obbQuad(50f, 50f, 10f, 10f, 0f)
        assertTrue(CardGeometry.touchesEdge(edge, 100, 100, 1))
        assertFalse(CardGeometry.touchesEdge(inside, 100, 100, 1))
    }
}
