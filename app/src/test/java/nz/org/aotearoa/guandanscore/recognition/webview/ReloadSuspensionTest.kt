package nz.org.aotearoa.guandanscore.recognition.webview

import nz.org.aotearoa.guandanscore.recognition.site.AnswerSnapshot
import nz.org.aotearoa.guandanscore.recognition.site.SubmitOutcome
import nz.org.aotearoa.guandanscore.recognition.source.AiSourceError
import nz.org.aotearoa.guandanscore.recognition.source.RecognizeRequest
import org.junit.Assert.assertEquals
import org.junit.Test

/**

AI-035：业务生成期间必须暂停自动 reload。

这些断言守的是「谁在什么时候置位」——生成中 reload 会直接丢掉正在进行的请求，

是比多等一会儿严重得多的故障。
*/
class ReloadSuspensionTest {

private val cardsJson = """[{"rank":"A","suit":"SPADE"},{"rank":"K","suit":"HEART"}]"""

private fun request() = RecognizeRequest(jpegBytes = byteArrayOf(1, 2, 3), prompt = "识别")

/** 骨架：全成功的一轮识牌。 */
private open class StubPages : PageOps {
override fun ensureLoaded(force: Boolean) = true
override fun applyPageFix() = Unit
override fun isLoggedIn() = true
override fun captureBaseline() = AnswerSnapshot(0, "", false, false, "")
override fun pollSnapshot() = AnswerSnapshot(1, ANSWER_JSON, false, false, "")
override fun fillAndSubmit(prompt: String, fileName: String, mimeType: String, base64: String) =
SubmitOutcome(ok = true, why = null, inputFilled = true, attached = true)
override fun pasteImage(fileName: String, mimeType: String, base64: String) = true
override fun prepareFileChooserUpload(fileName: String, mimeType: String, bytes: ByteArray) = true
override fun verifyAttachment() = true
override fun awaitSubmitStarted(baseline: AnswerSnapshot) = true
override fun diagnose() = null

companion object {
const val ANSWER_JSON = """[{"rank":"A","suit":"SPADE"},{"rank":"K","suit":"HEART"}]"""
}
}

/** 记录 setReloadSuspended 的调用序列。 */
private class RecordingPages(private val delegate: PageOps = StubPages()) :
PageOps by delegate {
val suspensionCalls = mutableListOf<Boolean>()

override fun setReloadSuspended(suspended: Boolean) {
suspensionCalls += suspended
delegate.setReloadSuspended(suspended)
}
}

private fun source(pages: PageOps) = WebViewAiSource(
adapter = FakeSiteAdapter(),
pages = pages,
pollIntervalMillis = 10,
sleepMillis = { Thread.sleep(it) },
timeoutMillis = 15_000,
base64Encoder = { "QUJD" }
)

@Test
fun suspensionIsOnDuringPipelineAndOffAfterSuccess() {
val pages = RecordingPages()
assertEquals(2, source(pages).recognize(request()).size)
assertEquals(listOf(true, false), pages.suspensionCalls)
}

/** 失败路径也必须恢复，否则一次失败会把后续所有 reload 永久关掉。 */
@Test
fun suspensionIsRestoredAfterFailure() {
val failing = object : PageOps by StubPages() {
override fun ensureLoaded(force: Boolean) = false
override fun lastFailureReason() = "需要重新登录"
}
val pages = RecordingPages(failing)
try {
source(pages).recognize(request())
throw AssertionError("expected NotReady")
} catch (_: AiSourceError.NotReady) {
}
assertEquals(listOf(true, false), pages.suspensionCalls)
}

/** 忙碌拒绝发生在置位之前，不得留下悬挂的暂停标志。 */
@Test
fun busyRejectionDoesNotTouchSuspension() {
lateinit var src: WebViewAiSource
val busyRejected = booleanArrayOf(false)
val pages = RecordingPages(object : PageOps by StubPages() {
override fun pollSnapshot(): AnswerSnapshot {
if (!busyRejected[0]) {
busyRejected[0] = true
try {
src.recognize(request())
throw AssertionError("expected Busy")
} catch (_: AiSourceError.Busy) {
}
}
return AnswerSnapshot(1, StubPages.ANSWER_JSON, false, false, "")
}
})
src = source(pages)
src.recognize(request())
// 成功一轮：true, false；被拒绝的那次不产生任何调用。
assertEquals(listOf(true, false), pages.suspensionCalls)
}
}