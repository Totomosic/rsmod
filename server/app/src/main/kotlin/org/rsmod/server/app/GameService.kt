package org.rsmod.server.app

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import org.rsmod.game.GameProcess
import org.rsmod.server.services.concurrent.ScheduledListenerService

class GameService
internal constructor(
    private val process: GameProcess,
    private val nanoTime: () -> Long,
    private val sleep: suspend (Long) -> Unit,
) : ScheduledListenerService {
    @Inject
    constructor(
        process: GameProcess
    ) : this(process, System::nanoTime, { if (it > 0) delay(it) else yield() })

    private val logger = InlineLogger()

    private var nextCycleNanos: Long? = null
    private val shutdownSignaled = AtomicBoolean(false)

    override suspend fun run() {
        // After `signalShutdown`, `run` may be invoked one final time - return early to avoid
        // redundant processing.
        if (shutdownSignaled.get()) {
            return
        }
        val started = nanoTime()
        // Advance the original timeline, including time lost waking up or between run calls.
        // A late cycle therefore reduces the next wait instead of permanently slowing the game.
        val deadline = (nextCycleNanos ?: started) + GAME_TICK_NANOS
        nextCycleNanos = deadline
        process.cycle()
        val finished = nanoTime()
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(finished - started)
        val remainingNanos = deadline - finished
        // Round up so a sub-millisecond remainder never starts the next cycle early.
        val sleepTime = if (remainingNanos > 0) (remainingNanos + 999_999L) / 1_000_000L else 0L
        if (remainingNanos < 0) {
            val behindMillis = TimeUnit.NANOSECONDS.toMillis(-remainingNanos)
            logger.error {
                "Cycle behind schedule (elapsed=${elapsedMillis}ms, behind=${behindMillis}ms)"
            }
        } else {
            logger.trace { "Cycle took ${elapsedMillis}ms, sleep=${sleepTime}ms" }
        }
        sleep(sleepTime)
    }

    override fun createExecutor(): ExecutorService {
        val threadFactory = ThreadFactory { runnable ->
            Thread(runnable, GAME_THREAD_NAME).apply { isDaemon = false }
        }
        return Executors.newSingleThreadExecutor(threadFactory)
    }

    override suspend fun setup() {
        // Intentionally call `process.startup` on the game thread instead of in `startup`,
        // which runs on the main application thread.
        process.startup()
    }

    override suspend fun signalStartup() {}

    override suspend fun signalShutdown() {
        shutdownSignaled.set(true)
        process.preShutdown()
    }

    override suspend fun startup() {}

    override suspend fun shutdown() {
        logger.info { "Attempting to shut down game service." }
        try {
            process.shutdown()
            logger.info { "Game service successfully shut down." }
        } catch (t: Throwable) {
            logger.error(t) { "Game service failed to shut down." }
        }
    }

    private companion object {
        private const val GAME_TICK_NANOS = 600_000_000L
        private const val GAME_THREAD_NAME = "game"
    }
}
