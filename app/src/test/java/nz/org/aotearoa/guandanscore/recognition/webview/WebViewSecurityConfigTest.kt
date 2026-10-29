package nz.org.aotearoa.guandanscore.recognition.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [WebViewSecurityConfig] 契约单测（AI-025）。纯 JVM。 */
class WebViewSecurityConfigTest {

@Test
fun defaultsAreCompliant() {
// 默认值即契约：任何改动都会在这里先失败。
assertEquals(emptyList<String>(), WebViewSecurityConfig().violations())
}

@Test
fun defaultsEnableWhatRecognitionNeeds() {
val c = WebViewSecurityConfig()
assertTrue("识牌脚本需要 JS", c.javaScriptEnabled)
assertTrue("登录态需要 DOM Storage", c.domStorageEnabled)
assertTrue("Google 登录需要第三方 Cookie", c.acceptThirdPartyCookies)
assertTrue("必须接受 Cookie 才能保持登录", c.acceptCookie)
}

@Test
fun defaultsDisableWhatIsNotNeeded() {
val c = WebViewSecurityConfig()
assertEquals(false, c.allowFileAccess)
assertEquals(false, c.allowContentAccess)
assertEquals(false, c.saveFormData)
assertEquals(false, c.geolocationEnabled)
assertTrue(c.mixedContentNeverAllow)
}

@Test
fun disablingJavaScriptIsAViolation() {
val v = WebViewSecurityConfig(javaScriptEnabled = false).violations()
assertEquals(1, v.size)
assertTrue(v.single().contains("javaScriptEnabled"))
}

@Test
fun enablingFileAccessIsAViolation() {
val v = WebViewSecurityConfig(allowFileAccess = true).violations()
assertTrue(v.any { it.contains("allowFileAccess") })
}

@Test
fun enablingFormSavingIsAViolation() {
val v = WebViewSecurityConfig(saveFormData = true).violations()
assertTrue(v.any { it.contains("saveFormData") })
}

@Test
fun allowingMixedContentIsAViolation() {
val v = WebViewSecurityConfig(mixedContentNeverAllow = false).violations()
assertTrue(v.any { it.contains("mixedContentNeverAllow") })
}

@Test
fun allBadSwitchesReportedTogether() {
val v = WebViewSecurityConfig(
javaScriptEnabled = false,
domStorageEnabled = false,
allowFileAccess = true,
allowContentAccess = true,
mixedContentNeverAllow = false,
saveFormData = true,
geolocationEnabled = true,
acceptCookie = false
).violations()
assertEquals(8, v.size)
}
}
