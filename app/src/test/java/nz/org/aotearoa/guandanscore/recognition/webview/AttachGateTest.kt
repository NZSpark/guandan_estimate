package nz.org.aotearoa.guandanscore.recognition.webview

import nz.org.aotearoa.guandanscore.recognition.webview.AttachGate.ContainerState
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [AttachGate] 挂接体检规则单测（AI-024）。纯 JVM，不依赖 Android。 */
class AttachGateTest {

    private fun state(
        attached: Boolean = true,
        shown: Boolean = true,
        w: Int = 392,
        h: Int = 601
    ) = ContainerState(isAttachedToWindow = attached, isShown = shown, widthPx = w, heightPx = h)

    @Test
    fun healthyContainerPasses() {
        assertNull(AttachGate.rejectReason(state()))
    }

    @Test
    fun detachedContainerRejected() {
        // Activity 正在 onPause / 转场：视图已离开窗口树。
        val why = AttachGate.rejectReason(state(attached = false))
        assertNotNull(why)
        assertTrue(why!!.contains("container-detached"))
    }

    @Test
    fun notShownContainerRejected() {
        // visibility=GONE/INVISIBLE：宽度可能非零但不会出帧。
        val why = AttachGate.rejectReason(state(shown = false))
        assertNotNull(why)
        assertTrue(why!!.contains("container-not-shown"))
    }

    @Test
    fun zeroSizedContainerRejected() {
        val why = AttachGate.rejectReason(state(w = 0, h = 0))
        assertNotNull(why)
        assertTrue(why!!.contains("container-zero-size"))
        // 尺寸进日志，便于区分「未 measure」与「真的 0dp」。
        assertTrue(why!!.contains("0x0"))
    }

    @Test
    fun onePixelSideIsEnough() {
        // MainActivity 用的是 1dp 占位容器；不能用「太小」当拒绝理由。
        assertNull(AttachGate.rejectReason(state(w = 1, h = 1)))
    }

    @Test
    fun zeroViewportRejectedAfterAttach() {
        val why = AttachGate.viewportRejectReason(innerHeight = 0)
        assertNotNull(why)
        assertTrue(why!!.contains("viewport-too-small"))
    }

    @Test
    fun smallButRenderableViewportAccepted() {
        // 真机 DeepSeek 实测 innerHeight=601；M3 阈值是 100。
        assertNull(AttachGate.viewportRejectReason(innerHeight = 601))
        assertNull(AttachGate.viewportRejectReason(innerHeight = AttachGate.MIN_VIEWPORT_HEIGHT))
        assertNotNull(AttachGate.viewportRejectReason(innerHeight = AttachGate.MIN_VIEWPORT_HEIGHT - 1))
    }
}
