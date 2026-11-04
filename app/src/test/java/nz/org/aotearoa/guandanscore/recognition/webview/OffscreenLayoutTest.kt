package nz.org.aotearoa.guandanscore.recognition.webview

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AI-171：离屏挂接必须铺满屏幕，否则视口退化、composer 塌陷。 */
class OffscreenLayoutTest {

    @Test
    fun fullScreenLayoutPasses() {
        assertNull(OffscreenLayout.rejectReason(1176, 2328, 1176, 2328))
    }

    @Test
    fun anchorSizedLayoutIsRejected() {
        val reason = OffscreenLayout.rejectReason(1176, 2328, 1, 1)
        assertNotNull(reason)
        assertTrue(reason!!.contains("1px"))
    }

    @Test
    fun mismatchedLayoutIsRejected() {
        val reason = OffscreenLayout.rejectReason(1176, 2328, 1176, 1801)
        assertNotNull(reason)
        assertTrue(reason!!.contains("不一致"))
    }

    @Test
    fun invalidScreenIsRejected() {
        assertNotNull(OffscreenLayout.rejectReason(0, 0, 0, 0))
    }
}
