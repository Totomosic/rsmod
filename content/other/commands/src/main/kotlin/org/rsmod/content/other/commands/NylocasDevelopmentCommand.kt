package org.rsmod.content.other.commands

import jakarta.inject.Inject
import org.rsmod.api.config.constants
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.repo.region.RegionRepository
import org.rsmod.content.areas.dungeons.nylocas.BloatExit
import org.rsmod.content.areas.dungeons.nylocas.BloatExitRoom
import org.rsmod.content.areas.dungeons.nylocas.NyloMaxHit
import org.rsmod.content.areas.dungeons.nylocas.NylocasRoom
import org.rsmod.content.areas.dungeons.nylocas.TheatreRaid
import org.rsmod.content.areas.dungeons.nylocas.TheatreRaidOptions
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
    private val raid: TheatreRaid,
) : PluginScript() {
    override fun ScriptContext.startup() {
        onCommand(
            "theatrecreate",
            "Create a Theatre lobby",
            {
                val launched =
                    protectedAccess.launch(player) {
                        val room = bloatExit.createRoom()
                        if (room == null) {
                            mes("Could not create a lobby. Try again in a few seconds.")
                            return@launch
                        }
                        telejump(room.normal[BloatExitRoom.doorstep])
                        raid.createLobby(player, room)
                    }
                if (!launched) player.mes(constants.dm_busy)
            },
        ) {
            modLevel = null
        }
        onCommand(
            "theatrejoin",
            "Join a Theatre lobby by host name",
            {
                val hostName = args.joinToString(" ")
                val launched =
                    protectedAccess.launch(player) {
                        val destination = raid.joinLobby(player, hostName) ?: return@launch
                        telejump(destination)
                    }
                if (!launched) player.mes(constants.dm_busy)
            },
        ) {
            modLevel = null
        }
        onCommand(
            "theatrestart",
            "Start your Theatre lobby",
            {
                val options = raidOptions(this) ?: return@onCommand
                val launched = protectedAccess.launch(player) { raid.startLobby(player, options) }
                if (!launched) player.mes(constants.dm_busy)
            },
        ) {
            modLevel = null
        }
        onCommand(
            "starttob",
            "Start the Theatre of Blood raid and show the party HUD",
            {
                val options = raidOptions(this) ?: return@onCommand
                val launched = protectedAccess.launch(player) { raid.start(player, options) }
                if (!launched) {
                    player.mes(constants.dm_busy)
                }
            },
        )
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

    private fun raidOptions(cheat: Cheat): TheatreRaidOptions? {
        val wave = if (cheat.args.isEmpty()) 1 else cheat.args.first().toIntOrNull()
        if (wave == null || wave !in 1..31) {
            cheat.player.mes("Starting wave must be a whole number between 1 and 31.")
            return null
        }
        val flags = cheat.args.drop(1)
        val validFlags = setOf("maxhit-all", "maxhit-slow", "hp250")
        if (flags.any { it !in validFlags } || ("maxhit-all" in flags && "maxhit-slow" in flags)) {
            cheat.player.mes("Use [starting wave] [maxhit-all OR maxhit-slow] [hp250].")
            return null
        }
        val maxHit =
            when {
                "maxhit-all" in flags -> NyloMaxHit.AllWeapons
                "maxhit-slow" in flags -> NyloMaxHit.SlowWeapons
                else -> NyloMaxHit.Off
            }
        return TheatreRaidOptions(wave, maxHit, "hp250" in flags)
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
                mes(
                    "Entered the development instance. Click Start Raid before passing the Nylocas barrier."
                )
            }
        if (!launched) {
            player.mes(constants.dm_busy)
        }
    }
}
