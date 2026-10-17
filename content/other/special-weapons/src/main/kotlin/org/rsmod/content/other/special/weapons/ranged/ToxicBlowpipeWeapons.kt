package org.rsmod.content.other.special.weapons.ranged

import jakarta.inject.Inject
import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.combat.commons.styles.RangedAttackStyle
import org.rsmod.api.combat.manager.RangedAmmoManager
import org.rsmod.api.config.refs.objs
import org.rsmod.api.config.refs.params
import org.rsmod.api.config.refs.projanims
import org.rsmod.api.config.refs.stats
import org.rsmod.api.config.refs.synths
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.righthand
import org.rsmod.api.player.worn.Blowpipe
import org.rsmod.api.random.GameRandom
import org.rsmod.api.specials.SpecialAttackManager
import org.rsmod.api.specials.SpecialAttackMap
import org.rsmod.api.specials.SpecialAttackRepository
import org.rsmod.api.specials.combat.RangedSpecialAttack
import org.rsmod.api.weapons.RangedWeapon
import org.rsmod.api.weapons.WeaponAttackManager
import org.rsmod.api.weapons.WeaponMap
import org.rsmod.api.weapons.WeaponRepository
import org.rsmod.content.other.special.weapons.configs.BlowpipeProjs
import org.rsmod.content.other.special.weapons.configs.BlowpipeSeqs
import org.rsmod.content.other.special.weapons.configs.BlowpipeSpots
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
    private val weaponManager: WeaponAttackManager,
) : WeaponMap, SpecialAttackMap {
    override fun WeaponRepository.register(manager: WeaponAttackManager) {
        val weapon = ToxicBlowpipe(manager, objTypes, ammunition, damageRandom)
        register(objs.toxic_blowpipe, weapon)
        register(objs.toxic_blowpipe_uncharged, weapon)
        register(objs.blazing_blowpipe, weapon)
        register(objs.blazing_blowpipe_uncharged, weapon)
    }

    override fun SpecialAttackRepository.register(manager: SpecialAttackManager) {
        val weapon = ToxicBlowpipe(weaponManager, objTypes, ammunition, damageRandom)
        val special = ToxicBlowpipeSpecial(weapon)
        registerRanged(objs.toxic_blowpipe, special)
        registerRanged(objs.blazing_blowpipe, special)
    }

    private class ToxicBlowpipeSpecial(private val weapon: ToxicBlowpipe) : RangedSpecialAttack {
        override suspend fun ProtectedAccess.attack(
            target: Npc,
            attack: CombatAttack.Ranged,
        ): Boolean = with(weapon) { shoot(target, attack, special = true) }

        override suspend fun ProtectedAccess.attack(
            target: Player,
            attack: CombatAttack.Ranged,
        ): Boolean = with(weapon) { shoot(target, attack, special = true) }
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

        fun ProtectedAccess.shoot(
            target: PathingEntity,
            attack: CombatAttack.Ranged,
            special: Boolean = false,
        ): Boolean {
            val obj = player.righthand ?: return false
            val scales = Blowpipe.scales(obj)
            val darts = Blowpipe.darts(obj)
            val dart = Blowpipe.dartType(obj)
            if (scales == 0 || darts == 0 || dart == null) {
                mes("Your blowpipe needs Zulrah's scales and unpoisoned darts before it can fire.")
                manager.stopCombat(this)
                return false
            }
            val dartType = objTypes[dart]
            val travel = dartType.paramOrNull(params.proj_travel)
            if (travel == null) {
                mes("Your blowpipe is unable to fire its darts.")
                manager.stopCombat(this)
                return false
            }

            manager.setNextAttackDelay(this, if (attack.style == RangedAttackStyle.Rapid) 2 else 3)
            val blazing = obj.isAnyType(objs.blazing_blowpipe, objs.blazing_blowpipe_uncharged)
            anim(if (blazing) BlowpipeSeqs.blazing_attack else BlowpipeSeqs.attack)
            manager.soundArea(player, synths.dart, radius = 10)
            val projectileType =
                if (special && player.bounds().distanceTo(target.bounds()) > 1) {
                    BlowpipeProjs.special
                } else {
                    projanims.thrown
                }
            val projectile =
                manager.spawnProjectile(
                    this,
                    target,
                    if (special) BlowpipeSpots.special else travel,
                    projectileType,
                )
            val (serverDelay, clientDelay) = projectile.durations

            // Roll while the equipped weapon still contains its dart, including the last shot.
            val damage =
                manager.rollRangedDamage(
                    this,
                    target,
                    attack,
                    accuracyMultiplier = if (special) 2.0 else 1.0,
                    maxHitMultiplier = if (special) 1.5 else 1.0,
                )
            // Healing uses the full roll before hit processing caps damage to remaining target HP.
            if (special) statHeal(stats.hitpoints, constant = damage / 2, percent = 0)
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
            return true
        }
    }
}
