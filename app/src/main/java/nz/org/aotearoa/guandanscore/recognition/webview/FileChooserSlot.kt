package nz.org.aotearoa.guandanscore.recognition.webview

/**
 * AI-073：`onShowFileChooser` 回调的**唯一所有者**。
 *
 * ## 为什么单独抽出来
 * 这个槽位的失效方式是「静默永久卡死」：某个回调被覆盖却没被释放，页面侧会一直等
 * 文件选择结果，表现为「点了一次上传之后，这个 WebView 再也不响应 `input[type=file]`」。
 * 它不出异常、不写日志、也没有超时 —— 只能靠规则保证。
 *
 * ## 规则
 * 1. [offer] 新的回调时，若槽内已有**不同**的回调，先以 `null` 释放它（页面解除等待）。
 * 2. [take] 交出所有权并清空槽位；调用方负责随后 `onReceiveValue(...)`。
 * 3. [release] 主动以 `null` 释放；多次调用幂等。
 * 4. 同一回调实例重复 [offer] 不重复释放（系统可能重入）。
 *
 * 本类不持有 WebView、不做线程切换；由 [WebViewHost] 在主线程调用。
 */
class FileChooserSlot<T> {

    private var current: T? = null

    /** 当前是否挂着未决回调（仅用于日志/断言）。 */
    val isPending: Boolean get() = current != null

    /**
     * 放入新回调。
     *
     * @param release 释放旧回调的方式；必须对页面调用 `onReceiveValue(null)`。
     * @return 是否释放了一个旧的、不同的回调
     */
    fun offer(next: T?, release: (T) -> Unit): Boolean {
        if (next == null) return false
        val previous = current
        if (previous != null && previous !== next) {
            release(previous)
            current = next
            return true
        }
        current = next
        return false
    }

    /** 取出所有权；返回后 [isPending] 为 false。 */
    fun take(): T? {
        val v = current
        current = null
        return v
    }

    /** 以 null 释放并清空。幂等。 */
    fun release(release: (T) -> Unit): Boolean {
        val v = current ?: return false
        current = null
        release(v)
        return true
    }

    /** 仅用于测试与日志，不交出所有权。 */
    internal fun peek(): T? = current
}
