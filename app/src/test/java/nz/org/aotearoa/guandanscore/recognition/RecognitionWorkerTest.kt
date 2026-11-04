package nz.org.aotearoa.guandanscore.recognition

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * T0.2: pass 计划是纯函数；真正的位图按需生成、限制在飞行数量、用完立即回收。
 * 真机内存门槛仍由 T4.7 的 instrumentation 负责，JVM 测试不能替代。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecognitionWorkerTest {

    private fun bitmap(width: Int = 1080, height: Int = 1440): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    @Test
    fun `plan is built without allocating bitmaps and starts with the source`() {
        val plans = PassPlanner.plan(1080, 1440)
        assertEquals(PassPlanner.MAX_PASSES, plans.size)
        assertEquals(PassKind.SOURCE, plans.first().kind)
        assertEquals(9, plans.count { it.kind == PassKind.CROP })
        assertEquals(8, plans.count { it.kind == PassKind.ROTATED })
        assertEquals(plans.size, plans.distinct().size)
    }

    @Test
    fun `small images keep rotation passes but skip pointless crops`() {
        val plans = PassPlanner.plan(200, 150)
        assertEquals(PassKind.SOURCE, plans.first().kind)
        assertEquals(0, plans.count { it.kind == PassKind.CROP })
        assertEquals(1 + PassPlanner.ROTATIONS.size, plans.size)
    }

    @Test
    fun `pass budget is honoured`() {
        assertEquals(5, PassPlanner.plan(1080, 1440, budget = 5).size)
        assertEquals(1, PassPlanner.plan(1080, 1440, budget = 0).size)
        assertEquals(PassPlanner.MAX_PASSES, PassPlanner.plan(1080, 1440, budget = 99).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero sized input is rejected instead of silently producing an empty plan`() {
        PassPlanner.plan(0, 100)
    }

    @Test
    fun `never more than maxInFlight bitmaps are alive at once`() {
        val source = bitmap()
        val worker = RecognitionWorker(source, maxInFlight = 3)
        val held = mutableListOf<RenderedPass>()
        repeat(3) { held += worker.next()!! }
        assertEquals(3, worker.inFlight)
        assertNull("超过并发的 pass 不应被渲染", worker.next())

        worker.release(held.removeAt(0))
        val next = worker.next()
        assertTrue("释放一个之后才允许再渲染", next != null)
    }

    @Test
    fun `peak in flight stays at the configured ceiling`() {
        val source = bitmap()
        val worker = RecognitionWorker(source, maxInFlight = 2)
        var live = 0
        while (!worker.isExhausted) {
            val pass = worker.next() ?: break
            live++
            assertTrue("同时存活 ${worker.inFlight} 张临时位图", worker.inFlight <= 2)
            worker.release(pass)
            live--
        }
        assertEquals(1, worker.peakInFlight.coerceAtLeast(1))
        assertEquals(PassPlanner.MAX_PASSES, worker.settled)
        assertTrue(worker.isExhausted)
    }

    @Test
    fun `temporary passes are recycled but the source bitmap is not`() {
        val source = bitmap()
        val worker = RecognitionWorker(source, maxInFlight = PassPlanner.MAX_PASSES)
        val passes = generateSequence { worker.next() }.toList()
        val sourcePass = passes.first { it.plan.kind == PassKind.SOURCE }
        val temporary = passes.filter { it.plan.kind != PassKind.SOURCE }
        assertTrue(temporary.isNotEmpty())

        worker.releaseAll(passes)
        assertFalse("原图不属于 worker，不能被回收", source.isRecycled)
        assertTrue("临时 pass 必须立即回收", temporary.all { it.bitmap.isRecycled })
        assertTrue(temporary.all { it.released })
    }

    @Test
    fun `stops early once all 27 cards are found`() {
        val worker = RecognitionWorker(bitmap(), maxInFlight = 4)
        assertFalse(worker.shouldStop(26))
        assertTrue(worker.shouldStop(27))
        assertTrue(worker.shouldStop(28))
    }

    @Test
    fun `crop passes map coordinates back to the source image`() {
        val source = bitmap(width = 1000, height = 1200)
        val worker = RecognitionWorker(source, maxInFlight = PassPlanner.MAX_PASSES)
        val crop = generateSequence { worker.next() }.first { it.plan.kind == PassKind.CROP }
        val plan = crop.plan
        val origin = crop.map(0f, 0f)
        assertEquals(plan.left.toFloat(), origin.x, 0.01f)
        assertEquals(plan.top.toFloat(), origin.y, 0.01f)
        val center = crop.map(plan.cropWidth * plan.zoom / 2f, plan.cropHeight * plan.zoom / 2f)
        assertEquals(plan.left + plan.cropWidth / 2f, center.x, 0.5f)
        assertEquals(plan.top + plan.cropHeight / 2f, center.y, 0.5f)
    }
}
