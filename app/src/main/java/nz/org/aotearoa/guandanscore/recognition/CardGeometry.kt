package nz.org.aotearoa.guandanscore.recognition

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * T4.2: 检测后处理所需的纯几何函数。
 *
 * 这里刻意不 import 任何 Android 类型：letterbox 比例、OBB 解码、多边形 IoU、NMS、
 * 逆坐标映射都能在 JVM 单测里逐个断言，与 `model_training/predict.py` 的算法一一对应。
 * 设备上的位图缩放与解释器调用放在 [CardCornerDetector]。
 */
data class Offset(val x: Float, val y: Float)

/** 四点按 TL,TR,BR,BL 排列——与 `prepare_crops.order_quad` 的顺序一致。 */
data class Quad(val tl: Offset, val tr: Offset, val br: Offset, val bl: Offset) {
    val points: List<Offset> get() = listOf(tl, tr, br, bl)
}

data class Letterbox(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val targetWidth: Int,
    val targetHeight: Int,
    val scale: Float,
    val padX: Float,
    val padY: Float,
) {
    /** 模型坐标 → 原图坐标。 */
    fun toSource(x: Float, y: Float): Offset = Offset((x - padX) / scale, (y - padY) / scale)

    /** 原图坐标 → 模型坐标。 */
    fun toTarget(x: Float, y: Float): Offset = Offset(x * scale + padX, y * scale + padY)
}

data class Detection(val quad: Quad, val score: Float)

object CardGeometry {
    /** ultralytics letterbox 的填充值（灰）。 */
    const val PAD: Int = 114

    /** 等比缩放 + 居中填充，返回缩放与偏移；小图不放大以外的一切都与 ultralytics 一致。 */
    fun letterbox(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): Letterbox {
        require(sourceWidth > 0 && sourceHeight > 0) { "非法源尺寸：${sourceWidth}x$sourceHeight" }
        require(targetWidth > 0 && targetHeight > 0) { "非法目标尺寸：${targetWidth}x$targetHeight" }
        val scale = min(targetWidth.toFloat() / sourceWidth, targetHeight.toFloat() / sourceHeight)
        val padX = (targetWidth - sourceWidth * scale) / 2f
        val padY = (targetHeight - sourceHeight * scale) / 2f
        return Letterbox(sourceWidth, sourceHeight, targetWidth, targetHeight, scale, padX, padY)
    }

    /**
     * OBB 参数 → 四点，公式与 ultralytics `xywhr2xyxyxyxy` 相同：
     * 角度为 0 时返回 TL,TR,BR,BL。
     */
    fun obbQuad(cx: Float, cy: Float, width: Float, height: Float, angle: Float): Quad {
        val cosA = cos(angle)
        val sinA = sin(angle)
        val halfW = width / 2f
        val halfH = height / 2f
        fun corner(signW: Float, signH: Float) = Offset(
            cx + signW * halfW * cosA - signH * halfH * sinA,
            cy + signW * halfW * sinA + signH * halfH * cosA,
        )
        return Quad(corner(-1f, -1f), corner(1f, -1f), corner(1f, 1f), corner(-1f, 1f))
    }

    /** 与 `prepare_crops.order_quad` 相同的排序：先按 y 分上下两组，组内按 x。 */
    fun orderQuad(points: List<Offset>): Quad {
        require(points.size == 4) { "四点数量不是 4：${points.size}" }
        val byY = points.sortedWith(compareBy({ it.y }, { it.x }))
        val top = byY.take(2).sortedBy { it.x }
        val bottom = byY.drop(2).sortedBy { it.x }
        return Quad(top[0], top[1], bottom[1], bottom[0])
    }

    /** 四边形最短边；用于过滤过小的牌角。 */
    fun minSide(quad: Quad): Float {
        val p = quad.points
        var shortest = Float.MAX_VALUE
        for (index in p.indices) {
            val a = p[index]
            val b = p[(index + 1) % p.size]
            shortest = min(shortest, hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat())
        }
        return shortest
    }

    fun polygonArea(points: List<Offset>): Float {
        if (points.size < 3) return 0f
        var total = 0f
        for (index in points.indices) {
            val a = points[index]
            val b = points[(index + 1) % points.size]
            total += a.x * b.y - b.x * a.y
        }
        return abs(total) / 2f
    }

