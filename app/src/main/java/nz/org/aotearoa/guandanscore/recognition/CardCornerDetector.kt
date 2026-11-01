package nz.org.aotearoa.guandanscore.recognition

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF

/** 一次模型推理的最小接口；测试用假实现替换真实解释器，避免 JVM 里加载 LiteRT native。 */
fun interface ModelRunner {
    fun run(input: FloatArray): List<FloatArray>
}

/**
 * T4.2: 检测器适配层。
 *
 * 责任划分很窄：把位图 letterbox 成 manifest 指定的输入 → 交给 [ModelRunner] → 用
 * [CardGeometry] 做 OBB 解码、逆映射、过滤和 NMS。位图复用一块目标缓冲，结束后立刻 recycle，
 * 不保留任何全尺寸中间位图。
 */
class CardCornerDetector(
    private val runner: ModelRunner,
    private val manifest: DetectorManifest,
) {
    fun detect(bitmap: Bitmap): List<Detection> {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return emptyList()
        val box = CardGeometry.letterbox(
            bitmap.width,
            bitmap.height,
            manifest.inputWidth,
            manifest.inputHeight,
        )
        val scaled = letterboxBitmap(bitmap, box)
        val pixels = try {
            IntArray(scaled.width * scaled.height).also {
                scaled.getPixels(it, 0, scaled.width, 0, 0, scaled.width, scaled.height)
            }
        } finally {
            if (!scaled.isRecycled && scaled !== bitmap) scaled.recycle()
        }
        val raw = runner.run(CardGeometry.toInputTensor(pixels, manifest.inputLayout)).firstOrNull()
            ?: return emptyList()
        return decode(raw, box, bitmap.width, bitmap.height)
    }

    /** 纯函数部分：原始输出 → 原图坐标下的检测框，可脱离解释器单测。 */
    fun decode(raw: FloatArray, box: Letterbox, sourceWidth: Int, sourceHeight: Int): List<Detection> {
        val mapped = CardGeometry.decodeObb(raw, manifest.anchors, manifest.channelStride, manifest.scoreThreshold)
            .mapNotNull { detection ->
                val quad = CardGeometry.orderQuad(
                    detection.quad.points.map { box.toSource(it.x, it.y) },
                )
                if (CardGeometry.minSide(quad) < manifest.minCornerPx) return@mapNotNull null
                if (CardGeometry.touchesEdge(quad, sourceWidth, sourceHeight, manifest.edgeMarginPx)) {
                    return@mapNotNull null
                }
                Detection(quad, detection.score)
            }
        return CardGeometry.nms(mapped, manifest.nmsIou)
    }

    private fun letterboxBitmap(bitmap: Bitmap, box: Letterbox): Bitmap {
        val target = Bitmap.createBitmap(box.targetWidth, box.targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(target)
        canvas.drawColor(Color.rgb(CardGeometry.PAD, CardGeometry.PAD, CardGeometry.PAD))
        val destination = RectF(
            box.padX,
            box.padY,
            box.padX + box.sourceWidth * box.scale,
            box.padY + box.sourceHeight * box.scale,
        )
        canvas.drawBitmap(
            bitmap,
            Rect(0, 0, bitmap.width, bitmap.height),
            destination,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
        return target
    }
}
