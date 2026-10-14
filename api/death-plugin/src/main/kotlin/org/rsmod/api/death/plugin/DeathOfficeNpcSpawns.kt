package org.rsmod.api.death.plugin

import org.rsmod.api.type.builders.map.npc.MapNpcSpawnBuilder

internal object DeathOfficeNpcSpawns : MapNpcSpawnBuilder() {
    override fun onPackMapTask() {
        resourceFile<DeathOfficeScript>("npcs.toml")
    }
}