    /** 凸多边形 IoU（Sutherland–Hodgman 裁剪），与 `predict.polygon_iou` 同算法。 */
    fun polygonIoU(a: Quad, b: Quad): Float {
        val subject = a.points
        val clip = b.points
        var output = subject
        for (index in clip.indices) {
            if (output.isEmpty()) return 0f
            val ax = clip[index].x
            val ay = clip[index].y
            val bx = clip[(index + 1) % clip.size].x
            val by = clip[(index + 1) % clip.size].y
            fun side(px: Float, py: Float) = (bx - ax) * (py - ay) - (by - ay) * (px - ax)
            val incoming = output
            output = mutableListOf()
            for (j in incoming.indices) {
                val current = incoming[j]
                val next = incoming[(j + 1) % incoming.size]
                val s1 = side(current.x, current.y)
                val s2 = side(next.x, next.y)
                if (s1 >= 0) output.add(current)
                if ((s1 >= 0) != (s2 >= 0)) {
                    val denominator = s1 - s2
                    if (abs(denominator) > 1e-12f) {
                        val t = s1 / denominator
                        output.add(Offset(current.x + (next.x - current.x) * t, current.y + (next.y - current.y) * t))
                    }
                }
            }
        }
        val intersection = if (output.size >= 3) polygonArea(output) else 0f
        val union = polygonArea(subject) + polygonArea(clip) - intersection
        return if (union > 1e-9f) intersection / union else 0f
    }

    /**
     * 贪心 NMS：分数降序保留，IoU ≥ 阈值的后续框被抑制。
     * 与 ultralytics 的 probiou NMS 不完全等价（这里是多边形 IoU），但语义相同且可单测。
     */
    fun nms(detections: List<Detection>, iouThreshold: Float): List<Detection> {
        val sorted = detections.sortedWith(compareByDescending { it.score })
        val kept = mutableListOf<Detection>()
        for (candidate in sorted) {
            val suppressed = kept.any { polygonIoU(it.quad, candidate.quad) >= iouThreshold }
            if (!suppressed) kept.add(candidate)
        }
        return kept
    }

    /** 四点任意一点落在图像边缘安全区内即视为贴边，按规范丢弃。 */
    fun touchesEdge(quad: Quad, width: Int, height: Int, margin: Int): Boolean =
        quad.points.any {
            it.x <= margin.toFloat() || it.y <= margin.toFloat() ||
                it.x >= width - 1 - margin || it.y >= height - 1 - margin
        }

    /**
     * 解码 `[stride][anchors]` 的原始输出：前 4 项 cx,cy,w,h，第 5 项 score，第 6 项 angle。
     * 通道优先（`raw[channel * anchors + anchor]`），与 LiteRT 导出的 `[1,6,18900]` 一致。
     * 注意：导出顺序是 score 在 angle 前；把两者对调会把角度当置信度，产生大量误检。
     */
    fun decodeObb(raw: FloatArray, anchors: Int, stride: Int, scoreThreshold: Float): List<Detection> {
        if (anchors <= 0 || stride < 6) return emptyList()
        if (raw.size < anchors * stride) return emptyList()
        val results = mutableListOf<Detection>()
        for (anchor in 0 until anchors) {
            val score = raw[4 * anchors + anchor]
            if (!(score >= scoreThreshold)) continue
            val cx = raw[anchor]
            val cy = raw[anchors + anchor]
            val width = raw[2 * anchors + anchor]
            val height = raw[3 * anchors + anchor]
            val angle = raw[5 * anchors + anchor]
            if (!(width > 0f) || !(height > 0f)) continue
            results.add(Detection(obbQuad(cx, cy, width, height, angle), score))
        }
        return results
    }

    /**
     * ARGB 像素 → 模型输入张量（RGB / 255）。
     * 检测器导出为 NCHW，分类器为 NHWC，因此两种布局都要支持。
     */
    fun toInputTensor(pixels: IntArray, layout: TensorLayout): FloatArray {
        val count = pixels.size
        return when (layout) {
            TensorLayout.NHWC -> FloatArray(count * 3).also { output ->
                for (index in 0 until count) {
                    val pixel = pixels[index]
                    output[index * 3] = ((pixel shr 16) and 0xFF) / 255f
                    output[index * 3 + 1] = ((pixel shr 8) and 0xFF) / 255f
                    output[index * 3 + 2] = (pixel and 0xFF) / 255f
                }
            }

            TensorLayout.NCHW -> FloatArray(count * 3).also { output ->
                for (index in 0 until count) {
                    val pixel = pixels[index]
                    output[index] = ((pixel shr 16) and 0xFF) / 255f
                    output[count + index] = ((pixel shr 8) and 0xFF) / 255f
                    output[count * 2 + index] = (pixel and 0xFF) / 255f
                }
            }
        }
    }

    /** 诊断用：四点的轴对齐包围盒。 */
    fun bounds(quad: Quad): Pair<Float, Float> {
        val xs = quad.points.map { it.x }
        val ys = quad.points.map { it.y }
        return max(abs(xs.max() - xs.min()), abs(ys.max() - ys.min())) to minSide(quad)
    }
}
