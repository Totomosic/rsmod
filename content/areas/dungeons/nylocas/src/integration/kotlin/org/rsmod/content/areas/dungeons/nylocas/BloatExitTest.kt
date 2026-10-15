package org.rsmod.content.areas.dungeons.nylocas

import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import org.rsmod.api.registry.region.RegionRegistry
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.testing.GameTestState
import org.rsmod.api.testing.scope.GameTestScope
import org.rsmod.game.entity.NpcList
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.loc.LocAngle
import org.rsmod.game.loc.LocShape
import org.rsmod.game.map.collision.get
import org.rsmod.game.region.Region
import org.rsmod.map.CoordGrid
import org.rsmod.routefinder.collision.CollisionFlagMap
import org.rsmod.routefinder.flag.CollisionFlag

class BloatExitTest {
    class Dependencies
    @Inject
    constructor(
        val exit: BloatExit,
        val regions: RegionRegistry,
        val locs: LocRepository,
        val npcs: NpcList,
        val collision: CollisionFlagMap,
        val raid: TheatreRaid,
    )

    @Test
    fun GameTestState.`either exit half enters the start of the bridge in the same instance`() =
        runInjectedGameTest(Dependencies::class, null, BloatExit::class) { deps ->
            val blocked =
                CollisionFlag.BLOCK_WALK or CollisionFlag.LOC or CollisionFlag.GROUND_DECOR
            // Check the loaded cache collision as well as the copied instance terrain.
            assertEquals(0, deps.collision[BloatExitRoom.doorstep] and blocked)
            assertEquals(0, deps.collision[NylocasRoom.bridgeStart] and blocked)
            val room = checkNotNull(deps.exit.createRoom())
            val west = passage(room, deps, 4447)
            val east = passage(room, deps, 4448)
            player.telejump(room.normal[BloatExitRoom.doorstep])
            val other = registerPlayer(coords = room.normal[CoordGrid(3322, 4448, 0)])
            advance()

            player.opLoc1(west)
            other.opLoc2(east)
            advance()

            for (member in listOf(player, other)) {
                assertEquals(CoordGrid(3302, 4275, 0), deps.regions.normalizeCoords(member.coords))
                assertEquals(room.uid, deps.regions[member.coords]?.uid)
                assertEquals(0, deps.collision[member.coords] and blocked)
            }
            advance(ticks = 8)
            assertEquals(0, deps.npcs.count())

            player.telejump(CoordGrid(3222, 3218, 0))
            other.telejump(CoordGrid(3223, 3218, 0))
            advance()
            removeInactiveRegions()
            assertEquals(Region.INVALID_SLOT, room.slot)
        }

    @Test
    fun GameTestState.`exit traversal leaves the barrier encounter ready to start`() =
        runInjectedGameTest(
            Dependencies::class,
            null,
            BloatExit::class,
            NylocasEncounter::class,
            TheatreRaid::class,
        ) { deps ->
            val source = placeMapLoc(CoordGrid(3295, 4255, 0), nylocas_locs.barrier)
            val room = checkNotNull(deps.exit.createRoom())
            player.telejump(room.normal[BloatExitRoom.doorstep])
            advance()
            deps.raid.start(player)
            player.opLoc1(passage(room, deps, 4447))
            advance(ticks = 8)
            assertEquals(0, deps.npcs.count())
            assertEquals(CoordGrid(3302, 4275, 0), deps.regions.normalizeCoords(player.coords))

            player.opLoc1(source.copy(coords = room.normal[source.coords]))
            advanceUntil(
                predicate = {
                    deps.regions.normalizeCoords(player.coords) == CoordGrid(3295, 4254, 0)
                },
                timeoutTicks = 64,
            )
            assertEquals(CoordGrid(3295, 4254, 0), deps.regions.normalizeCoords(player.coords))
            advance(ticks = 3)
            assertEquals(0, deps.npcs.count())
            advance()
            assertEquals(3, deps.npcs.count())
        }

    @Test
    fun GameTestState.`shared passages elsewhere are ignored`() =
        runInjectedGameTest(Dependencies::class, null, BloatExit::class) { deps ->
            val source =
                placeMapLoc(
                    CoordGrid(3303, 4275, 0),
                    nylocas_locs.passage,
                    shape = LocShape.WallStraight,
                    angle = LocAngle.East,
                )
            val room = createRegion(NylocasRoom.template)
            val start = room.normal[source.coords]
            player.telejump(start)
            advance()
            player.opLoc1(source.copy(coords = room.normal[source.coords]))
            advance(ticks = 3)
            assertEquals(start, player.coords)
            assertEquals(room.uid, deps.regions[player.coords]?.uid)
        }

    private fun GameTestScope.passage(room: Region, deps: Dependencies, z: Int): BoundLocInfo {
        val coords = room.normal[CoordGrid(3322, z, 0)]
        val loc = checkNotNull(deps.locs.findExact(coords, nylocas_locs.passage))
        assertEquals(LocAngle.East, loc.angle)
        assertEquals(LocShape.WallStraight, loc.shape)
        return BoundLocInfo(loc, locTypes[nylocas_locs.passage])
    }
}
