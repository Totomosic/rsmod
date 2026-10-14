package org.rsmod.content.other.special.attacks.melee

import kotlin.math.min
import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.config.constants
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.specials.SpecialAttackManager
import org.rsmod.api.specials.SpecialAttackMap
import org.rsmod.api.specials.SpecialAttackRepository
import org.rsmod.api.specials.combat.MeleeSpecialAttack
import org.rsmod.content.other.special.attacks.configs.special_objs
import org.rsmod.content.other.special.attacks.configs.special_seqs
import org.rsmod.content.other.special.attacks.configs.special_spots
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.PathingEntity
import org.rsmod.game.entity.Player

/**
 * Abyssal whip "Energy Drain": 25% increased accuracy. Against players, 10% of the target's run
 * energy is drained and transferred to the attacker.
 */
class AbyssalWhipSpecialAttack : SpecialAttackMap {
    override fun SpecialAttackRepository.register(manager: SpecialAttackManager) {
        registerMelee(special_objs.abyssal_whip, AbyssalWhip(manager))
        registerMelee(special_objs.volcanic_abyssal_whip, AbyssalWhip(manager))
        registerMelee(special_objs.frozen_abyssal_whip, AbyssalWhip(manager))
    }

    private class AbyssalWhip(private val manager: SpecialAttackManager) : MeleeSpecialAttack {
        override suspend fun ProtectedAccess.attack(
            target: Npc,
            attack: CombatAttack.Melee,
        ): Boolean {
            energyDrain(target, attack)
            return true
        }

        override suspend fun ProtectedAccess.attack(
            target: Player,
            attack: CombatAttack.Melee,
        ): Boolean {
            energyDrain(target, attack)

            val drained = target.runEnergy / 10
            if (drained > 0) {
                target.runEnergy -= drained
                player.runEnergy = min(constants.run_max_energy, player.runEnergy + drained)
            }
            return true
        }

        private fun ProtectedAccess.energyDrain(target: PathingEntity, attack: CombatAttack.Melee) {
            anim(special_seqs.abyssal_whip)
            target.spotanim(special_spots.abyssal_whip, height = 96)

            val damage =
                manager.rollMeleeDamage(
                    source = this,
                    target = target,
                    attack = attack,
                    accuracyMultiplier = 1.25,
                    maxHitMultiplier = 1.0,
                )
            manager.giveCombatXp(this, target, attack, damage)
            manager.queueMeleeHit(this, target, damage)
            manager.continueCombat(this, target)
        }
    }
}
