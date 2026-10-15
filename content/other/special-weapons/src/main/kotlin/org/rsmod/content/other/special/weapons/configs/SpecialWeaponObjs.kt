package org.rsmod.content.other.special.weapons.configs

import org.rsmod.api.config.refs.objs
import org.rsmod.api.config.refs.params
import org.rsmod.api.type.editors.obj.ObjEditor
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.game.type.obj.WeaponCategory

typealias special_weapon_objs = SpecialWeaponObjs

object SpecialWeaponObjs : ObjReferences() {
    val sulphur_blades = find("sulphur_blades")
    val eye_of_ayak = find("eye_of_ayak")
    val eye_of_ayak_uncharged = find("eye_of_ayak_uncharged")
    val demon_tear = find("demon_tear")
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
        for (eye in
            listOf(special_weapon_objs.eye_of_ayak, special_weapon_objs.eye_of_ayak_uncharged)) {
            edit(eye) {
                weaponCategory = WeaponCategory.PoweredStaff.id
                param[params.attackrate] = 3
                param[params.charged_variant] = special_weapon_objs.eye_of_ayak
                param[params.uncharged_variant] = special_weapon_objs.eye_of_ayak_uncharged
                objvar = mutableListOf(EyeOfAyakVars.charges, EyeOfAyakVars.demon_tears)
            }
        }
        edit(objs.tumekens_shadow) {
            param[params.uncharged_variant] = objs.tumekens_shadow_uncharged
        }
        edit(objs.tumekens_shadow_uncharged) {
            param[params.charged_variant] = objs.tumekens_shadow
        }
    }
}
