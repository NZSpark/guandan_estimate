package nz.org.aotearoa.guandanscore.recognition.webview

import kotlinx.coroutines.runBlocking

/**
 * 同步壳与协程世界之间的唯一桥（AI-023）。
 *
 * 背景：[PageOps] 是同步接口（`AiSource.recognize` 契约要求调用方在 worker 线程同步阻塞），
 * 而 `evaluateJavascript` 的回调只会回到主线程。若在**主线程**上 `runBlocking`，
 * 主线程被占住 → 回调永远排不上队 → 必然死锁（表现是识牌永久卡住，不是抛异常）。
 *
 * 因此这里把规则收成一个可单测的纯逻辑点：
 * - 在 worker 线程：允许 `runBlocking`，这是同步接口的正常用法；
 * - 在主线程：**立即抛**，把“偶发卡死”变成“确定性崩溃 + 明确提示”。
 *
 * 真正的正解仍然是走 [evaluateRawSuspend] 这类挂起接口；同步壳只服务于现有
 * 同步调用方，不得在 UI 线程被调用。
 */
internal object BlockingBridge {

    /** 当前线程是否是 Android 主线程。可注入以便 JVM 单测。 */
    fun isMainThread(): Boolean = try {
        android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
    } catch (_: Throwable) {
        // 纯 JVM 单测环境没有 Looper 类实现；没有主线程概念即不构成死锁风险。
        false
    }

    /**
     * 在同步壳里执行一段挂起逻辑。
     *
     * @throws IllegalStateException 当前线程是主线程时
     */
    fun <T> awaitBlocking(where: String, onMain: () -> Boolean = ::isMainThread, block: suspend () -> T): T {
        check(!onMain()) {
            "$where: 不允许在主线程上阻塞等待 JavaScript 回调（AI-023）。" +
                "请改用挂起接口，或把调用移到 worker 线程。"
        }
        return runBlocking { block() }
    }
}
