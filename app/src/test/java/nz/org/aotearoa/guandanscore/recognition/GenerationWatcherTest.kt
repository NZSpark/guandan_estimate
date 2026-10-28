package nz.org.aotearoa.guandanscore.recognition

import nz.org.aotearoa.guandanscore.recognition.GenerationWatcher.Decision
import nz.org.aotearoa.guandanscore.recognition.GenerationWatcher.Sample
import org.junit.Assert.*
import org.junit.Test

class GenerationWatcherTest {
    private val baseline = GenerationWatcher.Baseline(responseCount = 2, lastAnswerText = "旧回答")

    private fun newWatcher() = GenerationWatcher(
        minStableSamples = 3,
        minQuietMillis = 1500,
        hardTimeoutMillis = 60_000
    ).apply { reset(baseline, 0) }

    private fun sample(
        t: Long,
        count: Int = 3,
        text: String = "新回答",
        generating: Boolean = false,
        error: String? = null
    ) = Sample(count, text, generating, error, t)

    @Test
    fun waitsWhenOnlyOldAnswerPresent() {
        val w = newWatcher()
        repeat(10) { i ->
            assertEquals(Decision.Waiting, w.onSample(sample(i * 1000L, count = 2, text = "旧回答")))
        }
    }

    @Test
    fun waitsWhenNewNodeIsEmpty() {
        val w = newWatcher()
        repeat(10) { i ->
            assertEquals(Decision.Waiting, w.onSample(sample(i * 1000L, count = 3, text = "")))
        }
    }

    @Test
    fun finishesAfterStableQuietPeriod() {
        val w = newWatcher()
        assertEquals(Decision.Waiting, w.onSample(sample(0, text = "新回答")))   // 变化，计数 1
        assertEquals(Decision.Waiting, w.onSample(sample(1000, text = "新回答"))) // 计数 2，静默 1000ms 未达标
        assertEquals(Decision.Finished, w.onSample(sample(2000, text = "新回答"))) // 计数 3，静默 2000ms 达标
    }

    @Test
    fun textGrowthKeepsWaiting() {
        val w = newWatcher()
        assertEquals(Decision.Waiting, w.onSample(sample(0, text = "新")))
        assertEquals(Decision.Waiting, w.onSample(sample(1000, text = "新回答")))
        assertEquals(Decision.Waiting, w.onSample(sample(2000, text = "新回答继续")))
        assertEquals(Decision.Waiting, w.onSample(sample(3000, text = "新回答继续增长")))
    }

    @Test
    fun pauseThenResumeDoesNotFinishEarly() {
        val w = newWatcher()
        assertEquals(Decision.Waiting, w.onSample(sample(0, text = "第一段")))
        assertEquals(Decision.Waiting, w.onSample(sample(2000, text = "第一段")))
        // 生成暂停后恢复
        assertEquals(Decision.Waiting, w.onSample(sample(3000, text = "第一段第二段")))
        assertEquals(Decision.Waiting, w.onSample(sample(4000, text = "第一段第二段")))
    }

    @Test
    fun promptEchoIsNotTreatedAsAnswer() {
        val w = GenerationWatcher(minStableSamples = 1, minQuietMillis = 0)
            .apply { reset(GenerationWatcher.Baseline(2, "旧回答"), 0) }
        // 文本与基线相同，即便节点数增加也不算新回答
        assertEquals(Decision.Waiting, w.onSample(sample(0, count = 3, text = "旧回答")))
    }

    @Test
    fun pageErrorFails() {
        val w = newWatcher()
        val d = w.onSample(sample(0, error = "登录失效"))
        assertTrue(d is Decision.Failed)
        assertTrue((d as Decision.Failed).reason.contains("登录失效"))
    }

    @Test
    fun timesOut() {
        val w = newWatcher()
        repeat(70) { i ->
            val d = w.onSample(sample(i * 1000L, count = 2, text = "旧回答"))
            if (d is Decision.Failed) {
                assertTrue(d.reason.contains("超时"))
                return
            }
        }
        fail("应当超时")
    }

    @Test
    fun failsWithoutBaseline() {
        val w = GenerationWatcher()
        val d = w.onSample(sample(0))
        assertTrue(d is Decision.Failed)
    }

    @Test
    fun generatingSignalDelaysFinish() {
        val w = newWatcher()
        assertEquals(Decision.Waiting, w.onSample(sample(0, text = "新回答", generating = true)))
        assertEquals(Decision.Waiting, w.onSample(sample(1000, text = "新回答", generating = true)))
        // 仍标记生成中且静默不足
        assertEquals(Decision.Waiting, w.onSample(sample(1200, text = "新回答", generating = true)))
    }

    @Test
    fun resetClearsState() {
        val w = newWatcher()
        w.onSample(sample(0, text = "新回答"))
        w.reset(GenerationWatcher.Baseline(5, "另一旧回答"), 0)
        assertEquals(Decision.Waiting, w.onSample(sample(0, count = 5, text = "另一旧回答")))
    }
}
