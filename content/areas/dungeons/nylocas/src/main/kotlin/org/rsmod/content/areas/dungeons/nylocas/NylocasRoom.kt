package org.rsmod.content.areas.dungeons.nylocas

import org.rsmod.api.repo.region.RegionTemplate
import org.rsmod.map.CoordGrid

object NylocasRoom {
    val arena = CoordGrid(3295, 4248, 0)
    val bridge = CoordGrid(3295, 4264, 0)

    // Keep region 13122 as the instance source for RuneLite's ToB room and lane detection.
    val template =
        RegionTemplate.create {
            copyAllLevels(408, 528) {
                zoneWidth = 8
                zoneLength = 8
                regionZoneX = 4
                regionZoneZ = 4
            }
        }
}
