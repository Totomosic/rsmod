package org.rsmod.content.other.special.weapons.ranged

import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.weapons.RangedWeapon
import org.rsmod.api.weapons.WeaponAttackManager
import org.rsmod.api.weapons.WeaponMap
import org.rsmod.api.weapons.WeaponRepository
import org.rsmod.content.other.special.weapons.configs.TonalzticsProjs
import org.rsmod.content.other.special.weapons.configs.TonalzticsSpots
import org.rsmod.content.other.special.weapons.configs.special_weapon_objs
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.PathingEntity
import org.rsmod.game.entity.Player

class TonalzticsOfRalosWeapons : WeaponMap {
    override fun WeaponRepository.register(manager: WeaponAttackManager) {
        register(special_weapon_objs.tonalztics_of_ralos_charged, Tonalztics(manager))
    }

    private class Tonalztics(private val manager: WeaponAttackManager) : RangedWeapon {
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
            manager.playWeaponFx(this, attack)
            for ((index, spot) in
                listOf(TonalzticsSpots.first, TonalzticsSpots.second).withIndex()) {
                val projectile = manager.spawnProjectile(this, target, spot, TonalzticsProjs.travel)
                // Both accuracy and damage are independent, including when the first hit misses.
                val damage = manager.rollRangedDamage(this, target, attack, maxHitMultiplier = 0.75)
                manager.giveCombatXp(this, target, attack, damage)
                if (index == 0) {
                    manager.queueRangedHit(
                        this,
                        target,
                        ammo = null,
                        damage = damage,
                        clientDelay = projectile.clientCycles,
                        hitDelay = projectile.rangedServerCycles,
                    )
                } else {
                    manager.queueRangedDamage(
                        this,
                        target,
                        null,
                        damage,
                        projectile.rangedServerCycles,
                    )
                }
            }
            // The charged weapon stays equipped and does not use quiver ammunition.
            manager.continueCombat(this, target)
        }
    }
}
