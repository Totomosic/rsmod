package org.rsmod.content.other.special.attacks.melee

import jakarta.inject.Inject
import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.combat.commons.PlayerSpecialAttackCheck
import org.rsmod.api.combat.commons.types.MeleeAttackType
import org.rsmod.api.config.refs.areas
import org.rsmod.api.config.refs.stats
import org.rsmod.api.hunt.NpcSearch
import org.rsmod.api.npc.isValidTarget
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.righthand
import org.rsmod.api.player.stat.stat
import org.rsmod.api.player.stat.statSub
import org.rsmod.api.route.RayCastValidator
import org.rsmod.api.specials.SpecialAttackManager
import org.rsmod.api.specials.SpecialAttackMap
import org.rsmod.api.specials.SpecialAttackRepository
import org.rsmod.api.specials.combat.MeleeSpecialAttack
import org.rsmod.content.other.special.attacks.configs.SpecialAttackSynths
import org.rsmod.content.other.special.attacks.configs.special_objs
import org.rsmod.content.other.special.attacks.configs.special_seqs
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.PathingEntity
import org.rsmod.game.entity.Player
import org.rsmod.game.interact.InteractionOp
import org.rsmod.game.type.hunt.HuntVis

/** Shield Bash: a primary hit and a multi-combat sweep, with 25% increased accuracy. */
class DinhsBulwarkSpecialAttack
@Inject
constructor(
    private val search: NpcSearch,
    private val rays: RayCastValidator,
    private val eventBus: EventBus,
) : SpecialAttackMap {
    override fun SpecialAttackRepository.register(manager: SpecialAttackManager) {
        val special = DinhsBulwark(manager, search, rays, eventBus)
        registerMelee(special_objs.dinhs_bulwark, special)
        registerMelee(special_objs.dinhs_blazing_bulwark, special)
    }

    private class DinhsBulwark(
        private val manager: SpecialAttackManager,
        private val search: NpcSearch,
        private val rays: RayCastValidator,
        private val eventBus: EventBus,
    ) : MeleeSpecialAttack {
        override suspend fun ProtectedAccess.attack(
            target: Npc,
            attack: CombatAttack.Melee,
        ): Boolean {
            anim(special_seqs.dinhs_bulwark)
            manager.soundArea(player, SpecialAttackSynths.bulwark, radius = 10)

            if (
                coords.level != target.coords.level ||
                    !rays.hasLineOfSight(coords, target.coords) ||
                    !canHit(target)
            ) {
                manager.continueCombat(this, target)
                return true
            }

            // Reserve one of the ten sweep slots for the primary target, even in a crowded area.
            val sweep =
                if (mapMultiway() && inArea(areas.multiway, target.coords)) {
                    listOf(target) +
                        search
                            .findAllAny(coords, distance = 5, vis = HuntVis.LineOfSight)
                            .filter {
                                it != target &&
                                    it.isValidTarget() &&
                                    it.visType.hasOp(InteractionOp.Op2) &&
                                    inArea(areas.multiway, it.coords) &&
                                    canHit(it)
                            }
                            .take(9)
                            .toList()
                } else {
                    emptyList()
                }

            val first = rollBash(target, attack)
            val second = if (sweep.isNotEmpty()) rollBash(target, attack) else 0
            manager.giveCombatXp(this, target, attack, first + second)
            manager.queueMeleeHit(this, target, first)
            if (sweep.isNotEmpty()) manager.queueMeleeHit(this, target, second)
            if (first + second > 0) drainOffence(target)

            for (npc in sweep.drop(1)) {
                val damage = rollBash(npc, attack)
                manager.giveCombatXp(this, npc, attack, damage)
                manager.queueMeleeHit(this, npc, damage)
                if (damage > 0) drainOffence(npc)
            }
            manager.continueCombat(this, target)
            return true
        }

        private fun ProtectedAccess.canHit(target: Npc): Boolean {
            val check = PlayerSpecialAttackCheck(player, target, checkNotNull(player.righthand))
            eventBus.publish(check)
            return check.allowed
        }

        override suspend fun ProtectedAccess.attack(
            target: Player,
            attack: CombatAttack.Melee,
        ): Boolean {
            anim(special_seqs.dinhs_bulwark)
            manager.soundArea(player, SpecialAttackSynths.bulwark, radius = 10)
            val damage = rollBash(target, attack)
            manager.giveCombatXp(this, target, attack, damage)
            manager.queueMeleeHit(this, target, damage)
            if (damage > 0) {
                val drained =
                    drainedStats(
                        target.stat(stats.attack),
                        target.stat(stats.strength),
                        target.stat(stats.ranged),
                        target.stat(stats.magic),
                    )
                for (stat in drained) {
                    target.statSub(stat, constant = target.stat(stat) * 5 / 100, percent = 0)
                }
            }
            manager.continueCombat(this, target)
            return true
        }

        private fun ProtectedAccess.rollBash(
            target: PathingEntity,
            attack: CombatAttack.Melee,
        ): Int =
            manager.rollMeleeDamage(
                source = this,
                target = target,
                attack = attack,
                accuracyMultiplier = 1.25,
                maxHitMultiplier = 1.0,
                blockType = MeleeAttackType.Crush,
            )

        private fun drainOffence(npc: Npc) {
            val drained = drainedStats(npc.attackLvl, npc.strengthLvl, npc.rangedLvl, npc.magicLvl)
            for (stat in drained) {
                when (stat) {
                    stats.attack -> npc.attackLvl -= npc.attackLvl * 5 / 100
                    stats.strength -> npc.strengthLvl -= npc.strengthLvl * 5 / 100
                    stats.ranged -> npc.rangedLvl -= npc.rangedLvl * 5 / 100
                    stats.magic -> npc.magicLvl -= npc.magicLvl * 5 / 100
                    else -> error("Unexpected offensive stat: $stat")
                }
            }
        }

        // Melee drains both Attack and Strength; ties prefer melee, then Ranged, then Magic.
        private fun drainedStats(attack: Int, strength: Int, ranged: Int, magic: Int) =
            when {
                maxOf(attack, strength) >= maxOf(ranged, magic) ->
                    listOf(stats.attack, stats.strength)
                ranged >= magic -> listOf(stats.ranged)
                else -> listOf(stats.magic)
            }
    }
}
