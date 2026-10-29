package org.rsmod.game.entity.util

import kotlin.random.Random
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList

public class ShuffledPlayerList(private val playerList: PlayerList) : Iterable<Player> {
    private val shuffledSlots = IntArray(playerList.capacity) { it }
    private var random: Random = Random.Default

    /** Reproduce player processing order in offline simulations. Live worlds use Random.Default. */
    public fun reseed(seed: Long) {
        random = Random(seed)
        for (slot in shuffledSlots.indices) shuffledSlots[slot] = slot
    }

    public fun shuffle() {
        shuffledSlots.shuffle(random)
    }

    override fun iterator(): Iterator<Player> = ShuffledListIterator()

    private inner class ShuffledListIterator : Iterator<Player> {
        private var cursor = 0

        override fun hasNext(): Boolean {
            while (cursor < shuffledSlots.size) {
                val slot = shuffledSlots[cursor]
                val entry = playerList[slot]
                if (entry != null) {
                    return true
                }
                cursor++
            }
            return false
        }

        override fun next(): Player {
            val slot = shuffledSlots[cursor++]
            val entry = playerList[slot]
            return checkNotNull(entry)
        }
    }
}
