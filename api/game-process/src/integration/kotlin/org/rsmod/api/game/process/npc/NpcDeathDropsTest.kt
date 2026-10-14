package org.rsmod.api.game.process.npc

import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.rsmod.api.combat.commons.npc.queueCombatRetaliate
import org.rsmod.api.config.refs.objs
import org.rsmod.api.death.plugin.NpcDeathScript
import org.rsmod.api.hit.plugin.NpcHitScript
import org.rsmod.api.player.ironman.IronmanMode
import org.rsmod.api.player.ironman.ironmanMode
import org.rsmod.api.registry.obj.ObjRegistry
import org.rsmod.api.testing.GameTestState
import org.rsmod.api.testing.factory.npcTypeFactory
import org.rsmod.game.entity.npc.NpcMode
import org.rsmod.game.hit.HitType
import org.rsmod.map.CoordGrid

class NpcDeathDropsTest {
    @Test
    fun GameTestState.`npc without drop table drops bones for its killer`() =
        runInjectedGameTest(
            Dependencies::class,
            null,
            NpcHitScript::class,
            NpcDeathScript::class,
        ) { deps ->
            val npc = spawnNpc(CoordGrid(0, 50, 50, 10, 10), man)
            val deathCoords = npc.coords
            player.placeAt(npc.coords.translateZ(1))
            player.observerUUID = 1 // Normally assigned on login; required to own ground objs.

            // Hero points are normally awarded by combat; they determine who receives the drops.
            npc.heroPoints(player, 7)
            npc.queueCombatRetaliate(player, delay = 1)
            npc.queueHit(player, delay = 1, HitType.Melee, damage = 7)
            advance(ticks = 2)
            advanceUntil(npc::isNotDelayed)

            val drops = deps.objRegistry.findAll(deathCoords).map { it.type to it.count }.toList()
            assertEquals(listOf(objs.bones.id to 1), drops)
        }

    @Test
    fun GameTestState.`ironman gets no drops when another player attacked the npc`() =
        runInjectedGameTest(
            Dependencies::class,
            null,
            NpcHitScript::class,
            NpcDeathScript::class,
        ) { deps ->
            val npc = spawnNpc(CoordGrid(0, 50, 50, 10, 10), man)
            val deathCoords = npc.coords
            player.placeAt(npc.coords.translateZ(1))
            player.observerUUID = 1
            player.ironmanMode = IronmanMode.Standard
            val helper = registerPlayer(npc.coords.translateX(1))

            npc.heroPoints(player, 6)
            npc.heroPoints(helper, 1)
            npc.queueCombatRetaliate(player, delay = 1)
            npc.queueHit(player, delay = 1, HitType.Melee, damage = 7)
            advance(ticks = 2)
            advanceUntil(npc::isNotDelayed)

            assertEquals(
                emptyList<Int>(),
                deps.objRegistry.findAll(deathCoords).map { it.type }.toList(),
            )
        }

    class Dependencies @Inject constructor(val objRegistry: ObjRegistry)

    private companion object {
        private val man =
            npcTypeFactory.create {
                name = "Man"
                op[1] = "Attack"
                size = 1
                hitpoints = 7
                defaultMode = NpcMode.None
            }
    }
}
