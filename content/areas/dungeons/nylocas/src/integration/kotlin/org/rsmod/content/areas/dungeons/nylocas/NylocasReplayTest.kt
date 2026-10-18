package org.rsmod.content.areas.dungeons.nylocas

import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.rsmod.api.registry.region.RegionRegistry
import org.rsmod.api.route.BoundValidator
import org.rsmod.api.testing.GameTestState
import org.rsmod.game.entity.NpcAvatar
import org.rsmod.game.entity.npc.NpcMode
import org.rsmod.game.map.collision.add
import org.rsmod.game.map.collision.remove
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.routefinder.collision.CollisionFlagMap
import org.rsmod.routefinder.flag.CollisionFlag

/**
 * Conditional one-step comparison with pinned Blert coordinates. The focal NPC uses real AI,
 * movement and cache terrain. Surrounding NPC flags are supplied from either observed frame to
 * expose the uncertainty in server actor order. Rebuilding those flags deliberately excludes
 * persistent collision holes; their lifecycle is covered independently by NylocasOccupancyTest.
 */
class NylocasReplayTest {
    class Dependencies
    @Inject
    constructor(
        val regions: RegionRegistry,
        val npcTypes: NpcTypeList,
        val behaviour: NylocasBehaviour,
        val collision: CollisionFlagMap,
        val bounds: BoundValidator,
    )

