package org.rsmod.content.areas.dungeons.nylocas

import jakarta.inject.Inject
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.openrs2.cache.Cache
import org.rsmod.api.area.checker.AreaChecker
import org.rsmod.api.cache.Js5Archives
import org.rsmod.api.cache.map.GameMapDecoder
import org.rsmod.api.cache.map.area.MapAreaDecoder
import org.rsmod.api.cache.util.toInlineBuf
import org.rsmod.api.config.refs.areas
import org.rsmod.api.config.refs.varbits
import org.rsmod.api.repo.region.RegionTemplate
import org.rsmod.api.testing.GameTestState
import org.rsmod.content.areas.misc.multiway.MultiwayAreaScript
import org.rsmod.game.area.AreaIndex
import org.rsmod.map.CoordGrid
import org.rsmod.map.square.MapSquareKey

class TheatreMultiwayTest {
    class Dependencies @Inject constructor(val areas: AreaChecker, val index: AreaIndex) {
        fun loadPackedAreas() {
            // Each game-test scope starts with an empty area index. Load the actual
            // packed definitions so these checks also catch a missing cache repack.
            Cache.open(Path.of(".data/cache/game")).use { cache ->
                for ((mx, mz) in squares) {
                    val buffer = cache.read(Js5Archives.MAPS, "a${mx}_${mz}", 0)
                    try {
                        val definition = MapAreaDecoder.decode(buffer.toInlineBuf())
                        GameMapDecoder.putAreas(index, MapSquareKey(mx, mz), definition)
                    } finally {
                        buffer.release()
                    }
                }
            }
        }
    }

    companion object {
        private val squares =
            listOf(
                49 to 67,
                49 to 68,
                49 to 69,
                50 to 67,
                50 to 69,
                51 to 66,
                51 to 67,
                51 to 69,
                52 to 67,
            )
    }

    @Test
    fun GameTestState.`packed Theatre regions are multi combat on every level and in instances`() =
        runInjectedGameTest(Dependencies::class) { deps ->
            deps.loadPackedAreas()
            val template =
                RegionTemplate.createLarge {
                    for ((index, square) in squares.withIndex()) {
                        copyAllLevels(square.first * 8, square.second * 8) {
                            zoneWidth = 8
                            zoneLength = 8
                            regionZoneX = 4 + index % 3 * 8
                            regionZoneZ = 4 + index / 3 * 8
                        }
                    }
                }
            val region = createRegion(template)
            for ((mx, mz) in squares) {
                for (level in 0..3) {
                    for ((x, z) in listOf(0 to 0, 0 to 63, 63 to 0, 63 to 63, 32 to 32)) {
                        val source = CoordGrid(mx * 64 + x, mz * 64 + z, level)
                        assertTrue(deps.areas.inArea(areas.multiway, source)) { "Source: $source" }
                        val instanced = region.normal[source]
                        assertTrue(deps.areas.inArea(areas.multiway, instanced)) {
                            "Instance: $source"
                        }
                    }
                }
            }
            assertFalse(deps.areas.inArea(areas.multiway, CoordGrid(3328, 4248, 0)))
        }

    @Test
    fun GameTestState
        .`the native multi combat indicator follows entry and exit without starting a raid`() =
        runInjectedGameTest(Dependencies::class, null, MultiwayAreaScript::class) { deps ->
            deps.loadPackedAreas()
            val room = createRegion(BloatExitRoom.template)
            for (source in
                listOf(BloatExitRoom.doorstep, NylocasRoom.bridgeStart, NylocasRoom.arena)) {
                player.telejump(room.normal[source])
                advance(ticks = 2)
                assertTrue(deps.areas.inArea(areas.multiway, player.coords))
                assertEquals(1, player.vars[varbits.multiway_indicator])
            }
            player.telejump(CoordGrid(3328, 4248, 0))
            advance(ticks = 2)
            assertEquals(0, player.vars[varbits.multiway_indicator])
        }
}
