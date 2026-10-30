package nz.org.aotearoa.guandanscore

import android.Manifest
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.chip.Chip
import nz.org.aotearoa.guandanscore.databinding.ActivityMainBinding
import nz.org.aotearoa.guandanscore.imaging.HandImageRenderer
import nz.org.aotearoa.guandanscore.model.Card
import nz.org.aotearoa.guandanscore.model.Rank
import nz.org.aotearoa.guandanscore.model.Suit
import nz.org.aotearoa.guandanscore.probe.AiProbeActivity
import nz.org.aotearoa.guandanscore.recognition.AiRecognitionClient
import nz.org.aotearoa.guandanscore.recognition.AiSettings
import nz.org.aotearoa.guandanscore.recognition.AiSettingsStore
import nz.org.aotearoa.guandanscore.recognition.CardRecognizer
import nz.org.aotearoa.guandanscore.recognition.source.AiSourceError
import nz.org.aotearoa.guandanscore.recognition.source.AiSourcePresets
import nz.org.aotearoa.guandanscore.recognition.source.AiSourceRegistry
import nz.org.aotearoa.guandanscore.recognition.source.HealthCheckGate
import nz.org.aotearoa.guandanscore.recognition.source.RecognizeRequest
import nz.org.aotearoa.guandanscore.recognition.source.SourceHealth
import nz.org.aotearoa.guandanscore.recognition.webview.WebViewHost
import nz.org.aotearoa.guandanscore.scoring.HandResult
import nz.org.aotearoa.guandanscore.scoring.HandScorer
import nz.org.aotearoa.guandanscore.ui.AiFailureKind
import nz.org.aotearoa.guandanscore.ui.AiRecognitionState
import nz.org.aotearoa.guandanscore.ui.AiSettingsActivity
import nz.org.aotearoa.guandanscore.ui.HealthState
import nz.org.aotearoa.guandanscore.ui.ImageSource
import nz.org.aotearoa.guandanscore.ui.ImageState
import nz.org.aotearoa.guandanscore.ui.LocalRecognitionState
import nz.org.aotearoa.guandanscore.ui.OutputImageState
import nz.org.aotearoa.guandanscore.ui.ScoreState
import nz.org.aotearoa.guandanscore.ui.SourceState
import nz.org.aotearoa.guandanscore.ui.WorkbenchState
import java.io.File
import java.util.concurrent.Executors

/**
 * 主工作台（doc/ui_design.md §4）。
 *
 * 职责：把用户操作映射到业务层，并把结果渲染到分域状态控件上。
 * 不做：站点 DOM、脚本注入、评分算法。
 *
 * UI-003：不再让所有操作共用一个 status 文本；每个状态域有独立控件。
 * UI-005：每个异步操作都有进行中 / 成功 / 失败可见状态，失败时保留已有输入。
 */
class MainActivity : AppCompatActivity() {
    private lateinit var ui: ActivityMainBinding
    private val recognizer by lazy { CardRecognizer(this) }
    private val webHost by lazy { WebViewHost.get(this) }
    private lateinit var aiSettingsStore: AiSettingsStore
    private val imageRenderer by lazy { HandImageRenderer(this) }
    private val worker = Executors.newSingleThreadExecutor()

    private var state = WorkbenchState()
    private var cameraUri: Uri? = null

    /** 输入图与输出图分开保存（UI-013）：保存当前图片永远保存 [inputBitmap]。 */
    private var inputBitmap: Bitmap? = null
    private var outputBitmap: Bitmap? = null
    private var lastInputSource: ImageSource = ImageSource.PHOTO

    private val levels = Rank.entries.filter { it.value <= 14 }

