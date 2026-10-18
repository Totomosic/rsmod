package org.rsmod.content.other.special.weapons.configs

import org.rsmod.api.config.refs.objs
import org.rsmod.api.config.refs.params
import org.rsmod.api.type.editors.obj.ObjEditor
import org.rsmod.game.type.synth.SynthType

internal object BlackChinchompaSynths {
    // The player release uses throwingaxe; chinchompa_attack (359) is the creature's squeak.
    // https://github.com/OldSchoolSDK/osrs-sdk/blob/main/src/content/weapons/BlackChinchompa.ts
    // These existing cache sounds are absent from the workspace's synth symbols.
    val launch = SynthType(2706, "throwingaxe")
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
