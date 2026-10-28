package nz.org.aotearoa.guandanscore.recognition.source

import nz.org.aotearoa.guandanscore.model.Card

/** 一次识牌请求的输入。 */
data class RecognizeRequest(
    val jpegBytes: ByteArray,
    val prompt: String,
    val mimeType: String = "image/jpeg",
    val fileName: String = "hand.jpg"
) {
    // data class 含 ByteArray 必须覆写 equals/hashCode，否则退化为引用比较。
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RecognizeRequest) return false
        return jpegBytes.contentEquals(other.jpegBytes) &&
            prompt == other.prompt &&
            mimeType == other.mimeType &&
            fileName == other.fileName
    }

    override fun hashCode(): Int {
        var result = jpegBytes.contentHashCode()
        result = 31 * result + prompt.hashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + fileName.hashCode()
        return result
    }
}

/**
 * 统一的失败分类。
 *
 * 每类都必须在 UI 上映射成一句可读文案；不允许静默返回空列表。
 * 继承 Exception 是为了能直接作为 `Result` 或 worker 线程的异常载体。
 */
sealed class AiSourceError(message: String) : Exception(message) {
    class NotLoggedIn(val hint: String = "") : AiSourceError("AI 未登录${if (hint.isBlank()) "" else "：$hint"}")
    class NotReady(val reason: String) : AiSourceError("AI 页面未就绪：$reason")
    class UploadFailed(val detail: String) : AiSourceError("图片上传失败：$detail")
    class SubmitFailed(val detail: String) : AiSourceError("提交失败：$detail")
    class Timeout(val afterMillis: Long) : AiSourceError("等待回答超时（${afterMillis / 1000}s）")
    class ParseFailed(val detail: String) : AiSourceError("回答无法解析：$detail")
    class Busy : AiSourceError("已有识牌任务正在进行")
    class Cancelled : AiSourceError("识牌已取消")
    class Unknown(cause: Throwable?) : AiSourceError("未知错误：${cause?.message ?: "无详情"}")
}

/** 源健康态。Unavailable 与 NotLoggedIn 必须分开，前者是页面/窗口问题，后者是账号问题。 */
sealed interface SourceHealth {
    data object Ready : SourceHealth
    data class NotLoggedIn(val hint: String) : SourceHealth
    data class Unavailable(val reason: String) : SourceHealth
}

/**
 * 业务层唯一依赖的 AI 源抽象。
 *
 * 实现者负责：线程模型、站点 DOM、上传策略、结束判定。
 * 调用方（MainActivity / AiRecognitionService）不感知站点。
 *
 * 契约：
 * - [recognize] 是同步阻塞调用，内部自行切主线程执行 WebView 操作；调用方在 worker 线程。
 * - [recognize] 抛出的失败必须是 [AiSourceError] 子类。
 * - 同一实例同一时刻只处理一个请求；重入抛 [AiSourceError.Busy]。
 * - [healthCheck] 是只读的，且不得打断正在进行的识牌。
 */
interface AiSource {
    /** 与 [AiSourceId.key] 一致。 */
    val id: String
    val displayName: String

    /** 是否需要 endpoint / model / apiKey 这类网络配置（配置页据此显隐字段）。 */
    val requiresNetworkConfig: Boolean

    /** 是否需要在 WebView 里手动登录（配置页据此显示“打开登录页”）。 */
    val requiresWebLogin: Boolean

    fun healthCheck(): SourceHealth

    @Throws(AiSourceError::class)
    fun recognize(request: RecognizeRequest): List<Card>

    /** 请求取消当前任务。对没有进行中任务的调用是安全的空操作。 */
    fun cancel()

    /** 当前会话 URL，用于持久化恢复；不支持的实现返回 null。 */
    fun currentSessionUrl(): String?
}
