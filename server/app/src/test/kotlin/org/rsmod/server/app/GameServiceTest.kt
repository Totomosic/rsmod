package org.rsmod.server.app

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.rsmod.game.GameProcess

class GameServiceTest {
    @Test
    fun `late wakeup shortens the following tick`() = runBlocking {
        val clock = Clock(workMillis = 10, oversleepMillis = 30)
        val service = clock.service()
        repeat(3) { service.run() }
        assertEquals(listOf(0L, 630L, 1230L), clock.starts)
        assertEquals(listOf(590L, 560L, 560L), clock.sleeps)
    }

    @Test
    fun `one 630 millisecond tick is followed by a 570 millisecond tick`() = runBlocking {
        val clock = Clock(workMillis = 10, oversleepMillis = 30)
        val service = clock.service()
        service.run()
        clock.oversleepMillis = 0
        service.run()
        service.run()
        assertEquals(listOf(0L, 630L, 1200L), clock.starts)
    }

    @Test
    fun `slow processing is followed immediately by a compensating tick`() = runBlocking {
        val clock = Clock(workMillis = 650)
        val service = clock.service()
        service.run()
        clock.workMillis = 10
        service.run()
        service.run()
        assertEquals(listOf(0L, 650L, 1200L), clock.starts)
        assertEquals(listOf(0L, 540L, 590L), clock.sleeps)
    }

    @Test
    fun `repeated wakeup delays do not accumulate clock drift`() = runBlocking {
        val clock = Clock(workMillis = 10, oversleepMillis = 30)
        val service = clock.service()
        repeat(101) { service.run() }
        assertEquals(60030L, clock.starts.last())
    }

    private class Clock(var workMillis: Long, var oversleepMillis: Long = 0) : GameProcess {
        var now = 0L
        val starts = mutableListOf<Long>()
        val sleeps = mutableListOf<Long>()

        fun service() =
            GameService(
                this,
                { now * 1_000_000 },
                {
                    sleeps += it
                    if (it > 0) now += it + oversleepMillis
                },
            )

        override fun cycle() {
            starts += now
            now += workMillis
        }

        override fun startup() {}

        override fun preShutdown() {}

        override fun shutdown() {}
    }
}
