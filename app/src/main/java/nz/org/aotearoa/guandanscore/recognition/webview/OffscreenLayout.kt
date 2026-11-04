package nz.org.aotearoa.guandanscore.recognition.webview

/**
 * AI-171：离屏挂接后的布局断言（纯函数）。
 *
 * 离屏挂接唯一的价值是「窗口不可见，但视口等于真实屏高」。如果 layout 没铺满，
 * `innerHeight` 会退化成锚点尺寸甚至 1px，DeepSeek 的 composer 直接塌陷——比
 * 不挂接更糟，而且现象是「页面健康但永远点不到发送」。因此布局必须事后核对，
 * 不满足就如实拒绝，不能把 `Offscreen` 当作已就绪。
 *
 * 判定只依赖尺寸，不碰 Android，便于 JVM 单测。
 */
object OffscreenLayout {

    /** @return null 表示布局合法；否则返回可读的拒绝原因。 */
    fun rejectReason(
        screenWidth: Int,
        screenHeight: Int,
        layoutWidth: Int,
        layoutHeight: Int
    ): String? = when {
        screenWidth <= 0 || screenHeight <= 0 ->
            "屏幕尺寸无效（${screenWidth}x${screenHeight}）"
        layoutWidth <= 1 || layoutHeight <= 1 ->
            "WebView layout 过小（${layoutWidth}x${layoutHeight}），视口会退化为 1px"
        layoutWidth != screenWidth || layoutHeight != screenHeight ->
            "WebView layout ${layoutWidth}x${layoutHeight} 与屏幕 " +
                "${screenWidth}x${screenHeight} 不一致"
        else -> null
    }
}
