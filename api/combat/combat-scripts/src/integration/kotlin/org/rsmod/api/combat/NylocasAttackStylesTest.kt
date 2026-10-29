package org.rsmod.api.combat

import com.google.inject.AbstractModule
import com.google.inject.Scopes
import jakarta.inject.Inject
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.localscape.nylocas.mage.MageStrategy
import org.localscape.nylocas.ranger.RangerStrategy
import org.rsmod.api.combat.commons.styles.AttackStyle
import org.rsmod.api.combat.weapon.scripts.WeaponAttackStylesScript
import org.rsmod.api.combat.weapon.styles.AttackStyles
import org.rsmod.api.testing.GameTestState
import org.rsmod.game.type.obj.ObjTypeList

class NylocasAttackStylesTest {
    class Dependencies
    @Inject
    constructor(val styles: AttackStyles, val objTypes: ObjTypeList)

    @ParameterizedTest
    @CsvSource(
        "MAGE, EYE, LongrangeMagic",
        "MAGE, SHADOW, LongrangeMagic",
        "MAGE, SCYTHE, AggressiveMelee",
        "MAGE, SULPHUR, AggressiveMelee",
        "MAGE, DARTS, RapidRanged",
        "RANGER, BOW, RapidRanged",
        "RANGER, PIPE, RapidRanged",
        "RANGER, CHIN, RapidRanged",
        "RANGER, EYE, LongrangeMagic",
        "RANGER, CLAWS, AggressiveMelee",
        // Pummel is the only attacking stance on Dinh's; its other stance blocks attacks.
        "MAGE, DINH, AccurateMelee",
    )
    fun `agent weapons resolve to the intended server attack styles`(
        role: String,
        weapon: String,
        expected: AttackStyle,
        state: GameTestState,
    ) =
        state.runInjectedGameTest(
            Dependencies::class,
            object : AbstractModule() {
                override fun configure() {
                    bind(AttackStyles::class.java).`in`(Scopes.SINGLETON)
                }
            },
            WeaponAttackStylesScript::class,
        ) { deps ->
            val (itemId, stance) =
                if (role == "MAGE") {
                    MageStrategy.Weapon.valueOf(weapon).let { it.itemId to it.stance }
                } else {
                    RangerStrategy.Weapon.valueOf(weapon).let { it.itemId to it.stance }
                }
            assertEquals(expected, deps.styles.resolve(deps.objTypes[itemId], stance))
        }
}
