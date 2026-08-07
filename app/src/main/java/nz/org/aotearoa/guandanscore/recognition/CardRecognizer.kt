package nz.org.aotearoa.guandanscore.recognition

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import nz.org.aotearoa.guandanscore.imaging.VisualCardCodec
import nz.org.aotearoa.guandanscore.model.*
import kotlin.math.hypot

data class RecognitionResult(val cards: List<Card>, val warnings: List<String>)

class CardRecognizer {
    private val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private data class Candidate(val rank: Rank, val suit: Suit, val x: Float, val y: Float, val area: Int, val exact: Boolean)
    private data class Pass(
        val bitmap: Bitmap,
        val map: (Float, Float) -> PointF,
        val areaScale: Float = 1f,
        val temporary: Boolean
    )

    fun recognize(bitmap: Bitmap, done: (RecognitionResult) -> Unit, failed: (Exception) -> Unit) {
        VisualCardCodec.decode(bitmap)?.let {
            done(RecognitionResult(it, listOf("已通过图片校验码准确读取 27 张牌")))
            return
        }
        val passes = buildPasses(bitmap)
        val found = mutableListOf<Candidate>()
        val errors = mutableListOf<Exception>()
        var remaining = passes.size
        fun finishOne() {
            remaining--
            if (remaining != 0) return
            passes.filter { it.temporary }.forEach { it.bitmap.recycle() }
            if (found.isEmpty() && errors.size == passes.size) { failed(errors.first()); return }
            done(finalize(found, bitmap))
        }
        passes.forEach { pass ->
            client.process(InputImage.fromBitmap(pass.bitmap, 0)).addOnSuccessListener { text ->
                text.textBlocks.flatMap { it.lines }.flatMap { it.elements }.forEach { e ->
                    val box = e.boundingBox ?: return@forEach
                    val normalized = normalize(e.text)
                    val rank = Rank.parse(normalized) ?: return@forEach
                    if (box.height() !in 10..(pass.bitmap.height * .25).toInt()) return@forEach
                    val p = pass.map(box.centerX().toFloat(), box.centerY().toFloat())
                    val suit = if (rank.value > 14) Suit.JOKER else explicitSuit(e.text)
                        ?: classifySuit(pass.bitmap, box.centerX(), box.bottom, box.height())
                    val sourceArea = (box.width() * box.height() / pass.areaScale).toInt()
                    found += Candidate(rank, suit, p.x, p.y, sourceArea, normalized == rank.label || normalized == "SJ" || normalized == "BJ")
                }
                finishOne()
            }.addOnFailureListener { errors += it; finishOne() }
        }
    }

    fun recognizeGenerated(bitmap: Bitmap): List<Card>? = VisualCardCodec.decode(bitmap)

    private fun buildPasses(source: Bitmap): List<Pass> {
        val out = mutableListOf(Pass(source, { x,y -> PointF(x,y) }, temporary = false))
        listOf(-60f,-40f,-20f,20f,40f,60f,90f,180f).forEach { degrees -> out += rotatedPass(source,degrees) }
        // A crop alone does not add detail: ML Kit still sees the same tiny glyph pixels. Upscale
        // each overlapping tile so ranks in a wide fan (notably 750x498 test photos) reach a useful
        // OCR size. Coordinates and areas are converted back to source-image space afterwards.
        val cropW = (source.width*.55f).toInt(); val cropH = (source.height*.55f).toInt()
        val zoom = if (minOf(source.width, source.height) < 900) 2.4f else 1.8f
        for (fy in listOf(0f,.225f,.45f)) for (fx in listOf(0f,.225f,.45f)) {
            val left = (source.width*fx).toInt().coerceAtMost(source.width-cropW)
            val top = (source.height*fy).toInt().coerceAtMost(source.height-cropH)
            val crop = Bitmap.createBitmap(source,left,top,cropW,cropH)
            val enlarged = Bitmap.createScaledBitmap(crop, (cropW*zoom).toInt(), (cropH*zoom).toInt(), true)
            crop.recycle()
            out += Pass(enlarged, { x,y -> PointF(x/zoom+left,y/zoom+top) }, zoom*zoom, true)
        }
        return out
    }

    private fun rotatedPass(source:Bitmap,degrees:Float):Pass {
        val transform=Matrix().apply { setRotate(degrees,source.width/2f,source.height/2f) }
        val bounds=RectF(0f,0f,source.width.toFloat(),source.height.toFloat()).also(transform::mapRect)
        transform.postTranslate(-bounds.left,-bounds.top)
        val output=Bitmap.createBitmap(bounds.width().toInt()+1,bounds.height().toInt()+1,Bitmap.Config.ARGB_8888)
        Canvas(output).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(source,transform,Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)) }
        val inverse=Matrix().also { transform.invert(it) }
        return Pass(output,{x,y-> val pts=floatArrayOf(x,y); inverse.mapPoints(pts); PointF(pts[0],pts[1])},temporary=true)
    }

    private fun finalize(raw: List<Candidate>, bitmap: Bitmap): RecognitionResult {
        val radius = minOf(bitmap.width,bitmap.height)*.038f
        val clusters = mutableListOf<MutableList<Candidate>>()
        raw.sortedWith(compareByDescending<Candidate> { it.exact }.thenByDescending { it.area }).forEach { c ->
            val cluster = clusters.firstOrNull { group -> group.any { hypot((it.x-c.x).toDouble(),(it.y-c.y).toDouble()) < radius } }
            if (cluster == null) clusters += mutableListOf(c) else cluster += c
        }
        // Stage 1: merge observations into visible card-corner locations and count them.
        data class RankHit(val rank:Rank,val x:Float,val y:Float,val area:Int,val exact:Boolean,val suitVotes:Map<Suit,Int>)
        val hits=clusters.map { group ->
            val winning=group.groupBy { it.rank }.maxWith(compareBy<Map.Entry<Rank,List<Candidate>>> { it.value.size }
                .thenBy { e->e.value.count(Candidate::exact) }.thenBy { e->e.value.maxOf(Candidate::area) })
            val representative=winning.value.maxWith(compareBy<Candidate>{it.exact}.thenBy{it.area})
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
        return RecognitionResult(cards,stagedWarnings)
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
