package nz.org.aotearoa.guandanscore.recognition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import nz.org.aotearoa.guandanscore.imaging.VisualCardCodec
import nz.org.aotearoa.guandanscore.model.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.hypot

/** T4.5: 警告分级。INFO 只记录不打断，WARNING 需要人工核对，ERROR 表示该路径失败。 */
enum class IssueLevel { INFO, WARNING, ERROR }

data class RecognitionIssue(val level: IssueLevel, val text: String)

/** T4.5: 分阶段计数，供 UI 显示“待核对数量”而不是只给一句模糊提醒。 */
data class RecognitionStats(
    val engine: RecognitionEngine = RecognitionEngine.DEFAULT,
    val detected: Int = 0,
    val classified: Int = 0,
    val unknown: Int = 0,
    val merged: Int = 0,
    val elapsedMillis: Long = 0,
    val modelVersion: String? = null,
    val learnedStatus: String = "off",
    val learnedReason: String? = null,
)

enum class RecognitionDisplayState { SUCCESS, PARTIAL, FAILED }

data class RecognitionSummary(
    val state: RecognitionDisplayState,
    val cardCount: Int,
    val reviewCount: Int,
    val message: String,
)

data class RecognitionResult(
    val cards: List<Card>,
    val warnings: List<String>,
    val issues: List<RecognitionIssue> = warnings.map { RecognitionIssue(IssueLevel.WARNING, it) },
    val stats: RecognitionStats = RecognitionStats(),
) {
    /** 需要人工核对的张数（低置信度 + 缺失张数）。 */
    val reviewCount: Int get() = stats.unknown
}

/** T4.5: 结果 → UI 状态的唯一映射，避免 UI 自己再判一次“成功/部分/失败”。 */
fun RecognitionResult.summary(): RecognitionSummary {
    val actionable = warnings.filter { it.isNotBlank() }
    val state = when {
        cards.isEmpty() && actionable.isNotEmpty() -> RecognitionDisplayState.FAILED
        actionable.isNotEmpty() -> RecognitionDisplayState.PARTIAL
        else -> RecognitionDisplayState.SUCCESS
    }
    val message = when (state) {
        RecognitionDisplayState.FAILED -> actionable.joinToString("；")
        RecognitionDisplayState.PARTIAL -> buildString {
            append("识别 ${cards.size} 张，有 ${actionable.size} 条警告")
            if (reviewCount > 0) append("，$reviewCount 张需核对")
        }
        RecognitionDisplayState.SUCCESS -> "识别 ${cards.size} 张"
    }
    return RecognitionSummary(state, cards.size, reviewCount, message)
}

/** 模型路径的决策记录，写进 [RecognitionStats] 便于现场排查。 */
internal data class LearnedTrace(val status: String, val reason: String?, val outcome: LearnedOutcome? = null)

/** 方案 A：learned 为主，heuristic 只补 learned 尚未产出的牌面副本。 */
internal data class MissingCardFill(
    val cards: List<Card>,
    val added: List<Card>,
)

internal fun fillMissingCards(
    primary: List<Card>,
    secondary: List<Card>,
    maxCards: Int = 27,
): MissingCardFill {
    val limit = maxCards.coerceAtLeast(0)
    val cards = primary.take(limit).toMutableList()
    val counts = cards.groupingBy { it.rank to it.suit }.eachCount().toMutableMap()
    val available = secondary.groupingBy { it.rank to it.suit }.eachCount()
    val added = mutableListOf<Card>()
    for (candidate in secondary) {
        if (cards.size >= limit) break
        val key = candidate.rank to candidate.suit
        val have = counts[key] ?: 0
        val canAdd = minOf(2, available[key] ?: 0)
        if (have >= canAdd) continue
        val filled = candidate.copy(id = cards.size, needsReview = true)
        cards += filled
        added += filled
        counts[key] = have + 1
    }
    return MissingCardFill(cards, added)
}

/**
 * T4.4: 识别入口。
 *
 * - 条码路径永远最优先，命中即结束；
 * - [engine] 默认 [RecognitionEngine.DEFAULT]（当前为 LEARNED）；
 * - LEARNED 时模型加载失败/推理超时/牌角不足都会回退到启发式并记录原因；
 * - SHADOW 只跑模型做对照，正式结果仍走启发式，不改变 UI。
 */
