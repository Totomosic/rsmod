package org.rsmod.content.other.social.clan

import org.rsmod.api.type.builders.map.npc.MapNpcSpawnBuilder

object ClanHubNpcSpawns : MapNpcSpawnBuilder() {
    override fun onPackMapTask() {
        resourceFile<ClanScript>("npcs.toml")
    }
}
