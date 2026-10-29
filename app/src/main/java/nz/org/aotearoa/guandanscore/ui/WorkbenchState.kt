package nz.org.aotearoa.guandanscore.ui

import nz.org.aotearoa.guandanscore.model.Card
import nz.org.aotearoa.guandanscore.model.Rank
import nz.org.aotearoa.guandanscore.scoring.HandResult

/**
 * 主工作台的显示状态，按职责分域（UI-003）。
 *
 * 旧实现让所有操作直接覆盖同一个 `status.text`：识别警告会被一般状态盖掉、
 * 手牌计数与任务进度无法同时表达。这里把状态拆成互不覆盖的域，由 UI 层分别渲染。
 *
 * 纯数据 + 纯函数，可在 JVM 单测里直接驱动，不需要 Android 运行时。
 */
data class WorkbenchState(
    val image: ImageState = ImageState.Empty,
    val localRecognition: LocalRecognitionState = LocalRecognitionState.Idle,
    val aiRecognition: AiRecognitionState = AiRecognitionState.Idle,
    val hand: List<Card> = emptyList(),
    val level: Rank = Rank.TWO,
    val score: ScoreState = ScoreState.None,
    val outputImage: OutputImageState = OutputImageState.None,
    val source: SourceState = SourceState()
) {
    val cardCount: Int get() = hand.size

    /**
     * 手牌或级牌变化后，旧评分必须失效（UI-006）。
     * 保留 [HandResult] 以便提示「需要重新计算」，但不把它当作当前结果展示。
     */
    fun withHand(newHand: List<Card>): WorkbenchState = copy(
        hand = newHand.mapIndexed { i, c -> c.copy(id = i) },
        score = score.invalidate()
    )

    fun withLevel(newLevel: Rank): WorkbenchState = if (newLevel == level) this else copy(
        level = newLevel,
        score = score.invalidate()
    )
}

sealed interface ImageState {
    data object Empty : ImageState
    data class Loaded(val source: ImageSource, val label: String) : ImageState
    data class Failed(val reason: String) : ImageState
}

/** 输入图与输出图必须能区分（UI-013）：保存时不能互相覆盖。 */
enum class ImageSource { PHOTO, GALLERY, RANDOM, GENERATED }

sealed interface LocalRecognitionState {
    data object Idle : LocalRecognitionState
    data object Running : LocalRecognitionState
    data class Success(val count: Int) : LocalRecognitionState
    data class Partial(val count: Int, val warnings: List<String>) : LocalRecognitionState
    data class Failed(val reason: String) : LocalRecognitionState
}

sealed interface AiRecognitionState {
    data object Idle : AiRecognitionState
    data class Running(val sourceName: String) : AiRecognitionState
    data class Success(val sourceName: String, val count: Int) : AiRecognitionState
    data class Failed(val sourceName: String, val kind: AiFailureKind, val detail: String) : AiRecognitionState
}

/**
 * AI 失败必须按类别区分表达（UI-017 / §10.2）：
 * 未登录、页面不可用、上传失败、超时、解析失败不能被统一成「网络错误」。
 */
enum class AiFailureKind { NOT_LOGGED_IN, PAGE_UNAVAILABLE, UPLOAD_FAILED, SUBMIT_FAILED, TIMEOUT, PARSE_FAILED, BUSY, CANCELLED, UNKNOWN }

sealed interface ScoreState {
    data object None : ScoreState
    data object Running : ScoreState
    data class Success(val result: HandResult) : ScoreState
    data class Failed(val reason: String) : ScoreState

    /** 手牌/级牌变化后的过期态：保留结果以便说明「需要重新计算」，但不作为当前结果显示。 */
    data class Stale(val previous: HandResult) : ScoreState
}

/** 旧结果失效：Success -> Stale；None/Failed 保持不变；Running 保持（正在算新结果）。 */
fun ScoreState.invalidate(): ScoreState = when (this) {
    is ScoreState.Success -> ScoreState.Stale(result)
    else -> this
}

sealed interface OutputImageState {
    data object None : OutputImageState
    data object Generating : OutputImageState
    data object Generated : OutputImageState
    data object Saving : OutputImageState
    data class Saved(val label: String) : OutputImageState
    data class Failed(val reason: String) : OutputImageState
}

/** 当前 AI 源与最近一次健康检查结果（§10 sourceState）。 */
data class SourceState(
    val name: String = "未配置",
    val isWebView: Boolean = true,
    val health: HealthState = HealthState.Unknown
)

sealed interface HealthState {
    data object Unknown : HealthState
    data object Checking : HealthState
    data object Ready : HealthState
    data class NotLoggedIn(val hint: String) : HealthState
    data class Unavailable(val reason: String) : HealthState
}
