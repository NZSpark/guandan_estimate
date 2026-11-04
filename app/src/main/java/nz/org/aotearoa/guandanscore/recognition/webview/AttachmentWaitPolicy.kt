package nz.org.aotearoa.guandanscore.recognition.webview

import org.json.JSONObject

/**
 * 附件预览的等待策略（G-07）。
 *
 * 从 [PageDriver.verifyAttachment] 抽出的纯逻辑：给定“读一次页面”的采样函数，
 * 在 [timeoutMillis] 内轮询，直到
 * 1. 预览确认出现（[Result.Confirmed]）→ 上传成功；
 * 2. 页面给出不可自愈的阻断原因（[Result.Blocked]，如额度用尽）→ 立即失败，
 *    不白等满 20s；
 * 3. 超时（[Result.TimedOut]）→ 上传失败，调用方必须返回 `UploadFailed`，
 *    不得继续提交纯文本。
 *
 * 不依赖 WebView / Android，时钟与 sleep 均可注入，因此可直接单测“20s 超时”
 * 这条路径。**派发事件成功不代表上传成功**——唯一的成功判据是这里返回
 * [Result.Confirmed]。
 */
class AttachmentWaitPolicy(
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MILLIS,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val sleepMillis: (Long) -> Unit = { Thread.sleep(it) }
) {

    companion object {
        /** 与 PageDriver 的既有行为一致：最多等 20s。 */
        const val DEFAULT_TIMEOUT_MILLIS = 20_000L

        /** 轮询间隔。页面渲染附件预览是异步的，300ms 足够且不会压垮 WebView。 */
        const val DEFAULT_POLL_INTERVAL_MILLIS = 300L
    }

    sealed interface Result {
        /** 已采样的次数，用于超时日志区分“一次都没读到”和“读到了但没附件”。 */
        val samples: Int

        /** 预览已出现；[json] 是确认时的页面读数，便于日志核对。 */
        data class Confirmed(val json: JSONObject, override val samples: Int) : Result

        /** 页面明确表示无法完成（如上传额度用尽）；立即失败，不继续等待。 */
        data class Blocked(val json: JSONObject, override val samples: Int) : Result

        /** 截至 deadline 仍未确认预览。 */
        data class TimedOut(override val samples: Int) : Result
    }

    /**
     * @param sample 读一次页面附件状态；返回 null 表示本次没读到有效结果（脚本失败 / JSON 不可解析），
     *   继续轮询而不是直接判失败。
     * @param isAttached 站点适配器的“预览已出现”判据。
     * @param isBlocked 站点适配器的“不可自愈”判据（例如额度用尽）。
     * @param onSample 每次拿到非空读数时回调，用于记录页面失败原因；不参与判定。
     */
    fun await(
        sample: () -> JSONObject?,
        isAttached: (JSONObject) -> Boolean,
        isBlocked: (JSONObject) -> Boolean = { false },
        onSample: (JSONObject) -> Unit = {}
    ): Result {
        val deadline = nowMillis() + timeoutMillis
        // 防御：即使时钟不前进（注入实现出错），也不允许无限轮询。
        val maxSamples = (timeoutMillis / pollIntervalMillis.coerceAtLeast(1L)).toInt() + 2
        var samples = 0
        while (nowMillis() < deadline && samples < maxSamples) {
            samples++
            val json = sample()
            if (json != null) {
                onSample(json)
                if (isAttached(json)) return Result.Confirmed(json, samples)
                if (isBlocked(json)) return Result.Blocked(json, samples)
            }
            sleepMillis(pollIntervalMillis)
        }
        return Result.TimedOut(samples)
    }
}
