package org.rsmod.content.areas.city.sinhaza

import jakarta.inject.Inject
import org.rsmod.api.config.constants
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.script.onCommand
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.content.areas.dungeons.nylocas.BloatExit
import org.rsmod.content.areas.dungeons.nylocas.BloatExitRoom
import org.rsmod.content.areas.dungeons.nylocas.TheatreRaid
import org.rsmod.content.interfaces.character.creator.openCharacterCreator
import org.rsmod.content.other.commands.GearPresetService
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.npc.NpcMode
import org.rsmod.game.loc.LocAngle
import org.rsmod.game.loc.LocShape
import org.rsmod.game.movement.MoveRestrict
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

class VerSinhazaLobby
@Inject
constructor(
    private val protectedAccess: ProtectedAccessLauncher,
    private val npcTypes: NpcTypeList,
    private val npcs: NpcRepository,
    private val locs: LocRepository,
    private val bloatExit: BloatExit,
    private val raid: TheatreRaid,
    private val gear: GearPresetService,
    private val events: EventBus,
) : PluginScript() {
    override fun ScriptContext.startup() {
        onEvent<GameLifecycle.Startup> {
            val guide = Npc(npcTypes[sinhaza_npcs.guide], VerSinhaza.guide)
            guide.mode = NpcMode.None
            guide.moveRestrict = MoveRestrict.NoMove
            npcs.add(guide, Int.MAX_VALUE)
            locs.add(
                VerSinhaza.bank,
                sinhaza_locs.bank,
                Int.MAX_VALUE,
                // Keep the native orientation for this chest's restricted approach side.
                LocAngle.West,
                LocShape.CentrepieceStraight,
            )
        }
        onCommand("sinhaza") {
            desc = "Visit the Ver Sinhaza raid lobby"
            modLevel = null
            cheat {
                if (!protectedAccess.launch(player) { telejump(VerSinhaza.arrival) }) {
                    player.mes(constants.dm_busy)
                }
            }
        }
        onOpNpc1(sinhaza_npcs.guide) {
            if (it.npc.spawnCoords != VerSinhaza.guide) return@onOpNpc1
            when (
                choice5(
                    "Start a solo raid",
                    1,
                    "Create a party lobby",
                    2,
                    "Join a party lobby",
                    3,
                    "Change my appearance",
                    4,
                    "Maybe later",
                    5,
                    title = "Ver Sinhaza raid lobby",
                )
            ) {
                1 -> createRaid(start = true)
                2 -> createRaid(start = false)
                3 -> {
                    val name = stringDialog("Enter the lobby host's player name:")
                    val destination = raid.joinLobby(player, name) ?: return@onOpNpc1
                    telejump(destination)
                    gear.applyDefault(player)
                }
                4 -> player.openCharacterCreator(events)
            }
        }
    }

    private fun ProtectedAccess.createRaid(start: Boolean) {
        val room = bloatExit.createRoom()
        if (room == null) {
            mes("Could not create a lobby. Try again in a few seconds.")
            return
        }
        telejump(room.normal[BloatExitRoom.doorstep])
        raid.createLobby(player, room)
        gear.applyDefault(player)
        if (start) raid.startLobby(player)
    }
}

object VerSinhaza {
    val arrival = CoordGrid(3660, 3222, 0)
    val guide = CoordGrid(3662, 3222, 0)
    val bank = CoordGrid(3658, 3222, 0)
}

internal object sinhaza_npcs : NpcReferences() {
    val guide = find("tob_stranger")
}

object sinhaza_locs : LocReferences() {
    val bank = find("castlewars_bankchest")
}
