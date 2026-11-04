package nz.org.aotearoa.guandanscore.recognition.webview

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G-07：附件等待策略的直接单测。
 *
 * 重点覆盖真机难以稳定复现的两条路径：
 * - 附件一直不出现 → 20s 后判超时，不能把“等到过”当成成功；
 * - 页面明确额度用尽 → 立即失败，不空等满 20s。
 */
class AttachmentWaitPolicyTest {

    private class FakeClock {
        var now: Long = 0L
        val sleeps = mutableListOf<Long>()

        fun sleep(ms: Long) {
            sleeps += ms
            now += ms
        }
    }

    private fun sample(attached: Boolean, quotaBlocked: Boolean = false): JSONObject =
        JSONObject()
            .put("attached", attached)
            .put("quotaBlocked", quotaBlocked)
            .put("attachmentCount", if (attached) 1 else 0)

    private fun policy(clock: FakeClock): AttachmentWaitPolicy = AttachmentWaitPolicy(
        nowMillis = { clock.now },
        sleepMillis = clock::sleep
    )

    private fun isAttached(json: JSONObject): Boolean = json.optBoolean("attached", false)

    private fun isBlocked(json: JSONObject): Boolean = json.optBoolean("quotaBlocked", false)

    @Test
    fun confirmedOnFirstSampleStopsImmediately() {
        val clock = FakeClock()
        var calls = 0
        val result = policy(clock).await(
            sample = { calls++; sample(attached = true) },
            isAttached = ::isAttached,
            isBlocked = ::isBlocked
        )

        assertTrue("预览已出现时应返回 Confirmed", result is AttachmentWaitPolicy.Result.Confirmed)
        assertEquals(1, calls)
        assertEquals(1, result.samples)
        assertTrue("确认后不应再 sleep", clock.sleeps.isEmpty())
    }

    @Test
    fun transientMissIsPolledUntilPreviewAppears() {
        val clock = FakeClock()
        val states = listOf(false, false, false, true)
        var calls = 0
        val result = policy(clock).await(
            sample = {
                val attached = states.getOrElse(calls) { states.last() }
                calls++
                sample(attached)
            },
            isAttached = ::isAttached,
            isBlocked = ::isBlocked
        )

        assertTrue(result is AttachmentWaitPolicy.Result.Confirmed)
        assertEquals(4, result.samples)
        assertEquals(3, clock.sleeps.size)
        assertEquals(900L, clock.now)
    }

    @Test
    fun neverAppearingPreviewTimesOutAtTwentySeconds() {
        val clock = FakeClock()
        val result = policy(clock).await(
            sample = { sample(attached = false) },
            isAttached = ::isAttached,
            isBlocked = ::isBlocked
        )

        assertTrue("附件始终未出现时必须判超时", result is AttachmentWaitPolicy.Result.TimedOut)
        assertTrue("超时时间不得早于 20s", clock.now >= 20_000L)
        assertTrue("每个采样都应伴随一次轮询间隔", result.samples == clock.sleeps.size)
        // 20s / 300ms ≈ 67 次采样；固定上限保证不会无限轮询。
        assertEquals(67, result.samples)
    }

    @Test
    fun quotaBlockedFailsFastWithoutWaiting() {
        val clock = FakeClock()
        val result = policy(clock).await(
            sample = { sample(attached = false, quotaBlocked = true) },
            isAttached = ::isAttached,
            isBlocked = ::isBlocked
        )

        assertTrue("额度用尽应立即判 Blocked", result is AttachmentWaitPolicy.Result.Blocked)
        assertEquals(1, result.samples)
        assertTrue("额度用尽不应继续 sleep", clock.sleeps.isEmpty())
        assertEquals(0L, clock.now)
    }

    @Test
    fun unreadableSamplesStillEndInTimeout() {
        val clock = FakeClock()
        val result = policy(clock).await(
            sample = { null },
            isAttached = ::isAttached,
            isBlocked = ::isBlocked
        )

        assertTrue("读不到页面结果时也必须以超时收尾", result is AttachmentWaitPolicy.Result.TimedOut)
        assertTrue(clock.now >= 20_000L)
    }

    @Test
    fun everyReadableSampleIsReportedForFailureReason() {
        val clock = FakeClock()
        val seen = mutableListOf<Int>()
        val states = listOf(false, false, true)
        var calls = 0
        policy(clock).await(
            sample = {
                val attached = states.getOrElse(calls) { states.last() }
                calls++
                sample(attached).put("attachmentCount", calls)
            },
            isAttached = ::isAttached,
            isBlocked = ::isBlocked,
            onSample = { seen += it.optInt("attachmentCount") }
        )

        assertEquals(listOf(1, 2, 3), seen)
    }

    @Test
    fun attachedWinsOverBlockedOnTheSameSample() {
        val clock = FakeClock()
        val result = policy(clock).await(
            sample = { sample(attached = true, quotaBlocked = true) },
            isAttached = ::isAttached,
            isBlocked = ::isBlocked
        )

        assertTrue("真实预览已出现时以附件成功为准", result is AttachmentWaitPolicy.Result.Confirmed)
    }

    @Test
    fun frozenClockCannotSpinForever() {
        val sleeps = mutableListOf<Long>()
        val result = AttachmentWaitPolicy(
            nowMillis = { 0L },
            sleepMillis = { sleeps += it }
        ).await(
            sample = { sample(attached = false) },
            isAttached = ::isAttached,
            isBlocked = ::isBlocked
        )

        assertTrue(result is AttachmentWaitPolicy.Result.TimedOut)
        assertEquals(68, result.samples)
        assertEquals(68, sleeps.size)
    }
}