class CardRecognizer(
    context: Context,
    private val engine: RecognitionEngine = RecognitionEngine.DEFAULT,
    private val learned: LearnedCardRecognizer? = null,
    private val modelVersion: String? = null,
    private val learnedTimeoutMillis: Long = 3_000L,
    /** 模型加载失败原因；learned 为 null 时写入回退提示和统计。 */
    private val learnedUnavailableReason: String? = null,
) {
    private val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val templates = CardTemplateMatcher(context)
    private val learnedExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "learned-inference").apply { isDaemon = true }
    }
    private val dispatch = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "learned-dispatch").apply { isDaemon = true }
    }
    private data class Candidate(
        val rank: Rank,
        val suit: Suit,
        val x: Float,
        val y: Float,
        val area: Int,
        val exact: Boolean,
        val confidence: Double
    )

    /**
     * T0.2: pass 不再一次性建满 18 个 Bitmap，而是由 [RecognitionWorker] 按需渲染、
     * 在 OCR 回调里立即回收；同时在飞行中的 pass 数量受 [RecognitionWorker.maxInFlight] 限制，
     * 找到 27 张后提前结束。
     */
    fun recognize(bitmap: Bitmap, done: (RecognitionResult) -> Unit, failed: (Exception) -> Unit) {
        VisualCardCodec.decode(bitmap)?.let {
            done(
                RecognitionResult(
                    cards = it,
                    warnings = listOf("已通过图片校验码准确读取 27 张牌"),
                    stats = RecognitionStats(
                        engine = engine,
                        detected = it.size,
                        classified = it.size,
                        modelVersion = modelVersion,
                        learnedStatus = "barcode",
                    ),
                )
            )
            return
        }
        if (!EnginePolicy.runsModel(engine)) {
            heuristic(bitmap, done, failed, emptyList(), LearnedTrace("off", "启发式路径"))
            return
        }
        if (learned == null) {
            val reason = learnedUnavailableReason ?: "模型未加载"
            heuristic(
                bitmap, done, failed,
                listOf(RecognitionIssue(IssueLevel.WARNING, "模型路径回退：$reason")),
                LearnedTrace("fallback", reason),
            )
            return
        }
        dispatch.execute {
            val outcome = runLearned(bitmap)
            when {
                !EnginePolicy.usesModelResult(engine) -> heuristic(
                    bitmap, done, failed,
                    listOf(RecognitionIssue(IssueLevel.INFO, "shadow：${describe(outcome)}")),
                    LearnedTrace("shadow", describe(outcome), outcome),
                )

                outcome is LearnedOutcome.Success && outcome.cards.size < 27 ->
                    fillFromHeuristic(bitmap, outcome, done)

                outcome is LearnedOutcome.Success -> done(learnedResult(outcome))

                EnginePolicy.fallbackReason(engine, outcome) != null -> heuristic(
                    bitmap, done, failed,
                    listOf(RecognitionIssue(IssueLevel.WARNING, "模型路径回退：${describe(outcome)}")),
                    LearnedTrace("fallback", describe(outcome), outcome),
                )

                else -> heuristic(bitmap, done, failed, emptyList(), LearnedTrace("off", "启发式路径"))
            }
        }
    }

    /** 方案 A：模型结果不足 27 张时，用 heuristic 只补缺，不替换 learned 牌。 */
    private fun fillFromHeuristic(
        bitmap: Bitmap,
        outcome: LearnedOutcome.Success,
        done: (RecognitionResult) -> Unit,
    ) {
        heuristic(
            bitmap,
            { heuristicResult ->
                val fill = fillMissingCards(outcome.cards, heuristicResult.cards)
                val addedLabels = fill.added.take(8).joinToString("、") { it.display }
                val addedText = if (fill.added.size > 8) "$addedLabels 等 ${fill.added.size} 张" else addedLabels
                val extraWarnings = if (fill.added.isEmpty()) emptyList() else listOf(
                    "heuristic 补入 ${fill.added.size} 张需核对：$addedText"
                )
                val extraIssues = buildList {
                    add(RecognitionIssue(IssueLevel.INFO, "模型识别 ${outcome.cards.size}/27，尝试 heuristic 补缺"))
                    if (fill.added.isNotEmpty()) add(RecognitionIssue(IssueLevel.WARNING, extraWarnings.single()))
                }
                done(
                    learnedResult(
                        outcome,
                        cards = fill.cards,
                        extraWarnings = extraWarnings,
                        extraIssues = extraIssues,
                        unknownExtra = fill.added.size,
                        mergedExtra = heuristicResult.stats.merged,
                        elapsedExtra = heuristicResult.stats.elapsedMillis,
                        learnedStatus = "used+heuristic_fill",
                        learnedReason = "补入 ${fill.added.size} 张",
                    )
                )
            },
            { error ->
                val reason = error.localizedMessage ?: error.javaClass.simpleName
                done(
                    learnedResult(
                        outcome,
                        extraWarnings = listOf("heuristic 补缺失败：$reason；仅保留模型结果"),
                        extraIssues = listOf(
                            RecognitionIssue(IssueLevel.WARNING, "heuristic 补缺失败：$reason；仅保留模型结果")
                        ),
                        learnedStatus = "used+heuristic_fill_failed",
                        learnedReason = reason,
                    )
                )
            },
            listOf(RecognitionIssue(IssueLevel.INFO, "模型识别 ${outcome.cards.size}/27，尝试 heuristic 补缺")),
            LearnedTrace("fill", "模型识别 ${outcome.cards.size}/27，尝试补缺", outcome),
        )
    }

    /** T4.6: 启发式路径（原有 OCR + 模板匹配），模型任何故障都回到这里。 */
    private fun heuristic(
        bitmap: Bitmap,
        done: (RecognitionResult) -> Unit,
        failed: (Exception) -> Unit,
        extraIssues: List<RecognitionIssue>,
        trace: LearnedTrace,
    ) {
        val started = System.currentTimeMillis()
        val worker = RecognitionWorker(bitmap)
        val found = mutableListOf<Candidate>()
        val errors = mutableListOf<Exception>()
        var finished = false

        fun finish() {
            if (finished) return
            finished = true
            if (found.isEmpty() && errors.size >= worker.planned) {
                failed(errors.first())
                return
            }
            done(finalize(found, bitmap, extraIssues, trace, System.currentTimeMillis() - started))
        }

        fun pump() {
            if (finished) return
            while (worker.inFlight < worker.maxInFlight &&
                !worker.isExhausted &&
                !worker.shouldStop(found.size)
            ) {
                val pass = worker.next() ?: break
                client.process(InputImage.fromBitmap(pass.bitmap, 0))
                    .addOnSuccessListener { text ->
                        collect(text, pass, found)
                        worker.release(pass)
                        if (worker.isExhausted && worker.inFlight == 0) finish() else pump()
                    }
                    .addOnFailureListener { error ->
                        errors += error
                        worker.release(pass)
                        if (worker.isExhausted && worker.inFlight == 0) finish() else pump()
                    }
            }
            if (!finished && worker.isExhausted && worker.inFlight == 0) finish()
        }
        pump()
    }

    fun recognizeGenerated(bitmap: Bitmap): List<Card>? = VisualCardCodec.decode(bitmap)

    private fun collect(text: Text, pass: RenderedPass, found: MutableList<Candidate>) {
        text.textBlocks.flatMap { it.lines }.flatMap { it.elements }.forEach { e ->
            val box = e.boundingBox ?: return@forEach
            if (box.height() !in 10..(pass.bitmap.height * .25).toInt()) return@forEach
            val normalized = normalize(e.text)
            val ocrRank = Rank.parse(normalized)
            val template = templates.match(pass.bitmap, box)
            val rank = when {
                ocrRank == null && template != null && template.confidence >= .48 -> template.rank
                ocrRank != null && template != null && template.confidence >= .72 -> template.rank
                else -> ocrRank
            } ?: return@forEach
            val p: PointF = pass.map(box.centerX().toFloat(), box.centerY().toFloat())
            val suit = if (rank.value > 14) Suit.JOKER else explicitSuit(e.text)
                ?: template?.takeIf { it.rank == rank && it.suit != Suit.JOKER && it.confidence >= .46 }?.suit
                ?: classifySuit(pass.bitmap, box.centerX(), box.bottom, box.height())
            val sourceArea = (box.width() * box.height() / pass.areaScale).toInt()
            val exact = normalized == rank.label || normalized == "SJ" || normalized == "BJ"
            val confidence = when {
                exact && template?.rank == rank -> .98
                exact -> .86
                template?.rank == rank -> template.confidence
                else -> .55
            }
            found += Candidate(rank, suit, p.x, p.y, sourceArea, exact, confidence)
        }
    }

    private fun finalize(
        raw: List<Candidate>,
        bitmap: Bitmap,
        extraIssues: List<RecognitionIssue> = emptyList(),
        trace: LearnedTrace = LearnedTrace("off", null),
        elapsedMillis: Long = 0L,
    ): RecognitionResult {
        val radius = minOf(bitmap.width,bitmap.height)*.038f
        val clusters = mutableListOf<MutableList<Candidate>>()
        raw.sortedWith(compareByDescending<Candidate> { it.exact }.thenByDescending { it.area }).forEach { c ->
            val cluster = clusters.firstOrNull { group -> group.any { hypot((it.x-c.x).toDouble(),(it.y-c.y).toDouble()) < radius } }
            if (cluster == null) clusters += mutableListOf(c) else cluster += c
        }
        // Stage 1: merge observations into visible card-corner locations and count them.
        data class RankHit(val rank:Rank,val x:Float,val y:Float,val area:Int,val exact:Boolean,val suitVotes:Map<Suit,Int>)
        val hits=clusters.map { group ->
            val winning=group.groupBy { it.rank }.maxWith(compareBy<Map.Entry<Rank,List<Candidate>>> { entry -> entry.value.sumOf { it.confidence } }
                .thenBy { e->e.value.count(Candidate::exact) }.thenBy { e->e.value.maxOf(Candidate::area) })
            val representative=winning.value.maxWith(compareBy<Candidate>{it.confidence}.thenBy{it.area})
            RankHit(winning.key,representative.x,representative.y,representative.area,representative.exact,
                winning.value.groupingBy(Candidate::suit).eachCount())
        }.sortedWith(compareByDescending<RankHit>{it.exact}.thenByDescending{it.area})
        val rankCount=mutableMapOf<Rank,Int>()
        val located=hits.filter { hit ->
            val max=if(hit.rank.value>14)2 else 8
            ((rankCount[hit.rank]?:0)<max).also { if(it)rankCount[hit.rank]=(rankCount[hit.rank]?:0)+1 }
        }.take(27).sortedWith(compareBy({it.y},{it.x}))

        // Stage 2: settle one rank at every accepted card location. A bad suit guess must never
        // discard a good rank observation.
        val ranks = located

        // Stage 3: vote on suits only after rank recognition, enforcing two-deck limits afterwards.
        val used=mutableMapOf<Pair<Rank,Suit>,Int>(); var uncertainSuits=0
        val cards=ranks.mapIndexed { id,hit ->
            if(hit.rank.value>14) Card(id,hit.rank,Suit.JOKER) else {
                val rankedSuits=Suit.entries.filter{it!=Suit.JOKER}.sortedByDescending{hit.suitVotes[it]?:0}
                val chosen=rankedSuits.firstOrNull{(used[hit.rank to it]?:0)<2}?:Suit.SPADE
                val total=hit.suitVotes.values.sum().coerceAtLeast(1); val confidence=(hit.suitVotes[chosen]?:0).toDouble()/total
                if(confidence<.6)uncertainSuits++
                used[hit.rank to chosen]=(used[hit.rank to chosen]?:0)+1
                Card(id,hit.rank,chosen)
            }
        }
        val warnings = buildList {
            add("第一步点数识别：${cards.size}/27 张")
            if (cards.size != 27) add("被完全遮挡的点数请补录")
            add("第二步花色识别：${cards.size-uncertainSuits} 张较可信，$uncertainSuits 张需核对")
            if (raw.size > clusters.size) add("已合并 ${raw.size-clusters.size} 个旋转/局部重复结果")
            add("点数优先保留；花色不确定时请点牌校正")
        }
        val stagedWarnings = buildList {
            add("\u7b2c\u4e00\u6b65\u5f20\u6570\u8bc6\u522b\uff1a${located.size}/27 \u5f20")
            add("\u7b2c\u4e8c\u6b65\u70b9\u6570\u8bc6\u522b\uff1a${cards.size} \u5f20")
            if (cards.size != 27) add("\u88ab\u5b8c\u5168\u906e\u6321\u7684\u724c\u89d2\u8bf7\u8865\u5f55")
            add("\u7b2c\u4e09\u6b65\u82b1\u8272\u8bc6\u522b\uff1a${cards.size-uncertainSuits} \u5f20\u8f83\u53ef\u4fe1\uff0c$uncertainSuits \u5f20\u9700\u6838\u5bf9")
            addAll(warnings.drop(3))
        }
        val review = uncertainSuits + (27 - cards.size)
        val issues = buildList {
            extraIssues.forEach { add(it) }
            stagedWarnings.forEach { add(RecognitionIssue(IssueLevel.WARNING, it)) }
            if (review > 0) add(RecognitionIssue(IssueLevel.WARNING, "需核对 $review 张"))
        }
        return RecognitionResult(
            cards = cards,
            warnings = stagedWarnings + extraIssues.filter { it.level != IssueLevel.INFO }.map { it.text },
            issues = issues,
            stats = RecognitionStats(
                engine = engine,
                detected = located.size,
                classified = cards.size,
                unknown = review,
                merged = (raw.size - clusters.size).coerceAtLeast(0),
                elapsedMillis = elapsedMillis,
                modelVersion = modelVersion,
                learnedStatus = trace.status,
                learnedReason = trace.reason,
            ),
        )
    }

    private fun runLearned(bitmap: Bitmap): LearnedOutcome {
        val recognizer = learned ?: return LearnedOutcome.Unavailable("模型未就绪")
        val future = learnedExecutor.submit<LearnedOutcome> { recognizer.recognize(bitmap) }
        return try {
            future.get(learnedTimeoutMillis, TimeUnit.MILLISECONDS)
        } catch (error: TimeoutException) {
            future.cancel(true)
            LearnedOutcome.Unavailable("推理超时 ${learnedTimeoutMillis}ms")
        } catch (error: Exception) {
            LearnedOutcome.Unavailable("推理异常：${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun describe(outcome: LearnedOutcome): String = when (outcome) {
        is LearnedOutcome.Success ->
            "模型识别 ${outcome.cards.size} 张（检测 ${outcome.detections}，分类 ${outcome.classified}，未知 ${outcome.unknown}）"
        is LearnedOutcome.Unavailable -> outcome.reason
    }

    private fun learnedResult(
        outcome: LearnedOutcome.Success,
        cards: List<Card> = outcome.cards,
        extraWarnings: List<String> = emptyList(),
        extraIssues: List<RecognitionIssue> = emptyList(),
        unknownExtra: Int = 0,
        mergedExtra: Int = 0,
        elapsedExtra: Long = 0,
        learnedStatus: String = "used",
        learnedReason: String? = null,
    ): RecognitionResult {
        val headline = "模型识别：${outcome.cards.size} 张（检测 ${outcome.detections}，分类 ${outcome.classified}）"
        val actionable = buildList {
            if (outcome.unknown > 0) add("${outcome.unknown} 张置信度低需核对")
            if (outcome.merged > 0) add("合并 ${outcome.merged} 个重复检测")
        }
        val warnings = actionable + extraWarnings
        return RecognitionResult(
            cards = cards,
            warnings = warnings,
            issues = listOf(RecognitionIssue(IssueLevel.INFO, headline)) +
                actionable.map { RecognitionIssue(IssueLevel.WARNING, it) } + extraIssues,
            stats = RecognitionStats(
                engine = engine,
                detected = outcome.detections,
                classified = cards.size,
                unknown = outcome.unknown + unknownExtra,
                merged = outcome.merged + mergedExtra,
                elapsedMillis = outcome.elapsedMillis + elapsedExtra,
                modelVersion = modelVersion,
                learnedStatus = learnedStatus,
                learnedReason = learnedReason,
            ),
        )
    }

    private fun normalize(s: String): String {
        val compact=s.uppercase().replace(Regex("[^0-9A-Z]"),"")
        return when(compact) { "SJ","BJ"->compact; "10","1O","IO","I0","T","T0"->"10"; "O"->"Q"; "I"->"J"; else->compact }
    }
    private fun explicitSuit(s: String)=when { '♥' in s->Suit.HEART; '♦' in s->Suit.DIAMOND; '♣' in s->Suit.CLUB; '♠' in s->Suit.SPADE; else->null }

    private fun classifySuit(b: Bitmap,cx:Int,rankBottom:Int,rankHeight:Int):Suit {
        // The small suit pip sits directly below the corner rank. Keep this region narrow so
        // neighbouring ranks and large centre pips do not dominate its colour and silhouette.
        val half=(rankHeight*.58f).toInt().coerceAtLeast(8)
        val top=(rankBottom+(rankHeight*.08f).toInt()).coerceIn(0,b.height-1)
        val bottom=(rankBottom+(rankHeight*1.65f).toInt()).coerceIn(top+1,b.height)
        var red=0; var dark=0; val widths=IntArray(maxOf(1,bottom-top))
        for(y in top until bottom) for(x in (cx-half).coerceAtLeast(0) until (cx+half).coerceAtMost(b.width)) {
            val p=b.getPixel(x,y); val r=android.graphics.Color.red(p); val g=android.graphics.Color.green(p); val bl=android.graphics.Color.blue(p)
            if(r>g*1.3&&r>bl*1.3&&r>65){red++;widths[y-top]++} else if(r+g+bl<300){dark++;widths[y-top]++}
        }
        val third=maxOf(1,widths.size/3); val upper=widths.take(third).average(); val middle=widths.drop(third).take(third).average()
        return if(red>dark){if(upper<middle*.58)Suit.DIAMOND else Suit.HEART}else{if(upper<middle*.58)Suit.SPADE else Suit.CLUB}
    }
}
