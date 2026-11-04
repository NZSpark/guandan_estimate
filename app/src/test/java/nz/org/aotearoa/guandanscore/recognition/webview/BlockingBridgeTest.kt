package nz.org.aotearoa.guandanscore.recognition.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** [BlockingBridge] 的线程规则单测（AI-023）。不需要 Android 运行时。 */
class BlockingBridgeTest {

    @Test
    fun workerThreadIsAllowedAndReturnsValue() {
        val value = BlockingBridge.awaitBlocking("test", onMain = { false }) { 42 }
        assertEquals(42, value)
    }

    @Test
    fun mainThreadIsRejectedBeforeBlocking() {
        var blockRan = false
        val error = assertThrows(IllegalStateException::class.java) {
            BlockingBridge.awaitBlocking("PageDriver.evaluate", onMain = { true }) {
                blockRan = true
                "unreachable"
            }
        }
        // 关键：不是“跑了然后失败”，而是根本不进入挂起块 —— 说明没有把主线程堵住。
        assertTrue("suspend block must not start on main thread", !blockRan)
        assertTrue(error.message!!.contains("AI-023"))
        assertTrue(error.message!!.contains("PageDriver.evaluate"))
    }

    @Test
    fun suspendExceptionPropagates() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            BlockingBridge.awaitBlocking("test", onMain = { false }) {
                throw IllegalArgumentException("boom")
            }
        }
        assertEquals("boom", error.message)
    }
}
