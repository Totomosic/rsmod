package org.rsmod.content.areas.dungeons.nylocas

import jakarta.inject.Inject
import org.rsmod.api.config.refs.queues
import org.rsmod.api.death.NpcDeath
import org.rsmod.api.npc.access.StandardNpcAccess
import org.rsmod.api.repo.world.WorldRepository
import org.rsmod.api.script.onNpcQueue
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

class NylocasDeaths
@Inject
constructor(
    private val death: NpcDeath,
    private val world: WorldRepository,
    private val behaviour: NylocasBehaviour,
) : PluginScript() {
    override fun ScriptContext.startup() {
        for (size in NylocasSize.entries) {
            for (style in NylocasStyle.entries) {
                // Override the generic death handler, whose missing-table fallback drops bones.
                onNpcQueue(nylocas_npcs.incoming(size, style), queues.death) { die(size) }
                onNpcQueue(nylocas_npcs.fighting(size, style), queues.death) { die(size) }
            }
        }
    }

    private suspend fun StandardNpcAccess.die(size: NylocasSize) {
        // Style swaps are visual transmogs: the original NPC type can have a
        // different style. Select both parts of the death from the visible form.
        val style =
            NylocasStyle.entries.first {
                npc.visType.id == nylocas_npcs.incoming(size, it).id ||
                    npc.visType.id == nylocas_npcs.fighting(size, it).id
            }
        death.deathNoDrops(
            this,
            animation = nylocas_seqs.death(style),
            animationTicks = if (size == NylocasSize.Small) 1 else 5,
            delayIfMoving = size == NylocasSize.Big,
        )
        // The small's explosion is detached scenery after its one-tick NPC stage.
        // Bigs retain their body for the full five-tick standard death sequence.
        if (size == NylocasSize.Small) world.spotanimMap(nylocas_spots.death(style), coords)
        behaviour.completeDeath(npc)
    }
}
