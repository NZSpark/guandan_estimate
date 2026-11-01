package nz.org.aotearoa.guandanscore.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ModelManifestTest {

    @Test
    fun parse_reads_detector_and_classifier_fields() {
        val manifest = ModelManifest.parse(validManifestJson())
        assertEquals(1, manifest.schemaVersion)
        assertEquals("test.1", manifest.version)
        assertEquals(8400, manifest.detector.anchors)
        assertEquals(6, manifest.detector.channelStride)
        assertEquals(TensorLayout.NCHW, manifest.detector.inputLayout)
        assertEquals(0.4f, manifest.detector.scoreThreshold, 1e-6f)
        assertEquals(15, manifest.classifier.rankLabels.size)
        assertEquals(5, manifest.classifier.suitLabels.size)
        assertEquals(96, manifest.classifier.cropWidth)
    }

    @Test
    fun parse_rejects_unknown_schema_version() {
        assertThrows(ManifestException::class.java) {
            ModelManifest.parse(validManifestJson(schemaVersion = 2))
        }
    }

    @Test
    fun parse_rejects_out_of_range_threshold() {
        val text = validManifestJson(detectorScoreThreshold = "0.0")
        assertThrows(ManifestException::class.java) { ModelManifest.parse(text) }
    }

    @Test
    fun parse_rejects_wrong_label_count() {
        val text = validManifestJson(classifierSuitLabels = "[\"CLUB\",\"DIAMOND\",\"HEART\",\"SPADE\"]")
        assertThrows(ManifestException::class.java) { ModelManifest.parse(text) }
    }

    @Test
    fun parse_rejects_malformed_sha256() {
        val text = validManifestJson().replace("a".repeat(64), "not-a-hash")
        assertThrows(ManifestException::class.java) { ModelManifest.parse(text) }
    }

    @Test
    fun parse_rejects_non_json() {
        assertThrows(ManifestException::class.java) { ModelManifest.parse("{} nope") }
    }

    @Test
    fun apk_assets_match_the_frozen_manifest() {
        val context = RuntimeEnvironment.getApplication()
        val manifest = ModelManifest.parse(
            context.assets.open(ModelAssets.MANIFEST_ASSET).use { it.readBytes().toString(Charsets.UTF_8) },
        )
        assertNull(ModelAssets.verify(context, manifest))

        val detector = context.assets.open(manifest.detector.asset).use { it.readBytes() }
        val classifier = context.assets.open(manifest.classifier.asset).use { it.readBytes() }
        assertEquals(manifest.detector.sizeBytes, detector.size.toLong())
        assertEquals(manifest.classifier.sizeBytes, classifier.size.toLong())
        assertEquals(manifest.detector.sha256, ModelAssets.sha256(detector))
        assertEquals(manifest.classifier.sha256, ModelAssets.sha256(classifier))
    }

    @Test
    fun apk_assets_stay_within_the_release_size_budget() {
        val context = RuntimeEnvironment.getApplication()
        val manifest = ModelManifest.parse(
            context.assets.open(ModelAssets.MANIFEST_ASSET).use { it.readBytes().toString(Charsets.UTF_8) },
        )
        val total = manifest.detector.sizeBytes + manifest.classifier.sizeBytes
        assertTrue("模型资产合计 $total 字节超过 15MB 门槛", total <= 15L * 1024 * 1024)
    }

    @Test
    fun verify_reports_hash_mismatch_without_touching_the_interpreter() {
        val context = RuntimeEnvironment.getApplication()
        val manifest = ModelManifest.parse(validManifestJson())
        val error = ModelAssets.verify(context, manifest)
        assertNotNull(error)
        assertTrue(error!!.contains("hash") || error.contains("大小"))
    }
}
