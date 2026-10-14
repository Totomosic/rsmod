package org.rsmod.api.game.process.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.rsmod.api.config.refs.stats
import org.rsmod.api.config.refs.varbits
import org.rsmod.api.hit.plugin.PlayerHitScript
import org.rsmod.api.player.hit.queueHit
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.player.stat.stat
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.testing.GameTestState
import org.rsmod.api.testing.factory.npcTypeFactory
import org.rsmod.game.entity.npc.NpcMode
import org.rsmod.game.hit.HitType
import org.rsmod.map.CoordGrid

class RedemptionTest {
    @Test
    fun GameTestState.`redemption heals a quarter of prayer when hit below 10 percent`() =
        runGameTest(PlayerHitScript::class) {
            val npc = spawnNpc(CoordGrid(0, 50, 50, 0, 0), dummy)
            player.placeAt(npc.coords.translateZ(1))
            player.stats[stats.hitpoints] = 99
            player.stats[stats.prayer] = 80
            VarPlayerIntMapSetter.set(player, varbits.redemption, 1)

            player.queueHit(npc, delay = 1, HitType.Typeless, damage = 91)
            advance(ticks = 1)

            assertEquals(8 + 20, player.hitpoints)
            assertEquals(0, player.stat(stats.prayer))
        }

    @Test
    fun GameTestState.`redemption does not trigger above 10 percent`() =
        runGameTest(PlayerHitScript::class) {
            val npc = spawnNpc(CoordGrid(0, 50, 50, 0, 0), dummy)
            player.placeAt(npc.coords.translateZ(1))
            player.stats[stats.hitpoints] = 99
            player.stats[stats.prayer] = 80
            VarPlayerIntMapSetter.set(player, varbits.redemption, 1)

            player.queueHit(npc, delay = 1, HitType.Typeless, damage = 89)
            advance(ticks = 1)

            assertEquals(10, player.hitpoints)
            assertEquals(80, player.stat(stats.prayer))
        }

    private companion object {
        private val dummy =
            npcTypeFactory.create {
                name = "Dummy"
                size = 1
                hitpoints = 10
                defaultMode = NpcMode.None
            }
    }
}
