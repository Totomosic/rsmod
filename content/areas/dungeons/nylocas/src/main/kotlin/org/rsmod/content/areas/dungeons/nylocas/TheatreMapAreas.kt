package org.rsmod.content.areas.dungeons.nylocas

import org.rsmod.api.config.refs.areas
import org.rsmod.api.type.builders.map.area.MapAreaBuilder
import org.rsmod.map.square.MapSquareKey

object TheatreMapAreas : MapAreaBuilder() {
    override fun onPackMapTask() {
        // Theatre source regions, including connecting rooms, the Sotetseg maze,
        // and the reward area. Source-map areas also apply to all instanced copies.
        for (id in listOf(12611, 12612, 12613, 12867, 12869, 13122, 13123, 13125, 13379)) {
            area(areas.multiway) { mapSquare(MapSquareKey(id)) }
        }
    }
}
