package org.rsmod.content.areas.dungeons.nylocas

import com.google.inject.AbstractModule
import jakarta.inject.Inject
import net.rsprot.protocol.game.outgoing.zone.payload.MapAnim
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import org.rsmod.api.config.refs.components
import org.rsmod.api.config.refs.queues
import org.rsmod.api.config.refs.stats
import org.rsmod.api.config.refs.varbits
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.hit.plugin.PlayerHitScript
import org.rsmod.api.npc.isValidTarget
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.registry.region.RegionRegistry
import org.rsmod.api.registry.zone.ZoneUpdateMap
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.testing.GameTestState
import org.rsmod.api.testing.scope.GameTestScope
import org.rsmod.events.EventBus
import org.rsmod.game.MapClock
import org.rsmod.game.entity.NpcList
import org.rsmod.game.map.collision.get
import org.rsmod.game.movement.MoveSpeed
import org.rsmod.game.region.Region
import org.rsmod.game.seq.EntitySeq
import org.rsmod.map.CoordGrid
import org.rsmod.map.zone.ZoneGrid
import org.rsmod.map.zone.ZoneKey
import org.rsmod.routefinder.collision.CollisionFlagMap
import org.rsmod.routefinder.flag.CollisionFlag

class NylocasExpiryTest {
    class Dependencies
    @Inject
    constructor(
        val regions: RegionRegistry,
        val npcs: NpcList,
        val raid: TheatreRaid,
        val clock: MapClock,
        val events: EventBus,
        val zoneUpdates: ZoneUpdateMap,
        val collision: CollisionFlagMap,
        val npcRepo: NpcRepository,
    )

    @ParameterizedTest
    @CsvSource(
        "Small, Melee, false, 51, 52, 8005, 1565",
        "Small, Ranged, false, 51, 52, 7998, 1566",
        "Small, Magic, false, 51, 52, 7991, 1567",
        "Big, Melee, false, 52, 55, 8006, -1",
        "Big, Ranged, false, 52, 55, 8000, -1",
        "Big, Magic, false, 52, 55, 7992, -1",
        "Small, Melee, true, 51, 52, 8005, 1565",
        "Small, Ranged, true, 51, 52, 7998, 1566",
        "Small, Magic, true, 51, 52, 7991, 1567",
        "Big, Melee, true, 52, 55, 8006, -1",
        "Big, Ranged, true, 52, 55, 8000, -1",
        "Big, Magic, true, 52, 55, 7992, -1",
    )
    fun `expiry stops actions and preserves the body until its recorded removal age`(
        size: NylocasSize,
        style: NylocasStyle,
        aggro: Boolean,
        animationAge: Int,
        removalAge: Int,
        animationId: Int,
        effectId: Int,
        state: GameTestState,
    ) {
        val spawn = NylocasSpawn(size, style, if (aggro) null else NylocasPillar.SouthWest)
        state.runInjectedGameTest(
            Dependencies::class,
            table(spawn),
            NylocasEncounter::class,
            TheatreRaid::class,
        ) { deps ->
            start(deps)
            val npc = deps.npcs.single()
            val born = deps.clock.cycle - 1
            val animations = mutableListOf<Pair<Int, Int>>()
            val effects = mutableListOf<Pair<Int, MapAnim>>()
            deps.events.subscribeUnbound(GameLifecycle.LateCycle::class.java) {
                val age = deps.clock.cycle - 1 - born
                if (npc.pendingSequence != EntitySeq.NULL)
                    animations += age to npc.pendingSequence.id
                val updates = deps.zoneUpdates[ZoneKey.from(npc.coords)]
                for (effect in updates?.filterIsInstance<MapAnim>().orEmpty()) effects +=
                    age to effect
            }
            // Partial damage must not reset or shorten the absolute lifetime.
            advance(ticks = 20)
            npc.hitpoints = 1
            advance(ticks = animationAge - 21)
            assertTrue(npc.isValidTarget())
            assertTrue(animations.isEmpty())
            assertTrue(effects.isEmpty())
            val lastPosition = npc.coords
            npc.walk(npc.coords.translateX(4))
            npc.queue(queues.com_retaliate_player, 1)
            advance()
            assertEquals(lastPosition, npc.coords)
            assertEquals(MoveSpeed.Stationary, npc.moveSpeed)
            assertTrue(npc.queueList.isEmpty)
            assertFalse(npc.isValidTarget())
            assertEquals(listOf(animationAge to animationId), animations)
            assertEquals(1, npc.hitpoints)
            assertEquals(size.hitpoints(1), npc.baseHitpointsLvl)
            for (age in animationAge + 1 until removalAge) {
                advance()
                assertTrue(npc.isSlotAssigned)
                assertEquals(lastPosition, npc.coords)
                assertEquals(1, npc.hitpoints)
                assertTrue(effects.isEmpty())
            }
            advance()
            assertFalse(npc.isSlotAssigned)
            val expectedChildren = if (size == NylocasSize.Big) 2 else 0
            assertEquals(expectedChildren, deps.npcs.count())
            assertEquals(
                if (size == NylocasSize.Big) CollisionFlag.BLOCK_NPCS else 0,
                deps.collision[lastPosition] and CollisionFlag.BLOCK_NPCS,
            )
            if (effectId >= 0) {
                val local = ZoneGrid.from(lastPosition)
                assertEquals(
                    listOf(removalAge to MapAnim(effectId, 0, 0, local.x, local.z)),
                    effects,
                )
            } else {
                assertTrue(effects.isEmpty())
            }
            advance(ticks = 10)
            assertEquals(expectedChildren, deps.npcs.count())
            assertEquals(1, animations.size)
            assertEquals(if (effectId >= 0) 1 else 0, effects.size)
        }
    }

