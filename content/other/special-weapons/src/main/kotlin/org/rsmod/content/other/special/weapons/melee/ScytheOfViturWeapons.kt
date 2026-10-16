package org.rsmod.content.other.special.weapons.melee

import jakarta.inject.Inject
import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.combat.commons.rollDamage
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.random.GameRandom
import org.rsmod.api.weapons.MeleeWeapon
import org.rsmod.api.weapons.WeaponAttackManager
import org.rsmod.api.weapons.WeaponMap
import org.rsmod.api.weapons.WeaponRepository
import org.rsmod.content.other.special.weapons.configs.special_weapon_objs
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.PathingEntity
import org.rsmod.game.entity.Player

class ScytheOfViturWeapons @Inject constructor(private val random: GameRandom) : WeaponMap {
    override fun WeaponRepository.register(manager: WeaponAttackManager) {
        val scythe = ScytheOfVitur(manager, random)
        register(special_weapon_objs.scythe_of_vitur, scythe)
        register(special_weapon_objs.scythe_of_vitur_uncharged, scythe)
        register(special_weapon_objs.holy_scythe_of_vitur, scythe)
        register(special_weapon_objs.holy_scythe_of_vitur_uncharged, scythe)
        register(special_weapon_objs.sanguine_scythe_of_vitur, scythe)
        register(special_weapon_objs.sanguine_scythe_of_vitur_uncharged, scythe)
        register(special_weapon_objs.blighted_scythe_of_vitur, scythe)
        register(special_weapon_objs.blighted_scythe_of_vitur_uncharged, scythe)
    }

    private class ScytheOfVitur(
        private val manager: WeaponAttackManager,
        private val damageRandom: GameRandom,
    ) : MeleeWeapon {
        override suspend fun ProtectedAccess.attack(
            target: Npc,
            attack: CombatAttack.Melee,
        ): Boolean {
            hit(target, attack, hitCount = target.size.coerceIn(1, 3))
            return true
        }

        override suspend fun ProtectedAccess.attack(
            target: Player,
            attack: CombatAttack.Melee,
        ): Boolean {
            hit(target, attack, hitCount = 1)
            return true
        }

        private fun ProtectedAccess.hit(
            target: PathingEntity,
            attack: CombatAttack.Melee,
            hitCount: Int,
        ) {
            var maxHit =
                manager.calculateMeleeMaxHit(
                    this,
                    target,
                    attack.type,
                    attack.style,
                    multiplier = 1.0,
                )
            val damage =
                IntArray(hitCount) {
                    val rolled = rollDamage(target, attack, maxHit)
                    maxHit /= 2
                    rolled
                }

            manager.giveCombatXp(this, target, attack, damage.sum())
            manager.playWeaponFx(this, attack)
            for (splat in damage) {
                manager.queueMeleeHit(this, target, splat, delay = 1)
            }
            manager.continueCombat(this, target)
        }

        private fun ProtectedAccess.rollDamage(
            target: PathingEntity,
            attack: CombatAttack.Melee,
            maxHit: Int,
        ): Int {
            val accurate =
                manager.rollMeleeAccuracy(
                    source = this,
                    target = target,
                    attackType = attack.type,
                    attackStyle = attack.style,
                    blockType = attack.type,
                    multiplier = 1.0,
                )
            return if (accurate && maxHit > 0) damageRandom.rollDamage(maxHit, target) else 0
        }
    }
}
