package org.rsmod.content.areas.dungeons.nylocas

import jakarta.inject.Inject
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.registry.region.RegionRegistry
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.region.RegionRepository
import org.rsmod.api.repo.region.RegionTemplate
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLoc2
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.loc.LocAngle
import org.rsmod.game.loc.LocShape
import org.rsmod.game.region.Region
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

class BloatExit
@Inject
constructor(
    private val regions: RegionRegistry,
    private val regionRepo: RegionRepository,
    private val locRepo: LocRepository,
    private val raid: TheatreRaid,
) : PluginScript() {
    override fun ScriptContext.startup() {
        onOpLoc1(nylocas_locs.passage) { enter(it.loc) }
        onOpLoc2(nylocas_locs.passage) { enter(it.loc) }
    }

    fun createRoom(): Region? {
        val region = regionRepo.add(BloatExitRoom.template) ?: return null
        // The cache has the exit scenery, but its passage clickboxes are normally added at runtime.
        for (z in 4447..4448) {
            locRepo.add(
                coords = region.normal[CoordGrid(3322, z, 0)],
                type = nylocas_locs.passage,
                duration = Int.MAX_VALUE,
                angle = LocAngle.East,
                shape = LocShape.WallStraight,
            )
        }
        return region
    }

    private fun ProtectedAccess.enter(passage: BoundLocInfo) {
        val region = regions[passage.coords] ?: return
        val source = regions.normalizeCoords(passage.coords)
        // This passage type is shared by other Theatre rooms and by the Nylocas entrance.
        if (source.level != 0 || source.x != 3322 || source.z !in 4447..4448) {
            return
        }
        if (!raid.isStarted(player, region)) {
            mes("The host must click Start raid before you can pass through this door.")
            return
        }
        telejump(region.normal[NylocasRoom.bridgeStart])
    }
}

object BloatExitRoom {
    val doorstep = CoordGrid(3322, 4447, 0)

    // Both source maps belong to one instance. Leave enough space between them that the
    // client does not load Nylocas's region while standing at the Bloat exit.
    val template =
        RegionTemplate.createLarge {
            copyAllLevels(408, 528) {
                zoneWidth = 8
                zoneLength = 8
                regionZoneX = 4
                regionZoneZ = 4
            }
            copyAllLevels(408, 552) {
                zoneWidth = 8
                zoneLength = 8
                regionZoneX = 20
                regionZoneZ = 4
            }
        }
}
