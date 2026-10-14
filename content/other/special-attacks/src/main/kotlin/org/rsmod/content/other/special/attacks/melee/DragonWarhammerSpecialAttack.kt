package org.rsmod.content.other.special.attacks.melee

import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.config.constants
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.stat
import org.rsmod.api.player.stat.statSub
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
 * Dragon warhammer "Smash": 50% increased max hit. If the attack deals damage, the target's current
 * Defence level is lowered by 30%.
 */
class DragonWarhammerSpecialAttack : SpecialAttackMap {
    override fun SpecialAttackRepository.register(manager: SpecialAttackManager) {
        registerMelee(special_objs.dragon_warhammer, DragonWarhammer(manager))
        registerMelee(special_objs.dragon_warhammer_cr, DragonWarhammer(manager))
    }

    private class DragonWarhammer(private val manager: SpecialAttackManager) : MeleeSpecialAttack {
        override suspend fun ProtectedAccess.attack(
            target: Npc,
            attack: CombatAttack.Melee,
        ): Boolean {
            val damage = smash(target, attack)
            if (damage > 0) {
                target.defenceLvl -= defenceDrain(target.defenceLvl)
            }
            return true
        }

        override suspend fun ProtectedAccess.attack(
            target: Player,
            attack: CombatAttack.Melee,
        ): Boolean {
            val damage = smash(target, attack)
            if (damage > 0) {
                val drain = defenceDrain(target.stat(stats.defence))
                target.statSub(stats.defence, constant = drain, percent = 0)
            }
            return true
        }

        private fun ProtectedAccess.smash(target: PathingEntity, attack: CombatAttack.Melee): Int {
            anim(special_seqs.dragon_warhammer)
            spotanim(
                spot = special_spots.dragon_warhammer,
                slot = constants.spotanim_slot_combat,
                height = 96,
            )

            val damage =
                manager.rollMeleeDamage(
                    source = this,
                    target = target,
                    attack = attack,
                    accuracyMultiplier = 1.0,
                    maxHitMultiplier = 1.5,
                )
            manager.giveCombatXp(this, target, attack, damage)
            manager.queueMeleeHit(this, target, damage)
            manager.continueCombat(this, target)
            return damage
        }

        private fun defenceDrain(currentLevel: Int): Int = (currentLevel * 30) / 100
    }
}
