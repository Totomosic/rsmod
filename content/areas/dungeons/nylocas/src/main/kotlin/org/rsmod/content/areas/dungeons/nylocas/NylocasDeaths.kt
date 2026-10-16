package org.rsmod.content.areas.dungeons.nylocas

import jakarta.inject.Inject
import org.rsmod.api.config.refs.queues
import org.rsmod.api.death.NpcDeath
import org.rsmod.api.script.onNpcQueue
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

class NylocasDeaths @Inject constructor(private val death: NpcDeath) : PluginScript() {
    override fun ScriptContext.startup() {
        for (size in NylocasSize.entries) {
            for (style in NylocasStyle.entries) {
                // Override the generic death handler, whose missing-table fallback drops bones.
                onNpcQueue(nylocas_npcs.incoming(size, style), queues.death) {
                    death.deathNoDrops(this)
                }
                onNpcQueue(nylocas_npcs.fighting(size, style), queues.death) {
                    death.deathNoDrops(this)
                }
            }
        }
    }
}
