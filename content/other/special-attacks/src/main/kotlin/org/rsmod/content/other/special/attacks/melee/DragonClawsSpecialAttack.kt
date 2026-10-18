package org.rsmod.content.other.special.attacks.melee

import kotlin.math.max
import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.combat.commons.rollDamage
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
import org.rsmod.game.hit.HitType

/**
 * Dragon claws "Slice and Dice": four hits in a cascade. Up to four accuracy rolls are made, and
 * the first accurate roll decides the damage of every hit (`max` is the standard max hit):
 * 1. First roll hits: `X` in `[max/2, max-1]`, then `X/2`, `X/4`, `X/4 + 1`.
 * 2. Second roll hits: `0`, `X` in `[3max/8, 7max/8]`, `X/2`, `X/2 + 1`.
 * 3. Third roll hits: `0`, `0`, `X` in `[max/4, 3max/4]`, `X + 1`.
 * 4. Fourth roll hits: `0`, `0`, `0`, `X` in `[max/4, 5max/4]`.
 * 5. All rolls miss: 2/3 chance of `0`, `0`, `1`, `1`; otherwise four zeros.
 *
 * All divisions round down. The first two hits land on the next cycle, the last two one cycle
 * later.
 */
class DragonClawsSpecialAttack : SpecialAttackMap {
    override fun SpecialAttackRepository.register(manager: SpecialAttackManager) {
        registerMelee(special_objs.dragon_claws, DragonClaws(manager))
        registerMelee(special_objs.dragon_claws_cr, DragonClaws(manager))
    }

    private class DragonClaws(private val manager: SpecialAttackManager) : MeleeSpecialAttack {
        override suspend fun ProtectedAccess.attack(
            target: Npc,
            attack: CombatAttack.Melee,
        ): Boolean {
            sliceAndDice(target, attack)
            return true
        }

        override suspend fun ProtectedAccess.attack(
            target: Player,
            attack: CombatAttack.Melee,
        ): Boolean {
            sliceAndDice(target, attack)
            return true
        }

        private fun ProtectedAccess.sliceAndDice(
            target: PathingEntity,
            attack: CombatAttack.Melee,
        ) {
            anim(special_seqs.dragon_claws)
            spotanim(
                spot = special_spots.dragon_claws,
                slot = constants.spotanim_slot_combat,
                height = 96,
            )

            val hits = rollHits(target, attack)
            manager.giveCombatXp(this, target, attack, hits.sum())
            manager.queueMeleeHit(this, target, hits[0], delay = 1)
            manager.queueMeleeHit(this, target, hits[1], delay = 1)
            manager.queueMeleeHit(this, target, hits[2], delay = 2)
            manager.queueMeleeHit(this, target, hits[3], delay = 2)
            manager.continueCombat(this, target)
        }

        private fun ProtectedAccess.rollHits(
            target: PathingEntity,
            attack: CombatAttack.Melee,
        ): IntArray {
            val maxHit =
                manager.calculateMeleeMaxHit(
                    source = this,
                    target = target,
                    attackType = attack.type,
                    attackStyle = attack.style,
                    multiplier = 1.0,
                )

            fun accurate(): Boolean =
                manager.rollMeleeAccuracy(
                    source = this,
                    target = target,
                    attackType = attack.type,
                    attackStyle = attack.style,
                    blockType = attack.type,
                    multiplier = 1.0,
                )

            fun roll(min: Int, max: Int): Int =
                if (manager.alwaysMaxHit(this, target, HitType.Melee)) max(min, max)
                else random.rollDamage(min..max(min, max), target)

            if (accurate()) {
                val first = roll(maxHit / 2, maxHit - 1)
                val second = first / 2
                val third = second / 2
                return intArrayOf(first, second, third, third + 1)
            }

            if (accurate()) {
                val second = roll((maxHit * 3) / 8, (maxHit * 7) / 8)
                val third = second / 2
                return intArrayOf(0, second, third, third + 1)
            }

            if (accurate()) {
                val third = roll(maxHit / 4, (maxHit * 3) / 4)
                return intArrayOf(0, 0, third, third + 1)
            }

            if (accurate()) {
                val fourth = roll(maxHit / 4, (maxHit * 5) / 4)
                return intArrayOf(0, 0, 0, fourth)
            }

            val chipDamage = random.of(maxExclusive = 3) != 0
            return if (chipDamage) intArrayOf(0, 0, 1, 1) else intArrayOf(0, 0, 0, 0)
        }
    }
}
