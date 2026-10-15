package org.rsmod.content.other.special.weapons.configs

import org.rsmod.api.config.refs.objs
import org.rsmod.api.config.refs.params
import org.rsmod.api.type.editors.obj.ObjEditor
import org.rsmod.api.type.refs.obj.ObjReferences

typealias special_weapon_objs = SpecialWeaponObjs

object SpecialWeaponObjs : ObjReferences() {
    val sulphur_blades = find("sulphur_blades")
    val scythe_of_vitur = find("scythe_of_vitur")
    val scythe_of_vitur_uncharged = find("scythe_of_vitur_uncharged")
    val holy_scythe_of_vitur = find("scythe_of_vitur_or")
    val holy_scythe_of_vitur_uncharged = find("scythe_of_vitur_uncharged_or")
    val sanguine_scythe_of_vitur = find("scythe_of_vitur_bl")
    val sanguine_scythe_of_vitur_uncharged = find("scythe_of_vitur_uncharged_bl")
    val blighted_scythe_of_vitur = find("deadman_blighted_scythe_of_vitur")
    val blighted_scythe_of_vitur_uncharged = find("deadman_blighted_scythe_of_vitur_uncharged")
}

internal object SpecialWeaponObjEditor : ObjEditor() {
    init {
        edit(objs.tumekens_shadow) {
            param[params.uncharged_variant] = objs.tumekens_shadow_uncharged
        }
        edit(objs.tumekens_shadow_uncharged) {
            param[params.charged_variant] = objs.tumekens_shadow
        }
    }
}
