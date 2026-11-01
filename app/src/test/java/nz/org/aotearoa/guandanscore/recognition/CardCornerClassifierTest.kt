package nz.org.aotearoa.guandanscore.recognition

import android.graphics.Bitmap
import android.graphics.Color
import nz.org.aotearoa.guandanscore.model.Rank
import nz.org.aotearoa.guandanscore.model.Suit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CardCornerClassifierTest {

    private fun probabilities(size: Int, winner: Int, winnerValue: Float): FloatArray {
        val rest = (1f - winnerValue) / (size - 1)
        return FloatArray(size) { index -> if (index == winner) winnerValue else rest }
    }

    private fun classifier(
        minConfidence: Float = 0.5f,
        minMargin: Float = 0.02f,
        temperature: Float = 1f,
    ) = CardCornerClassifier(
        runner = { emptyList() },
        manifest = classifierManifest(
            minConfidence = minConfidence,
            minMargin = minMargin,
            temperature = temperature,
        ),
    )

    @Test
    fun decode_maps_heads_to_rank_and_suit_labels() {
        val result = classifier().decode(
            rankRaw = probabilities(15, winner = 11, winnerValue = 0.85f),
            suitRaw = probabilities(5, winner = 2, winnerValue = 0.8f),
        )
        assertEquals(Rank.KING, result.rank)
        assertEquals(Suit.HEART, result.suit)
        assertEquals(0.85f * 0.8f, result.confidence, 1e-4f)
        assertFalse(result.unknown)
    }

    @Test
    fun decode_forces_joker_suit_for_joker_ranks() {
        val result = classifier().decode(
            rankRaw = probabilities(15, winner = 13, winnerValue = 0.9f),
            suitRaw = probabilities(5, winner = 1, winnerValue = 0.7f),
        )
        assertEquals(Rank.SMALL_JOKER, result.rank)
        assertEquals(Suit.JOKER, result.suit)
    }

    @Test
    fun decode_marks_low_confidence_as_unknown() {
        val result = classifier(minConfidence = 0.9f).decode(
            rankRaw = probabilities(15, winner = 0, winnerValue = 0.6f),
            suitRaw = probabilities(5, winner = 0, winnerValue = 0.6f),
        )
        assertTrue(result.unknown)
        assertTrue(result.confidence < 0.9f)
    }

    @Test
    fun decode_marks_low_margin_as_unknown() {
        val flat = FloatArray(15) { 1f / 15f }
        val result = classifier(minMargin = 0.05f).decode(
            rankRaw = flat,
            suitRaw = probabilities(5, winner = 3, winnerValue = 0.9f),
        )
        assertTrue(result.margin < 0.05f)
        assertTrue(result.unknown)
    }

    @Test
    fun softmaxWithTemperature_flattens_as_temperature_grows() {
        val raw = floatArrayOf(0.8f, 0.1f, 0.05f, 0.03f, 0.02f)
        val sharp = CardCornerClassifier.softmaxWithTemperature(raw, 1f)
        val flat = CardCornerClassifier.softmaxWithTemperature(raw, 4f)
        assertTrue(flat.max() < sharp.max())
        assertEquals(1f, flat.sum(), 1e-4f)
    }

    @Test
    fun pick_prefers_the_more_confident_direction() {
        val forward = classifier().decode(probabilities(15, 0, 0.6f), probabilities(5, 0, 0.6f))
        val backward = classifier().decode(probabilities(15, 0, 0.95f), probabilities(5, 0, 0.95f))
        assertEquals(backward.confidence, classifier().pick(forward, backward).confidence, 1e-6f)
        assertEquals(forward.confidence, classifier().pick(forward, forward).confidence, 1e-6f)
    }

    @Test
    fun warp_returns_manifest_crop_size() {
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        val quad = CardGeometry.obbQuad(100f, 100f, 60f, 40f, 0.2f)
        val crop = classifier().warp(bitmap, quad)
        assertEquals(96, crop.width)
        assertEquals(128, crop.height)
    }

    @Test
    fun infer_reads_heads_by_label_count_not_output_order() {
        val manifest = classifierManifest(temperature = 1f, minConfidence = 0.5f)
        val runner = ModelRunner {
            listOf(
                probabilities(5, winner = 2, winnerValue = 0.8f),
                probabilities(15, winner = 8, winnerValue = 0.9f),
            )
        }
        val classifier = CardCornerClassifier(runner, manifest)
        val bitmap = Bitmap.createBitmap(96, 128, Bitmap.Config.ARGB_8888)
        val result = classifier.infer(bitmap, rotation = 0f)
        assertEquals(Rank.TEN, result.rank)
        assertEquals(Suit.HEART, result.suit)
    }
}
