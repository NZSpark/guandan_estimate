package nz.org.aotearoa.guandanscore.recognition

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * T0.2 补充：2736×3648 输入不再一次性构建 18 份位图。
 *
 * 断言的是“同时存活的临时位图数”，不是系统 RSS；真机峰值内存由 T4.7 用 Debug.MemoryInfo 测量。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageMemoryTest {

    private val phoneWidth = 2736
    private val phoneHeight = 3648

    @Test
    fun `a 2736x3648 photo is processed one temporary bitmap at a time`() {
        val source = Bitmap.createBitmap(phoneWidth, phoneHeight, Bitmap.Config.ARGB_8888)
        val worker = RecognitionWorker(source, maxInFlight = 1)

        var seen = 0
        var maxLive = 0
        while (!worker.isExhausted) {
            val pass = worker.next() ?: break
            seen++
            maxLive = maxOf(maxLive, worker.inFlight)
            assertEquals(1, worker.inFlight)
            // 用完这一份才允许下一份存在。
            worker.release(pass)
            assertEquals(0, worker.inFlight)
        }

        assertEquals(PassPlanner.MAX_PASSES, seen)
        assertEquals(1, maxLive)
        assertEquals(seen, worker.settled)
        assertFalse(source.isRecycled)
        source.recycle()
    }

    @Test
    fun `all passes are recycled so nothing outlives the recognition round`() {
        val source = Bitmap.createBitmap(phoneWidth, phoneHeight, Bitmap.Config.ARGB_8888)
        // 并发上限会阻止 next() 继续返回；这里一次放完，专门验证回收。
        val worker = RecognitionWorker(source, maxInFlight = PassPlanner.MAX_PASSES)
        val passes = generateSequence { worker.next() }.toList()
        assertEquals(PassPlanner.MAX_PASSES, passes.size)

        worker.releaseAll(passes)
        val temporaries = passes.filter { it.plan.kind != PassKind.SOURCE }
        assertEquals(PassPlanner.MAX_PASSES - 1, temporaries.size)
        assertTrue(temporaries.all { it.bitmap.isRecycled })
        assertFalse(source.isRecycled)
        source.recycle()
    }

    @Test
    fun `early stop after 27 cards leaves later passes unrendered`() {
        val source = Bitmap.createBitmap(phoneWidth, phoneHeight, Bitmap.Config.ARGB_8888)
        val worker = RecognitionWorker(source, maxInFlight = 4)

        var found = 0
        var rendered = 0
        while (!worker.shouldStop(found) && !worker.isExhausted) {
            val pass = worker.next() ?: break
            rendered++
            found += 5
            worker.release(pass)
        }

        assertTrue("应在找到 27 张后停止", found >= 27)
        assertTrue("只渲染了 $rendered 个 pass", rendered < PassPlanner.MAX_PASSES)
        source.recycle()
    }
}
