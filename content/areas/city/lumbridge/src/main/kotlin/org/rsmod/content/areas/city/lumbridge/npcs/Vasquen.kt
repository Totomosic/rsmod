package org.rsmod.content.areas.city.lumbridge.npcs

import jakarta.inject.Inject
import org.rsmod.api.config.refs.mesanims
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.script.onOpNpc3
import org.rsmod.api.script.onOpNpc5
import org.rsmod.api.shops.Shops
import org.rsmod.content.areas.city.lumbridge.configs.lumbridge_npcs
import org.rsmod.content.areas.city.lumbridge.npcs.FarmingGardener.gardenerDialogue
import org.rsmod.content.areas.city.lumbridge.npcs.FarmingGardener.openFarmingSupplies
import org.rsmod.game.entity.Npc
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/** Vasquen looks after the hops patch north of Lumbridge. */
class Vasquen @Inject constructor(private val shops: Shops) : PluginScript() {
    override fun ScriptContext.startup() {
        onOpNpc1(lumbridge_npcs.vasquen) { talkTo(it.npc) }
        onOpNpc3(lumbridge_npcs.vasquen) { pay(it.npc) }
        onOpNpc5(lumbridge_npcs.vasquen) { player.openFarmingSupplies(shops, it.npc) }
    }

    private suspend fun ProtectedAccess.talkTo(npc: Npc) =
        startDialogue(npc) { gardenerDialogue(shops, npc, NOT_PLANTED) }

    // TODO(content): Farming - payment/protection once hops patches are implemented.
    private suspend fun ProtectedAccess.pay(npc: Npc) {
        chatNpc(npc, mesanims.neutral, NOT_PLANTED)
    }

    private companion object {
        // Vasquen's wiki transcript is incomplete; this is the standard seed-patch gardener
        // response (e.g. Transcript:Kragen).
        const val NOT_PLANTED =
            "You don't have any seeds planted in that patch. Plant some and I might agree to " +
                "look after it for you."
    }
}
