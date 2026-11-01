package nz.org.aotearoa.guandanscore.recognition

/** 测试用 manifest 构造器；默认值与 `app/src/main/assets/models/model_manifest.json` 一致。 */
fun detectorManifest(
    anchors: Int = 4,
    channelStride: Int = 6,
    scoreThreshold: Float = 0.4f,
    nmsIou: Float = 0.5f,
    minCornerPx: Float = 4f,
    edgeMarginPx: Int = 1,
    inputWidth: Int = 640,
    inputHeight: Int = 640,
    inputLayout: TensorLayout = TensorLayout.NCHW,
) = DetectorManifest(
    asset = "models/card_corner.tflite",
    sha256 = "a".repeat(64),
    sizeBytes = 1_000L,
    inputWidth = inputWidth,
    inputHeight = inputHeight,
    inputLayout = inputLayout,
    channelStride = channelStride,
    anchors = anchors,
    scoreThreshold = scoreThreshold,
    nmsIou = nmsIou,
    minCornerPx = minCornerPx,
    edgeMarginPx = edgeMarginPx,
    labels = listOf("card_corner"),
)

fun classifierManifest(
    minConfidence: Float = 0.5f,
    minMargin: Float = 0.02f,
    temperature: Float = 2.0f,
    cropWidth: Int = 96,
    cropHeight: Int = 128,
) = ClassifierManifest(
    asset = "models/classifier.tflite",
    sha256 = "b".repeat(64),
    sizeBytes = 2_000L,
    cropWidth = cropWidth,
    cropHeight = cropHeight,
    rankLabels = listOf("2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K", "A", "SJ", "BJ"),
    suitLabels = listOf("CLUB", "DIAMOND", "HEART", "SPADE", "JOKER"),
    temperature = temperature,
    minConfidence = minConfidence,
    minMargin = minMargin,
    normalization = "rgb/255",
)

fun validManifestJson(
    detectorScoreThreshold: String = "0.4",
    schemaVersion: Int = 1,
    classifierSuitLabels: String = "[\"CLUB\",\"DIAMOND\",\"HEART\",\"SPADE\",\"JOKER\"]",
): String = """
{
  "schema_version": $schemaVersion,
  "version": "test.1",
  "detector": {
    "asset": "models/card_corner.tflite",
    "sha256": "${"a".repeat(64)}",
    "size_bytes": 1000,
    "input_width": 640,
    "input_height": 640,
    "input_layout": "NCHW",
    "channel_stride": 6,
    "anchors": 8400,
    "score_threshold": $detectorScoreThreshold,
    "nms_iou": 0.5,
    "min_corner_px": 8.0,
    "edge_margin_px": 2,
    "labels": ["card_corner"]
  },
  "classifier": {
    "asset": "models/classifier.tflite",
    "sha256": "${"b".repeat(64)}",
    "size_bytes": 2000,
    "crop_width": 96,
    "crop_height": 128,
    "rank_labels": ["2","3","4","5","6","7","8","9","10","J","Q","K","A","SJ","BJ"],
    "suit_labels": $classifierSuitLabels,
    "temperature": 2.0,
    "min_confidence": 0.5,
    "min_margin": 0.02
  }
}
""".trimIndent()

/** 构造一段 `[stride][anchors]` 的原始检测输出。 */
fun obbRaw(
    anchors: Int,
    stride: Int = 6,
    boxes: List<Pair<FloatArray, Float>>,
): FloatArray {
    val raw = FloatArray(stride * anchors)
    boxes.forEachIndexed { index, (box, score) ->
        raw[0 * anchors + index] = box[0]
        raw[1 * anchors + index] = box[1]
        raw[2 * anchors + index] = box[2]
        raw[3 * anchors + index] = box[3]
        raw[4 * anchors + index] = box.getOrElse(4) { 0f }
        raw[5 * anchors + index] = score
    }
    return raw
}
