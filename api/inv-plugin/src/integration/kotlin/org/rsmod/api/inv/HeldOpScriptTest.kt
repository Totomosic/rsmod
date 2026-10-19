package org.rsmod.api.inv

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.rsmod.api.config.refs.objs
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.righthand
import org.rsmod.api.testing.GameTestState
import org.rsmod.game.inv.InvObj
import org.rsmod.game.type.obj.Wearpos

@Execution(ExecutionMode.SAME_THREAD)
class HeldOpScriptTest {
    @Test
    fun GameTestState.`rapid repeated gear click does not undo the switch`() =
        runGameTest(HeldOpScript::class) {
            player.stats[stats.attack] = 99
            player.stats[stats.defence] = 99
            player.righthand = InvObj(objs.adamant_axe)
            player.inv[0] = InvObj(objs.rune_axe)
            player.inv[1] = InvObj(objs.rune_platebody)

            // Both packets refer to the rune axe, before the client sees the first swap.
            player.opHeld(0, op = 2)
            player.opHeld(1, op = 2)
            player.opHeld(0, op = 2)
            advance(ticks = 1)

            assertEquals(InvObj(objs.rune_axe), player.righthand)
            assertEquals(InvObj(objs.adamant_axe), player.inv[0])
            assertEquals(InvObj(objs.rune_platebody), player.worn[Wearpos.Torso.slot])
            assertNull(player.inv[1])

            // A fresh click on the returned item must still be allowed to switch back.
            player.opHeld(0, op = 2)
            advance(ticks = 1)
            assertEquals(InvObj(objs.adamant_axe), player.righthand)
            assertEquals(InvObj(objs.rune_axe), player.inv[0])
        }

    @Test
    fun GameTestState.`rapid gear clicks equip every item in the same tick`() =
        runGameTest(HeldOpScript::class) {
            for (stat in
                listOf(stats.attack, stats.strength, stats.defence, stats.ranged, stats.magic)) {
                player.stats[stat] = 99
            }
            val gear =
                listOf(
                    objs.music_hood to Wearpos.Hat,
                    objs.rune_platebody to Wearpos.Torso,
                    objs.rune_platelegs to Wearpos.Legs,
                    objs.rune_axe to Wearpos.RightHand,
                    objs.rune_kiteshield to Wearpos.LeftHand,
                )
            for ((slot, entry) in gear.withIndex()) {
                player.inv[slot] = InvObj(entry.first)
                player.opHeld(slot, op = 2)
            }
            advance(ticks = 1)
            for ((slot, entry) in gear.withIndex()) {
                assertEquals(InvObj(entry.first), player.worn[entry.second.slot])
                assertNull(player.inv[slot])
            }
        }
}
