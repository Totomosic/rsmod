package org.rsmod.content.areas.dungeons.nylocas

import jakarta.inject.Inject
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.script.onEvent
import org.rsmod.events.UnboundEvent
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.npc.NpcMode
import org.rsmod.game.movement.MoveRestrict
import org.rsmod.game.region.Region
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

data class NylocasRoomStart(val region: Region) : UnboundEvent

class NylocasPillars
@Inject
constructor(private val npcTypes: NpcTypeList, private val npcs: NpcRepository) : PluginScript() {
    private val pillars = mutableMapOf<Int, Pair<Region, List<Npc>>>()

    override fun ScriptContext.startup() {
        onEvent<NylocasRoomStart> { spawn(region) }
        onEvent<GameLifecycle.StartCycle> {
            pillars.entries.removeAll { (_, room) ->
                val (region, supports) = room
                if (region.slot != Region.INVALID_SLOT) return@removeAll false
                supports.filter { it.isSlotAssigned }.forEach { npcs.del(it, Int.MAX_VALUE) }
                true
            }
        }
    }

    private fun spawn(region: Region) {
        if (region.uid in pillars) return
        // ToB QoL uses these spawn events as its authoritative room timer start.
        // They must arrive during the countdown, before its first GameTick fallback.
        val supports =
            NylocasPillar.entries.map { pillar ->
                Npc(npcTypes[nylocas_npcs.support], region.normal[pillar.source]).apply {
                    // The native NPC is size three, but pillar scenery/contact is 2x2.
                    // Its extra north/east reservations otherwise block the inward faces.
                    collisionSize = 2
                    mode = NpcMode.None
                    moveRestrict = MoveRestrict.NoMove
                    setHunt(0)
                    aiTimer = 0
                    npcs.add(this, Int.MAX_VALUE)
                    respawns = false
                }
            }
        pillars[region.uid] = region to supports
    }
}
