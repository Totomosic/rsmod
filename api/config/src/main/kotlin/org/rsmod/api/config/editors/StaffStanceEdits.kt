package org.rsmod.api.config.editors

import org.rsmod.api.config.refs.params
import org.rsmod.api.type.editors.obj.ObjEditor
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.seq.SeqReferences

internal object StaffStanceEdits : ObjEditor() {
    init {
        // These newer staves lack the base animations supplied by cache enrichment for older ones.
        for (staff in UprightStaffObjs.staves) {
            edit(staff) {
                param[params.bas_readyanim] = UprightStaffSeqs.ready
                param[params.bas_turnonspot] = UprightStaffSeqs.turn
                param[params.bas_walk_f] = UprightStaffSeqs.walk
                param[params.bas_walk_b] = UprightStaffSeqs.back
                param[params.bas_walk_l] = UprightStaffSeqs.left
                param[params.bas_walk_r] = UprightStaffSeqs.right
                param[params.bas_running] = UprightStaffSeqs.run
            }
        }
    }
}

internal object UprightStaffObjs : ObjReferences() {
    val staves =
        listOf(
                "eye_of_ayak",
                "eye_of_ayak_uncharged",
                "ancient_sceptre",
                "ancient_sceptre_trouver",
                "ancient_sceptre_blood",
                "ancient_sceptre_blood_trouver",
                "ancient_sceptre_blood_broken",
                "ancient_sceptre_ice",
                "ancient_sceptre_ice_trouver",
                "ancient_sceptre_ice_broken",
                "ancient_sceptre_smoke",
                "ancient_sceptre_smoke_trouver",
                "ancient_sceptre_smoke_broken",
                "ancient_sceptre_shadow",
                "ancient_sceptre_shadow_trouver",
                "ancient_sceptre_shadow_broken",
            )
            .map { find(it) }
}

internal object UprightStaffSeqs : SeqReferences() {
    val ready = find("human_staffready")
    val turn = find("human_halberdturnonspot")
    val walk = find("human_halberdwalk_f")
    val back = find("human_halberdwalk_b")
    val left = find("human_halberdwalk_l")
    val right = find("human_halberdwalk_r")
    val run = find("human_halberdrunning")
}
