package nz.org.aotearoa.guandanscore.recognition

import android.graphics.BitmapFactory
import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * T4.7: real-device learned-path benchmark.
 *
 * Measures [LearnedCardRecognizer] (LiteRT detector + classifier), not the OCR fallback:
 * case116 has six learned detections at the shipped 960/0.6 threshold, so every iteration
 * exercises the model path. The host pulls the JSON and passes it to
 * `model_training/device_benchmark.py --runner-report`.
 */
@RunWith(AndroidJUnit4::class)
class RealDeviceBenchmarkTest {

    @Test
    fun benchmarkRecognition() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val testContext = instrumentation.context

        val bitmap = testContext.assets.open("case116.jpg").use { BitmapFactory.decodeStream(it) }
            ?: error("case116.jpg missing from androidTest assets")

        val availability = ModelAssets.load(target)
        assertTrue("model assets unavailable: $availability", availability is ModelAvailability.Ready)
        val ready = availability as ModelAvailability.Ready

        val timings = mutableListOf<Long>()
        val memory = mutableListOf<Int>()

        fun once(): LearnedOutcome {
            val started = System.nanoTime()
            val outcome = ready.recognizer.recognize(bitmap)
            timings += (System.nanoTime() - started) / 1_000_000

            val info = Debug.MemoryInfo()
            Debug.getMemoryInfo(info)
            memory += info.totalPss / 1024

            assertTrue("learned path did not return a result: $outcome", outcome is LearnedOutcome.Success)
            return outcome
        }

        repeat(10) { once() }
        repeat(20) { once() }

        val payload = JSONObject()
        payload.put("timings_ms", JSONArray().also { array -> timings.forEach { array.put(it) } })
        payload.put("peak_memory_mb", memory.maxOrNull() ?: 0)
        payload.put("samples", timings.size)
        payload.put("model_version", ready.version)
        payload.put("model_detector_sha256", ready.detectorSha256)
        payload.put("model_classifier_sha256", ready.classifierSha256)
        payload.put("device_model", android.os.Build.MODEL)
        payload.put("api_level", android.os.Build.VERSION.SDK_INT)

        val directory = target.getExternalFilesDir(null) ?: target.filesDir
        val output = File(directory, "t4_7_runner.json")
        output.writeText(payload.toString())
        println("T4_7_REPORT=${output.absolutePath}")
    }
}
