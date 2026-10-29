package org.rsmod.api.combat

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test

class NylocasCombatIdleTest {
    @Test
    fun `each role starts counting at its own first confirmed attack`() {
        val metric = NylocasCombatIdle()
        repeat(5) { metric.observe(true, booleanArrayOf(false, false), booleanArrayOf(true, true)) }
        metric.observe(true, booleanArrayOf(true, false), booleanArrayOf(true, true))
        metric.observe(true, booleanArrayOf(false, false), booleanArrayOf(false, true))
        metric.observe(true, booleanArrayOf(false, true), booleanArrayOf(true, true))
        assertArrayEquals(intArrayOf(2, 0), metric.idle)
        assertArrayEquals(intArrayOf(1, 0), metric.readyIdle)
    }

    @Test
    fun `terminal death animations and boss waits are excluded but waits for children remain`() {
        val metric = NylocasCombatIdle()
        val ready = booleanArrayOf(true, true)
        metric.observe(true, booleanArrayOf(true, true), ready)
        repeat(3) { metric.observe(false, booleanArrayOf(false, false), ready) }
        assertArrayEquals(intArrayOf(0, 0), metric.readyIdle)
        // Children of the dying big are living work, so the preceding wait was part of combat.
        metric.observe(true, booleanArrayOf(false, false), ready)
        assertArrayEquals(intArrayOf(4, 4), metric.readyIdle)
        metric.observe(true, booleanArrayOf(true, true), ready)
        repeat(20) { metric.observe(false, booleanArrayOf(false, false), ready) }
        assertArrayEquals(intArrayOf(4, 4), metric.idle)
        assertArrayEquals(intArrayOf(4, 4), metric.readyIdle)
    }
}
