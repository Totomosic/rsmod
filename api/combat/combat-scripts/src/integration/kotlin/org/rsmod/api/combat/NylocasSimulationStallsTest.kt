package org.rsmod.api.combat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NylocasSimulationStallsTest {
    @Test
    fun `count each failed admission by incoming wave including boundary waves and retries`() {
        val stalls = NylocasSimulationStalls()
        val waves = listOf(1, 19, 20, 20, 21, 28, 29, 29, 29, 30, 31)
        waves.forEachIndexed { index, wave ->
            val cap = if (wave <= 20) 12 else 24
            stalls.observe("LNC1 room ${index + 1} ${index * 4} $wave 0 $cap $cap")
        }
        assertEquals(4, stalls.preCap)
        assertEquals(7, stalls.postCap)
        assertEquals(6, stalls.pre29)
        assertEquals(3, stalls.wave29)
    }

    @Test
    fun `successful admissions and unrelated messages are not stalls`() {
        val stalls = NylocasSimulationStalls()
        stalls.observe("LNW4 room starting 0 0 0")
        stalls.observe("LNC1 room 1 4 20 0 11 12")
        stalls.observe("LNC1 room 2 8 29 0 23 24")
        assertEquals(0, stalls.preCap)
        assertEquals(0, stalls.postCap)
        assertEquals(0, stalls.pre29)
        assertEquals(0, stalls.wave29)
    }
}
