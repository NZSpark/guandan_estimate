package nz.org.aotearoa.guandanscore.recognition.source

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import nz.org.aotearoa.guandanscore.model.Card
import nz.org.aotearoa.guandanscore.recognition.AiRecognitionClient
import nz.org.aotearoa.guandanscore.recognition.AiSettings
import nz.org.aotearoa.guandanscore.recognition.AiTransport

/**
 * HTTP API 源：把现有 [AiRecognitionClient] 包成 [AiSource]。
 *
 * 这是唯一允许保留 `isGemini()` / `isOpenAiChat()` 字符串嗅探的地方；
 * 新代码不得再依赖 provider 名字推断协议（见 `doc/multi_ai_update.md` §1.1）。
 *
 * 该实现无 WebView、无状态、无登录态，因此 [healthCheck] 只做配置校验，
 * [cancel] 是空操作（HTTP 请求无法中途取消，靠 readTimeout 兜底）。
 */
class HttpApiSource(
    private val settings: AiSettings,
    private val client: AiRecognitionClient = AiRecognitionClient()
) : AiSource {

    override val id: String = AiSourceId.HTTP_API.key
    override val displayName: String = settings.provider.ifBlank { AiSourceId.HTTP_API.label }
    override val requiresNetworkConfig: Boolean = true
    override val requiresWebLogin: Boolean = false

    override fun healthCheck(): SourceHealth = when {
        settings.transport != AiTransport.API ->
            SourceHealth.Unavailable("配置不是 API 模式（transport=${settings.transport}）")
        settings.apiKey.isBlank() ->
            SourceHealth.NotLoggedIn("请在设置中填写 API Key")
        !settings.endpoint.startsWith("https://") ->
            SourceHealth.Unavailable("接口地址必须使用 HTTPS")
        settings.model.isBlank() ->
            SourceHealth.Unavailable("请填写模型名称")
        else -> SourceHealth.Ready
    }

    override fun recognize(request: RecognizeRequest): List<Card> {
        if (settings.apiKey.isBlank()) throw AiSourceError.NotLoggedIn("请先在 AI 设置中填写 API Key")
        if (!settings.endpoint.startsWith("https://")) throw AiSourceError.NotReady("接口地址必须使用 HTTPS")
        val bitmap = BitmapFactory.decodeByteArray(request.jpegBytes, 0, request.jpegBytes.size)
            ?: throw AiSourceError.UploadFailed("JPEG 解码失败")
        return try {
            client.recognize(bitmap, settings)
        } catch (e: IllegalArgumentException) {
            throw AiSourceError.NotReady(e.message ?: "请求参数无效")
        } catch (e: IllegalStateException) {
            val msg = e.message.orEmpty()
            throw when {
                msg.contains("没有返回") -> AiSourceError.ParseFailed(msg)
                else -> AiSourceError.SubmitFailed(msg)
            }
        } catch (e: AiSourceError) {
            throw e
        } catch (e: Exception) {
            throw AiSourceError.Unknown(e)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    override fun cancel() = Unit

    override fun currentSessionUrl(): String? = null
}
