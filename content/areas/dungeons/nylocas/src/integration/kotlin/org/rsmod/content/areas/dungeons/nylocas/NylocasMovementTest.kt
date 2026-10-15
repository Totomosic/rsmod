package org.rsmod.content.areas.dungeons.nylocas

import com.google.inject.AbstractModule
import com.google.inject.Scopes
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.rsmod.api.combat.weapon.scripts.WeaponAttackStylesScript
import org.rsmod.api.combat.weapon.styles.AttackStyles
import org.rsmod.api.config.refs.stats
import org.rsmod.api.hit.plugin.PlayerHitScript
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.registry.region.RegionRegistry
import org.rsmod.api.route.BoundValidator
import org.rsmod.api.testing.GameTestState
import org.rsmod.api.testing.scope.GameTestScope
import org.rsmod.game.entity.NpcAvatar
import org.rsmod.game.entity.NpcList
import org.rsmod.game.map.collision.add
import org.rsmod.game.region.Region
import org.rsmod.map.CoordGrid
import org.rsmod.routefinder.collision.CollisionFlagMap
import org.rsmod.routefinder.flag.CollisionFlag

class NylocasMovementTest {
    class Dependencies
    @Inject
    constructor(
        val regions: RegionRegistry,
        val npcs: NpcList,
        val raid: TheatreRaid,
        val bounds: BoundValidator,
        val collision: CollisionFlagMap,
    )

    @Test
    fun GameTestState.`default first wave finishes lanes before walking to assigned pillars`() =
        runInjectedGameTest(
            Dependencies::class,
            null,
            NylocasEncounter::class,
            TheatreRaid::class,
        ) { deps ->
            val room = start(deps)
            val entries =
                NylocasWaveTable().waves.single().spawns.associate { (slot, spawn) ->
                    slot.source to (slot to spawn)
                }
            assertEquals(3, deps.npcs.count())
            val definitions =
                deps.npcs.associateWith {
                    entries.getValue(deps.regions.normalizeCoords(it.coords))
                }
            val entered = mutableSetOf<org.rsmod.game.entity.Npc>()
            repeat(55) {
                for ((npc, definition) in definitions) {
                    val (slot, _) = definition
                    val source = deps.regions.normalizeCoords(npc.coords)
                    if (source == slot.entry) entered.add(npc)
                    if (npc !in entered) {
                        if (slot.name.startsWith("South")) assertEquals(slot.source.x, source.x)
                        else assertEquals(slot.source.z, source.z)
                        assertEquals(npc.id, npc.visType.id)
                    }
                }
                advance()
            }
            assertEquals(3, entered.size)
            for ((npc, definition) in definitions) {
                val spawn = definition.second
                assertEquals(nylocas_npcs.fighting(spawn.size, spawn.style).id, npc.visType.id)
                val pillar = NpcAvatar(3).apply { coords = room.normal[spawn.pillar!!.source] }
                assertTrue(deps.bounds.touches(npc.avatar, pillar))
            }
        }

    @Test
    fun GameTestState.`six slots support every size and style and later rows use their delay`() {
        val small = NylocasSize.Small
        val big = NylocasSize.Big
        val pillar = NylocasPillar.SouthEast
        val wave =
            NylocasWave(
                4,
                westNorth = NylocasSpawn(small, NylocasStyle.Melee, pillar),
                westSouth = NylocasSpawn(big, NylocasStyle.Melee, pillar),
                southWest = NylocasSpawn(small, NylocasStyle.Ranged, pillar),
                southEast = NylocasSpawn(big, NylocasStyle.Ranged, pillar),
                eastNorth = NylocasSpawn(small, NylocasStyle.Magic, pillar),
                eastSouth = NylocasSpawn(big, NylocasStyle.Magic, pillar),
            )
        val later = NylocasWave(6, westSouth = NylocasSpawn(small, NylocasStyle.Magic, pillar))
        runInjectedGameTest(
            Dependencies::class,
            table(wave, later),
            NylocasEncounter::class,
            TheatreRaid::class,
        ) { deps ->
            start(deps)
            assertEquals((8342..8347).toSet(), deps.npcs.map { it.id }.toSet())
            assertEquals(
                wave.spawns.map { it.first.source }.toSet(),
                deps.npcs.map { deps.regions.normalizeCoords(it.coords) }.toSet(),
            )
            for (npc in deps.npcs) assertEquals(if (npc.size == 1) 8 else 16, npc.hitpoints)
            advance(ticks = 5)
            assertEquals(6, deps.npcs.count())
            advance()
            assertEquals(7, deps.npcs.count())
            assertEquals(2, player.vars[tob_varbits.progress_value])
            advance(ticks = 60)
            assertTrue(deps.npcs.all { it.transmog != null })
        }
    }

