package nz.org.aotearoa.guandanscore.recognition

import org.json.JSONObject

/**
 * T4.1: 模型 manifest 的唯一解析入口。
 *
 * 模型文件、输入尺寸、标签顺序、阈值都必须从 manifest 读取，Android 侧不允许硬编码——
 * 换模型只换资产和 manifest，不改代码。字段缺失、形状非法或阈值越界一律抛
 * [ManifestException]，由调用方回退到启发式路径，绝不在运行时“猜一个默认值”继续跑。
 */
enum class TensorLayout { NCHW, NHWC }

class ManifestException(message: String) : IllegalArgumentException(message)

/** 检测器（单类 `card_corner`，OBB 输出）。 */
data class DetectorManifest(
    val asset: String,
    val sha256: String,
    val sizeBytes: Long,
    val inputWidth: Int,
    val inputHeight: Int,
    val inputLayout: TensorLayout,
    /** 导出张量的通道顺序；当前固定为 cx,cy,w,h,score,angle。 */
    val outputOrder: String,
    /** 输出通道数：cx,cy,w,h,score,angle = 6。 */
    val channelStride: Int,
    /** 输出锚点数（`[1,6,8400]` 里的 8400）。 */
    val anchors: Int,
    val scoreThreshold: Float,
    /**
     * 短场景补漏阈值；null 表示关闭。只有基础阈值检测数少于 27 时才使用，
     * 且补入候选仍必须通过分类器 known 过滤。
     */
    val fallbackScoreThreshold: Float?,
    val nmsIou: Float,
    val minCornerPx: Float,
    val edgeMarginPx: Int,
    val labels: List<String>,
)

/** 分类器（15 点数 × 5 花色双头）。 */
data class ClassifierManifest(
    val asset: String,
    val sha256: String,
    val sizeBytes: Long,
    val cropWidth: Int,
    val cropHeight: Int,
    val rankLabels: List<String>,
    val suitLabels: List<String>,
    val temperature: Float,
    val minConfidence: Float,
    val minMargin: Float,
    val normalization: String,
)