    @ParameterizedTest
    @CsvSource("Small, 51, 52", "Big, 52, 55")
    fun `expiry continues when normal AI processing is delayed or disabled`(
        size: NylocasSize,
        animationAge: Int,
        removalAge: Int,
        state: GameTestState,
    ) {
        val spawn = NylocasSpawn(size, NylocasStyle.Magic, NylocasPillar.SouthWest)
        state.runInjectedGameTest(
            Dependencies::class,
            table(spawn),
            NylocasEncounter::class,
            TheatreRaid::class,
        ) { deps ->
            start(deps)
            val npc = deps.npcs.single()
            advance(ticks = 10)
            npc.delay = deps.clock.cycle + 100
            npc.aiTimer(0)
            val position = npc.coords
            advance(ticks = animationAge - 10)
            assertTrue(npc.isSlotAssigned)
            assertFalse(npc.canProcess)
            assertEquals(position, npc.coords)
            advance(ticks = removalAge - animationAge)
            assertFalse(npc.isSlotAssigned)
        }
    }

    @ParameterizedTest
    @EnumSource(NylocasSize::class)
    fun `a prior lethal hit cannot trigger a later expiry`(
        size: NylocasSize,
        state: GameTestState,
    ) {
        val spawn = NylocasSpawn(size, NylocasStyle.Melee, NylocasPillar.SouthWest)
        state.runInjectedGameTest(
            Dependencies::class,
            table(spawn),
            NylocasEncounter::class,
            TheatreRaid::class,
        ) { deps ->
            start(deps)
            val npc = deps.npcs.single()
            advance(ticks = 49)
            npc.hitpoints = 0
            npc.queue(queues.death, 1)
            advance(ticks = 7)
            // No default death script is registered: expiry must leave the kill alone.
            assertTrue(npc.isSlotAssigned)
            assertFalse(npc.isValidTarget())
            assertTrue(npc.delay < deps.clock.cycle)
            deps.npcRepo.del(npc, Int.MAX_VALUE)
            advance(ticks = 60)
            assertEquals(0, deps.npcs.count())
        }
    }

