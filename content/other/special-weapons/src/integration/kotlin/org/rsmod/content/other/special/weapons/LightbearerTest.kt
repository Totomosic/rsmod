package org.rsmod.content.other.special.weapons

import org.junit.jupiter.api.Test
import org.rsmod.api.config.refs.objs
import org.rsmod.api.config.refs.varps
import org.rsmod.api.player.deathResetTimers
import org.rsmod.api.player.events.interact.HeldEquipEvents
import org.rsmod.api.player.vars.intVarp
import org.rsmod.api.specials.scripts.SpecialEnergyScript
import org.rsmod.api.testing.GameTestState
import org.rsmod.api.testing.scope.GameTestScope
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.player.SessionStateEvent
import org.rsmod.game.inv.InvObj
import org.rsmod.game.type.obj.Wearpos

class LightbearerTest {
    @Test
    fun GameTestState.`death timer reset respects an equipped lightbearer`() = runRegenTest {
        ring(true)
        advance(ticks = 10)
        player.deathResetTimers()
        advance(ticks = 24)
        assertEquals(0, player.energy)
        advance()
        assertEquals(100, player.energy)
    }

    @Test
    fun GameTestState.`normal regeneration restores ten percent every fifty ticks`() =
        runRegenTest {
            advance(ticks = 49)
            assertEquals(0, player.energy)
            advance()
            assertEquals(100, player.energy)
            advance(ticks = 50)
            assertEquals(200, player.energy)
        }

    @Test
    fun GameTestState.`logging in with lightbearer halves the regeneration interval`() =
        runRegenTest {
            ring(true)
            eventBus.publish(SessionStateEvent.Login(player))
            advance(ticks = 24)
            assertEquals(0, player.energy)
            advance()
            assertEquals(100, player.energy)
            advance(ticks = 25)
            assertEquals(200, player.energy)
        }

    @Test
    fun GameTestState.`equipping caps a long remaining interval to twenty five ticks`() =
        runRegenTest {
            advance(ticks = 10)
            ring(true)
            advance(ticks = 24)
            assertEquals(0, player.energy)
            advance()
            assertEquals(100, player.energy)
        }

    @Test
    fun GameTestState.`equipping preserves a restore due in fewer than twenty five ticks`() =
        runRegenTest {
            advance(ticks = 40)
            ring(true)
            advance(ticks = 9)
            assertEquals(0, player.energy)
            advance()
            assertEquals(100, player.energy)
            advance(ticks = 25)
            assertEquals(200, player.energy)
        }

    @Test
    fun GameTestState.`unequipping resets to a full normal interval`() = runRegenTest {
        ring(true)
        advance(ticks = 10)
        ring(false)
        advance(ticks = 49)
        assertEquals(0, player.energy)
        advance()
        assertEquals(100, player.energy)
        advance(ticks = 50)
        assertEquals(200, player.energy)
    }

    @Test
    fun GameTestState
        .`unrelated equipment notifications do not reset the ring timer and energy caps at full`() =
        runRegenTest {
            ring(true)
            player.energy = 950
            advance(ticks = 20)
            ring(true)
            advance(ticks = 6)
            assertEquals(1000, player.energy)
            advance(ticks = 25)
            assertEquals(1000, player.energy)
        }

    private fun GameTestState.runRegenTest(body: GameTestScope.() -> Unit) =
        runGameTest(SpecialEnergyScript::class) {
            eventBus.publish(SessionStateEvent.Login(player))
            player.energy = 0
            body()
        }

    private fun GameTestScope.ring(equipped: Boolean) {
        player.worn[Wearpos.Ring.slot] = if (equipped) InvObj(objs.lightbearer) else null
        eventBus.publish(
            HeldEquipEvents.WearposChange(player, Wearpos.Ring, objTypes[objs.lightbearer])
        )
    }

    private companion object {
        var Player.energy by intVarp(varps.sa_energy)
    }
}
