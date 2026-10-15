package org.rsmod.content.other.commands

import jakarta.inject.Inject
import net.rsprot.protocol.game.incoming.misc.user.ClientCheat
import org.junit.jupiter.api.Test
import org.rsmod.api.config.refs.modlevels
import org.rsmod.api.net.rsprot.handlers.ClientCheatHandler
import org.rsmod.api.registry.region.RegionRegistry
import org.rsmod.api.testing.GameTestState
import org.rsmod.content.areas.dungeons.nylocas.TheatreRaid
import org.rsmod.content.areas.dungeons.nylocas.tob_interfaces
import org.rsmod.content.areas.dungeons.nylocas.tob_varbits
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
    fun GameTestState.`start raid command initializes the HUD in the current room`() =
        runInjectedGameTest(
            Dependencies::class,
            null,
            NylocasDevelopmentCommand::class,
            TheatreRaid::class,
        ) { deps ->
            player.modLevel = deps.modLevels[modlevels.admin]
            deps.handler.handle(player, ClientCheat("bloatexit"))
            advance(ticks = 2)
            val original = player.coords
            deps.handler.handle(player, ClientCheat("starttob"))

            assertEquals(original, player.coords)
            assertEquals(2, player.vars[tob_varbits.party_status])
            assertEquals(1, player.vars[tob_varbits.party_slot])
            assertTrue(player.vars[tob_varbits.party_hp[0]] > 0)
            assertTrue(player.ui.containsOverlay(tob_interfaces.hud))
            deps.handler.handle(player, ClientCheat("starttob"))
            assertEquals(original, player.coords)
            assertEquals(2, player.vars[tob_varbits.party_status])
        }

    @Test
    fun GameTestState.`admin enters an instance preserving plugin source coordinates`() =
        runInjectedGameTest(Dependencies::class, null, NylocasDevelopmentCommand::class) { deps ->
            player.modLevel = deps.modLevels[modlevels.admin]
            deps.handler.handle(player, ClientCheat("nylocas"))
            advance()

            val region = checkNotNull(deps.regions[player.coords])
            assertEquals(CoordGrid(3295, 4248, 0), deps.regions.normalizeCoords(player.coords))
            assertEquals(0, deps.collision[player.coords] and CollisionFlag.BLOCK_WALK)

            deps.handler.handle(player, ClientCheat("bloatexit"))
            advance()
            assertNotNull(deps.regions[player.coords])
            assertEquals(CoordGrid(3322, 4447, 0), deps.regions.normalizeCoords(player.coords))
            val blocked =
                CollisionFlag.BLOCK_WALK or CollisionFlag.LOC or CollisionFlag.GROUND_DECOR
            assertEquals(0, deps.collision[player.coords] and blocked)

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

            deps.handler.handle(player, ClientCheat("nylocasbridge"))
            advance()
            assertNotNull(deps.regions[player.coords])
            assertEquals(CoordGrid(3295, 4264, 0), deps.regions.normalizeCoords(player.coords))
            assertEquals(0, deps.collision[player.coords] and CollisionFlag.BLOCK_WALK)
        }

    @Test
    fun GameTestState.`ordinary players cannot allocate or enter the development room`() =
        runInjectedGameTest(Dependencies::class, null, NylocasDevelopmentCommand::class) { deps ->
            player.modLevel = deps.modLevels[modlevels.player]
            val original = player.coords
            for (command in listOf("nylocas", "nylocasbridge", "bloatexit", "starttob")) {
                deps.handler.handle(player, ClientCheat(command))
                advance()

                assertEquals(original, player.coords)
                assertNull(deps.regions[player.coords])
            }
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
