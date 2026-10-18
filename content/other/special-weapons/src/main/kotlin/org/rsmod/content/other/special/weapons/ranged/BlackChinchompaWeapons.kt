package org.rsmod.content.other.special.weapons.ranged

import jakarta.inject.Inject
import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.combat.commons.ranged.RangedAmmunition
import org.rsmod.api.combat.manager.CombatSplashTargets
import org.rsmod.api.config.constants
import org.rsmod.api.config.refs.objs
import org.rsmod.api.config.refs.params
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.righthand
import org.rsmod.api.weapons.RangedWeapon
import org.rsmod.api.weapons.WeaponAttackManager
import org.rsmod.api.weapons.WeaponMap
import org.rsmod.api.weapons.WeaponRepository
import org.rsmod.content.other.special.weapons.configs.BlackChinchompaSynths
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.PathingEntity
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.HitType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.obj.Wearpos

class BlackChinchompaWeapons
@Inject
constructor(
    private val objTypes: ObjTypeList,
    private val splashTargets: CombatSplashTargets,
    private val eventBus: EventBus,
) : WeaponMap {
    override fun WeaponRepository.register(manager: WeaponAttackManager) {
        register(objs.black_chinchompa, BlackChinchompa(manager, objTypes, splashTargets, eventBus))
    }

    private class BlackChinchompa(
        private val manager: WeaponAttackManager,
        private val objTypes: ObjTypeList,
        private val splashTargets: CombatSplashTargets,
        private val eventBus: EventBus,
    ) : RangedWeapon {
        override suspend fun ProtectedAccess.attack(
            target: Npc,
            attack: CombatAttack.Ranged,
        ): Boolean {
            throwChinchompa(target, attack)
            return true
        }

        override suspend fun ProtectedAccess.attack(
            target: Player,
            attack: CombatAttack.Ranged,
        ): Boolean {
            throwChinchompa(target, attack)
            return true
        }

        private fun ProtectedAccess.throwChinchompa(
            primary: PathingEntity,
            attack: CombatAttack.Ranged,
        ) {
            val weaponType = objTypes[attack.weapon]
            val travel = weaponType.param(params.proj_travel)
            val projectileType = weaponType.param(params.proj_type)

            // Materialize the search at launch: movement during flight cannot change the victims.
            val targets = splashTargets.find(this, primary)
            manager.playWeaponFx(this, attack)
            manager.soundArea(player, BlackChinchompaSynths.launch, radius = 10)
            spotanim(
                weaponType.paramOrNull(params.proj_launch),
                height = 96,
                slot = constants.spotanim_slot_combat,
            )
            val projectile = manager.spawnProjectile(this, primary, travel, projectileType)
            val (serverDelay, clientDelay) = projectile.rangedDurations
            manager.soundArea(
                primary,
                BlackChinchompaSynths.explode,
                delay = clientDelay,
                radius = 10,
            )

            val accurate =
                manager.rollRangedAccuracy(
                    this,
                    primary,
                    attack.type,
                    attack.style,
                    attack.type,
                    1.0,
                    // Damage immunity on the primary must not suppress the whole explosion.
                    checkDamageRestrictions = false,
                )
            for (target in targets) {
                val damage =
                    if (
                        manager.canDamage(this, target, HitType.Ranged) &&
                            (accurate || manager.alwaysMaxHit(this, target, HitType.Ranged))
                    ) {
                        manager.rollRangedMaxHit(this, target, attack.type, attack.style, 1.0, 0)
                    } else {
                        0
                    }
                manager.giveCombatXp(this, target, attack, damage)
                manager.queueRangedHit(this, target, null, damage, clientDelay, serverDelay)
            }

            // Chinchompas explode. Consume one per throw, without recovery or a ground drop.
            // Keep it equipped until all rolls and hit snapshots include the final chinchompa.
            RangedAmmunition.detractAmmo(player, Wearpos.RightHand, weaponType, 1, eventBus)
            if (player.righthand == null) {
                mes("That was your last one!")
                manager.stopCombat(this)
            } else {
                manager.continueCombat(this, primary)
            }
        }
    }
}
