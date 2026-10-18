package org.rsmod.content.areas.dungeons.nylocas

import org.rsmod.api.config.refs.params
import org.rsmod.api.type.editors.npc.NpcEditor

internal object NylocasNpcEdits : NpcEditor() {
    init {
        for (size in NylocasSize.entries) {
            for (style in NylocasStyle.entries) {
                for (type in
                    listOf(
                        nylocas_npcs.incoming(size, style),
                        nylocas_npcs.fighting(size, style),
                    )) {
                    edit(type) {
                        param[params.attack_anim] = nylocas_seqs.attack(style)
                        param[params.death_anim] = nylocas_seqs.death(style)
                        param[params.defend_sound] = NylocasSynths.defend
                    }
                }
            }
        }
    }
}
