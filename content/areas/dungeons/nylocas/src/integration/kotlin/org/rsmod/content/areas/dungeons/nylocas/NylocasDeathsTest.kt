package org.rsmod.content.areas.dungeons.nylocas

import jakarta.inject.Inject
import net.rsprot.protocol.game.outgoing.zone.payload.MapAnim
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.rsmod.api.config.refs.npcs
import org.rsmod.api.config.refs.objs
import org.rsmod.api.death.plugin.NpcDeathScript
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.hit.plugin.NpcHitScript
import org.rsmod.api.npc.events.NpcHitEvents
import org.rsmod.api.npc.queueDeath
import org.rsmod.api.registry.zone.ZoneUpdateMap
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.testing.GameTestState
import org.rsmod.events.EventBus
import org.rsmod.game.MapClock
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.npc.NpcMode
import org.rsmod.game.hit.HitType
import org.rsmod.game.movement.MoveRestrict
import org.rsmod.game.movement.MoveSpeed
import org.rsmod.game.seq.EntitySeq
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.map.zone.ZoneGrid
import org.rsmod.map.zone.ZoneKey

class NylocasDeathsTest {
    class Dependencies
    @Inject
    constructor(
        val npcTypes: NpcTypeList,
        val npcRepo: NpcRepository,
        val objRepo: ObjRepository,
        val events: EventBus,
        val clock: MapClock,
        val zoneUpdates: ZoneUpdateMap,
    )

    @ParameterizedTest
    @CsvSource(
        "Small, false, false, 2",
        "Small, false, true, 1",
        "Small, true, false, 2",
        "Small, true, true, 1",
        "Big, false, false, 6",
        "Big, false, true, 5",
        "Big, true, false, 7",
        "Big, true, true, 6",
    )
    fun `extra hitsplat removes a killed nylo exactly one tick sooner`(
        size: NylocasSize,
        moving: Boolean,
        overkill: Boolean,
        removalDelay: Int,
        state: GameTestState,
    ) =
        state.runInjectedGameTest(
            Dependencies::class,
            null,
            NpcHitScript::class,
            NylocasDeaths::class,
        ) { deps ->
            player.telejump(CoordGrid(512, 512, 3))
            val type = nylocas_npcs.incoming(size, NylocasStyle.Melee)
            val npc = Npc(deps.npcTypes[type], player.coords.translateX(2))
            npc.mode = NpcMode.None
            npc.setHunt(0)
            npc.aiTimer(0)
            npc.moveRestrict = MoveRestrict.Normal
            npc.defaultMoveSpeed = MoveSpeed.Walk
            npc.collisionSize = 1
            for (x in 0..7) allocZoneCollision(npc.coords.translateX(x))
            deps.npcRepo.add(npc, Int.MAX_VALUE)
            npc.respawns = false
            advance(ticks = 2)
            if (moving) {
                npc.walk(npc.coords.translateX(5))
                advance()
                assertTrue(npc.hasMovedThisCycle)
            }
            var impactCycle = -1
            var removalCycle = -1
            val impacts = mutableListOf<Int>()
            deps.events.subscribeKeyed(NpcHitEvents.Impact::class.java, type.id.toLong()) {
                if (impactCycle == -1) impactCycle = deps.clock.cycle
                impacts += hit.damage
            }
            deps.events.subscribeUnbound(GameLifecycle.LateCycle::class.java) {
                if (!npc.isSlotAssigned && removalCycle == -1) removalCycle = deps.clock.cycle - 1
            }
            val hp = npc.hitpoints
            npc.queueHit(player, delay = 1, HitType.Melee, damage = hp)
            if (overkill) npc.queueHit(player, delay = 1, HitType.Melee, damage = 1)
            advanceUntil(predicate = { !npc.isSlotAssigned }, timeoutTicks = 12)
            assertEquals(if (overkill) listOf(hp, 0) else listOf(hp), impacts)
            assertEquals(removalDelay, removalCycle - impactCycle)
        }

    @ParameterizedTest
    @CsvSource(
        "8342, 8342, 8005, 1, 1562",
        "8343, 8343, 7998, 1, 1563",
        "8344, 8344, 7991, 1, 1564",
        "8345, 8345, 8005, 5, -1",
        "8346, 8346, 7998, 5, -1",
        "8347, 8347, 7991, 5, -1",
        "8348, 8348, 8005, 1, 1562",
        "8349, 8349, 7998, 1, 1563",
        "8350, 8350, 7991, 1, 1564",
        "8351, 8351, 8005, 5, -1",
        "8352, 8352, 7998, 5, -1",
        "8353, 8353, 7991, 5, -1",
        "8342, 8349, 7998, 1, 1563",
        "8343, 8350, 7991, 1, 1564",
        "8344, 8348, 8005, 1, 1562",
        "8345, 8352, 7998, 5, -1",
        "8346, 8353, 7991, 5, -1",
        "8347, 8351, 8005, 5, -1",
    )
    fun `every wave form dies with its visible style and native effect without drops`(
        id: Int,
        visibleId: Int,
        animationId: Int,
        animationTicks: Int,
        effectId: Int,
        state: GameTestState,
    ) =
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
            if (id != visibleId) npc.transmog(checkNotNull(deps.npcTypes[visibleId]), Int.MAX_VALUE)
            val animations = mutableListOf<Pair<Int, Int>>()
            val effects = mutableListOf<MapAnim>()
            var removalCycle = -1
            deps.events.subscribeUnbound(GameLifecycle.LateCycle::class.java) {
                if (npc.pendingSequence != EntitySeq.NULL)
                    animations += deps.clock.cycle to npc.pendingSequence.id
                effects +=
                    deps.zoneUpdates[ZoneKey.from(npc.coords)]
                        ?.filterIsInstance<MapAnim>()
                        .orEmpty()
                if (!npc.isSlotAssigned && removalCycle == -1) removalCycle = deps.clock.cycle
            }
            // Give a real player credit so the generic handler would create its bone drop.
            npc.heroPoints(player, 1)
            npc.hitpoints = 0
            npc.queueDeath()
            advanceUntil(predicate = { !npc.isSlotAssigned }, timeoutTicks = 20)
            assertEquals(listOf(animationId), animations.map { it.second })
            assertEquals(animationTicks, removalCycle - animations.single().first)
            val local = ZoneGrid.from(npc.coords)
            assertEquals(
                if (effectId >= 0) listOf(MapAnim(effectId, 0, 0, local.x, local.z))
                else emptyList(),
                effects,
            )
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
