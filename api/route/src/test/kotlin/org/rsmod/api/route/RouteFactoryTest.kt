package org.rsmod.api.route

import kotlin.math.abs
import kotlin.math.sign
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.rsmod.game.entity.NpcAvatar
import org.rsmod.game.entity.PlayerAvatar
import org.rsmod.game.movement.RouteRequestCoord
import org.rsmod.game.movement.RouteRequestLoc
import org.rsmod.game.movement.RouteRequestPathingEntity
import org.rsmod.map.CoordGrid
import org.rsmod.routefinder.Route
import org.rsmod.routefinder.collision.CollisionFlagMap

class RouteFactoryTest {
    @ParameterizedTest
    @ValueSource(ints = [1, 2, 3])
    fun `npc coordinate routes move diagonally first in every octant`(size: Int) {
        val routes = RouteFactory(openMap())
        val start = CoordGrid(3200, 3200)
        for ((dx, dz) in offsets) {
            val npc = NpcAvatar(size).apply { coords = start }
            val dest = start.translate(dx, dz)
            val diagonalSteps = minOf(abs(dx), abs(dz))
            val turn = start.translate(dx.sign * diagonalSteps, dz.sign * diagonalSteps)
            val expected = listOf(turn, dest)
            assertEquals(expected, routes.create(npc, dest).coords(), "offset $dx,$dz size $size")
            assertEquals(expected, routes.create(npc, RouteRequestCoord(dest)).coords())

            val player = PlayerAvatar().apply { coords = start }
            val straightSteps = abs(abs(dx) - abs(dz))
            val playerTurn =
                if (abs(dx) > abs(dz)) start.translate(dx.sign * straightSteps, 0)
                else start.translate(0, dz.sign * straightSteps)
            assertEquals(listOf(playerTurn, dest), routes.create(player, dest).coords())
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 2, 3])
    fun `npc entity and loc requests also move diagonally first`(size: Int) {
        val routes = RouteFactory(openMap())
        val start = CoordGrid(3200, 3200)
        val npc = NpcAvatar(size).apply { coords = start }
        val target = NpcAvatar(2).apply { coords = start.translate(7, 3) }
        val entityRoute = routes.create(npc, RouteRequestPathingEntity(target))
        val locRoute =
            routes.create(
                npc,
                RouteRequestLoc(target.coords, 2, 2, shape = 10, angle = 0, forceApproachFlags = 0),
            )
        for (route in listOf(entityRoute, locRoute)) {
            assertTrue(route.success)
            val dest = route.coords().last()
            val diagonalSteps = minOf(dest.x - start.x, dest.z - start.z)
            assertTrue(diagonalSteps > 0)
            assertEquals(start.translate(diagonalSteps, diagonalSteps), route.coords().first())
        }
    }

    private fun Route.coords(): List<CoordGrid> = map { CoordGrid(it.x, it.z, it.level) }

    private fun openMap(): CollisionFlagMap =
        CollisionFlagMap().apply {
            for (x in 3120..3280 step 8) {
                for (z in 3120..3280 step 8) allocateIfAbsent(x, z, 0)
            }
        }

    private companion object {
        private val offsets =
            listOf(5 to 2, 2 to 5, -5 to 2, -2 to 5, 5 to -2, 2 to -5, -5 to -2, -2 to -5)
    }
}
