package nz.org.aotearoa.guandanscore.recognition.source

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Test

class HealthCheckGateTest {

    @Test
    fun concurrentChecksNeverOverlap() {
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val threads = (1..4).map {
            Thread {
                HealthCheckGate.run {
                    val now = active.incrementAndGet()
                    maxActive.updateAndGet { previous -> maxOf(previous, now) }
                    Thread.sleep(20)
                    active.decrementAndGet()
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(5_000) }

        assertEquals("同一时刻只允许一个健康检查", 1, maxActive.get())
        assertEquals(0, active.get())
    }
}
