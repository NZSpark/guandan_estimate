package nz.org.aotearoa.guandanscore.recognition.webview

/**
 * AI-015：页面视口坐标 → View 局部像素坐标的换算。
 *
 * 抽成纯函数的原因：真机上「触摸已派发但 `onShowFileChooser` 从不触发」的根因就在这一步。
 * 早期实现按 **高度** 等比缩放同时作用于 x 和 y，隐含假设「WebView 宽高比 == 视口宽高比」。
 * 探测页/正式页的 WebView 都会被布局拉伸，宽高比并不相等，于是偏离越远偏得越多，
 * 点空了目标按钮 —— 而日志只显示「已派发」，看起来像是站点不响应。
 *
 * 硬规则：
 * - 横纵**分别**缩放，不用单一比例。
 * - 缺视口宽度时退回纵向比例（旧脚本兼容），但这是降级路径，不是正解。
 * - 结果钳制在 View 内部；视口坐标本身越界时钳制会掩盖问题，因此单独提供
 *   [isWithinViewport] 供调用方在换算前拒绝明显非法的点。
 */
internal object ViewportTapMapper {

    /** 换算结果。`null` 表示输入不可用（尺寸非正）。 */
    data class Mapped(val x: Int, val y: Int, val scaleX: Double, val scaleY: Double)

    /** 视口坐标是否落在视口内。用于在换算前拒绝明显越界的点。 */
    fun isWithinViewport(cssX: Int, cssY: Int, viewportWidth: Int, viewportHeight: Int): Boolean {
        if (cssX < 0 || cssY < 0) return false
        if (viewportHeight <= 0) return false
        if (cssY >= viewportHeight) return false
        if (viewportWidth > 0 && cssX >= viewportWidth) return false
        return true
    }

    /**
     * 把视口 CSS 坐标换算成 View 局部像素坐标。
     *
     * @param cssX 视口横坐标（CSS px）
     * @param cssY 视口纵坐标（CSS px）
     * @param viewWidth View 宽度（物理 px）
     * @param viewHeight View 高度（物理 px）
     * @param viewportWidth 页面 `window.innerWidth`；<= 0 时退回纵向比例
     * @param viewportHeight 页面 `window.innerHeight`
     */
    fun map(
        cssX: Int,
        cssY: Int,
        viewWidth: Int,
        viewHeight: Int,
        viewportWidth: Int,
        viewportHeight: Int
    ): Mapped? {
        if (viewWidth <= 0 || viewHeight <= 0 || viewportHeight <= 0) return null
        val scaleY = viewHeight.toDouble() / viewportHeight.toDouble()
        val scaleX = if (viewportWidth > 0) viewWidth.toDouble() / viewportWidth.toDouble() else scaleY
        val px = (cssX * scaleX).toInt().coerceIn(0, viewWidth - 1)
        val py = (cssY * scaleY).toInt().coerceIn(0, viewHeight - 1)
        return Mapped(px, py, scaleX, scaleY)
    }
}
