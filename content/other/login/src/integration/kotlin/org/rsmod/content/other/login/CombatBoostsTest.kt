package org.rsmod.content.other.login

import jakarta.inject.Inject
import net.rsprot.protocol.game.outgoing.misc.player.UpdateStatV2
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.rsmod.api.config.refs.stats
import org.rsmod.api.config.refs.timers
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.game.process.player.PlayerEngineQueueProcessor
import org.rsmod.api.player.events.PlayerTimerEvent
import org.rsmod.api.player.stat.stat
import org.rsmod.api.player.stat.statSub
import org.rsmod.api.stats.plugin.StatRegenScript
import org.rsmod.api.testing.GameTestState
import org.rsmod.game.entity.player.SessionStateEvent

class CombatBoostsTest {
    class Dependencies @Inject constructor(val queues: PlayerEngineQueueProcessor)

    @Test
    fun GameTestState.`decay is corrected by the stat queue before player combat interactions`() =
        runInjectedGameTest(
            Dependencies::class,
            null,
            LoginScript::class,
            StatRegenScript::class,
        ) { deps ->
            eventBus.publish(SessionStateEvent.EngineLogin(player))
            // Exercise the real timer handler and subsequent engine queues without
            // any cycle-boundary top-up masking an incorrect processing order.
            eventBus.publish(PlayerTimerEvent.Soft(player, timers.stat_boost_restore.id))
            assertEquals(117, player.stat(stats.attack))
            assertEquals(111, player.stat(stats.ranged))
            assertEquals(111, player.stat(stats.magic))
            deps.queues.process(player)
            assertEquals(118, player.stat(stats.attack))
            assertEquals(112, player.stat(stats.ranged))
            assertEquals(112, player.stat(stats.magic))
        }

    @Test
    fun GameTestState
        .`boosts survive recurring decay and a complete stat drain without stacking`() =
        runGameTest(LoginScript::class, StatRegenScript::class) {
            eventBus.publish(SessionStateEvent.EngineLogin(player))
            player.softTimer(timers.stat_boost_restore, 1)
            for (stat in
                listOf(stats.attack, stats.strength, stats.defence, stats.ranged, stats.magic)) {
                player.statSub(stat, constant = 200, percent = 0)
                assertEquals(0, player.stat(stat))
            }
            var checkedCycles = 0
            eventBus.subscribeUnbound(GameLifecycle.EndCycle::class.java) {
                assertEquals(118, player.stat(stats.attack))
                assertEquals(118, player.stat(stats.strength))
                assertEquals(118, player.stat(stats.defence))
                assertEquals(112, player.stat(stats.ranged))
                assertEquals(112, player.stat(stats.magic))
                checkedCycles++
            }
            advance(ticks = 205)
            assertEquals(205, checkedCycles)
            assertEquals(13_034_431, player.statMap.getXP(stats.attack))
            assertEquals(126, player.combatLevel)
        }

    @ParameterizedTest
    @CsvSource("1, 6, 5", "69, 84, 79", "70, 85, 81", "99, 118, 112")
    fun `boosts use base levels and round potion percentages down`(
        base: Int,
        melee: Int,
        rangedAndMagic: Int,
        state: GameTestState,
    ) =
        state.runGameTest(LoginScript::class) {
            for (stat in
                listOf(stats.attack, stats.strength, stats.defence, stats.ranged, stats.magic)) {
                player.setBaseLevel(stat, base)
                player.setCurrentLevel(stat, 0)
            }
            advance()
            assertEquals(melee, player.stat(stats.attack))
            assertEquals(melee, player.stat(stats.strength))
            assertEquals(melee, player.stat(stats.defence))
            assertEquals(rangedAndMagic, player.stat(stats.ranged))
            assertEquals(rangedAndMagic, player.stat(stats.magic))
        }

    @Test
    fun GameTestState.`mid cycle restoration restores boosted levels before client stat updates`() =
        runGameTest(LoginScript::class) {
            eventBus.publish(SessionStateEvent.EngineLogin(player))
            // Simulate Restore Skills running after the initial cycle boost pass.
            eventBus.subscribeUnbound(GameLifecycle.StartCycle::class.java) {
                player.setCurrentLevel(stats.attack, 99)
                player.setCurrentLevel(stats.ranged, 99)
                player.setCurrentLevel(stats.magic, 99)
            }
            client.clearOutgoing()
            advance()
            assertEquals(118, player.stat(stats.attack))
            assertEquals(112, player.stat(stats.ranged))
            assertEquals(112, player.stat(stats.magic))
            val packets = client.filterIsInstance<UpdateStatV2>()
            assertTrue(UpdateStatV2(stats.attack.id, 118, 118, 13_034_431) in packets)
            assertTrue(UpdateStatV2(stats.ranged.id, 112, 112, 13_034_431) in packets)
            assertTrue(UpdateStatV2(stats.magic.id, 112, 112, 13_034_431) in packets)
        }

    @Test
    fun GameTestState.`boost upkeep preserves stronger boosts and other drained skills`() =
        runGameTest(LoginScript::class) {
            eventBus.publish(SessionStateEvent.EngineLogin(player))
            player.setCurrentLevel(stats.attack, 125)
            player.setCurrentLevel(stats.magic, 120)
            player.setCurrentLevel(stats.hitpoints, 30)
            player.setCurrentLevel(stats.prayer, 7)
            player.setCurrentLevel(stats.agility, 50)
            advance()
            assertEquals(125, player.stat(stats.attack))
            assertEquals(120, player.stat(stats.magic))
            assertEquals(30, player.stat(stats.hitpoints))
            assertEquals(7, player.stat(stats.prayer))
            assertEquals(50, player.stat(stats.agility))
        }
}
