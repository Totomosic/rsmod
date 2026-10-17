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

class SulphurBladesWeapons @Inject constructor(private val random: GameRandom) : WeaponMap {
    override fun WeaponRepository.register(manager: WeaponAttackManager) {
        register(special_weapon_objs.sulphur_blades, SulphurBlades(manager, random))
    }

    private class SulphurBlades(
        private val manager: WeaponAttackManager,
        private val damageRandom: GameRandom,
    ) : MeleeWeapon {
        override suspend fun ProtectedAccess.attack(
            target: Npc,
            attack: CombatAttack.Melee,
        ): Boolean {
            hit(target, attack)
            return true
        }

        override suspend fun ProtectedAccess.attack(
            target: Player,
            attack: CombatAttack.Melee,
        ): Boolean {
            hit(target, attack)
            return true
        }

        private fun ProtectedAccess.hit(target: PathingEntity, attack: CombatAttack.Melee) {
            val maxHit =
                manager.calculateMeleeMaxHit(
                    this,
                    target,
                    attack.type,
                    attack.style,
                    multiplier = 1.0,
                )
            // Keep an odd max hit's remaining point on the second splat: 45 becomes 22 + 23.
            val firstMaxHit = maxHit / 2
            val secondMaxHit = maxHit - firstMaxHit
            val firstDamage = rollDamage(target, attack, firstMaxHit)
            val secondDamage = rollDamage(target, attack, secondMaxHit)

            manager.giveCombatXp(this, target, attack, firstDamage)
            manager.giveCombatXp(this, target, attack, secondDamage)
            manager.playWeaponFx(this, attack)
            manager.queueMeleeHit(this, target, firstDamage, delay = 1)
            manager.queueMeleeHit(this, target, secondDamage, delay = 1)
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
