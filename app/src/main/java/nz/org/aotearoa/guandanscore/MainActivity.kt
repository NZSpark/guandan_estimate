package nz.org.aotearoa.guandanscore

import android.Manifest
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.chip.Chip
import nz.org.aotearoa.guandanscore.databinding.ActivityMainBinding
import nz.org.aotearoa.guandanscore.imaging.HandImageRenderer
import nz.org.aotearoa.guandanscore.model.*
import nz.org.aotearoa.guandanscore.recognition.CardRecognizer
import nz.org.aotearoa.guandanscore.recognition.source.AiSourceError
import nz.org.aotearoa.guandanscore.recognition.source.AiSourceRegistry
import nz.org.aotearoa.guandanscore.recognition.source.RecognizeRequest
import nz.org.aotearoa.guandanscore.recognition.source.SourceHealth
import nz.org.aotearoa.guandanscore.recognition.webview.FileChooserHandler
import nz.org.aotearoa.guandanscore.recognition.webview.WebViewHost
import nz.org.aotearoa.guandanscore.ui.AiSettingsDialog
import nz.org.aotearoa.guandanscore.recognition.AiRecognitionClient
import nz.org.aotearoa.guandanscore.recognition.AiPresets
import nz.org.aotearoa.guandanscore.recognition.AiSettings
import nz.org.aotearoa.guandanscore.recognition.AiSettingsStore
import nz.org.aotearoa.guandanscore.scoring.HandScorer
import nz.org.aotearoa.guandanscore.scoring.HandResult
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var ui: ActivityMainBinding
    private val recognizer by lazy { CardRecognizer(this) }
    private val aiClient = AiRecognitionClient()
    private val webHost by lazy { WebViewHost.get(this) }
    private lateinit var aiSettingsStore: AiSettingsStore
    private lateinit var webContainer: android.widget.FrameLayout
    private val imageRenderer by lazy { HandImageRenderer(this) }
    private val worker = Executors.newSingleThreadExecutor()
    private val hand = mutableListOf<Card>()
    private var cameraUri: Uri? = null
    private var currentBitmap: Bitmap? = null
    private var lastResult: HandResult? = null

    private val gallery = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) result.data?.data?.let(::load)
    }
    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) cameraUri?.let(::load) }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) launchCamera() else toast("需要相机权限才能拍照") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); ui = ActivityMainBinding.inflate(layoutInflater); setContentView(ui.root)
        aiSettingsStore = AiSettingsStore(this)
        // 可见挂接容器：必须有真实尺寸，否则 WebView 的 innerHeight 为 0，页面不出帧（M3）。
        webContainer = android.widget.FrameLayout(this).apply {
            id = android.view.View.generateViewId()
            visibility = android.view.View.GONE
        }
        // ScrollView 只能有一个直接子节点（activity_main.xml 里的 mainContent LinearLayout），
        // 因此容器必须挂到 mainContent 上，直接 add 到 mainScroll 会抛 IllegalStateException。
        ui.mainContent.addView(
            webContainer,
            0,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                (360 * resources.displayMetrics.density).toInt()
            ).apply { topMargin = (8 * resources.displayMetrics.density).toInt() }
        )
        val levels = Rank.entries.filter { it.value <= 14 }
        ui.level.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, levels.map { it.label })
        ui.camera.setOnClickListener { cameraPermission.launch(Manifest.permission.CAMERA) }
        ui.gallery.setOnClickListener {
            val pick = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply { type = "image/*" }
            // Prefer the device's actual gallery so unrelated apps that also advertise ACTION_PICK
            // (for example printer utilities) do not trigger an app chooser.
            val handlers = packageManager.queryIntentActivities(pick, 0)
            val preferred = listOf("com.android.gallery3d", "com.google.android.apps.photos", "com.sec.android.gallery3d", "com.miui.gallery")
                .firstNotNullOfOrNull { packageName -> handlers.firstOrNull { it.activityInfo.packageName == packageName } }
            preferred?.activityInfo?.let { pick.setClassName(it.packageName, it.name) }
            gallery.launch(pick)
        }
        ui.addCard.setOnClickListener { editCard(null) }
        ui.calculate.setOnClickListener { calculate(levels[ui.level.selectedItemPosition]) }
        ui.randomHand.setOnClickListener { generateRandomHand() }
        ui.saveImage.setOnClickListener { currentBitmap?.let { saveToGallery(it, "随机手牌") } }
        ui.sortedImage.setOnClickListener { generateSortedImage() }
        ui.onlineRecognition.setOnClickListener { recognizeOnline() }
        ui.aiSettings.setOnClickListener { showAiSettings() }
        // 网页 AI 需要一个可见容器才能出帧（M2）。挂到滚动区顶部的 1dp 占位里，
        // 用户切到网页登录页时可在此看到页面；识牌期间不需额外遮挡。
        webHost.setAttachTarget {
            // GONE 的视图没有测量尺寸；挂接前先让它可见（用户首次识牌时会看到网页）。
            webContainer.visibility = android.view.View.VISIBLE
            webContainer
        }
        webHost.fileChooserHandler = FileChooserHandler { _ ->
            // 首版只记录并拒绝：文件选择桥接在真机验证前不接管系统选择器（AI-009D）。
            false
        }
    }

    private fun launchCamera() {
        val dir = File(cacheDir, "photos").apply { mkdirs() }
        cameraUri = FileProvider.getUriForFile(this, "$packageName.files", File(dir, "hand-${System.currentTimeMillis()}.jpg"))
        takePhoto.launch(requireNotNull(cameraUri))
    }

    private fun load(uri: Uri) {
        try {
            val source = ImageDecoder.createSource(contentResolver, uri)
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val scale = maxOf(info.size.width / 1800, info.size.height / 1800, 1)
                decoder.setTargetSize(info.size.width / scale, info.size.height / scale)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            currentBitmap = bitmap; ui.saveImage.isEnabled = true
            ui.onlineRecognition.isEnabled = true
            ui.photo.setImageBitmap(bitmap); ui.status.text = "第一步：正在优先识别点数…\n第二步将根据点数位置识别花色"
            recognizer.recognize(bitmap, { result ->
                hand.clear(); hand += result.cards; renderCards()
                ui.status.text = result.warnings.joinToString("；")
                ui.mainScroll.post { ui.mainScroll.scrollTo(0, 0) }
            }, { ui.status.text = "识别失败：${it.localizedMessage}" })
        } catch (e: Exception) { toast("无法读取图片：${e.localizedMessage}") }
    }

    private fun recognizeOnline() {
        val bitmap = currentBitmap ?: return
        val settings = aiSettingsStore.load()
        val source = AiSourceRegistry.create(settings, host = webHost, context = this)
        // AI-141：只有 API 源才需要 API Key；网页源复用登录态。
        if (source.requiresNetworkConfig && settings.apiKey.isBlank()) {
            showAiSettings()
            toast("请先填写 API Key")
            return
        }
        ui.onlineRecognition.isEnabled = false
        ui.onlineRecognition.text = "AI 识别中…"
        ui.status.text = "正在通过 ${settings.provider} 识图…"
        worker.execute {
            try {
                val jpeg = java.io.ByteArrayOutputStream().also {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)
                }.toByteArray()
                val cards = source.recognize(
                    RecognizeRequest(jpegBytes = jpeg, prompt = AiRecognitionClient.PROMPT)
                )
                runOnUiThread {
                    hand.clear(); hand += cards; renderCards()
                    ui.status.text = "${settings.provider} 识别：${cards.size}/27 张；请核对点数和花色后计算"
                    ui.onlineRecognition.isEnabled = true; ui.onlineRecognition.text = "联网识图"
                }
            } catch (e: AiSourceError) {
                runOnUiThread {
                    ui.status.text = "识图失败：${e.message}；已保留原有识别结果"
                    ui.onlineRecognition.isEnabled = true; ui.onlineRecognition.text = "联网识图"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    ui.status.text = "识图失败：${e.localizedMessage}；已保留原有识别结果"
                    ui.onlineRecognition.isEnabled = true; ui.onlineRecognition.text = "联网识图"
                }
            }
        }
    }

    private fun showAiSettings() {
        AiSettingsDialog(
            activity = this,
            store = aiSettingsStore,
            healthProvider = { settings ->
                val source = AiSourceRegistry.create(settings, host = webHost, context = this)
                when (val h = source.healthCheck()) {
                    SourceHealth.Ready -> "已就绪"
                    is SourceHealth.NotLoggedIn -> "未登录：${h.hint}"
                    is SourceHealth.Unavailable -> "不可用：${h.reason}"
                }
            },
            onSaved = { settings ->
                toast("当前 AI 源：${settings.provider}")
            }
        ).show()
    }

    private fun renderCards() {
        hand.replaceAllIndexed { i, c -> c.copy(id = i) }
        ui.cards.removeAllViews()
        hand.forEachIndexed { index, card ->
            val chip = Chip(this).apply {
                text = card.display; isCloseIconVisible = true
                setTextColor(if (card.suit == Suit.HEART || card.suit == Suit.DIAMOND) 0xffb3261e.toInt() else 0xff1b1b1f.toInt())
                setOnClickListener { editCard(index) }
                setOnCloseIconClickListener { hand.removeAt(index); renderCards() }
            }
            ui.cards.addView(chip)
        }
        ui.calculate.isEnabled = hand.isNotEmpty() && hand.size <= 27
        val suffix = if (hand.isEmpty()) "，请先识别或补录扑克牌" else "，可以按当前牌数计算"
        ui.status.text = "已确认 ${hand.size}/27 张$suffix"
        ui.resultCard.visibility = View.GONE
        lastResult = null
    }

    private fun editCard(index: Int?) {
        val ranks = Rank.entries; val suits = Suit.entries
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(32, 8, 32, 0) }
        val rank = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, ranks.map { it.label }) }
        val suit = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, suits.map { if (it == Suit.JOKER) "王" else it.symbol }) }
        row.addView(rank, LinearLayout.LayoutParams(0, -2, 1f)); row.addView(suit, LinearLayout.LayoutParams(0, -2, 1f))
        index?.let { rank.setSelection(ranks.indexOf(hand[it].rank)); suit.setSelection(suits.indexOf(hand[it].suit)) }
        AlertDialog.Builder(this).setTitle(if (index == null) "补录扑克牌" else "校正扑克牌").setView(row)
            .setNegativeButton("取消", null).setPositiveButton("保存") { _, _ ->
                val r = ranks[rank.selectedItemPosition]; val s = if (r.value > 14) Suit.JOKER else suits[suit.selectedItemPosition].takeUnless { it == Suit.JOKER } ?: Suit.SPADE
                val card = Card(index ?: hand.size, r, s)
                if (index == null) { if (hand.size < 27) hand += card else toast("已经有 27 张牌") } else hand[index] = card
                renderCards()
            }.show()
    }

    private fun calculate(level: Rank) {
        ui.calculate.isEnabled = false; ui.calculate.text = "正在寻找最优拆牌…"
        val snapshot = hand.toList()
        worker.execute {
            try {
                val result = HandScorer(level).score(snapshot)
                runOnUiThread {
                    lastResult = result
                    ui.spi.text = "SPI %.2f · NSPI %.1f · %s".format(result.spi, result.nspi, result.grade)
                    val penaltyNote = if (result.waste > 0) "（含散牌惩罚 P${result.waste * 15}）" else ""
                    ui.summary.text = "${snapshot.size} 张 · SPI分子 ${"%.2f".format(result.numerator)}$penaltyNote ÷ ${result.rounds} 轮 · T_max ${result.tMax}"
                    ui.breakdown.text = result.melds.sortedByDescending { it.score }.joinToString("\n") { it.label }
                    ui.sortedImage.isEnabled = snapshot.size == 27
                    ui.resultCard.visibility = View.VISIBLE; ui.calculate.isEnabled = true; ui.calculate.text = "重新计算"
                }
            } catch (e: Exception) { runOnUiThread { toast("计算失败：${e.localizedMessage}"); ui.calculate.isEnabled = true; ui.calculate.text = "计算综合牌力" } }
        }
    }

    private fun generateRandomHand() {
        val deck = randomCards()
        hand.clear(); hand += deck
        currentBitmap = imageRenderer.render(hand, "随机生成手牌")
        ui.photo.setImageBitmap(currentBitmap); ui.saveImage.isEnabled = true
        renderCards(); ui.status.text = "已随机生成并确认 27 张牌，可保存或直接计算"
    }

    private fun randomCards(): List<Card> = buildList {
            repeat(2) { copy ->
                Rank.entries.filter { it.value <= 14 }.forEach { rank ->
                    Suit.entries.filter { it != Suit.JOKER }.forEach { suit -> add(Card(size, rank, suit)) }
                }
                add(Card(size, Rank.SMALL_JOKER, Suit.JOKER)); add(Card(size, Rank.BIG_JOKER, Suit.JOKER))
            }
        }.shuffled().take(27).mapIndexed { id, card -> card.copy(id = id) }

    private fun generateSortedImage() {
        val result = lastResult ?: return
        currentBitmap = imageRenderer.render(hand, "最优拆牌排序", result.melds.sortedByDescending { it.score })
        ui.photo.setImageBitmap(currentBitmap)
        ui.saveImage.isEnabled = true
        saveToGallery(requireNotNull(currentBitmap), "最优拆牌")
    }

    private fun saveToGallery(bitmap: Bitmap, label: String, notify: Boolean = true): Uri? {
        try {
            val name = "guandan-${label}-${System.currentTimeMillis()}.png"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/GuanDanScore")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("无法创建相册文件")
            contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: error("无法写入图片")
            if (notify) toast("已保存到 Pictures/GuanDanScore/$name")
            return uri
        } catch (e: Exception) {
            if (notify) toast("保存失败：${e.localizedMessage}")
            return null
        }
    }


    private inline fun <T> MutableList<T>.replaceAllIndexed(block: (Int, T) -> T) { for (i in indices) this[i] = block(i, this[i]) }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    override fun onResume() {
        super.onResume()
        // 回到前台时正是“之前不存在的窗口现在可能存在”的时刻（SMSProxy D43）。
        webHost.attach()
    }

    override fun onPause() {
        // AI-021：退到后台只“移除”，保留 WebView 与登录会话，onResume 直接挂回。
        webHost.detachFromVisible()
        super.onPause()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        // AI-021：Activity 真正销毁时才是终态，必须拆掉宿主并清理状态。
        webHost.destroy()
        super.onDestroy()
    }
}
