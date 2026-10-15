package org.rsmod.content.other.login

import net.rsprot.protocol.game.outgoing.varp.VarpSmall
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.rsmod.api.config.refs.stats
import org.rsmod.api.config.refs.varps
import org.rsmod.api.player.stat.stat
import org.rsmod.api.player.stat.statBase
import org.rsmod.api.testing.GameTestState
import org.rsmod.game.entity.player.SessionStateEvent

class LoginVarsTest {
    @Test
    fun GameTestState.`login starts every skill at 99 with matching xp and combat level`() =
        runGameTest(LoginScript::class) {
            eventBus.publish(SessionStateEvent.EngineLogin(player))

            for (stat in cacheTypes.stats.values) {
                assertEquals(99, player.statBase(stat))
                assertEquals(99, player.stat(stat))
                assertEquals(13_034_431, player.statMap.getXP(stat))
            }
            assertEquals(126, player.combatLevel)
        }

    @Test
    fun GameTestState.`login restores stats while preserving xp earned above 99`() =
        runGameTest(LoginScript::class) {
            val savedFineXp = 200_000_001
            player.statMap.setFineXP(stats.attack, savedFineXp)
            player.statMap.setBaseLevel(stats.attack, 99)
            player.statMap.setCurrentLevel(stats.hitpoints, 20)
            player.statMap.setCurrentLevel(stats.prayer, 0)

            eventBus.publish(SessionStateEvent.EngineLogin(player))
            eventBus.publish(SessionStateEvent.EngineLogin(player))

            assertEquals(savedFineXp, player.statMap.getFineXP(stats.attack))
            assertEquals(99, player.stat(stats.hitpoints))
            assertEquals(99, player.stat(stats.prayer))
        }

    @Test
    fun GameTestState.`attack option settings are sent on login even when default`() =
        runGameTest(LoginScript::class) {
            check(player.vars[varps.option_attackpriority_npc] == 0)
            check(player.vars[varps.option_attackpriority] == 0)
            client.clearOutgoing()
            eventBus.publish(SessionStateEvent.EngineLogin(player))
            val sent = client.filterIsInstance<VarpSmall>()
            assertTrue(sent.any { it.id == varps.option_attackpriority_npc.id && it.value == 0 })
            assertTrue(sent.any { it.id == varps.option_attackpriority.id && it.value == 0 })
        }
}
