package nz.org.aotearoa.guandanscore.recognition.webview

/**
 * 挂接前的容器体检 + 挂接后的页面体检（AI-024）。
 *
 * 为什么需要它：`attach()` 原来只判断 `attachTargetProvider` 返回值非空就 `addView`。
 * 但“拿得到容器”和“容器能出帧”是两件事：
 * - Activity 正在 `onPause` / 转场中，容器已从窗口树摘下；
 * - 容器尺寸为 0（未 measure 完 / 被 `GONE`）；
 * - 容器 `visibility != VISIBLE`。
 * 以上任一情况 `addView` 都会成功，但页面 `innerHeight == 0`，后续所有选择器判定、
 * 坐标换算、`onShowFileChooser` 原生触摸都会失效，表现为“已挂接但永远不识牌”。
 *
 * 同理，挂接后若 `window.innerHeight` 仍为 0，说明这次挂接没生效，应回落 `NotAttached`
 * 并让调用方重试，而不是谎报 `Attached`。
 *
 * 本对象只做纯判断，便于 JVM 单测；真正的 View / JS 读取留在 [WebViewHost] / 驱动层。
 */
internal object AttachGate {

    /** 容器可用性所需的最小尺寸（px）。低于它视口不足以渲染任何页面。 */
    const val MIN_CONTAINER_SIDE_PX = 1

    /** 页面可用性所需的最小视口高度（CSS px）。与 `M3` 的 100 一致。 */
    const val MIN_VIEWPORT_HEIGHT = 100

    /** 容器体检输入。用基本类型，避免把 Android View 拖进单测。 */
    data class ContainerState(
        val isAttachedToWindow: Boolean,
        val isShown: Boolean,
        val widthPx: Int,
        val heightPx: Int,
        /** 现有父容器是否就是这个容器；已是它则无需重挂。 */
        val alreadyParent: Boolean = false
    ) {
        val hasUsableSize: Boolean
            get() = widthPx >= MIN_CONTAINER_SIDE_PX && heightPx >= MIN_CONTAINER_SIDE_PX
    }

    /**
     * 容器能否作为挂接目标。返回 null 表示通过，否则返回失败原因（用于日志）。
     */
    fun rejectReason(state: ContainerState): String? = when {
        !state.isAttachedToWindow -> "container-detached"
        !state.isShown -> "container-not-shown"
        !state.hasUsableSize -> "container-zero-size ${state.widthPx}x${state.heightPx}"
        else -> null
    }

    /**
     * 挂接后页面是否真的出帧。
     *
     * @param innerHeight `window.innerHeight`（CSS px）；读取失败按 0 处理
     */
    fun viewportRejectReason(innerHeight: Int): String? = when {
        innerHeight < MIN_VIEWPORT_HEIGHT -> "viewport-too-small innerHeight=$innerHeight"
        else -> null
    }
}
