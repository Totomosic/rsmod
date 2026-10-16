package org.rsmod.content.areas.dungeons.nylocas

import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.rsmod.api.config.refs.npcs
import org.rsmod.api.config.refs.objs
import org.rsmod.api.death.plugin.NpcDeathScript
import org.rsmod.api.npc.queueDeath
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.testing.GameTestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.npc.NpcMode
import org.rsmod.game.type.npc.NpcTypeList

class NylocasDeathsTest {
    class Dependencies
    @Inject
    constructor(val npcTypes: NpcTypeList, val npcRepo: NpcRepository, val objRepo: ObjRepository)

    @ParameterizedTest
    @ValueSource(ints = [8342, 8343, 8344, 8345, 8346, 8347, 8348, 8349, 8350, 8351, 8352, 8353])
    fun `every wave nylo form dies without dropping bones`(id: Int, state: GameTestState) =
        state.runInjectedGameTest(
            Dependencies::class,
            null,
            NpcDeathScript::class,
            NylocasDeaths::class,
        ) { deps ->
            player.observerUUID = 1 // Normally assigned on login; required for ground drops.
            val npc = Npc(checkNotNull(deps.npcTypes[id]), player.coords.translateX(1))
            npc.mode = NpcMode.None
            npc.setHunt(0)
            npc.aiTimer(0)
            deps.npcRepo.add(npc, Int.MAX_VALUE)
            npc.respawns = false
            // Give a real player credit so the generic handler would create its bone drop.
            npc.heroPoints(player, 1)
            npc.hitpoints = 0
            npc.queueDeath()
            advanceUntil(predicate = { !npc.isSlotAssigned }, timeoutTicks = 20)
            advance()
            assertTrue(deps.objRepo.findAll(npc.coords).none())
        }

    @Test
    fun GameTestState.`unrelated NPCs retain their ordinary death drops`() =
        runInjectedGameTest(
            Dependencies::class,
            null,
            NpcDeathScript::class,
            NylocasDeaths::class,
        ) { deps ->
            player.observerUUID = 1 // Normally assigned on login; required for ground drops.
            val npc = Npc(deps.npcTypes[npcs.cow], player.coords.translateX(1))
            npc.mode = NpcMode.None
            npc.setHunt(0)
            npc.aiTimer(0)
            deps.npcRepo.add(npc, Int.MAX_VALUE)
            npc.respawns = false
            npc.heroPoints(player, 1)
            npc.hitpoints = 0
            npc.queueDeath()
            advanceUntil(predicate = { !npc.isSlotAssigned }, timeoutTicks = 20)
            advance()
            assertEquals(
                listOf(objs.bones.id),
                deps.objRepo.findAll(npc.coords).map { it.type }.toList(),
            )
        }
}
