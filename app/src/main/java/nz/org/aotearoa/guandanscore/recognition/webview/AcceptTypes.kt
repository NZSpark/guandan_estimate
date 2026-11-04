package nz.org.aotearoa.guandanscore.recognition.webview

/**
 * FileChooserParams.acceptTypes 的解析工具。
 *
 * 真机实测（LIO-N29 / webview 114，DeepSeek 2026-10-29）：accept 可达 5868 字符、
 * 约 900 个扩展名，且开头是 .wxml/.wxss/.wxs 这类与图片无关的项。因此：
 * 1. 禁止把原始 acceptTypes 整串写日志：会撑爆 logcat 单行并挤掉后续日志；
 * 2. 摘要必须优先列图片类型，按前 N 个截断看不到 hasImage 的真实结论。
 */
object AcceptTypes {

    private const val IMAGE_EXT_PATTERN =
        "^\\.(png|jpe?g|webp|gif|bmp|ico|svgz?|tiff?|avif|apng|jfif|pjpeg|pjp|dib|xbm)$"

    /** 常见图片扩展名（大小写不敏感；站点大小写并不统一）。 */
    private val IMAGE_EXT_RE: Regex = Regex(IMAGE_EXT_PATTERN, RegexOption.IGNORE_CASE)

    /** 是否为图片类型项（image/jpeg、.png 等）。 */
    fun isImageType(type: String): Boolean {
        val t = type.trim()
        if (t.isEmpty()) return false
        if (t.startsWith("image/", ignoreCase = true)) return true
        return IMAGE_EXT_RE.matches(t)
    }

    /** 过滤出图片类型项，保持原始顺序。 */
    fun imageTypes(types: Array<String>?): List<String> =
        types?.filter { isImageType(it) } ?: emptyList()

    /**
     * 生成安全的单行摘要：只含图片类型，最多 limit 项，超出部分以计数表示。
     * 例：.png|.jpg|.jpeg|...(+6)
     */
    fun imageSummary(types: Array<String>?, limit: Int = 6): String {
        val images = imageTypes(types)
        if (images.isEmpty()) return ""
        val head = images.take(limit).joinToString("|")
        return if (images.size > limit) head + "...(+" + (images.size - limit) + ")" else head
    }
}