    @ParameterizedTest
    @EnumSource(NylocasSize::class)
    fun `externally deleting a nylo cancels its pending expiry`(
        size: NylocasSize,
        state: GameTestState,
    ) {
        val spawn = NylocasSpawn(size, NylocasStyle.Magic, NylocasPillar.SouthWest)
        state.runInjectedGameTest(
            Dependencies::class,
            table(spawn),
            NylocasEncounter::class,
            TheatreRaid::class,
        ) { deps ->
            start(deps)
            val npc = deps.npcs.single()
            advance(ticks = 50)
            val zone = ZoneKey.from(npc.coords)
            var effects = 0
            deps.events.subscribeUnbound(GameLifecycle.LateCycle::class.java) {
                effects += deps.zoneUpdates[zone]?.filterIsInstance<MapAnim>().orEmpty().size
            }
            deps.npcRepo.del(npc, Int.MAX_VALUE)
            advance(ticks = 10)
            assertEquals(0, deps.npcs.count())
            assertEquals(0, effects)
        }
    }

    @ParameterizedTest
    @CsvSource("Small, 51, 52, 18", "Big, 52, 55, 21")
    fun `provisional explosion hits its footprint and adjacent tiles once in the same instance`(
        size: NylocasSize,
        animationAge: Int,
        removalAge: Int,
        maxHit: Int,
        state: GameTestState,
    ) {
        val spawn = NylocasSpawn(size, NylocasStyle.Melee, NylocasPillar.SouthWest)
        state.runInjectedGameTest(
            Dependencies::class,
            table(spawn),
            NylocasEncounter::class,
            TheatreRaid::class,
            PlayerHitScript::class,
        ) { deps ->
            val room = start(deps)
            val npc = deps.npcs.single()
            advance(ticks = animationAge - 1)
            val source = CoordGrid(3295, 4247, 0)
            npc.telejump(room.normal[source])
            advance()
            player.telejump(room.normal[source])
            val near = registerPlayer(coords = room.normal[source.translate(npc.size, npc.size)])
            val westCorner = registerPlayer(coords = room.normal[source.translate(-1, -1)])
            val far = registerPlayer(coords = room.normal[source.translateX(npc.size + 1)])
            val upstairs = registerPlayer(coords = room.normal[CoordGrid(source.x, source.z, 1)])
            val otherRoom = createRegion(NylocasRoom.template)
            val outsider = registerPlayer(coords = otherRoom.normal[source])
            for (target in listOf(player, near, westCorner, far, upstairs, outsider)) {
                target.setBaseLevel(stats.hitpoints, 99)
                target.setCurrentLevel(stats.hitpoints, 99)
            }
            VarPlayerIntMapSetter.set(player, varbits.protect_from_melee, 1)
            VarPlayerIntMapSetter.set(player, varbits.protect_from_missiles, 1)
            VarPlayerIntMapSetter.set(player, varbits.protect_from_magic, 1)
            advance(ticks = removalAge - animationAge - 1)
            for (target in listOf(player, near, westCorner)) assertEquals(99, target.hitpoints)
            random.next = maxHit
            random.then = maxHit
            random.then = maxHit
            advance()
            for (target in listOf(player, near, westCorner)) assertEquals(
                99 - maxHit,
                target.hitpoints,
            )
            for (target in listOf(far, upstairs, outsider)) assertEquals(99, target.hitpoints)
            assertFalse(npc.isSlotAssigned)
            advance(ticks = 4)
            for (target in listOf(player, near, westCorner)) assertEquals(
                99 - maxHit,
                target.hitpoints,
            )
        }
    }

    private fun table(spawn: NylocasSpawn): AbstractModule =
        object : AbstractModule() {
            override fun configure() {
                bind(NylocasWaveTable::class.java)
                    .toInstance(NylocasWaveTable(listOf(NylocasWave(4, southWest = spawn))))
            }
        }

    private fun GameTestScope.start(deps: Dependencies): Region {
        val barrier = placeMapLoc(CoordGrid(3295, 4255, 0), nylocas_locs.barrier)
        val room = createRegion(NylocasRoom.template)
        player.telejump(room.normal[CoordGrid(3295, 4256, 0)])
        advance()
        deps.raid.start(player)
        player.opLoc1(barrier.copy(coords = room.normal[barrier.coords]))
        advance()
        player.resumePauseButton(components.chatmenu_pbutton, sub = 1)
        advance(ticks = 5)
        player.telejump(room.normal[NylocasRoom.bridge])
        return room
    }
}
