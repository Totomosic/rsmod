package org.rsmod.routefinder

import kotlin.math.sign
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.rsmod.routefinder.collision.CollisionFlagMap
import org.rsmod.routefinder.collision.CollisionStrategy
import org.rsmod.routefinder.collision.buildCollisionMap
import org.rsmod.routefinder.flag.CollisionFlag

class DiagonalFirstRouteFindingTest {
    @ParameterizedTest
    @CsvSource("1, false", "2, false", "3, false", "1, true", "2, true", "3, true")
    fun `detours retain shortest length and validate every forward step`(
        size: Int,
        blockers: Boolean,
    ) {
        val map = openMap()
        val start = RouteCoordinates(3200, 3200)
        val dest = RouteCoordinates(3210, 3204)
        for (z in 3197..3207) {
            map[3204, z, 0] = CollisionFlag.LOC or CollisionFlag.LOC_ROUTE_BLOCKER
        }
        // This blocks entry from the west but permits departure towards the east.
        // A reverse search checking collision in its own direction would misinterpret it.
        map[3207, 3204, 0] = CollisionFlag.WALL_WEST or CollisionFlag.WALL_WEST_ROUTE_BLOCKER
        val finder = RouteFinding(map, useRouteBlockerFlags = blockers)
        val player = finder.findRoute(0, start.x, start.z, dest.x, dest.z, srcSize = size)
        val npc =
            finder.findRoute(
                0,
                start.x,
                start.z,
                dest.x,
                dest.z,
                srcSize = size,
                diagonalFirst = true,
            )
        assertTrue(player.success)
        assertTrue(npc.success)
        assertFalse(npc.alternative)
        assertEquals(dest, npc.last())
        assertEquals(steps(start, player).size, steps(start, npc).size)
        validate(map, start, npc, size)
    }

    @Test
    fun `occupied source can be left and custom npc collision is respected`() {
        val map = openMap()
        val start = RouteCoordinates(3200, 3200)
        val dest = RouteCoordinates(3205, 3202)
        map[start.x, start.z, 0] = CollisionFlag.BLOCK_NPCS
        map[3202, 3202, 0] = CollisionFlag.BLOCK_NPCS
        val collision =
            object : CollisionStrategy {
                override fun canMove(tileFlag: Int, blockFlag: Int): Boolean =
                    tileFlag and (blockFlag or CollisionFlag.BLOCK_NPCS) == 0
            }
        val finder = RouteFinding(map)
        val route =
            finder.findRoute(
                0,
                start.x,
                start.z,
                dest.x,
                dest.z,
                collision = collision,
                diagonalFirst = true,
            )
        assertEquals(dest, route.last())
        validate(map, start, route, 1, collision)
    }

    @Test
    fun `alternative failed empty and truncated routes retain their contracts`() {
        val map = openMap()
        val finder = RouteFinding(map)
        val start = RouteCoordinates(3200, 3200)
        val dest = RouteCoordinates(3205, 3202)
        val expected = listOf(RouteCoordinates(3202, 3202), dest)
        assertEquals(expected, finder.findRoute(0, 3200, 3200, 3205, 3202, diagonalFirst = true))
        // The same finder is reused for both players and NPCs, including across map changes.
        assertEquals(
            listOf(RouteCoordinates(3203, 3200), dest),
            finder.findRoute(0, 3200, 3200, 3205, 3202),
        )
        assertEquals(expected, finder.findRoute(0, 3200, 3200, 3205, 3202, diagonalFirst = true))
        val truncated =
            finder.findRoute(0, 3200, 3200, 3205, 3202, maxWaypoints = 1, diagonalFirst = true)
        assertEquals(expected.take(1), truncated.waypoints)
        val empty = finder.findRoute(0, 3200, 3200, 3200, 3200, diagonalFirst = true)
        assertTrue(empty.success)
        assertTrue(empty.isEmpty())
        map[dest.x, dest.z, 0] = CollisionFlag.LOC
        val failed =
            finder.findRoute(0, 3200, 3200, 3205, 3202, moveNear = false, diagonalFirst = true)
        assertTrue(failed.failed)
        assertTrue(failed.isEmpty())
        val alternative = finder.findRoute(0, 3200, 3200, 3205, 3202, diagonalFirst = true)
        assertTrue(alternative.alternative)
        assertEquals(RouteCoordinates(3204, 3202), alternative.last())
        validate(map, start, alternative, 1)
    }

    @Test
    fun `cardinal and diagonal destinations do not add turns`() {
        val finder = RouteFinding(openMap())
        for ((dx, dz) in listOf(5 to 0, 0 to 5, -5 to 0, 0 to -5, 5 to 5, -5 to -5)) {
            val dest = RouteCoordinates(3200 + dx, 3200 + dz)
            val route = finder.findRoute(0, 3200, 3200, dest.x, dest.z, diagonalFirst = true)
            assertEquals(listOf(dest), route.waypoints)
        }
    }

    private fun validate(
        map: CollisionFlagMap,
        start: RouteCoordinates,
        route: Route,
        size: Int,
        collision: CollisionStrategy = CollisionStrategy.Normal,
    ) {
        val validator = StepValidator(map)
        var previous = start
        for (next in steps(start, route)) {
            assertTrue(
                validator.canTravel(
                    previous.level,
                    previous.x,
                    previous.z,
                    next.x - previous.x,
                    next.z - previous.z,
                    size,
                    collision = collision,
                ),
                "Invalid forward step $previous -> $next for size $size",
            )
            previous = next
        }
    }

    private fun steps(start: RouteCoordinates, route: Route): List<RouteCoordinates> = buildList {
        var current = start
        for (waypoint in route) {
            while (current != waypoint) {
                current =
                    current.translate((waypoint.x - current.x).sign, (waypoint.z - current.z).sign)
                add(current)
            }
        }
    }

    private fun openMap(): CollisionFlagMap = buildCollisionMap(3168, 3168, 3232, 3232)
}
