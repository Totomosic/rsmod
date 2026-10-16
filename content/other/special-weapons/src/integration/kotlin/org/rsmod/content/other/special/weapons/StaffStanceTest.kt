package org.rsmod.content.other.special.weapons

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.rsmod.api.config.refs.params
import org.rsmod.api.testing.GameTestState

class StaffStanceTest {
    @ParameterizedTest
    @ValueSource(
        strings =
            [
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
            ]
    )
    fun `staffs use an upright stance while idle and moving`(name: String, state: GameTestState) =
        state.runGameTest {
            val type = findObjType { it.internalName == name }.single()
            assertEquals(813, type.param(params.bas_readyanim).id)
            assertEquals(1209, type.param(params.bas_turnonspot).id)
            assertEquals(1205, type.param(params.bas_walk_f).id)
            assertEquals(1206, type.param(params.bas_walk_b).id)
            assertEquals(1207, type.param(params.bas_walk_l).id)
            assertEquals(1208, type.param(params.bas_walk_r).id)
            assertEquals(1210, type.param(params.bas_running).id)
        }
}
