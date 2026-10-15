package org.rsmod.content.other.special.weapons.configs

import org.rsmod.api.config.refs.categories
import org.rsmod.api.config.refs.objs
import org.rsmod.api.config.refs.params
import org.rsmod.api.config.refs.varobjs
import org.rsmod.api.type.editors.obj.ObjEditor
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.game.type.obj.WeaponCategory

object BlowpipeSeqs : SeqReferences() {
    val attack = find("snakeboss_blowpipe_attack")
    val blazing_attack = find("snakeboss_blowpipe_attack_ornament")
}

internal object ToxicBlowpipeObjEditor : ObjEditor() {
    init {
        for (pipe in
            listOf(
                objs.toxic_blowpipe,
                objs.toxic_blowpipe_uncharged,
                objs.blazing_blowpipe,
                objs.blazing_blowpipe_uncharged,
            )) {
            edit(pipe) {
                category = categories.throwing_weapon.id
                weaponCategory = WeaponCategory.Thrown.id
                param[params.attackrate] = 3
                param[params.attackrange] = 5
                val loaded = pipe == objs.toxic_blowpipe || pipe == objs.blazing_blowpipe
                param[params.attack_ranged] = if (loaded) 30 else 0
                param[params.ranged_strength] = if (loaded) 20 else 0
                objvar =
                    mutableListOf(
                        varobjs.snakeboss_blowpipe_darrtype,
                        varobjs.snakeboss_blowpipe_dartcount,
                        varobjs.snakeboss_blowpipe_flakes,
                    )
                iop2 = if (loaded) "Wield" else "Check"
                iop3 = if (loaded) "Check" else "Load"
                iop4 = "Unload"
                iop5 = "Drop"
            }
        }
    }
}
