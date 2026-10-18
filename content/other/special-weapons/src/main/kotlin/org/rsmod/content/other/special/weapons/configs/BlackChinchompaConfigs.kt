package org.rsmod.content.other.special.weapons.configs

import org.rsmod.api.config.refs.objs
import org.rsmod.api.config.refs.params
import org.rsmod.api.type.editors.obj.ObjEditor
import org.rsmod.game.type.synth.SynthType

internal object BlackChinchompaSynths {
    // These existing cache sounds are absent from the workspace's synth symbols.
    // https://gist.github.com/Jire/d8db4e0964feb92c719b95cf4821ab2c
    val launch = SynthType(359, "chinchompa_attack")
    val explode = SynthType(360, "chinchompa_explode")
}

internal object BlackChinchompaObjEditor : ObjEditor() {
    init {
        edit(objs.black_chinchompa) {
            param[params.attackrate] = 4
            param[params.attackrange] = 9
        }
    }
}
