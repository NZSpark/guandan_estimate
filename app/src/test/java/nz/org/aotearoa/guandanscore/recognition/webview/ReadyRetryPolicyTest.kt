package nz.org.aotearoa.guandanscore.recognition.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AI-034 / AI-035：退避与 reload 上限的纯策略单测。 */
class ReadyRetryPolicyTest {

// ---- AI-034 退避 ----

@Test
fun delayGrowsByOneAndAHalf() {
assertEquals(450L, ReadyRetryPolicy.nextDelay(300L))
assertEquals(675L, ReadyRetryPolicy.nextDelay(450L))
}

@Test
fun delayIsCappedAtMax() {
assertEquals(ReadyRetryPolicy.MAX_DELAY_MILLIS, ReadyRetryPolicy.nextDelay(1_200L))
assertEquals(ReadyRetryPolicy.MAX_DELAY_MILLIS, ReadyRetryPolicy.nextDelay(10_000L))
}

@Test
fun delayNeverDropsBelowFirstDelay() {
// 传入异常小值（如 1ms）时也要回到首次间隔，避免把轮询压成忙等。
assertEquals(ReadyRetryPolicy.FIRST_DELAY_MILLIS, ReadyRetryPolicy.nextDelay(1L))
assertEquals(ReadyRetryPolicy.FIRST_DELAY_MILLIS, ReadyRetryPolicy.nextDelay(0L))
}

@Test
fun delaySequenceConvergesToCap() {
var d = ReadyRetryPolicy.FIRST_DELAY_MILLIS
repeat(20) { d = ReadyRetryPolicy.nextDelay(d) }
assertEquals(ReadyRetryPolicy.MAX_DELAY_MILLIS, d)
}

// ---- AI-035 reload 允许条件 ----

@Test
fun reloadAllowedWhenFresh() {
assertTrue(ReadyRetryPolicy.canReload(reloadsDone = 0, generating = false, justNavigated = false))
assertNull(ReadyRetryPolicy.reloadSkipReason(0, generating = false, justNavigated = false))
}

@Test
fun reloadBlockedWhileGenerating() {
assertFalse(ReadyRetryPolicy.canReload(0, generating = true, justNavigated = false))
assertEquals("business-task-running", ReadyRetryPolicy.reloadSkipReason(0, true, false))
}

@Test
fun reloadBlockedRightAfterNavigation() {
assertFalse(ReadyRetryPolicy.canReload(0, generating = false, justNavigated = true))
assertEquals("just-navigated", ReadyRetryPolicy.reloadSkipReason(0, false, true))
}

@Test
fun reloadBlockedAtLimit() {
assertFalse(
ReadyRetryPolicy.canReload(
ReadyRetryPolicy.MAX_RELOADS, generating = false, justNavigated = false
)
)
assertEquals(
"reload-limit-reached",
ReadyRetryPolicy.reloadSkipReason(ReadyRetryPolicy.MAX_RELOADS, false, false)
)
}

/** 生成中的优先级高于其它原因：日志必须指向最要紧的那一条。 */
@Test
fun generatingReasonWinsOverOtherReasons() {
assertEquals("business-task-running", ReadyRetryPolicy.reloadSkipReason(99, true, true))
}
}