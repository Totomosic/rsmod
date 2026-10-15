package org.rsmod.content.other.special.weapons.configs

import org.rsmod.api.type.builders.varobjbit.VarObjBitBuilder
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.api.type.refs.spot.SpotanimReferences

object EyeOfAyakSeqs : SeqReferences() {
    val cast = find("human_eye_of_ayak_normal")
}

object EyeOfAyakSpots : SpotanimReferences() {
    val cast = find("vfx_ayak_player_normal_spotanim")
    val travel = find("vfx_ayak_normal_projectile")
    val impact = find("vfx_ayak_normal_impact")
}

object EyeOfAyakVars : VarObjBitBuilder() {
    val charges = build("eye_of_ayak_charges") { bits = 0..15 }
    val demon_tears = build("eye_of_ayak_demon_tears") { bits = 16..16 }
}
