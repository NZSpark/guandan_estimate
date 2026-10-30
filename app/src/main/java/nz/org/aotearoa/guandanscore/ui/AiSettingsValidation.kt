package nz.org.aotearoa.guandanscore.ui

import nz.org.aotearoa.guandanscore.recognition.AiSettings
import nz.org.aotearoa.guandanscore.recognition.source.AiSourceId
import java.net.URI

/** AI 配置表单里可编辑的字段。 */
enum class AiField { NAME, HOME_URL, ENDPOINT, MODEL, API_KEY }

/** 字段 → 错误文案。空 map 表示校验通过。 */
data class AiFieldErrors(val byField: Map<AiField, String> = emptyMap()) {
    fun message(field: AiField): String? = byField[field]

    val isEmpty: Boolean get() = byField.isEmpty()
}

/**
 * AI 配置字段校验（纯 JVM，可单测）。
 *
 * 只校验当前源真正使用的字段：
 * - 网页源只校验名称与首页 URL，绝不对隐藏的 endpoint / model / key 报错；
 * - API 源只校验名称、接口地址、模型与 Key，忽略首页 URL。
 */
object AiSettingsValidation {

    const val MAX_NAME_LENGTH = 40

    fun validate(source: AiSourceId, settings: AiSettings): AiFieldErrors {
        val errors = linkedMapOf<AiField, String>()

        if (settings.provider.trim().length > MAX_NAME_LENGTH) {
            errors[AiField.NAME] = "名称过长（最多 $MAX_NAME_LENGTH 字）"
        }

        if (source.isWebView) {
            when {
                settings.homeUrl.isBlank() -> errors[AiField.HOME_URL] = "网页源必须填写首页地址"
                else -> httpsProblem(settings.homeUrl, "首页地址")?.let { errors[AiField.HOME_URL] = it }
            }
        } else {
            when {
                settings.endpoint.isBlank() -> errors[AiField.ENDPOINT] = "接口地址不能为空"
                else -> httpsProblem(settings.endpoint, "接口地址")?.let { errors[AiField.ENDPOINT] = it }
            }
            if (settings.model.isBlank()) {
                errors[AiField.MODEL] = "模型名称不能为空"
            }
            if (settings.apiKey.isBlank()) {
                errors[AiField.API_KEY] = "API 源必须填写 API Key"
            }
        }

        return AiFieldErrors(errors)
    }

    /** 非空 URL 必须是带 host 的 HTTPS；空串由调用方给出更具体的必填文案。 */
    private fun httpsProblem(raw: String, label: String): String? {
        val value = raw.trim()
        if (!value.startsWith("https://", ignoreCase = true)) {
            return "${label}必须使用 HTTPS"
        }
        return try {
            if (URI(value).host.isNullOrBlank()) "${label}格式不正确" else null
        } catch (_: Exception) {
            "${label}格式不正确"
        }
    }
}
