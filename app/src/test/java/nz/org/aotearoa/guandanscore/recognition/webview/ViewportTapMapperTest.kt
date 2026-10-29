package nz.org.aotearoa.guandanscore.recognition.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**

AI-015：视口坐标 → View 像素坐标的换算。

这些用例守的是真机踩过的坑：按单一比例等比缩放会把点算到 View 外面，

表现为「触摸已派发、页面毫无反应」，而 onShowFileChooser 永远不触发。
*/
class ViewportTapMapperTest {

@Test
fun uniformCaseMatchesSimpleScaling() {
// 视口与 View 同比例时，横纵缩放一致，与旧实现结果相同。
val m = ViewportTapMapper.map(
cssX = 100, cssY = 200,
viewWidth = 360, viewHeight = 600,
viewportWidth = 360, viewportHeight = 600
)!!
assertEquals(100, m.x)
assertEquals(200, m.y)
assertEquals(1.0, m.scaleX, 1e-9)
assertEquals(1.0, m.scaleY, 1e-9)
}

@Test
fun nonUniformAspectUsesIndependentScale() {
// View 1176x1519 / 视口 392x601：横纵比例不同。
val m = ViewportTapMapper.map(
cssX = 56, cssY = 507,
viewWidth = 1176, viewHeight = 1519,
viewportWidth = 392, viewportHeight = 601
)!!
// 横向 scale=3.0；纵向 1519/601≈2.5275。旧实现用纵向比例算 x，
// 得到 56*2.5275≈141，比正确值 168 偏了 27px —— 足以点空按钮。
assertEquals(168, m.x)
assertEquals(1281, m.y)
assertEquals(3.0, m.scaleX, 1e-9)
assertEquals(1519.0 / 601.0, m.scaleY, 1e-9)
assertTrue("x 必须用横向比例算出", m.x != (56 * m.scaleY).toInt())
}

@Test
fun missingViewportWidthFallsBackToHeightScale() {
// 旧脚本没有 innerWidth：降级到纵向比例，不应崩、也不应返回 null。
val m = ViewportTapMapper.map(
cssX = 10, cssY = 10,
viewWidth = 360, viewHeight = 600,
viewportWidth = 0, viewportHeight = 600
)!!
assertEquals(10, m.x)
assertEquals(10, m.y)
assertEquals(m.scaleY, m.scaleX, 1e-9)
}

@Test
fun resultIsClampedInsideView() {
// 视口坐标越界时钳制在 View 内部，避免派发到负数坐标。
val m = ViewportTapMapper.map(
cssX = 5000, cssY = 5000,
viewWidth = 360, viewHeight = 600,
viewportWidth = 360, viewportHeight = 600
)!!
assertEquals(359, m.x)
assertEquals(599, m.y)
}

@Test
fun invalidSizesReturnNull() {
assertNull(
ViewportTapMapper.map(1, 1, viewWidth = 0, viewHeight = 600, viewportWidth = 360, viewportHeight = 600)
)
assertNull(
ViewportTapMapper.map(1, 1, viewWidth = 360, viewHeight = 0, viewportWidth = 360, viewportHeight = 600)
)
assertNull(
ViewportTapMapper.map(1, 1, viewWidth = 360, viewHeight = 600, viewportWidth = 360, viewportHeight = 0)
)
}

@Test
fun isWithinViewportRejectsOutOfRangePoints() {
assertTrue(ViewportTapMapper.isWithinViewport(0, 0, 392, 601))
assertTrue(ViewportTapMapper.isWithinViewport(391, 600, 392, 601))
assertFalse(ViewportTapMapper.isWithinViewport(392, 10, 392, 601))
assertFalse(ViewportTapMapper.isWithinViewport(10, 601, 392, 601))
assertFalse(ViewportTapMapper.isWithinViewport(-1, 10, 392, 601))
// 未知视口宽度时不做横向判定，只查纵轴。
assertTrue(ViewportTapMapper.isWithinViewport(9999, 10, 0, 601))
}
}