    private val gallery = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) result.data?.data?.let { load(it, ImageSource.GALLERY) }
    }
    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) cameraUri?.let { load(it, ImageSource.PHOTO) }
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else toast("需要相机权限才能拍照")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityMainBinding.inflate(layoutInflater)
        setContentView(ui.root)
        aiSettingsStore = AiSettingsStore(this)

        ui.level.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, levels.map { it.label })
        ui.level.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                setState(state.withLevel(levels[position]))
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        ui.camera.setOnClickListener { cameraPermission.launch(Manifest.permission.CAMERA) }
        ui.gallery.setOnClickListener { pickFromGallery() }
        ui.randomHand.setOnClickListener { generateRandomHand() }
        ui.saveImage.setOnClickListener { saveCurrentImage() }
        ui.onlineRecognition.setOnClickListener { recognizeOnline() }
        ui.aiSettings.setOnClickListener { showAiSettings() }
        ui.openLogin.setOnClickListener { openLoginPage() }
        ui.addCard.setOnClickListener { editCard(null) }
        ui.calculate.setOnClickListener { calculate() }
        ui.toggleBreakdown.setOnClickListener { toggleBreakdown() }
        ui.sortedImage.setOnClickListener { generateSortedImage() }

        // AI-165：主界面不再插入可见 WebView 容器。网页登录走独立页面（UI-018）。
        webHost.setAttachTarget { null }
        // AI-166/AI-167：识牌改走离屏挂接 —— 锚点 1×1 不可见，但 WebView 按全屏像素
        // layout，`innerHeight` 等于屏高，页面会出帧、文件选择器也照常触发。
        webHost.setOffscreenAnchorProvider { anchor ->
            val root = findViewById<android.view.ViewGroup>(android.R.id.content)
            root?.addView(anchor, android.view.ViewGroup.LayoutParams(1, 1))
        }

        // AI-184：首次健康检查由 onResume post 触发。onCreate/onResume 阶段 decorView
        // 还没挂进窗口，此时离屏锚点 attach 必然失败，会把后台自动挂接误报成
        // 「WebView 未挂接窗口」。
        render()
    }

    // ---------------------------------------------------------------- 状态

    private fun setState(next: WorkbenchState) {
        state = next
        render()
    }

    private fun render() {
        renderImageState()
        renderLocalRecognition()
        renderSourceState()
        renderAiRecognition()
        renderHand()
        renderScore()
        renderOutput()
    }

    private fun renderImageState() {
        val hasImage = inputBitmap != null
        ui.emptyState.visibility = if (hasImage) View.GONE else View.VISIBLE
        ui.photo.setImageBitmap(inputBitmap)
        ui.saveImage.isEnabled = hasImage
        ui.onlineRecognition.isEnabled = hasImage && !isAiRunning()

        when (val s = state.image) {
            is ImageState.Loaded -> {
                ui.imageStateText.visibility = View.VISIBLE
                ui.imageStateText.text = s.label
            }
            is ImageState.Failed -> {
                ui.imageStateText.visibility = View.VISIBLE
                ui.imageStateText.text = "图片读取失败：${s.reason}"
                ui.imageStateText.setTextColor(getColor(R.color.error))
            }
            ImageState.Empty -> ui.imageStateText.visibility = View.GONE
        }
    }

    private fun renderLocalRecognition() {
        ui.localWarnings.visibility = View.GONE
        when (val s = state.localRecognition) {
            LocalRecognitionState.Idle -> {
                ui.localRecognitionState.text = "本地识牌：等待导入照片"
                ui.localRecognitionState.setTextColor(getColor(R.color.text_primary))
            }
            LocalRecognitionState.Running -> {
                ui.localRecognitionState.text = "本地识牌：正在识别手牌…"
                ui.localRecognitionState.setTextColor(getColor(R.color.text_primary))
            }
            is LocalRecognitionState.Success -> {
                ui.localRecognitionState.text = "本地识牌：已识别 ${s.count} 张，请核对"
                ui.localRecognitionState.setTextColor(getColor(R.color.success))
            }
            is LocalRecognitionState.Partial -> {
                ui.localRecognitionState.text = "本地识牌：识别 ${s.count} 张，有 ${s.warnings.size} 条警告"
                ui.localRecognitionState.setTextColor(getColor(R.color.gold))
                ui.localWarnings.visibility = View.VISIBLE
                ui.localWarnings.text = s.warnings.joinToString("\n")
            }
            is LocalRecognitionState.Failed -> {
                ui.localRecognitionState.text = "本地识牌失败：${s.reason}"
                ui.localRecognitionState.setTextColor(getColor(R.color.error))
            }
        }
    }

    private fun renderSourceState() {
        val src = state.source
        ui.sourceName.text = "当前 AI：${src.name}"
        if (!src.isWebView) {
            ui.sourceHealth.text = "类型：HTTP API"
            ui.sourceHealth.setTextColor(getColor(R.color.text_secondary))
            ui.openLogin.visibility = View.GONE
            return
        }
        when (val h = src.health) {
            HealthState.Unknown -> {
                ui.sourceHealth.text = "状态：未知"
                ui.sourceHealth.setTextColor(getColor(R.color.text_secondary))
                ui.openLogin.visibility = View.VISIBLE
            }
            HealthState.Checking -> {
                ui.sourceHealth.text = "状态：检测中…"
                ui.sourceHealth.setTextColor(getColor(R.color.text_secondary))
                ui.openLogin.visibility = View.GONE
            }
            HealthState.Ready -> {
                ui.sourceHealth.text = "状态：网页可用"
                ui.sourceHealth.setTextColor(getColor(R.color.success))
                ui.openLogin.visibility = View.GONE
            }
            is HealthState.NotLoggedIn -> {
                ui.sourceHealth.text = "状态：需要登录${if (h.hint.isBlank()) "" else "（${h.hint}）"}"
                ui.sourceHealth.setTextColor(getColor(R.color.gold))
                ui.openLogin.visibility = View.VISIBLE
            }
            is HealthState.Unavailable -> {
                ui.sourceHealth.text = "状态：页面暂不可用（${h.reason}）"
                ui.sourceHealth.setTextColor(getColor(R.color.error))
                ui.openLogin.visibility = View.VISIBLE
            }
        }
    }

    private fun renderAiRecognition() {
        when (val s = state.aiRecognition) {
            AiRecognitionState.Idle -> ui.aiRecognitionState.visibility = View.GONE
            is AiRecognitionState.Running -> {
                ui.aiRecognitionState.visibility = View.VISIBLE
                ui.aiRecognitionState.text = "正在通过 ${s.sourceName} 识图…"
                ui.aiRecognitionState.setTextColor(getColor(R.color.text_primary))
            }
            is AiRecognitionState.Success -> {
                ui.aiRecognitionState.visibility = View.VISIBLE
                ui.aiRecognitionState.text = "${s.sourceName} 识别：${s.count}/27 张，请核对"
                ui.aiRecognitionState.setTextColor(getColor(R.color.success))
            }
            is AiRecognitionState.Failed -> {
                ui.aiRecognitionState.visibility = View.VISIBLE
                ui.aiRecognitionState.text = "${s.sourceName} 识图失败（${s.kind.label()}）：${s.detail}"
                ui.aiRecognitionState.setTextColor(getColor(R.color.error))
            }
        }
    }

    private fun renderHand() {
        ui.cards.removeAllViews()
        state.hand.forEachIndexed { index, card ->
            ui.cards.addView(buildCardChip(card, index))
        }
        ui.handCount.text = "${state.cardCount} / 27"
        ui.handCount.setTextColor(
            when {
                state.cardCount == 0 -> getColor(R.color.text_secondary)
                state.cardCount == 27 -> getColor(R.color.success)
                else -> getColor(R.color.text_primary)
            }
        )
        ui.addCard.isEnabled = state.cardCount < 27
        ui.calculate.isEnabled = state.cardCount in 1..27 && state.score !is ScoreState.Running
        ui.calculate.text = if (state.score is ScoreState.Success || state.score is ScoreState.Stale) "重新计算" else "计算综合牌力"
    }

    private fun buildCardChip(card: Card, index: Int): Chip = Chip(this).apply {
        text = card.display
        isCloseIconVisible = true
        setTextColor(
            if (card.suit == Suit.HEART || card.suit == Suit.DIAMOND) getColor(R.color.card_red)
            else getColor(R.color.card_black)
        )
        contentDescription = "${cardAccessibleName(card)}，点击校正，点击删除图标移除"
        setOnClickListener { editCard(index) }
        setOnCloseIconClickListener {
            val next = state.hand.toMutableList().apply { removeAt(index) }
            setState(state.withHand(next))
        }
    }

    private fun renderScore() {
        when (val s = state.score) {
            ScoreState.None -> {
                ui.resultCard.visibility = View.GONE
                ui.calculateState.visibility = View.GONE
                ui.levelHint.visibility = View.GONE
            }
            ScoreState.Running -> {
                ui.resultCard.visibility = View.GONE
                ui.calculateState.visibility = View.VISIBLE
                ui.calculateState.text = "正在寻找最优拆牌…"
                ui.calculateState.setTextColor(getColor(R.color.text_primary))
                ui.levelHint.visibility = View.GONE
            }
            is ScoreState.Success -> {
                ui.calculateState.visibility = View.GONE
                ui.levelHint.visibility = View.GONE
                showResult(s.result)
            }
            is ScoreState.Failed -> {
                ui.resultCard.visibility = View.GONE
                ui.calculateState.visibility = View.VISIBLE
                ui.calculateState.text = "计算失败：${s.reason}"
                ui.calculateState.setTextColor(getColor(R.color.error))
                ui.levelHint.visibility = View.GONE
            }
            is ScoreState.Stale -> {
                ui.resultCard.visibility = View.GONE
                ui.calculateState.visibility = View.VISIBLE
                ui.calculateState.text = "手牌或级牌已改变，需要重新计算"
                ui.calculateState.setTextColor(getColor(R.color.gold))
                ui.levelHint.visibility = View.GONE
            }
        }
    }

    private fun showResult(result: HandResult) {
        ui.resultCard.visibility = View.VISIBLE
        ui.resultCount.text = "${result.cardCount} 张"
        ui.grade.text = result.grade
        ui.spi.text = "%.2f".format(result.spi)
        ui.nspi.text = "%.1f".format(result.nspi)
        ui.summary.text = buildString {
            append("评分分子 ")
            append("%.2f".format(result.numerator))
            append(" · 计划出牌轮数 ${result.rounds}")
            append(" · T_max ${result.tMax}")
        }
        if (result.waste > 0) {
            ui.penaltyNote.visibility = View.VISIBLE
            ui.penaltyNote.text = "散牌惩罚：P = " + "%.0f".format(result.penalty) + "（${result.waste} 组孤立单张/小对子）"
        } else {
            ui.penaltyNote.visibility = View.GONE
        }
        ui.breakdown.text = result.melds.sortedByDescending { it.score }
            .joinToString("\n") { m -> m.label + "　" + "%d".format(m.score) }
        ui.sortedImage.isEnabled = result.cardCount == 27
        ui.sortedImage.text = if (result.cardCount == 27) "生成并保存最优拆牌图" else "需 27 张手牌才能生成拆牌图"
    }

    private fun renderOutput() {
        when (val s = state.outputImage) {
            OutputImageState.None -> ui.outputState.visibility = View.GONE
            OutputImageState.Generating -> {
                ui.outputState.visibility = View.VISIBLE
                ui.outputState.text = "正在生成最优拆牌图…"
                ui.outputState.setTextColor(getColor(R.color.text_primary))
            }
            OutputImageState.Generated -> {
                ui.outputState.visibility = View.VISIBLE
                ui.outputState.text = "已生成最优拆牌图"
                ui.outputState.setTextColor(getColor(R.color.success))
            }
            OutputImageState.Saving -> {
                ui.outputState.visibility = View.VISIBLE
                ui.outputState.text = "正在保存…"
                ui.outputState.setTextColor(getColor(R.color.text_primary))
            }
            is OutputImageState.Saved -> {
                ui.outputState.visibility = View.VISIBLE
                ui.outputState.text = "已保存到相册：${s.label}"
                ui.outputState.setTextColor(getColor(R.color.success))
            }
            is OutputImageState.Failed -> {
                ui.outputState.visibility = View.VISIBLE
                ui.outputState.text = "失败：${s.reason}"
                ui.outputState.setTextColor(getColor(R.color.error))
            }
        }
    }

    private fun isAiRunning() = state.aiRecognition is AiRecognitionState.Running

    private fun toggleBreakdown() {
        val visible = ui.breakdown.visibility == View.VISIBLE
        ui.breakdown.visibility = if (visible) View.GONE else View.VISIBLE
        ui.scoreExplain.visibility = if (visible) View.GONE else View.VISIBLE
        ui.toggleBreakdown.text = if (visible) "查看逐组拆牌明细" else "收起拆牌明细"
    }

    // ---------------------------------------------------------------- 输入

    private fun launchCamera() {
        val dir = File(cacheDir, "photos").apply { mkdirs() }
        cameraUri = FileProvider.getUriForFile(this, "$packageName.files", File(dir, "hand-${System.currentTimeMillis()}.jpg"))
        takePhoto.launch(requireNotNull(cameraUri))
    }

    private fun pickFromGallery() {
        val pick = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply { type = "image/*" }
        val handlers = packageManager.queryIntentActivities(pick, 0)
        listOf("com.android.gallery3d", "com.google.android.apps.photos", "com.sec.android.gallery3d", "com.miui.gallery")
            .firstNotNullOfOrNull { pkg -> handlers.firstOrNull { it.activityInfo.packageName == pkg } }
            ?.activityInfo?.let { pick.setClassName(it.packageName, it.name) }
        gallery.launch(pick)
    }

    /**
     * 读取相册/相机图片，最长边按约 1800px 降采样。
     *
     * [ImageDecoder] 只在 API 28+ 可用；minSdk 26 必须保留 BitmapFactory 回退，
     * 否则 Android 8/9 点「相册」会直接 NoClassDefFoundError/NoSuchMethodError。
     */
    private fun decodeInput(uri: Uri): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val decoderSource = ImageDecoder.createSource(contentResolver, uri)
            return ImageDecoder.decodeBitmap(decoderSource) { decoder, info, _ ->
                val scale = maxOf(info.size.width / 1800, info.size.height / 1800, 1)
                decoder.setTargetSize(info.size.width / scale, info.size.height / scale)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }

        // API 26–27：BitmapFactory 不支持 ImageDecoder 的 targetSize，用 inSampleSize 近似。
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 1800 || bounds.outHeight / (sample * 2) >= 1800) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        } ?: throw IllegalStateException("无法读取图片")
    }

    private fun load(uri: Uri, source: ImageSource) {
        try {
            val bitmap = decodeInput(uri)
            inputBitmap = bitmap
            outputBitmap = null
            lastInputSource = source
            setState(
                state.copy(
                    image = ImageState.Loaded(source, "输入图片 · ${source.label()}"),
                    localRecognition = LocalRecognitionState.Running,
                    outputImage = OutputImageState.None
                )
            )
            recognizer.recognize(bitmap, { result ->
                runOnUiThread {
                    val warnings = result.warnings.filter { it.isNotBlank() }
                    val local = when {
                        result.cards.isEmpty() && warnings.isNotEmpty() -> LocalRecognitionState.Failed(warnings.joinToString("；"))
                        warnings.isNotEmpty() -> LocalRecognitionState.Partial(result.cards.size, warnings)
                        else -> LocalRecognitionState.Success(result.cards.size)
                    }
                    setState(state.copy(hand = result.cards, localRecognition = local, score = ScoreState.None))
                    ui.mainScroll.post { ui.mainScroll.scrollTo(0, 0) }
                }
            }, { e ->
                runOnUiThread {
                    setState(state.copy(localRecognition = LocalRecognitionState.Failed(e.localizedMessage ?: "未知错误")))
                }
            })
        } catch (e: Exception) {
            setState(state.copy(image = ImageState.Failed(e.localizedMessage ?: "无法读取图片")))
        }
    }

    // ---------------------------------------------------------------- 手牌

    private fun editCard(index: Int?) {
        val ranks = Rank.entries
        val suits = Suit.entries
        val initial = index?.let { state.hand[it] }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(32, 8, 32, 0) }
        val rankSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, ranks.map { it.label })
        }
        val suitSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, suits.map { if (it == Suit.JOKER) "王" else it.symbol })
        }
        if (initial != null) {
            rankSpinner.setSelection(ranks.indexOf(initial.rank))
            suitSpinner.setSelection(suits.indexOf(initial.suit))
        }
        row.addView(rankSpinner, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(suitSpinner, LinearLayout.LayoutParams(0, -2, 1f))

        fun refreshSuit() {
            val isJoker = ranks[rankSpinner.selectedItemPosition].value > 14
            suitSpinner.isEnabled = !isJoker
            if (isJoker) suitSpinner.setSelection(suits.indexOf(Suit.JOKER))
            else if (suits[suitSpinner.selectedItemPosition] == Suit.JOKER) suitSpinner.setSelection(suits.indexOf(Suit.SPADE))
        }
        rankSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = refreshSuit()
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        refreshSuit()

        AlertDialog.Builder(this)
            .setTitle(if (index == null) "补录扑克牌" else "校正扑克牌")
            .setView(row)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val rank = ranks[rankSpinner.selectedItemPosition]
                val suit = if (rank.value > 14) Suit.JOKER else {
                    val picked = suits[suitSpinner.selectedItemPosition]
                    if (picked == Suit.JOKER) Suit.SPADE else picked
                }
                val hand = state.hand.toMutableList()
                if (index == null) {
                    if (hand.size >= 27) return@setPositiveButton toast("已有 27 张牌")
                    hand += Card(hand.size, rank, suit)
                } else {
                    hand[index] = hand[index].copy(rank = rank, suit = suit)
                }
                setState(state.withHand(hand))
            }
            .show()
    }

    private fun calculate() {
        if (state.cardCount !in 1..27) return
        val level = levels[ui.level.selectedItemPosition]
        val snapshot = state.hand.toList()
        setState(state.copy(score = ScoreState.Running))
        worker.execute {
            try {
                val result = HandScorer(level).score(snapshot)
                runOnUiThread { setState(state.copy(score = ScoreState.Success(result))) }
            } catch (e: Exception) {
                runOnUiThread { setState(state.copy(score = ScoreState.Failed(e.localizedMessage ?: "未知错误"))) }
            }
        }
    }

    private fun generateRandomHand() {
        val cards = randomCards()
        inputBitmap = imageRenderer.render(cards, "随机生成手牌")
        outputBitmap = null
        lastInputSource = ImageSource.RANDOM
        setState(
            state.copy(
                image = ImageState.Loaded(ImageSource.RANDOM, "输入图片 · 随机生成 27 张"),
                localRecognition = LocalRecognitionState.Success(cards.size),
                outputImage = OutputImageState.None
            ).withHand(cards)
        )
    }

    private fun randomCards(): List<Card> = buildList {
        repeat(2) {
            Rank.entries.filter { it.value <= 14 }.forEach { rank ->
                Suit.entries.filter { it != Suit.JOKER }.forEach { suit -> add(Card(size, rank, suit)) }
            }
            add(Card(size, Rank.SMALL_JOKER, Suit.JOKER))
            add(Card(size, Rank.BIG_JOKER, Suit.JOKER))
        }
    }.shuffled().take(27).mapIndexed { id, card -> card.copy(id = id) }

    // ---------------------------------------------------------------- AI

    private fun recognizeOnline() {
        val bitmap = inputBitmap ?: return
        val settings = aiSettingsStore.load()
        if (settings.requiresApiKey && settings.apiKey.isBlank()) {
            toast("HTTP API 源需要填写 API Key")
            showAiSettings()
            return
        }
        val source = AiSourceRegistry.create(settings, host = webHost, context = this)
        val name = settings.provider.ifBlank { settings.source.label }
        setState(state.copy(aiRecognition = AiRecognitionState.Running(name)))
        worker.execute {
            try {
                val jpeg = java.io.ByteArrayOutputStream()
                    .also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    .toByteArray()
                val cards = source.recognize(RecognizeRequest(jpegBytes = jpeg, prompt = AiRecognitionClient.PROMPT))
                runOnUiThread {
                    setState(
                        state.copy(
                            hand = cards,
                            aiRecognition = AiRecognitionState.Success(name, cards.size),
                            score = ScoreState.None
                        )
                    )
                }
            } catch (e: AiSourceError) {
                runOnUiThread {
                    setState(state.copy(aiRecognition = AiRecognitionState.Failed(name, e.toKind(), e.message ?: "")))
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setState(state.copy(aiRecognition = AiRecognitionState.Failed(name, AiFailureKind.UNKNOWN, e.localizedMessage ?: "未知错误")))
                }
            }
        }
    }

    private fun AiSourceError.toKind(): AiFailureKind = when (this) {
        is AiSourceError.NotLoggedIn -> AiFailureKind.NOT_LOGGED_IN
        is AiSourceError.NotReady -> AiFailureKind.PAGE_UNAVAILABLE
        is AiSourceError.UploadFailed -> AiFailureKind.UPLOAD_FAILED
        is AiSourceError.SubmitFailed -> AiFailureKind.SUBMIT_FAILED
        is AiSourceError.Timeout -> AiFailureKind.TIMEOUT
        is AiSourceError.ParseFailed -> AiFailureKind.PARSE_FAILED
        is AiSourceError.Busy -> AiFailureKind.BUSY
        is AiSourceError.Cancelled -> AiFailureKind.CANCELLED
        is AiSourceError.Unknown -> AiFailureKind.UNKNOWN
    }

    private fun refreshSourceState() {
        val settings = aiSettingsStore.load()
        val sourceId = settings.source
        val name = settings.provider.ifBlank { sourceId.label }
        setState(
            state.copy(
                source = SourceState(name = name, isWebView = sourceId.isWebView, health = HealthState.Unknown)
            )
        )
        if (sourceId.isWebView) {
            // AI-184：离屏自动挂接要求锚点已 attached 到窗口，否则 attachOffscreenViaProvider
            // 会拒绝（anchor.isAttachedToWindow=false）。此时 post，等 dispatchAttachedToWindow
            // 之后再做检查；不在这里直接发起，避免把宿主问题伪装成页面问题。
            if (ui.root.isAttachedToWindow) {
                checkHealth(settings)
            } else if (!isFinishing && !isDestroyed) {
                ui.root.post { refreshSourceState() }
            }
        }
    }

    /**
     * AI-067：每次健康检查带序号；Activity 已销毁或已有更新的一次检查时丢弃迟到结果，
     * 避免对失效界面更新状态。检查本身只读，不会销毁或重建业务 WebView。
     */
    private var healthCheckSeq = 0

    private fun checkHealth(settings: AiSettings) {
        val seq = ++healthCheckSeq
        setState(state.copy(source = state.source.copy(health = HealthState.Checking)))
        Thread({
            val health = try {
                val source = AiSourceRegistry.create(settings, host = webHost, context = this)
                // 与 AI 源管理页共用一把检查锁，避免冷启动时重复预热同一个 WebView。
                when (val h = HealthCheckGate.run { source.healthCheck() }) {
                    SourceHealth.Ready -> HealthState.Ready
                    is SourceHealth.NotLoggedIn -> HealthState.NotLoggedIn(h.hint)
                    is SourceHealth.Unavailable -> HealthState.Unavailable(h.reason)
                }
            } catch (e: Exception) {
                HealthState.Unavailable(e.localizedMessage ?: "检测失败")
            }
            runOnUiThread {
                if (isFinishing || isDestroyed || seq != healthCheckSeq) return@runOnUiThread
                setState(state.copy(source = state.source.copy(health = health)))
            }
        }, "ai-health-check").start()
    }

    private fun showAiSettings() {
        // AI 源配置已改为独立管理页面（doc/ai_configure.md）；返回后由 onResume 刷新健康态。
        startActivity(Intent(this, AiSettingsActivity::class.java))
    }

    private fun openLoginPage() {
        val settings = aiSettingsStore.load()
        val sourceId = settings.source
        val url = settings.homeUrl.ifBlank { AiSourcePresets.homeUrlFor(sourceId).orEmpty() }
        if (url.isBlank()) {
            toast("请先在 AI 源设置中填写网页地址")
            return
        }
        startActivity(
            Intent(this, AiProbeActivity::class.java)
                .putExtra(AiProbeActivity.EXTRA_URL, url)
                .putExtra(AiProbeActivity.EXTRA_SITE, sourceId.key)
        )
    }

    // ---------------------------------------------------------------- 图片

    private fun saveCurrentImage() {
        val bitmap = inputBitmap ?: return
        saveToGallery(bitmap, lastInputSource.label())
    }

    private fun generateSortedImage() {
        val result = (state.score as? ScoreState.Success)?.result ?: return
        if (state.cardCount != 27) return
        setState(state.copy(outputImage = OutputImageState.Generating))
        val snapshot = state.hand.toList()
        worker.execute {
            try {
                val bitmap = imageRenderer.render(snapshot, "最优拆牌排序", result.melds.sortedByDescending { it.score })
                runOnUiThread {
                    outputBitmap = bitmap
                    setState(state.copy(outputImage = OutputImageState.Generated))
                    saveToGallery(bitmap, "最优拆牌")
                }
            } catch (e: Exception) {
                runOnUiThread { setState(state.copy(outputImage = OutputImageState.Failed(e.localizedMessage ?: "生成失败"))) }
            }
        }
    }

    private fun saveToGallery(bitmap: Bitmap, label: String) {
        setState(state.copy(outputImage = OutputImageState.Saving))
        try {
            val name = "guandan-${label}-${System.currentTimeMillis()}.png"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/GuanDanScore")
                }
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("无法创建相册文件")
            contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: error("无法写入图片")
            setState(state.copy(outputImage = OutputImageState.Saved(name)))
            toast("已保存到 Pictures/GuanDanScore/$name")
        } catch (e: Exception) {
            setState(state.copy(outputImage = OutputImageState.Failed(e.localizedMessage ?: "保存失败")))
            outputBitmap?.let { setState(state.copy(outputImage = OutputImageState.Generated)) }
        }
    }

    // ---------------------------------------------------------------- 杂项

    private fun cardAccessibleName(card: Card): String = when (card.suit) {
        Suit.JOKER -> card.rank.label
        Suit.SPADE -> "黑桃${card.rank.label}"
        Suit.HEART -> "红心${card.rank.label}"
        Suit.CLUB -> "梅花${card.rank.label}"
        Suit.DIAMOND -> "方块${card.rank.label}"
    }

    private fun ImageSource.label(): String = when (this) {
        ImageSource.PHOTO -> "拍照"
        ImageSource.GALLERY -> "相册"
        ImageSource.RANDOM -> "随机生成"
        ImageSource.GENERATED -> "生成图"
    }

    private fun AiFailureKind.label(): String = when (this) {
        AiFailureKind.NOT_LOGGED_IN -> "需要登录"
        AiFailureKind.PAGE_UNAVAILABLE -> "页面不可用"
        AiFailureKind.UPLOAD_FAILED -> "上传失败"
        AiFailureKind.SUBMIT_FAILED -> "提交失败"
        AiFailureKind.TIMEOUT -> "回答超时"
        AiFailureKind.PARSE_FAILED -> "解析失败"
        AiFailureKind.BUSY -> "已有任务"
        AiFailureKind.CANCELLED -> "已取消"
        AiFailureKind.UNKNOWN -> "未知错误"
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onResume() {
        super.onResume()
        // AI-184：decorView 在 onResume 返回后才由 WindowManager 挂上窗口；
        // post 的 runnable 由 dispatchAttachedToWindow 触发，保证健康检查（以及其中的
        // 后台自动挂接）开始时视图树已经 attached。
        ui.root.post { refreshSourceState() }
    }

    /**
     * singleTask：桌面图标 / 快捷方式 / 深链再次进入时回到本实例，不会新建第二个主窗口。
     * 此刻 Activity 可能已 resumed（没有 onResume 回调），所以在这里补一次源状态刷新。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ui.root.post { refreshSourceState() }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        // AI-169：宿主是**进程级**的，不能由主界面销毁。
        // 真机实测：主界面 →「打开网页登录」→ 返回键回主界面再返回登录页时，
        // 主界面的 onDestroy 会把登录页正在使用的 WebView 销毁（日志 `destroy: webview released`），
        // 页面随即变成空白。登录态与页面属于整个进程，Activity 退出只停止本页任务。
        super.onDestroy()
    }
}
