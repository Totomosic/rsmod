package org.rsmod.content.other.commands

import jakarta.inject.Inject
import org.rsmod.api.config.constants
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.repo.region.RegionRepository
import org.rsmod.api.repo.region.RegionTemplate
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
            "Enter an empty Nylocas development instance",
            { teleport(this, arena) },
        )
        onCommand(
            "nylocasbridge",
            "Teleport onto the bridge outside Nylocas",
            { teleport(this, bridge) },
        )
    }

    private fun teleport(cheat: Cheat, destination: CoordGrid) {
        val player = cheat.player
        val launched =
            protectedAccess.launch(player) {
                val region = regionRepo.add(template)
                if (region == null) {
                    mes("Could not create the Nylocas room. Try again in a few seconds.")
                    return@launch
                }
                telejump(region.normal[destination])
                mes(
                    "Entered the Nylocas development instance. The encounter is not implemented yet."
                )
            }
        if (!launched) {
            player.mes(constants.dm_busy)
        }
    }

    private companion object {
        private val arena = CoordGrid(3295, 4248, 0)
        private val bridge = CoordGrid(3295, 4264, 0)

        // Keep region 13122 as the instance source: ToB QoL checks both the instance flag and
        // template region. A static teleport or unrelated blank map does not satisfy its checks.
        // The revision-233 cache already contains the arena, scenery, and collision.
        private val template =
            RegionTemplate.create {
                copyAllLevels(408, 528) {
                    zoneWidth = 8
                    zoneLength = 8
                    regionZoneX = 4
                    regionZoneZ = 4
                }
            }
    }
}
