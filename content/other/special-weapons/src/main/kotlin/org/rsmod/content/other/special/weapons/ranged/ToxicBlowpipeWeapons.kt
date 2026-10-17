package org.rsmod.content.other.special.weapons.ranged

import jakarta.inject.Inject
import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.combat.commons.styles.RangedAttackStyle
import org.rsmod.api.combat.manager.RangedAmmoManager
import org.rsmod.api.config.refs.objs
import org.rsmod.api.config.refs.params
import org.rsmod.api.config.refs.projanims
import org.rsmod.api.config.refs.synths
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.righthand
import org.rsmod.api.player.worn.Blowpipe
import org.rsmod.api.random.GameRandom
import org.rsmod.api.weapons.RangedWeapon
import org.rsmod.api.weapons.WeaponAttackManager
import org.rsmod.api.weapons.WeaponMap
import org.rsmod.api.weapons.WeaponRepository
import org.rsmod.content.other.special.weapons.configs.BlowpipeSeqs
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.PathingEntity
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.isAnyType
import org.rsmod.game.type.obj.ObjTypeList

class ToxicBlowpipeWeapons
@Inject
constructor(
    private val objTypes: ObjTypeList,
    private val ammunition: RangedAmmoManager,
    private val damageRandom: GameRandom,
) : WeaponMap {
    override fun WeaponRepository.register(manager: WeaponAttackManager) {
        val weapon = ToxicBlowpipe(manager, objTypes, ammunition, damageRandom)
        register(objs.toxic_blowpipe, weapon)
        register(objs.toxic_blowpipe_uncharged, weapon)
        register(objs.blazing_blowpipe, weapon)
        register(objs.blazing_blowpipe_uncharged, weapon)
    }

    private class ToxicBlowpipe(
        private val manager: WeaponAttackManager,
        private val objTypes: ObjTypeList,
        private val ammunition: RangedAmmoManager,
        private val damageRandom: GameRandom,
    ) : RangedWeapon {
        override suspend fun ProtectedAccess.attack(
            target: Npc,
            attack: CombatAttack.Ranged,
        ): Boolean {
            shoot(target, attack)
            return true
        }

        override suspend fun ProtectedAccess.attack(
            target: Player,
            attack: CombatAttack.Ranged,
        ): Boolean {
            shoot(target, attack)
            return true
        }

        private fun ProtectedAccess.shoot(target: PathingEntity, attack: CombatAttack.Ranged) {
            val obj = player.righthand ?: return
            val scales = Blowpipe.scales(obj)
            val darts = Blowpipe.darts(obj)
            val dart = Blowpipe.dartType(obj)
            if (scales == 0 || darts == 0 || dart == null) {
                mes("Your blowpipe needs Zulrah's scales and unpoisoned darts before it can fire.")
                manager.stopCombat(this)
                return
            }
            val dartType = objTypes[dart]
            val travel = dartType.paramOrNull(params.proj_travel)
            if (travel == null) {
                mes("Your blowpipe is unable to fire its darts.")
                manager.stopCombat(this)
                return
            }

            manager.setNextAttackDelay(this, if (attack.style == RangedAttackStyle.Rapid) 2 else 3)
            val blazing = obj.isAnyType(objs.blazing_blowpipe, objs.blazing_blowpipe_uncharged)
            anim(if (blazing) BlowpipeSeqs.blazing_attack else BlowpipeSeqs.attack)
            manager.soundArea(player, synths.dart, radius = 10)
            val projectile = manager.spawnProjectile(this, target, travel, projanims.thrown)
            val (serverDelay, clientDelay) = projectile.durations

            // Roll while the equipped weapon still contains its dart, including the last shot.
            val damage = manager.rollRangedDamage(this, target, attack)
            manager.giveCombatXp(this, target, attack, damage)
            manager.queueRangedHit(this, target, dartType, damage, clientDelay, serverDelay)

            val useDart = ammunition.useStoredAmmo(player, dartType, target.coords, serverDelay)
            val useScale = damageRandom.of(3) != 0
            val remainingScales = scales - if (useScale) 1 else 0
            val remainingDarts = darts - if (useDart) 1 else 0
            player.righthand = Blowpipe.withAmmo(obj, remainingScales, dart, remainingDarts)
            if (remainingScales == 0 || remainingDarts == 0) {
                mes(
                    if (remainingScales == 0) "Your blowpipe has run out of scales."
                    else "Your blowpipe has run out of darts."
                )
                manager.stopCombat(this)
            } else {
                manager.continueCombat(this, target)
            }
        }
    }
}
