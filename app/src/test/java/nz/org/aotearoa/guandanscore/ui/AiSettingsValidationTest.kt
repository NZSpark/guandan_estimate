package nz.org.aotearoa.guandanscore.ui

import nz.org.aotearoa.guandanscore.recognition.AiSettings
import nz.org.aotearoa.guandanscore.recognition.AiTransport
import nz.org.aotearoa.guandanscore.recognition.source.AiSourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiSettingsValidationTest {

    private fun web(
        source: AiSourceId = AiSourceId.DEEPSEEK_WEBVIEW,
        name: String = "DeepSeek 网页版",
        homeUrl: String = "https://chat.deepseek.com",
        endpoint: String = "https://leftover.invalid",
        model: String = "leftover-model",
        apiKey: String = "leftover-key"
    ) = AiSettings(
        id = "web",
        sourceKey = source.key,
        provider = name,
        endpoint = endpoint,
        model = model,
        apiKey = apiKey,
        homeUrl = homeUrl,
        transport = AiTransport.WEBVIEW
    )

    private fun api(
        name: String = "Gemini API",
        endpoint: String = "https://generativelanguage.googleapis.com/v1beta/models",
        model: String = "gemini-3.6-flash",
        apiKey: String = "test-key",
        homeUrl: String = "https://leftover.invalid"
    ) = AiSettings(
        id = "api",
        sourceKey = AiSourceId.HTTP_API.key,
        provider = name,
        endpoint = endpoint,
        model = model,
        apiKey = apiKey,
        homeUrl = homeUrl,
        transport = AiTransport.API
    )

    @Test
    fun validWebConfigPasses() {
        assertTrue(AiSettingsValidation.validate(AiSourceId.DEEPSEEK_WEBVIEW, web()).isEmpty)
    }

    @Test
    fun webRequiresHomeUrl() {
        val errors = AiSettingsValidation.validate(AiSourceId.DEEPSEEK_WEBVIEW, web(homeUrl = ""))
        assertEquals("网页源必须填写首页地址", errors.message(AiField.HOME_URL))
    }

    @Test
    fun webRejectsHttpAndMalformedUrl() {
        assertEquals(
            "首页地址必须使用 HTTPS",
            AiSettingsValidation.validate(AiSourceId.DEEPSEEK_WEBVIEW, web(homeUrl = "http://x.test"))
                .message(AiField.HOME_URL)
        )
        assertEquals(
            "首页地址格式不正确",
            AiSettingsValidation.validate(AiSourceId.DEEPSEEK_WEBVIEW, web(homeUrl = "https://"))
                .message(AiField.HOME_URL)
        )
    }

    @Test
    fun webIgnoresApiFieldsEntirely() {
        // 网页源即使残留 endpoint/model/key 也不能报错：这些字段在 UI 中整组隐藏。
        val errors = AiSettingsValidation.validate(
            AiSourceId.GEMINI_WEBVIEW,
            web(source = AiSourceId.GEMINI_WEBVIEW, homeUrl = "https://gemini.google.com/app")
        )
        assertTrue(errors.isEmpty)
        assertNull(errors.message(AiField.ENDPOINT))
        assertNull(errors.message(AiField.MODEL))
        assertNull(errors.message(AiField.API_KEY))
    }

    @Test
    fun customWebSourceOnlyNeedsHttpsHomeUrl() {
        // 兼容性提示由页面展示；这里确认它不会把自定义源当成保存错误。
        val errors = AiSettingsValidation.validate(
            AiSourceId.CUSTOM_WEBVIEW,
            web(source = AiSourceId.CUSTOM_WEBVIEW, homeUrl = "https://example.test/chat")
        )
        assertTrue(errors.isEmpty)
    }

    @Test
    fun validApiConfigPasses() {
        assertTrue(AiSettingsValidation.validate(AiSourceId.HTTP_API, api()).isEmpty)
    }

    @Test
    fun apiRequiresEndpointModelAndKey() {
        val errors = AiSettingsValidation.validate(
            AiSourceId.HTTP_API,
            api(endpoint = "", model = "", apiKey = "")
        )
        assertEquals("接口地址不能为空", errors.message(AiField.ENDPOINT))
        assertEquals("模型名称不能为空", errors.message(AiField.MODEL))
        assertEquals("API 源必须填写 API Key", errors.message(AiField.API_KEY))
    }

    @Test
    fun apiRejectsHttpEndpoint() {
        val errors = AiSettingsValidation.validate(
            AiSourceId.HTTP_API,
            api(endpoint = "http://api.example.test/v1")
        )
        assertEquals("接口地址必须使用 HTTPS", errors.message(AiField.ENDPOINT))
    }

    @Test
    fun apiIgnoresHomeUrl() {
        val errors = AiSettingsValidation.validate(AiSourceId.HTTP_API, api(homeUrl = ""))
        assertTrue(errors.isEmpty)
        assertNull(errors.message(AiField.HOME_URL))
    }

    @Test
    fun blankNameIsAllowed() {
        // 名称留空时保存阶段会回落为源名称；这里不应阻断。
        assertTrue(AiSettingsValidation.validate(AiSourceId.DEEPSEEK_WEBVIEW, web(name = "")).isEmpty)
    }

    @Test
    fun tooLongNameIsRejected() {
        val errors = AiSettingsValidation.validate(
            AiSourceId.DEEPSEEK_WEBVIEW,
            web(name = "a".repeat(AiSettingsValidation.MAX_NAME_LENGTH + 1))
        )
        assertFalse(errors.isEmpty)
        assertTrue(errors.message(AiField.NAME)!!.contains("最多"))
    }
}
