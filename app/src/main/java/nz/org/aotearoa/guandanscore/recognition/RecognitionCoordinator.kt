package nz.org.aotearoa.guandanscore.recognition

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * T0.3: one recognition job at a time, stale results are dropped, timeouts fall back.
 *
 * - newJob() bumps the token, older jobs stop being current immediately.
 * - Callbacks wrap in Job.guard so stale tokens never touch the UI.
 * - Job.scheduleTimeout posts one cancellable fallback action.
 *
 * Pure JVM, no Android and no coroutines. Thread safety via a single lock.
 */
class RecognitionCoordinator(
    val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val scheduler: ScheduledThreadPoolExecutor = defaultScheduler(),
) {

    private val lock = Any()
    private val counter = AtomicLong(0)
    private var active: Job? = null

    inner class Job internal constructor(val token: Long) : AutoCloseable {
        @Volatile
        private var cancelled = false

        @Volatile
        private var completed = false

        private var timeout: java.util.concurrent.ScheduledFuture<*>? = null

        /** Token is still current: not superseded, not cancelled, not completed. */
        val isCurrent: Boolean
            get() = synchronized(lock) { !cancelled && !completed && active === this }

        /** Run [block] only if this token is still current. */
        fun guard(block: (Job) -> Unit): Boolean {
            if (!isCurrent) return false
            block(this)
            return true
        }

        /** Mark finished so later callbacks cannot update the UI. */
        fun complete() {
            synchronized(lock) {
                completed = true
                timeout?.cancel(false)
                timeout = null
            }
        }

        /** Run [action] after [millis], only if this job is still current then. */
        fun scheduleTimeout(millis: Long = timeoutMillis, action: (Job) -> Unit) {
            val future = scheduler.schedule({
                if (isCurrent) {
                    complete()
                    action(this)
                }
            }, millis, TimeUnit.MILLISECONDS)
            synchronized(lock) {
                timeout?.cancel(false)
                timeout = future
            }
        }

        override fun close() {
            synchronized(lock) {
                cancelled = true
                timeout?.cancel(false)
                timeout = null
            }
        }
    }

    /** Start a new job, cancelling the previous one. */
    fun newJob(): Job = synchronized(lock) {
        active?.close()
        val job = Job(counter.incrementAndGet())
        active = job
        job
    }

    /** Current valid token; 0 when idle. */
    val currentToken: Long
        get() = synchronized(lock) { active?.token ?: 0L }

    /** Cancel the current job (screen destroyed, new image, random hand, ...). */
    fun cancelAll() {
        synchronized(lock) {
            active?.close()
            active = null
        }
    }

    fun shutdown() {
        cancelAll()
        scheduler.shutdownNow()
    }

    companion object {
        /** Suggested total timeout, 6s; recalibrate on a real device in T4.7. */
        const val DEFAULT_TIMEOUT_MILLIS = 6_000L

        fun defaultScheduler(): ScheduledThreadPoolExecutor =
            ScheduledThreadPoolExecutor(1) { r ->
                Thread(r, "recognition-timeout").apply { isDaemon = true }
            }.apply { removeOnCancelPolicy = true }

        /** Convenience factory for tests and lightweight call sites. */
        fun singleThreadExecutor() = Executors.newSingleThreadExecutor()
    }
}