    @Test
    fun `recorded contexts retain their movement agreement`(state: GameTestState) {
        assertEquals(27, arrivalCases().size)
        var totalMatches = 0
        var totalSteps = 0
        for (case in cases()) for (phase in 0..1) {
            state.runInjectedGameTest(Dependencies::class, null, NylocasEncounter::class) { deps ->
                val room = createRegion(NylocasRoom.template)
                player.telejump(room.normal[NylocasRoom.bridge])
                val spawn = NylocasSpawn(case.size, NylocasStyle.Melee, case.pillar)
                val npc =
                    spawnNpc(
                        room.normal[case.slot.entry],
                        deps.npcTypes[nylocas_npcs.incoming(case.size, NylocasStyle.Melee)],
                    ) {
                        mode = NpcMode.None
                        setHunt(0)
                        deps.behaviour.add(this, room, case.slot, spawn, 2)
                    }
                advance(ticks = 2)
                var previousFlags = emptySet<CoordGrid>()
                var matches = 0
                val mismatches = mutableListOf<String>()
                for (step in case.steps) {
                    for (tile in previousFlags) deps.collision.remove(
                        tile,
                        CollisionFlag.BLOCK_NPCS,
                    )
                    npc.telejump(room.normal[step.before])
                    previousFlags = step.blockers[phase].map { room.normal[it] }.toSet()
                    for (tile in previousFlags) deps.collision.add(tile, CollisionFlag.BLOCK_NPCS)
                    advance()
                    val actual = deps.regions.normalizeCoords(npc.coords)
                    assertTrue(actual.chebyshevDistance(step.before) <= 1)
                    if (actual == step.after) matches++
                    else
                        mismatches +=
                            "${step.tick}:${actual.x},${actual.z}/${step.after.x},${step.after.z}"
                }
                println(
                    "NYLO_REPLAY\t${case.name}\t$phase\t$matches\t${case.steps.size}\t${mismatches.joinToString(";")}"
                )
                totalMatches += matches
                totalSteps += case.steps.size
            }
        }
        // Seeded retries make this a deterministic, recording-backed floor.
        // Individual ambiguous retry directions remain visible in the report.
        assertTrue(totalMatches.toDouble() / totalSteps >= 0.94) {
            "Recorded next-step agreement regressed: $totalMatches/$totalSteps"
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("arrivalCases")
    fun `east big arrivals retain the recorded north approach`(
        case: ReplayCase,
        state: GameTestState,
    ) {
        for (phase in 0..1) state.runInjectedGameTest(
            Dependencies::class,
            null,
            NylocasEncounter::class,
        ) { deps ->
            val room = createRegion(NylocasRoom.template)
            player.telejump(room.normal[NylocasRoom.bridge])
            val spawn = NylocasSpawn(case.size, NylocasStyle.Melee, case.pillar)
            val npc =
                spawnNpc(
                    room.normal[case.slot.entry],
                    deps.npcTypes[nylocas_npcs.incoming(case.size, NylocasStyle.Melee)],
                ) {
                    mode = NpcMode.None
                    setHunt(0)
                    deps.behaviour.add(this, room, case.slot, spawn, 2)
                }
            advance(ticks = 2)
            npc.telejump(room.normal[case.steps.first().before])
            val pillar = NpcAvatar(2).apply { coords = room.normal[case.pillar.source] }
            var previousFlags = emptySet<CoordGrid>()
            var contacted = false
            for (step in case.steps) {
                for (tile in previousFlags) deps.collision.remove(tile, CollisionFlag.BLOCK_NPCS)
                previousFlags = step.blockers[phase].map { room.normal[it] }.toSet()
                for (tile in previousFlags) deps.collision.add(tile, CollisionFlag.BLOCK_NPCS)
                advance()
                if (deps.bounds.touches(npc.avatar, pillar)) {
                    assertTrue(deps.regions.normalizeCoords(npc.coords).z == 4245) {
                        "${case.name} phase$phase reached another face"
                    }
                    contacted = true
                }
            }
            assertTrue(contacted) { "${case.name} phase$phase never reached north contact" }
        }
    }

    data class ReplayCase(
        val name: String,
        val size: NylocasSize,
        val pillar: NylocasPillar,
        val slot: NylocasSlot,
        val northArrival: Boolean,
        val steps: MutableList<ReplayStep> = mutableListOf(),
    ) {
        override fun toString(): String = name
    }

    data class ReplayStep(
        val tick: Int,
        val before: CoordGrid,
        val after: CoordGrid,
        val blockers: List<List<CoordGrid>>,
    )

    companion object {
        @JvmStatic fun arrivalCases(): List<ReplayCase> = cases().filter { it.northArrival }

        @JvmStatic
        fun cases(): List<ReplayCase> {
            val stream =
                checkNotNull(
                    NylocasReplayTest::class.java.getResourceAsStream("/nylocas-pathing.tsv")
                )
            val cases = mutableListOf<ReplayCase>()
            stream.bufferedReader().useLines { lines ->
                for (line in lines.filter { !it.startsWith("#") }) {
                    val fields = line.split('\t')
                    if (fields[0] == "C") {
                        val pillar =
                            when (fields[5]) {
                                "SW" -> NylocasPillar.SouthWest
                                "SE" -> NylocasPillar.SouthEast
                                "NW" -> NylocasPillar.NorthWest
                                "NE" -> NylocasPillar.NorthEast
                                else -> error("Unknown pillar: ${fields[5]}")
                            }
                        cases +=
                            ReplayCase(
                                fields[1],
                                if (fields[4] == "2") NylocasSize.Big else NylocasSize.Small,
                                pillar,
                                NylocasSlot.valueOf(fields[6]),
                                fields[7] == "N",
                            )
                    } else {
                        check(fields[0] == "T")
                        val blockers =
                            fields.drop(6).map { encoded ->
                                if (encoded == "-") emptyList()
                                else
                                    encoded.split(';').flatMap { value ->
                                        val (x, z, size) = value.split(',').map(String::toInt)
                                        (0 until size).flatMap { dx ->
                                            (0 until size).map { dz ->
                                                CoordGrid(x + dx, z + dz, 0)
                                            }
                                        }
                                    }
                            }
                        cases.last().steps +=
                            ReplayStep(
                                fields[1].toInt(),
                                CoordGrid(fields[2].toInt(), fields[3].toInt(), 0),
                                CoordGrid(fields[4].toInt(), fields[5].toInt(), 0),
                                blockers,
                            )
                    }
                }
            }
            return cases
        }
    }
}
