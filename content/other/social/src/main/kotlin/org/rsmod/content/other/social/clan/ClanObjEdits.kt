package org.rsmod.content.other.social.clan

import org.rsmod.api.config.refs.params
import org.rsmod.api.type.editors.obj.ObjEditor

/** A wielded clan vexillum adds a "Recruit" option to other players (handled in [ClanScript]). */
internal object ClanObjEdits : ObjEditor() {
    init {
        for (vexillum in clan_objs.vexilla) {
            edit(vexillum) { param[params.player_op5_text] = "Recruit" }
        }
    }
}
