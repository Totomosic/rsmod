package org.rsmod.game.proj

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.rsmod.map.CoordGrid

class ProjAnimTimingTest {
    @ParameterizedTest
    @CsvSource(
        "0, 1, 1",
        "15, 1, 1",
        "29, 1, 1",
        "30, 1, 2",
        "31, 1, 2",
        "59, 1, 2",
        "60, 2, 3",
        "89, 2, 3",
        "90, 3, 4",
    )
    fun `magic and ranged include their queue tick separately from raw projectile timing`(
        clientCycles: Int,
        impactTick: Int,
        rangedTick: Int,
    ) {
        val projectile =
            ProjAnim(
                spotanim = 1,
                startHeight = 0,
                endHeight = 0,
                startTime = 0,
                endTime = clientCycles,
                angle = 0,
                progress = 0,
                sourceIndex = 0,
                targetIndex = 0,
                startCoord = CoordGrid(3200, 3200),
                endCoord = CoordGrid(3200, 3201),
            )
        assertEquals(clientCycles, projectile.clientCycles)
        assertEquals(impactTick, projectile.serverCycles)
        assertEquals(ProjAnim.Duration(impactTick, clientCycles), projectile.durations)
        assertEquals(rangedTick, projectile.rangedServerCycles)
        assertEquals(ProjAnim.Duration(rangedTick, clientCycles), projectile.rangedDurations)
        assertEquals(rangedTick, projectile.magicServerCycles)
        assertEquals(ProjAnim.Duration(rangedTick, clientCycles), projectile.magicDurations)
    }
}
