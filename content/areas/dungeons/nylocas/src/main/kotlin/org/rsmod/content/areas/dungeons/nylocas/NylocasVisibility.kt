package org.rsmod.content.areas.dungeons.nylocas

import jakarta.inject.Inject
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.registry.region.RegionRegistry
import org.rsmod.api.script.onEvent
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.map.square.MapSquareKey
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

class NylocasVisibility
@Inject
constructor(private val players: PlayerList, private val regions: RegionRegistry) : PluginScript() {
    private val viewers = mutableSetOf<Player>()

    override fun ScriptContext.startup() {
        // Movement has finished; update the radius before this tick's NPC info is encoded.
        onEvent<GameLifecycle.LateCycle> {
            for (player in players) {
                val source = regions.normalizeCoords(player.coords)
                val inRoom = source.level == 0 && MapSquareKey.from(source).id == 13122
                if (inRoom) {
                    // The east and west incoming lanes are 29 tiles apart.
                    player.npcViewDistance = 32
                    viewers.add(player)
                } else if (viewers.remove(player)) {
                    player.npcViewDistance = 15
                }
            }
            viewers.retainAll(players.toSet())
        }
    }
}