    @Test
    fun GameTestState.`arena pathfinding routes around obstacles to a pillar`() {
        val wave =
            NylocasWave(
                4,
                westNorth =
                    NylocasSpawn(NylocasSize.Big, NylocasStyle.Melee, NylocasPillar.NorthEast),
            )
        runInjectedGameTest(
            Dependencies::class,
            table(wave),
            NylocasEncounter::class,
            TheatreRaid::class,
        ) { deps ->
            val room = start(deps)
            for (z in 4246..4251) deps.collision.add(
                room.normal[CoordGrid(3295, z, 0)],
                CollisionFlag.LOC,
            )
            val npc = deps.npcs.single()
            advance(ticks = 60)
            val pillar = NpcAvatar(3).apply { coords = room.normal[NylocasPillar.NorthEast.source] }
            assertTrue(deps.bounds.touches(npc.avatar, pillar))
        }
    }

    @Test
    fun GameTestState.`player targets are replaced on departure and stay inside this instance`() {
        val wave = NylocasWave(4, westNorth = NylocasSpawn(NylocasSize.Small, NylocasStyle.Magic))
        runInjectedGameTest(
            Dependencies::class,
            table(wave),
            NylocasEncounter::class,
            TheatreRaid::class,
            WeaponAttackStylesScript::class,
            PlayerHitScript::class,
        ) { deps ->
            val room = start(deps)
            player.setBaseLevel(stats.hitpoints, 99)
            player.setCurrentLevel(stats.hitpoints, 99)
            player.telejump(room.normal[CoordGrid(3293, 4249, 0)])
            val other = registerPlayer(coords = room.normal[CoordGrid(3298, 4250, 0)])
            other.setBaseLevel(stats.hitpoints, 99)
            other.setCurrentLevel(stats.hitpoints, 99)
            val secondRoom = createRegion(NylocasRoom.template)
            val outsider = registerPlayer(coords = secondRoom.normal[CoordGrid(3293, 4249, 0)])
            outsider.setBaseLevel(stats.hitpoints, 99)
            outsider.setCurrentLevel(stats.hitpoints, 99)
            advance(ticks = 50)
            assertTrue(player.hitpoints < 99)
            assertEquals(99, other.hitpoints)
            assertEquals(99, outsider.hitpoints)
            player.telejump(room.normal[NylocasRoom.bridge])
            advance(ticks = 3)
            val hpAfterDeparture = player.hitpoints
            advance(ticks = 50)
            assertEquals(hpAfterDeparture, player.hitpoints)
            assertTrue(other.hitpoints < 99)
            assertEquals(99, outsider.hitpoints)
        }
    }

    @ParameterizedTest
    @CsvSource(
        "Small, Melee",
        "Small, Ranged",
        "Small, Magic",
        "Big, Melee",
        "Big, Ranged",
        "Big, Magic",
    )
    fun `nylos without pillars finish the lane and attack a player in the arena`(
        size: NylocasSize,
        style: NylocasStyle,
        state: GameTestState,
    ) {
        val wave = NylocasWave(4, southWest = NylocasSpawn(size, style))
        state.runInjectedGameTest(
            Dependencies::class,
            table(wave),
            NylocasEncounter::class,
            TheatreRaid::class,
            WeaponAttackStylesScript::class,
            PlayerHitScript::class,
        ) { deps ->
            val room = start(deps)
            player.setBaseLevel(stats.hitpoints, 99)
            player.setCurrentLevel(stats.hitpoints, 99)
            player.telejump(room.normal[CoordGrid(3294, 4243, 0)])
            advance()
            val npc = deps.npcs.single()
            advance(ticks = 8)
            assertTrue(deps.regions.normalizeCoords(npc.coords).z < NylocasSlot.SouthWest.entry.z)
            assertEquals(99, player.hitpoints)
            advance(ticks = 100)
            assertEquals(nylocas_npcs.fighting(size, style).id, npc.visType.id)
            assertTrue(player.hitpoints < 99)
        }
    }

    private fun table(vararg waves: NylocasWave): AbstractModule =
        object : AbstractModule() {
            override fun configure() {
                bind(NylocasWaveTable::class.java).toInstance(NylocasWaveTable(waves.toList()))
                bind(AttackStyles::class.java).`in`(Scopes.SINGLETON)
            }
        }

    private fun GameTestScope.start(deps: Dependencies): Region {
        val source = placeMapLoc(CoordGrid(3295, 4255, 0), nylocas_locs.barrier)
        val room = createRegion(NylocasRoom.template)
        player.telejump(room.normal[CoordGrid(3295, 4256, 0)])
        advance()
        deps.raid.start(player)
        player.opLoc1(source.copy(coords = room.normal[source.coords]))
        advance(ticks = 5)
        return room
    }
}
