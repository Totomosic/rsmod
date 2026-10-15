package org.rsmod.content.other.commands

import jakarta.inject.Inject
import org.rsmod.api.config.constants
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.repo.region.RegionRepository
import org.rsmod.content.areas.dungeons.nylocas.NylocasRoom
import org.rsmod.game.cheat.Cheat
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

class NylocasDevelopmentCommand
@Inject
constructor(
    private val protectedAccess: ProtectedAccessLauncher,
    private val regionRepo: RegionRepository,
) : PluginScript() {
    override fun ScriptContext.startup() {
        onCommand(
            "nylocas",
            "Enter a Nylocas development instance",
            { teleport(this, NylocasRoom.arena) },
        )
        onCommand(
            "nylocasbridge",
            "Teleport onto the bridge outside Nylocas",
            { teleport(this, NylocasRoom.bridge) },
        )
    }

    private fun teleport(cheat: Cheat, destination: CoordGrid) {
        val player = cheat.player
        val launched =
            protectedAccess.launch(player) {
                val region = regionRepo.add(NylocasRoom.template)
                if (region == null) {
                    mes("Could not create the Nylocas room. Try again in a few seconds.")
                    return@launch
                }
                telejump(region.normal[destination])
                mes("Entered the Nylocas development instance. Pass the barrier to start wave one.")
            }
        if (!launched) {
            player.mes(constants.dm_busy)
        }
    }
}
