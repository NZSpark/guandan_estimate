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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.chip.Chip
import nz.org.aotearoa.guandanscore.databinding.ActivityMainBinding
import nz.org.aotearoa.guandanscore.imaging.HandImageRenderer
import nz.org.aotearoa.guandanscore.model.*
import nz.org.aotearoa.guandanscore.recognition.CardRecognizer
import nz.org.aotearoa.guandanscore.recognition.AiRecognitionClient
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
    private lateinit var aiSettingsStore: AiSettingsStore
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
        if (settings.apiKey.isBlank()) { showAiSettings(); toast("请先填写 API Key"); return }
        ui.onlineRecognition.isEnabled = false
        ui.onlineRecognition.text = "AI 识别中…"
        ui.status.text = "正在通过 ${settings.provider} / ${settings.model} 联网识图…"
        worker.execute {
            try {
                val cards = aiClient.recognize(bitmap, settings)
                runOnUiThread {
                    hand.clear(); hand += cards; renderCards()
                    ui.status.text = "AI 联网识别：${cards.size}/27 张；请核对点数和花色后计算"
                    ui.onlineRecognition.isEnabled = true; ui.onlineRecognition.text = "联网识图"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    ui.status.text = "联网识图失败：${e.localizedMessage}；已保留原有识别结果"
                    ui.onlineRecognition.isEnabled = true; ui.onlineRecognition.text = "联网识图"
                }
            }
        }
    }

    private fun showAiSettings() {
        val profiles = aiSettingsStore.loadAll()
        val current = aiSettingsStore.load()
        fun field(hint: String, value: String, secret: Boolean = false) = EditText(this).apply {
            this.hint = hint; setText(value); setSingleLine(true)
            if (secret) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val selector = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                profiles.map { "${it.provider} · ${it.model}" } + "＋ 新建 Provider")
        }
        val provider = field("Provider 名称", current.provider)
        val endpoint = field("HTTPS API 地址", current.endpoint)
        val model = field("模型", current.model)
        val key = field("API Key（仅本机加密保存）", current.apiKey, true)
        fun showProfile(profile: AiSettings?) {
            provider.setText(profile?.provider ?: "")
            endpoint.setText(profile?.endpoint ?: "https://api.openai.com/v1/responses")
            model.setText(profile?.model ?: "gpt-5.6-sol")
            key.setText(profile?.apiKey ?: "")
        }
        selector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) =
                showProfile(profiles.getOrNull(position))
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(40, 8, 40, 0)
            addView(selector); addView(provider); addView(endpoint); addView(model); addView(key)
        }
        selector.setSelection(profiles.indexOfFirst { it.id == current.id }.coerceAtLeast(0))
        AlertDialog.Builder(this).setTitle("AI Provider 设置").setView(form)
            .setNegativeButton("取消", null).setNeutralButton("删除所选") { _, _ ->
                profiles.getOrNull(selector.selectedItemPosition)?.let {
                    aiSettingsStore.delete(it.id); toast("已删除 ${it.provider}")
                }
            }.setPositiveButton("保存并选用") { _, _ ->
                val selected = profiles.getOrNull(selector.selectedItemPosition)
                val settings = AiSettings(provider.text.toString(), endpoint.text.toString(), model.text.toString(), key.text.toString(), selected?.id ?: java.util.UUID.randomUUID().toString())
                if (settings.provider.isBlank() || settings.endpoint.isBlank() || settings.model.isBlank()) toast("Provider、接口地址和模型不能为空")
                else if (!settings.endpoint.startsWith("https://")) toast("接口地址必须使用 HTTPS")
                else { aiSettingsStore.save(settings); aiSettingsStore.select(settings.id); toast("已保存并选用 ${settings.provider}") }
            }.show()
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
                    ui.summary.text = "${snapshot.size} 张 · 总分 ${result.total} ÷ ${result.rounds} 轮 · T_max ${result.tMax}"
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
    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }
}
