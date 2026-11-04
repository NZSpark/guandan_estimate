package nz.org.aotearoa.guandanscore.recognition

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale

/**
 * T4.1/T4.6: 模型资产加载与校验。
 *
 * 规则：manifest 与资产都必须来自 APK 内的 `assets/models`，运行时不下载；加载前逐个核对
 * SHA-256 与字节数，任何不一致都返回 [ModelAvailability.Unavailable] 让上层回退，
 * 绝不带着错误的模型继续推理。上一版资产保留在同一目录下由 `package_model.py` 管理。
 */
sealed interface ModelAvailability {
    data class Ready(
        val recognizer: LearnedCardRecognizer,
        val version: String,
        val detectorSha256: String,
        val classifierSha256: String,
    ) : ModelAvailability

    data class Unavailable(val reason: String) : ModelAvailability
}

/** LiteRT 解释器的最小封装；输入输出都走直接缓冲区，避免每次推理重新分配内存。 */
class LiteRtRunner(private val interpreter: Interpreter) : ModelRunner, AutoCloseable {
    private val inputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(interpreter.getInputTensor(0).numBytes()).order(ByteOrder.nativeOrder())
    private val outputBuffers: Map<Int, ByteBuffer> = (0 until interpreter.outputTensorCount).associateWith { index ->
        ByteBuffer.allocateDirect(interpreter.getOutputTensor(index).numBytes()).order(ByteOrder.nativeOrder())
    }

    override fun run(input: FloatArray): List<FloatArray> {
        inputBuffer.rewind()
        inputBuffer.asFloatBuffer().put(input)
        val outputs = HashMap<Int, Any>(outputBuffers.size)
        outputBuffers.forEach { (index, buffer) -> outputs[index] = buffer }
        interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputs)
        return outputBuffers.entries.sortedBy { it.key }.map { (_, buffer) ->
            buffer.rewind()
            val floats = FloatArray(buffer.remaining() / 4)
            buffer.asFloatBuffer().get(floats)
            floats
        }
    }

    override fun close() = interpreter.close()
}

object ModelAssets {
    const val MANIFEST_ASSET = "models/model_manifest.json"

    fun load(context: Context): ModelAvailability = try {
        val manifest = ModelManifest.parse(readText(context, MANIFEST_ASSET))
        verify(context, manifest)?.let { throw ManifestException(it) }
        val detectorBytes = read(context, manifest.detector.asset)
        val classifierBytes = read(context, manifest.classifier.asset)
        val detectorRunner = LiteRtRunner(interpreterOf(detectorBytes))
        val classifierRunner = LiteRtRunner(interpreterOf(classifierBytes))
        ModelAvailability.Ready(
            recognizer = LearnedCardRecognizer(
                detector = CardCornerDetector(detectorRunner, manifest.detector),
                classifier = CardCornerClassifier(classifierRunner, manifest.classifier),
            ),
            version = manifest.version,
            detectorSha256 = manifest.detector.sha256,
            classifierSha256 = manifest.classifier.sha256,
        )
    } catch (error: ManifestException) {
        ModelAvailability.Unavailable("manifest 非法：${error.message}")
    } catch (error: Throwable) {
        ModelAvailability.Unavailable("模型加载失败：${error.javaClass.simpleName}: ${error.message}")
    }

    /** 只校验资产：返回 null 表示全部一致，否则返回首个不一致原因（可脱离解释器单测）。 */
    fun verify(context: Context, manifest: ModelManifest): String? = try {
        verified(context, manifest.detector.asset, manifest.detector.sha256, manifest.detector.sizeBytes)
        verified(context, manifest.classifier.asset, manifest.classifier.sha256, manifest.classifier.sizeBytes)
        null
    } catch (error: Throwable) {
        error.message ?: error.javaClass.simpleName
    }

    private fun readText(context: Context, path: String): String =
        context.assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }

    private fun read(context: Context, path: String): ByteArray =
        context.assets.open(path).use { it.readBytes() }

    private fun verified(context: Context, path: String, expectedSha: String, expectedSize: Long): ByteArray {
        val bytes = read(context, path)
        if (bytes.size.toLong() != expectedSize) {
            throw ManifestException("$path 大小 ${bytes.size} 与 manifest $expectedSize 不一致")
        }
        val actual = sha256(bytes)
        if (actual != expectedSha.lowercase(Locale.ROOT)) {
            throw ManifestException("$path hash 不匹配：$actual")
        }
        return bytes
    }

    private fun interpreterOf(bytes: ByteArray): Interpreter {
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        buffer.put(bytes)
        buffer.rewind()
        return Interpreter(buffer)
    }

    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
