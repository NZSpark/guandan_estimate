package nz.org.aotearoa.guandanscore.recognition.source

/**
 * 进程级健康检查串行化。
 *
 * 网页源只有一个共享 `WebViewHost`。主界面（AI-184 冷启动自动挂接）与 AI 源管理页
 * 都可能同时发起 `healthCheck()`；两个检查同时走 `ensureLoaded()` 会互相销毁/重挂
 * 同一个 WebView。这里用一个进程级锁把检查排队，后到者等前一个结束后再读页面。
 *
 * 约束：调用方必须在**后台线程**使用；禁止在主线程包住可能等待 15s 的就绪流程。
 */
object HealthCheckGate {

    private val lock = Any()

    fun <T> run(block: () -> T): T = synchronized(lock) { block() }
}