data class ModelManifest(
    val schemaVersion: Int,
    val version: String,
    val detector: DetectorManifest,
    val classifier: ClassifierManifest,
) {
    companion object {
        const val SCHEMA_VERSION = 1
        private val HEX64 = Regex("^[0-9a-f]{64}$")

        fun parse(text: String): ModelManifest {
            val root = try {
                JSONObject(text)
            } catch (error: Exception) {
                throw ManifestException("manifest 不是合法 JSON：${error.message}")
            }
            val schema = root.optInt("schema_version", -1)
            if (schema != SCHEMA_VERSION) {
                throw ManifestException("不支持的 manifest schema_version=$schema")
            }
            val version = root.optString("version").ifBlank { throw ManifestException("缺少 version") }

            val detectorJson = root.optJSONObject("detector")
                ?: throw ManifestException("缺少 detector 段")
            val classifierJson = root.optJSONObject("classifier")
                ?: throw ManifestException("缺少 classifier 段")

            val detectorScoreThreshold = probability(detectorJson, "score_threshold", "detector", allowOne = false)
            val detectorFallbackThreshold = optionalProbability(
                detectorJson,
                "fallback_score_threshold",
                "detector",
            )
            if (detectorFallbackThreshold != null && detectorFallbackThreshold >= detectorScoreThreshold) {
                throw ManifestException(
                    "detector.fallback_score_threshold 必须低于 score_threshold" +
                        "（$detectorFallbackThreshold >= $detectorScoreThreshold）",
                )
            }
            val detector = DetectorManifest(
                asset = assetPath(detectorJson, "detector"),
                sha256 = hash(detectorJson, "detector"),
                sizeBytes = detectorJson.optLong("size_bytes", -1).also {
                    if (it <= 0) throw ManifestException("detector.size_bytes 非法")
                },
                inputWidth = positive(detectorJson, "input_width", "detector"),
                inputHeight = positive(detectorJson, "input_height", "detector"),
                inputLayout = when (detectorJson.optString("input_layout", "NCHW").uppercase()) {
                    "NCHW" -> TensorLayout.NCHW
                    "NHWC" -> TensorLayout.NHWC
                    else -> throw ManifestException("detector.input_layout 只支持 NCHW/NHWC")
                },
                outputOrder = detectorJson.optString("output_order", "cx,cy,w,h,score,angle").replace(" ", "").also {
                    if (it != "cx,cy,w,h,score,angle") {
                        throw ManifestException("detector.output_order 必须为 cx,cy,w,h,score,angle")
                    }
                },
                channelStride = positive(detectorJson, "channel_stride", "detector").also {
                    if (it < 6) throw ManifestException("detector.channel_stride 至少 6（4 框 + angle + score）")
                },
                anchors = positive(detectorJson, "anchors", "detector"),
                scoreThreshold = detectorScoreThreshold,
                fallbackScoreThreshold = detectorFallbackThreshold,
                nmsIou = probability(detectorJson, "nms_iou", "detector", allowOne = false),
                minCornerPx = detectorJson.optDouble("min_corner_px", 8.0).toFloat().also {
                    if (!(it > 0f)) throw ManifestException("detector.min_corner_px 必须 > 0")
                },
                edgeMarginPx = detectorJson.optInt("edge_margin_px", 2).also {
                    if (it < 0) throw ManifestException("detector.edge_margin_px 不能为负")
                },
                labels = labels(detectorJson, "labels", "detector", expected = 1),
            )

            val classifier = ClassifierManifest(
                asset = assetPath(classifierJson, "classifier"),
                sha256 = hash(classifierJson, "classifier"),
                sizeBytes = classifierJson.optLong("size_bytes", -1).also {
                    if (it <= 0) throw ManifestException("classifier.size_bytes 非法")
                },
                cropWidth = positive(classifierJson, "crop_width", "classifier"),
                cropHeight = positive(classifierJson, "crop_height", "classifier"),
                rankLabels = labels(classifierJson, "rank_labels", "classifier", expected = 15),
                suitLabels = labels(classifierJson, "suit_labels", "classifier", expected = 5),
                temperature = classifierJson.optDouble("temperature", 1.0).toFloat().also {
                    if (!(it > 0f)) throw ManifestException("classifier.temperature 必须 > 0")
                },
                minConfidence = probability(classifierJson, "min_confidence", "classifier", allowOne = true),
                minMargin = probability(classifierJson, "min_margin", "classifier", allowOne = true),
                normalization = classifierJson.optString("normalization", "rgb/255"),
            )
            if (classifier.rankLabels.toSet().size != classifier.rankLabels.size) {
                throw ManifestException("classifier.rank_labels 有重复项")
            }
            if (classifier.suitLabels.toSet().size != classifier.suitLabels.size) {
                throw ManifestException("classifier.suit_labels 有重复项")
            }
            return ModelManifest(schema, version, detector, classifier)
        }

        private fun assetPath(json: JSONObject, section: String): String =
            json.optString("asset").ifBlank { throw ManifestException("$section.asset 不能为空") }

        private fun hash(json: JSONObject, section: String): String {
            val value = json.optString("sha256").lowercase()
            if (!HEX64.matches(value)) throw ManifestException("$section.sha256 必须是 64 位十六进制")
            return value
        }

        private fun positive(json: JSONObject, key: String, section: String): Int {
            val value = json.optInt(key, -1)
            if (value <= 0) throw ManifestException("$section.$key 必须为正整数")
            return value
        }

        private fun probability(json: JSONObject, key: String, section: String, allowOne: Boolean): Float {
            if (!json.has(key)) throw ManifestException("缺少 $section.$key")
            val value = json.optDouble(key, -1.0).toFloat()
            val upperOk = if (allowOne) value <= 1f else value < 1f
            if (!(value > 0f && upperOk)) throw ManifestException("$section.$key 必须在 (0,1${if (allowOne) "]" else ")"})")
            return value
        }

        /** 可选概率字段；JSON null 与字段缺失等价。 */
        private fun optionalProbability(json: JSONObject, key: String, section: String): Float? {
            if (!json.has(key) || json.isNull(key)) return null
            val value = json.optDouble(key, Double.NaN).toFloat()
            if (!(value > 0f && value < 1f)) {
                throw ManifestException("$section.$key 必须在 (0,1)")
            }
            return value
        }

        private fun labels(json: JSONObject, key: String, section: String, expected: Int): List<String> {
            val array = json.optJSONArray(key) ?: throw ManifestException("缺少 $section.$key")
            if (array.length() != expected) {
                throw ManifestException("$section.$key 需要 $expected 项，实际 ${array.length()}")
            }
            return (0 until array.length()).map { index ->
                array.optString(index).ifBlank { throw ManifestException("$section.$key[$index] 为空") }
            }
        }
    }
}
