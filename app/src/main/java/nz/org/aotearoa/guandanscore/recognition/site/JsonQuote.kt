package nz.org.aotearoa.guandanscore.recognition.site

import org.json.JSONObject

/**
 * 把任意文本编码成安全的 JS 字符串字面量。
 *
 * 站点无关工具：驱动层与各适配器共用，避免驱动层为了 quote 去依赖某个站点的脚本类。
 * `JSONObject.quote` 会正确处理引号、反斜杠、换行、制表与 U+2028/2029。
 */
object JsonQuote {
    fun of(raw: String): String = JSONObject.quote(raw)
}
