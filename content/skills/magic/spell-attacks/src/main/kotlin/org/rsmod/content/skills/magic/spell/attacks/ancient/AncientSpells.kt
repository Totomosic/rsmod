package org.rsmod.content.skills.magic.spell.attacks.ancient

import jakarta.inject.Inject
import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.combat.manager.CombatSplashTargets
import org.rsmod.api.combat.manager.MagicRuneManager.Companion.isFailure
import org.rsmod.api.config.refs.objs
import org.rsmod.api.config.refs.projanims
import org.rsmod.api.config.refs.seqs
import org.rsmod.api.config.refs.spotanims
import org.rsmod.api.config.refs.stats
import org.rsmod.api.config.refs.synths
import org.rsmod.api.freeze.freeze
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.random.GameRandom
import org.rsmod.api.spells.attack.SpellAttack
import org.rsmod.api.spells.attack.SpellAttackManager
import org.rsmod.api.spells.attack.SpellAttackMap
import org.rsmod.api.spells.attack.SpellAttackRepository
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.PathingEntity
import org.rsmod.game.entity.Player
import org.rsmod.game.proj.ProjAnim
import org.rsmod.game.type.proj.ProjAnimTypeList
import org.rsmod.game.type.seq.SeqType
import org.rsmod.game.type.spot.SpotanimType
import org.rsmod.game.type.synth.SynthType

class AncientSpells
@Inject
constructor(
    private val random: GameRandom,
    private val projTypes: ProjAnimTypeList,
    private val splashTargets: CombatSplashTargets,
) : SpellAttackMap {
    override fun SpellAttackRepository.register(manager: SpellAttackManager) {
        register(
            objs.spell_ice_blitz,
            Attack(
                manager,
                random,
                projTypes,
                castAnim = seqs.zaros_casting,
                castSound = synths.ice_cast,
                castSpot = spotanims.ice_blitz_travel,
                impactSpot = spotanims.ice_blitz_impact,
                hitSound = synths.ice_blitz_impact,
                freezeCycles = 24,
            ),
        )
        register(
            objs.spell_ice_barrage,
            Attack(
                manager,
                random,
                projTypes,
                castAnim = seqs.zaros_vertical_casting,
                castSound = synths.ice_cast,
                castSpot = spotanims.ice_barrage_travel,
                impactSpot = spotanims.ice_barrage_impact,
                hitSound = synths.ice_barrage_impact,
                freezeCycles = 32,
                splashTargets = splashTargets,
            ),
        )
        register(
            objs.spell_blood_barrage,
            Attack(
                manager,
                random,
                projTypes,
                castAnim = seqs.zaros_vertical_casting,
                castSpot = null,
                castSound = synths.blood_cast,
                impactSpot = spotanims.spell_blood_barrage_impact,
                hitSound = synths.blood_barrage_impact,
                splashTargets = splashTargets,
                healFromDamage = true,
            ),
        )
    }

    private class Attack(
        private val manager: SpellAttackManager,
        private val random: GameRandom,
        private val projTypes: ProjAnimTypeList,
        private val castAnim: SeqType,
        private val castSound: SynthType,
        private val castSpot: SpotanimType?,
        private val impactSpot: SpotanimType,
        private val hitSound: SynthType,
        private val freezeCycles: Int = 0,
        private val splashTargets: CombatSplashTargets? = null,
        private val healFromDamage: Boolean = false,
    ) : SpellAttack {
        override suspend fun ProtectedAccess.attack(target: Npc, attack: CombatAttack.Spell) {
            cast(target, attack)
        }

        override suspend fun ProtectedAccess.attack(target: Player, attack: CombatAttack.Spell) {
            cast(target, attack)
        }

        private fun ProtectedAccess.cast(primary: PathingEntity, attack: CombatAttack.Spell) {
            val castResult = manager.attemptCast(this, attack)
            if (castResult.isFailure()) {
                return
            }

            // Capture the entire square at launch, even when the primary's accuracy roll misses.
            val targets = splashTargets?.find(this, primary) ?: listOf(primary)
            anim(castAnim)
            // Play once per cast, including a splash, rather than once per AoE victim.
            manager.soundArea(player, castSound)
            // Ancient spell travel effects play around the caster rather than flying to the target.
            if (castSpot != null) {
                spotanim(castSpot)
            }
            val clientDelay =
                ProjAnim.calculateEndTime(
                    projTypes[projanims.magic_spell_low],
                    player.distanceTo(primary),
                )
            val hitDelay = ProjAnim.calculateHitDelay(clientDelay)
            val spell = attack.spell.obj
            var totalRolledDamage = 0

            for (target in targets) {
                if (manager.rollSplash(this, target, attack, castResult)) {
                    manager.playSplashFx(
                        this,
                        target,
                        clientDelay,
                        castSound = null,
                        soundRadius = 8,
                    )
                    manager.queueSplashHit(this, target, spell, clientDelay, hitDelay)
                    continue
                }

                // Ice effects apply only to accurate targets, immediately at launch.
                if (freezeCycles > 0) {
                    target.freeze(cycles = freezeCycles, random = random, source = player)
                }
                val damage =
                    manager.rollMaxHit(this, target, attack, castResult, attack.spell.maxHit)
                totalRolledDamage += damage
                manager.playHitFx(
                    source = this,
                    target = target,
                    clientDelay = clientDelay,
                    castSound = null,
                    soundRadius = 8,
                    hitSpot = impactSpot,
                    hitSpotHeight = 0,
                    hitSound = hitSound,
                )
                manager.giveCombatXp(this, target, attack, damage)
                manager.queueMagicHit(this, target, spell, damage, clientDelay, hitDelay)
            }
            // Sum successful damage rolls before the hit processors clamp them to target HP.
            // Round once for the whole cast, rather than losing fractional healing per target.
            val healing = totalRolledDamage / 4
            if (healFromDamage && healing > 0) {
                statHeal(stats.hitpoints, constant = healing, percent = 0)
            }
            manager.continueCombatIfAutocast(this, primary)
        }
    }
}
