package org.rsmod.content.other.npcdrops

import org.rsmod.api.drop.table.NpcDropTableProvider
import org.rsmod.plugin.module.PluginModule

class NpcDropsModule : PluginModule() {
    override fun bind() {
        addSetBinding<NpcDropTableProvider>(LumbridgeNpcDrops::class.java)
    }
}
