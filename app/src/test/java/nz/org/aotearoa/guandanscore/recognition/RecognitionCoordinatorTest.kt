package nz.org.aotearoa.guandanscore.recognition

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** T0.3: single active job, stale callbacks dropped, timeout fallback fires once. */
class RecognitionCoordinatorTest {

    private lateinit var coordinator: RecognitionCoordinator

    private fun newCoordinator(timeoutMillis: Long = 200): RecognitionCoordinator {
        val scheduler = ScheduledThreadPoolExecutor(1) { r ->
            Thread(r, "test-timeout").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
        coordinator = RecognitionCoordinator(timeoutMillis, scheduler)
        return coordinator
    }

    @After
    fun tearDown() {
        if (::coordinator.isInitialized) coordinator.shutdown()
    }

    @Test
    fun `new job invalidates the previous token`() {
        val c = newCoordinator()
        val first = c.newJob()
        val second = c.newJob()

        assertNotEquals(first.token, second.token)
        assertFalse(first.isCurrent)
        assertTrue(second.isCurrent)
        assertEquals(second.token, c.currentToken)
    }

    @Test
    fun `guard drops stale results and runs current ones`() {
        val c = newCoordinator()
        val stale = c.newJob()
        c.newJob()

        val ran = AtomicInteger(0)
        val staleRan = stale.guard { ran.incrementAndGet() }
        assertEquals(0, ran.get())
        assertFalse(staleRan)

        val currentRan = c.newJob().guard { ran.incrementAndGet() }
        assertTrue(currentRan)
        assertEquals(1, ran.get())
    }

    @Test
    fun `completed job cannot write the UI even without a new job`() {
        val c = newCoordinator()
        val job = c.newJob()
        assertTrue(job.isCurrent)
        job.complete()
        assertFalse(job.isCurrent)
        assertFalse(job.guard { error("must not run") })
    }

    @Test
    fun `cancelAll stops the current job`() {
        val c = newCoordinator()
        val job = c.newJob()
        c.cancelAll()
        assertFalse(job.isCurrent)
        assertEquals(0L, c.currentToken)
    }

    @Test
    fun `timeout fires exactly once for the live job`() {
        val c = newCoordinator(timeoutMillis = 120)
        val job = c.newJob()
        val fired = CountDownLatch(1)
        val count = AtomicInteger(0)

        job.scheduleTimeout {
            count.incrementAndGet()
            fired.countDown()
        }

        assertTrue(fired.await(2, TimeUnit.SECONDS))
        Thread.sleep(200)
        assertEquals(1, count.get())
    }

    @Test
    fun `timeout does not fire for a superseded job`() {
        val c = newCoordinator(timeoutMillis = 80)
        val stale = c.newJob()
        val fired = CountDownLatch(1)
        val count = AtomicInteger(0)

        stale.scheduleTimeout {
            count.incrementAndGet()
            fired.countDown()
        }
        c.newJob()

        fired.await(400, TimeUnit.MILLISECONDS)
        assertEquals(0, count.get())
    }

    @Test
    fun `timeout fallback triggers failed() once and never done()`() {
        val c = newCoordinator(timeoutMillis = 60)
        val job = c.newJob()
        var failedCount = 0
        var doneCount = 0

        // scheduleTimeout() marks the job complete right before firing, so the fallback
        // callback must be invoked directly (it is already token-filtered by the scheduler).
        job.scheduleTimeout {
            failedCount++
        }
        Thread.sleep(300)

        job.guard { doneCount++ }
        assertEquals(1, failedCount)
        assertEquals(0, doneCount)
    }
}
