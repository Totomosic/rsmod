package org.rsmod.content.other.commands

import jakarta.inject.Inject
import net.rsprot.protocol.game.incoming.misc.user.ClientCheat
import org.junit.jupiter.api.Test
import org.rsmod.api.config.refs.modlevels
import org.rsmod.api.net.rsprot.handlers.ClientCheatHandler
import org.rsmod.api.registry.region.RegionRegistry
import org.rsmod.api.testing.GameTestState
import org.rsmod.game.map.collision.get
import org.rsmod.game.region.Region
import org.rsmod.game.type.mod.ModLevelTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.map.zone.ZoneKey
import org.rsmod.routefinder.collision.CollisionFlagMap
import org.rsmod.routefinder.flag.CollisionFlag

class NylocasDevelopmentCommandTest {
    class Dependencies
    @Inject
    constructor(
        val handler: ClientCheatHandler,
        val regions: RegionRegistry,
        val modLevels: ModLevelTypeList,
        val collision: CollisionFlagMap,
    )

    @Test
    fun GameTestState.`admin enters an instance preserving plugin source coordinates`() =
        runInjectedGameTest(Dependencies::class, null, NylocasDevelopmentCommand::class) { deps ->
            player.modLevel = deps.modLevels[modlevels.admin]
            deps.handler.handle(player, ClientCheat("nylocas"))
            advance()

            val region = checkNotNull(deps.regions[player.coords])
            assertEquals(CoordGrid(3295, 4248, 0), deps.regions.normalizeCoords(player.coords))
            assertEquals(0, deps.collision[player.coords] and CollisionFlag.BLOCK_WALK)

            // Nyloer's three incoming lanes and ToB QoL's entry line must resolve to source
            // tiles, including tiles away from the player's current chunk.
            val sourceTiles =
                listOf(
                    CoordGrid(3281, 4249, 0),
                    CoordGrid(3295, 4233, 0),
                    CoordGrid(3310, 4248, 0),
                    CoordGrid(3304, 4274, 0),
                )
            for (source in sourceTiles) {
                assertEquals(source, deps.regions.normalizeCoords(region.normal[source]))
            }
            for (level in 0..3) {
                val source = CoordGrid(3295, 4248, level)
                val copy = deps.regions[ZoneKey.from(region.normal[source])]
                assertEquals(ZoneKey.from(source), copy.normalZone())
                assertEquals(0, copy.rotation)
            }
        }

    @Test
    fun GameTestState.`ordinary players cannot allocate or enter the development room`() =
        runInjectedGameTest(Dependencies::class, null, NylocasDevelopmentCommand::class) { deps ->
            player.modLevel = deps.modLevels[modlevels.player]
            val original = player.coords
            deps.handler.handle(player, ClientCheat("nylocas"))
            advance()

            assertEquals(original, player.coords)
            assertNull(deps.regions[player.coords])
        }

    @Test
    fun GameTestState.`empty instances are reclaimed after the player leaves`() =
        runInjectedGameTest(Dependencies::class, null, NylocasDevelopmentCommand::class) { deps ->
            player.modLevel = deps.modLevels[modlevels.admin]
            deps.handler.handle(player, ClientCheat("nylocas"))
            advance()
            val first = checkNotNull(deps.regions[player.coords])

            deps.handler.handle(player, ClientCheat("nylocas"))
            advance()
            val second = checkNotNull(deps.regions[player.coords])
            assertNotEquals(first.uid, second.uid)
            removeInactiveRegions()
            assertEquals(Region.INVALID_SLOT, first.slot)
            assertNotEquals(Region.INVALID_SLOT, second.slot)

            player.telejump(CoordGrid(3222, 3218, 0))
            advance()
            removeInactiveRegions()
            assertEquals(Region.INVALID_SLOT, second.slot)
        }
}
