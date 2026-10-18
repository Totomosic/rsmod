package org.rsmod.content.other.special.weapons.melee

import jakarta.inject.Inject
import kotlin.math.abs
import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.combat.commons.rollDamage
import org.rsmod.api.config.refs.areas
import org.rsmod.api.hunt.NpcSearch
import org.rsmod.api.npc.isValidTarget
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
import org.rsmod.game.hit.HitType
import org.rsmod.game.interact.InteractionOp
import org.rsmod.game.type.hunt.HuntVis

class ScytheOfViturWeapons
@Inject
constructor(private val random: GameRandom, private val search: NpcSearch) : WeaponMap {
    override fun WeaponRepository.register(manager: WeaponAttackManager) {
        val scythe = ScytheOfVitur(manager, random, search)
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
        private val search: NpcSearch,
    ) : MeleeWeapon {
        override suspend fun ProtectedAccess.attack(
            target: Npc,
            attack: CombatAttack.Melee,
        ): Boolean {
            hit(target, attack, hitCount = target.size.coerceIn(1, 3))
            for ((index, npc) in sweepTargets(target).withIndex()) {
                hit(npc, attack, hitCount = 1, firstHit = index + 1)
            }
            manager.playWeaponFx(this, attack)
            manager.continueCombat(this, target)
            return true
        }

        override suspend fun ProtectedAccess.attack(
            target: Player,
            attack: CombatAttack.Melee,
        ): Boolean {
            hit(target, attack, hitCount = 1)
            manager.playWeaponFx(this, attack)
            manager.continueCombat(this, target)
            return true
        }

        private fun ProtectedAccess.sweepTargets(target: Npc): List<Npc> {
            if (target.size != 1 || !mapMultiway() || !inArea(areas.multiway, target.coords)) {
                return emptyList()
            }
            val dx = target.coords.x - coords.x
            val dz = target.coords.z - coords.z
            if (target.coords.level != coords.level || abs(dx) + abs(dz) != 1) {
                return emptyList()
            }

            val candidates =
                search
                    .findAllAny(coords, distance = 1, vis = HuntVis.LineOfSight)
                    .filter {
                        it != target &&
                            it.size == 1 &&
                            it.isValidTarget() &&
                            it.visType.hasOp(InteractionOp.Op2) &&
                            inArea(areas.multiway, it.coords)
                    }
                    .toList()

            // Jagex describes a 1x3 arc and 100%/50%/25% hits, but does not specify sides:
            // https://secure.runescape.com/m=news/dev-blog-theatre-of-blood?oldschool=1
            // TODO: Verify OSRS target ordering. After the selected target, prefer its tile,
            // then left, then right. This is a local assumption, not a confirmed OSRS rule.
            // Stacked NPCs can fill both extra hits; empty/ineligible tiles consume no hit.
            val left = target.coords.translate(-dz, dx)
            val right = target.coords.translate(dz, -dx)
            return listOf(target.coords, left, right)
                .asSequence()
                .flatMap { tile -> candidates.asSequence().filter { it.coords == tile } }
                .take(2)
                .toList()
        }

        private fun ProtectedAccess.hit(
            target: PathingEntity,
            attack: CombatAttack.Melee,
            hitCount: Int,
            firstHit: Int = 0,
        ) {
            var maxHit =
                manager.calculateMeleeMaxHit(
                    this,
                    target,
                    attack.type,
                    attack.style,
                    multiplier = 1.0,
                ) / (1 shl firstHit)
            val damage =
                IntArray(hitCount) {
                    val rolled = rollDamage(target, attack, maxHit)
                    maxHit /= 2
                    rolled
                }

            for (splat in damage) {
                manager.giveCombatXp(this, target, attack, splat)
                manager.queueMeleeHit(this, target, splat, delay = 1)
            }
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
            if (!accurate || maxHit <= 0) return 0
            return if (manager.alwaysMaxHit(this, target, HitType.Melee)) maxHit
            else damageRandom.rollDamage(maxHit, target)
        }
    }
}
