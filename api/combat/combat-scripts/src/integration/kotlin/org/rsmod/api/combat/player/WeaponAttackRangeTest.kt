package org.rsmod.api.combat.player

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.rsmod.api.combat.commons.styles.AttackStyle
import org.rsmod.api.config.refs.objs
import org.rsmod.api.player.righthand
import org.rsmod.api.testing.GameTestState
import org.rsmod.game.inv.InvObj

@Execution(ExecutionMode.SAME_THREAD)
class WeaponAttackRangeTest {
    @Test
    fun GameTestState.`Eye of ayak attacks from six tiles on accurate`() = runGameTest {
        val eye = findObjType { it.internalName == "eye_of_ayak" }.single()
        player.righthand = InvObj(eye)
        player.withProtectedAccess { assertEquals(6, weaponAttackRange(AttackStyle.AccurateMagic)) }
    }

    @Test
    fun GameTestState.`Eye of ayak attacks from eight tiles on longrange`() = runGameTest {
        val eye = findObjType { it.internalName == "eye_of_ayak" }.single()
        player.righthand = InvObj(eye)
        player.withProtectedAccess {
            assertEquals(8, weaponAttackRange(AttackStyle.LongrangeMagic))
        }
    }

    @Test
    fun GameTestState.`ranged longrange and unarmed ranges retain their existing behavior`() =
        runGameTest {
            player.righthand = InvObj(objs.toxic_blowpipe)
            player.withProtectedAccess {
                assertEquals(5, weaponAttackRange(AttackStyle.AccurateRanged))
                assertEquals(7, weaponAttackRange(AttackStyle.LongrangeRanged))
            }
            player.righthand = null
            player.withProtectedAccess {
                assertEquals(1, weaponAttackRange(AttackStyle.AccurateMelee))
            }
        }
}
