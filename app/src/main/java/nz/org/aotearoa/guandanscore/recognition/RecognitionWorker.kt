package nz.org.aotearoa.guandanscore.recognition

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF

/**
 * T0.2: 识别 pass 的“配方”与懒生成器。
 *
 * 旧实现一次性创建 18 个 Bitmap，再等全部 OCR 返回后才统一回收，2736×3648 输入下峰值内存
 * 由 18 份图决定。现在拆成两层：
 *
 * - [PassPlanner]：纯函数，只按原图尺寸算 pass 列表，不分配任何 Bitmap；
 * - [RecognitionWorker]：按需渲染下一个 pass，调用方用完后立即 [release]，同时限制在飞行中的
 *   pass 数量，峰值临时位图数 = maxInFlight，而不是 18。
 *
 * 找到 27 张后 [shouldStop] 让调用方提前结束，不再渲染剩余 pass。
 */

enum class PassKind { SOURCE, ROTATED, CROP }

/** 一个 pass 的描述；不持有 Bitmap，可安全跨线程传递与单测。 */
data class PassPlan(
    val kind: PassKind,
    val degrees: Float = 0f,
    val left: Int = 0,
    val top: Int = 0,
    val cropWidth: Int = 0,
    val cropHeight: Int = 0,
    val zoom: Float = 1f,
)

object PassPlanner {
    /** 旋转角度：覆盖竖拍/横拍/倒置手牌。 */
    val ROTATIONS = listOf(-60f, -40f, -20f, 20f, 40f, 60f, 90f, 180f)

    /** 局部放大的 3×3 网格步长（相对边长）。 */
    private const val GRID_STEP = 0.225f
    private const val GRID = 3
    private const val CROP_FRACTION = 0.55f

    /** 文档上限：18 个 pass（1 原图 + 8 旋转 + 9 局部）。 */
    const val MAX_PASSES = 18

    /** 小于此边长的小图不再切局部放大：裁切+放大没有新增像素，只会浪费内存。 */
    const val MIN_CROP_EDGE = 320

    fun plan(width: Int, height: Int, budget: Int = MAX_PASSES): List<PassPlan> {
        require(width > 0 && height > 0) { "非法图片尺寸：${width}x$height" }
        val plans = mutableListOf(PassPlan(PassKind.SOURCE))
        ROTATIONS.forEach { plans += PassPlan(PassKind.ROTATED, degrees = it) }
        if (minOf(width, height) >= MIN_CROP_EDGE) {
            val cropWidth = (width * CROP_FRACTION).toInt().coerceAtLeast(1)
            val cropHeight = (height * CROP_FRACTION).toInt().coerceAtLeast(1)
            val zoom = if (minOf(width, height) < 900) 2.4f else 1.8f
            for (row in 0 until GRID) for (column in 0 until GRID) {
                val left = (width * column * GRID_STEP).toInt().coerceIn(0, width - cropWidth)
                val top = (height * row * GRID_STEP).toInt().coerceIn(0, height - cropHeight)
                plans += PassPlan(
                    kind = PassKind.CROP,
                    left = left,
                    top = top,
                    cropWidth = cropWidth,
                    cropHeight = cropHeight,
                    zoom = zoom,
                )
            }
        }
        return plans.take(budget.coerceIn(1, MAX_PASSES))
    }
}

/** 已渲染的 pass：位图 + 坐标映射回原图 + 面积换算系数。 */
class RenderedPass(
    val plan: PassPlan,
    val bitmap: Bitmap,
    private val owned: Boolean,
    private val mapper: (Float, Float) -> PointF,
    val areaScale: Float,
) {
    var released: Boolean = false
        private set

    fun map(x: Float, y: Float): PointF = mapper(x, y)

    /** 临时位图立即回收；原图（SOURCE）不属于本对象，绝不回收。 */
    internal fun release() {
        if (released) return
        released = true
        if (owned && !bitmap.isRecycled) bitmap.recycle()
    }
}

class RecognitionWorker(
    private val source: Bitmap,
    val maxInFlight: Int = 4,
    private val total: Int = 27,
    plans: List<PassPlan> = PassPlanner.plan(source.width, source.height),
) {
    private val lock = Any()
    private val queue = ArrayDeque(plans)
    private var running = 0
    private var releasedCount = 0
    private var peak = 0

    val planned: Int = plans.size
    val inFlight: Int get() = synchronized(lock) { running }
    val settled: Int get() = synchronized(lock) { releasedCount }

    /** 峰值“同时存在”的临时位图数，供 T0.2/内存测试断言。 */
    val peakInFlight: Int get() = synchronized(lock) { peak }

    val isExhausted: Boolean get() = synchronized(lock) { queue.isEmpty() }

    /** 已经找到 27 张牌：剩下的 pass 不再渲染。 */
    fun shouldStop(foundCards: Int): Boolean = foundCards >= total

    fun next(): RenderedPass? = synchronized(lock) {
        if (running >= maxInFlight) return null
        val plan = queue.removeFirstOrNull() ?: return null
        val rendered = try {
            render(plan)
        } catch (error: Throwable) {
            queue.addFirst(plan)
            throw error
        }
        running++
        if (running > peak) peak = running
        rendered
    }

    fun release(pass: RenderedPass) {
        synchronized(lock) {
            pass.release()
            if (running > 0) running--
            releasedCount++
        }
    }

    fun releaseAll(passes: Iterable<RenderedPass>) = passes.forEach(::release)

    private fun render(plan: PassPlan): RenderedPass = when (plan.kind) {
        PassKind.SOURCE -> RenderedPass(plan, source, owned = false, mapper = { x, y -> PointF(x, y) }, areaScale = 1f)

        PassKind.CROP -> {
            val crop = Bitmap.createBitmap(source, plan.left, plan.top, plan.cropWidth, plan.cropHeight)
            val enlarged = Bitmap.createScaledBitmap(
                crop,
                (plan.cropWidth * plan.zoom).toInt(),
                (plan.cropHeight * plan.zoom).toInt(),
                true,
            )
            crop.recycle()
            RenderedPass(
                plan = plan,
                bitmap = enlarged,
                owned = true,
                mapper = { x, y -> PointF(x / plan.zoom + plan.left, y / plan.zoom + plan.top) },
                areaScale = plan.zoom * plan.zoom,
            )
        }

        PassKind.ROTATED -> {
            val transform = Matrix().apply { setRotate(plan.degrees, source.width / 2f, source.height / 2f) }
            val bounds = RectF(0f, 0f, source.width.toFloat(), source.height.toFloat()).also(transform::mapRect)
            transform.postTranslate(-bounds.left, -bounds.top)
            val output = Bitmap.createBitmap(
                bounds.width().toInt() + 1,
                bounds.height().toInt() + 1,
                Bitmap.Config.ARGB_8888,
            )
            Canvas(output).apply {
                drawColor(Color.WHITE)
                drawBitmap(source, transform, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            }
            val inverse = Matrix().also { transform.invert(it) }
            RenderedPass(
                plan = plan,
                bitmap = output,
                owned = true,
                mapper = { x, y ->
                    val points = floatArrayOf(x, y)
                    inverse.mapPoints(points)
                    PointF(points[0], points[1])
                },
                areaScale = 1f,
            )
        }
    }
}
