package nz.org.aotearoa.guandanscore.recognition

/**
 * Gemini 网页上游的抽象接口。把 WebView 细节隔离在实现之外，
 * 使 [AiRecognitionService] 与状态机可用 Fake 实现做单元测试。
 *
 * 实现方必须保证所有方法在主线程调用，且不得在生成过程中执行 reload / 导航。
 */
interface GeminiUpstream {

    /** 上游状态，用于区分“未就绪”与真正的错误。 */
    sealed interface Status {
        data object Ready : Status
        data object NotLoggedIn : Status
        data object PageLoading : Status
        data object SelectorMissing : Status
        data object PageUnhealthy : Status
        data class Failed(val reason: String) : Status
    }

    /** 回答快照，对应 [GenerationWatcher.Sample] 的数据来源。 */
    data class ReplySnapshot(
        val responseCount: Int,
        val lastAnswerText: String,
        val generating: Boolean
    )

    /** 检查页面是否可操作。 */
    fun checkReady(): Status

    /** 记录发送前基线。 */
    fun captureBaseline(): ReplySnapshot

    /**
     * 上传图片并要求实现方确认附件预览真实出现。
     * 事件派发成功但无预览时必须返回 false，不得伪装成功。
     */
    fun attachImage(jpegBytes: ByteArray): Boolean

    /** 把 prompt 写入输入框（不提交）。 */
    fun fillPrompt(prompt: String): Boolean

    /** 点击发送。 */
    fun submit(): Boolean

    /** 读取当前回答快照。 */
    fun snapshot(): ReplySnapshot
}
