package nz.org.aotearoa.guandanscore.recognition.webview

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 协程 ↔ `evaluateJavascript` 的桥。
 *
 * 硬规则（M1）：所有 WebView 调用必须主线程。本类把 `evaluateJavascript` post 到主线程，
 * 并把回调转成挂起点，避免在 worker 线程上直接调 WebView（会抛 `CalledFromWrongThread`）。
 */
class WebViewBridge(private val host: WebViewHost) {

    private val main = Handler(Looper.getMainLooper())

    /** 执行脚本并返回原始字符串（可能是 JSON、带引号的 JSON、或 `null`）。 */
    suspend fun evaluateRaw(script: String): String = suspendCancellableCoroutine { cont ->
        main.post {
            if (!cont.isActive) return@post
            val view = host.currentWebView()
            if (view == null) {
                // 空串对上层是「脚本返回了空」与「根本没有页面」的同一信号，
                // 会被误判成「页面未就绪」（AI-036）。至少留下可检索的痕迹。
                Log.w(TAG, "evaluateRaw: webView is null (host not attached)")
                cont.resume("")
                return@post
            }
            try {
                view.evaluateJavascript(script) { raw ->
                    if (cont.isActive) cont.resume(raw ?: "")
                }
            } catch (e: Exception) {
                if (cont.isActive) cont.resume("")
            }
        }
    }

    /** 执行脚本并返回反转义后的字符串（用于需要文本而非 JSON 的场景）。 */
    suspend fun evaluateText(script: String): String = unescapeJsonString(evaluateRaw(script))

    /** 同步版本，仅供主线程内部使用（如 onShowFileChooser 之后的状态读取）。 */
    fun evaluateOnMain(script: String, onResult: (String) -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            host.currentWebView()?.evaluateJavascript(script) { onResult(it ?: "") }
        } else {
            main.post { host.currentWebView()?.evaluateJavascript(script) { onResult(it ?: "") } }
        }
    }

    companion object {
        private const val TAG = "WebViewBridge"

        /**
         * `evaluateJavascript` 对字符串结果返回带引号的 JSON 字面量。
         * 纯逻辑，可单测：`"\"a\\nb\""` → `a<换行>b`。
         */
        fun unescapeJsonString(raw: String?): String {
            if (raw == null || raw.isEmpty() || raw == "null") return ""
            val s = raw.trim()
            if (!s.startsWith("\"") || !s.endsWith("\"") || s.length < 2) return s
            val body = s.substring(1, s.length - 1)
            val sb = StringBuilder(body.length)
            var i = 0
            while (i < body.length) {
                val c = body[i]
                if (c != '\\' || i == body.length - 1) {
                    sb.append(c)
                    i++
                    continue
                }
                val n = body[i + 1]
                when (n) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'u' -> {
                        if (i + 5 < body.length) {
                            val hex = body.substring(i + 2, i + 6)
                            val code = hex.toIntOrNull(16)
                            if (code != null) {
                                sb.append(code.toChar())
                                i += 6
                                continue
                            }
                        }
                        sb.append(c)
                    }
                    else -> sb.append(n)
                }
                i += 2
            }
            return sb.toString()
        }
    }
}
