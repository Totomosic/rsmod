package org.rsmod.content.other.special.weapons.configs

import org.rsmod.api.config.refs.params
import org.rsmod.api.type.builders.proj.ProjAnimBuilder
import org.rsmod.api.type.editors.obj.ObjEditor
import org.rsmod.api.type.refs.proj.ProjAnimReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.api.type.refs.spot.SpotanimReferences

internal object TonalzticsSeqs : SeqReferences() {
    val charged = find("human_glaive_ralos01_charged_throw")
}

internal object TonalzticsSpots : SpotanimReferences() {
    val first = find("projanim_glaive_01_regular")
    val second = find("projanim_glaive_02_regular")
}

internal object TonalzticsProjs : ProjAnimReferences() {
    val travel = find("tonalztics_of_ralos")
}

internal object TonalzticsProjBuilds : ProjAnimBuilder() {
    init {
        build("tonalztics_of_ralos") {
            startHeight = 160
            endHeight = 120
            delay = 0
            angle = 0
            lengthAdjustment = 30
            progress = 11
            // One game tick of travel at every distance; ranged hit delay includes another queue
            // tick.
            stepMultiplier = 0
        }
    }
}

internal object TonalzticsObjEditor : ObjEditor() {
    init {
        edit(special_weapon_objs.tonalztics_of_ralos_charged) {
            param[params.attack_anim_stance1] = TonalzticsSeqs.charged
            param[params.attack_anim_stance2] = TonalzticsSeqs.charged
            param[params.attack_anim_stance4] = TonalzticsSeqs.charged
        }
    }
}
