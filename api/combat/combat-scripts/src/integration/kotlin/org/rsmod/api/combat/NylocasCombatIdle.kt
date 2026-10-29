package org.rsmod.api.combat

/** Counts within each player's first attack through the final living crab's death. */
internal class NylocasCombatIdle {
    val idle = IntArray(2)
    val readyIdle = IntArray(2)
    private val started = BooleanArray(2)
    private val pendingIdle = IntArray(2)
    private val pendingReadyIdle = IntArray(2)

    fun observe(living: Boolean, attacked: BooleanArray, ready: BooleanArray) {
        for (role in 0..1) {
            if (attacked[role]) started[role] = true
            else if (started[role]) {
                pendingIdle[role]++
                if (ready[role]) pendingReadyIdle[role]++
            }
        }
        // A later child/spawn restores intervening waits. Only the terminal tail is excluded.
        if (living) {
            pendingIdle.copyInto(idle)
            pendingReadyIdle.copyInto(readyIdle)
        }
    }
}
