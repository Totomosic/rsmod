package org.rsmod.content.other.commands

import jakarta.inject.Inject
import org.rsmod.api.config.constants
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.repo.region.RegionRepository
import org.rsmod.content.areas.dungeons.nylocas.BloatExit
import org.rsmod.content.areas.dungeons.nylocas.BloatExitRoom
import org.rsmod.content.areas.dungeons.nylocas.NylocasRoom
import org.rsmod.game.cheat.Cheat
import org.rsmod.game.region.Region
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

class NylocasDevelopmentCommand
@Inject
constructor(
    private val protectedAccess: ProtectedAccessLauncher,
    private val regionRepo: RegionRepository,
    private val bloatExit: BloatExit,
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
        onCommand(
            "bloatexit",
            "Teleport to the Bloat exit before the Nylocas bridge",
            { teleport(this, BloatExitRoom.doorstep, bloatExit::createRoom) },
        )
    }

    private fun teleport(
        cheat: Cheat,
        destination: CoordGrid,
        createRoom: () -> Region? = { regionRepo.add(NylocasRoom.template) },
    ) {
        val player = cheat.player
        val launched =
            protectedAccess.launch(player) {
                val region = createRoom()
                if (region == null) {
                    mes("Could not create the development room. Try again in a few seconds.")
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
