package org.rsmod.content.other.special.weapons.configs

import org.rsmod.api.config.refs.objs
import org.rsmod.api.config.refs.params
import org.rsmod.api.type.editors.obj.ObjEditor

internal object BlackChinchompaObjEditor : ObjEditor() {
    init {
        edit(objs.black_chinchompa) {
            param[params.attackrate] = 4
            param[params.attackrange] = 9
        }
    }
}
