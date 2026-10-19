package org.rsmod.content.other.special.weapons.configs

import org.rsmod.api.config.refs.categories
import org.rsmod.api.config.refs.objs
import org.rsmod.api.config.refs.params
import org.rsmod.api.config.refs.varobjs
import org.rsmod.api.type.builders.proj.ProjAnimBuilder
import org.rsmod.api.type.editors.obj.ObjEditor
import org.rsmod.api.type.refs.proj.ProjAnimReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.api.type.refs.spot.SpotanimReferences
import org.rsmod.game.type.obj.WeaponCategory

object BlowpipeSeqs : SeqReferences() {
    val attack = find("snakeboss_blowpipe_attack")
    val blazing_attack = find("snakeboss_blowpipe_attack_ornament")
}

object BlowpipeSpots : SpotanimReferences() {
    val special = find("toxic_blowpipe_specialattack")
}

object BlowpipeProjs : ProjAnimReferences() {
    val special = find("blowpipe_special")
}

internal object BlowpipeProjBuilds : ProjAnimBuilder() {
    init {
        build("blowpipe_special") {
            startHeight = 163
            endHeight = 146
            delay = 32
            angle = 15
            // One tick longer than a thrown dart; adjacent shots use the thrown timing instead.
            lengthAdjustment = 30
            progress = 11
            stepMultiplier = 5
        }
    }
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
                val ascension =
                    pipe == objs.blazing_blowpipe || pipe == objs.blazing_blowpipe_uncharged
                if (ascension) {
                    name =
                        if (pipe == objs.blazing_blowpipe) "Ascension crossbows"
                        else "Ascension crossbows (empty)"
                }
                category = categories.throwing_weapon.id
                weaponCategory = WeaponCategory.Thrown.id
                param[params.attackrate] = 3
                param[params.attackrange] = if (ascension) 6 else 5
                val loaded = pipe == objs.toxic_blowpipe || pipe == objs.blazing_blowpipe
                param[params.attack_ranged] = if (!loaded) 0 else if (ascension) 34 else 30
                // Dragon darts add 35 strength, bringing Ascension crossbows to 58 total.
                param[params.ranged_strength] = if (!loaded) 0 else if (ascension) 23 else 20
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
