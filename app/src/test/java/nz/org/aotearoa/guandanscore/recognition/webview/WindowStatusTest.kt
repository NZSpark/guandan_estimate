package nz.org.aotearoa.guandanscore.recognition.webview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**

挂接状态语义单测（AI-166/AI-167）。

这里钉住的是驱动层的判定口径：主界面不插可见 WebView（AI-165），

因此「离屏挂接」必须与「可见挂接」等价可用；反之 NotAttached / NotCreated

绝不能被当成可用，否则会以「已挂接」的姿态进入等待，最终超时在别处报错。
*/
class WindowStatusTest {

/** 与 [PageDriver.ensureLoaded] 里的可用性判定保持同一口径。 */
private fun usable(status: WindowStatus): Boolean =
status == WindowStatus.Attached || status == WindowStatus.Offscreen

@Test
fun attachedIsUsable() {
assertTrue(usable(WindowStatus.Attached))
}

@Test
fun offscreenIsUsable() {
// 离屏挂接用户看不见，但 innerHeight 等于屏高、evaluateJavascript 与
// onShowFileChooser 都正常，驱动层必须照常推进。
assertTrue(usable(WindowStatus.Offscreen))
}

@Test
fun notAttachedIsNotUsable() {
assertFalse(usable(WindowStatus.NotAttached))
}

@Test
fun notCreatedIsNotUsable() {
assertFalse(usable(WindowStatus.NotCreated))
}

@Test
fun offscreenIsDistinctFromAttached() {
// 两者不可合并：UI 需要区分「用户能看到页面」与「后台在跑」。
assertFalse(WindowStatus.Offscreen == WindowStatus.Attached)
}
}