package org.rsmod.content.other.special.weapons.magic

import jakarta.inject.Inject
import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.combat.manager.CombatChargeManager
import org.rsmod.api.config.refs.synths
import org.rsmod.api.obj.charges.ObjChargeManager.Companion.isFailure
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.magicLvl
import org.rsmod.api.weapons.MagicWeapon
import org.rsmod.api.weapons.WeaponAttackManager
import org.rsmod.api.weapons.WeaponMap
import org.rsmod.api.weapons.WeaponRepository
import org.rsmod.content.other.special.weapons.configs.EyeOfAyakProjs
import org.rsmod.content.other.special.weapons.configs.EyeOfAyakSeqs
import org.rsmod.content.other.special.weapons.configs.EyeOfAyakSpots
import org.rsmod.content.other.special.weapons.configs.EyeOfAyakVars
import org.rsmod.content.other.special.weapons.configs.special_weapon_objs
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.PathingEntity
import org.rsmod.game.entity.Player

class EyeOfAyakWeapons @Inject constructor(private val charges: CombatChargeManager) : WeaponMap {
    override fun WeaponRepository.register(manager: WeaponAttackManager) {
        register(special_weapon_objs.eye_of_ayak, EyeOfAyak(manager, charges))
        register(special_weapon_objs.eye_of_ayak_uncharged, UnchargedEyeOfAyak(manager))
    }

    private class EyeOfAyak(
        private val manager: WeaponAttackManager,
        private val charges: CombatChargeManager,
    ) : MagicWeapon {
        override suspend fun ProtectedAccess.attack(
            target: Npc,
            attack: CombatAttack.Staff,
        ): Boolean {
            cast(target, attack)
            return true
        }

        override suspend fun ProtectedAccess.attack(
            target: Player,
            attack: CombatAttack.Staff,
        ): Boolean {
            cast(target, attack)
            return true
        }

        private fun ProtectedAccess.cast(target: PathingEntity, attack: CombatAttack.Staff) {
            val chargeResult = charges.attemptDetractWeapon(player, EyeOfAyakVars.charges)
            if (chargeResult.isFailure()) {
                mes("Your Eye of ayak has no charges.")
                manager.stopCombat(this)
                return
            }
            manager.setNextAttackDelay(this, 3)
            anim(EyeOfAyakSeqs.cast)
            spotanim(EyeOfAyakSpots.cast)
            val projectile =
                manager.spawnProjectile(this, target, EyeOfAyakSpots.travel, EyeOfAyakProjs.travel)
            val (serverDelay, clientDelay) = projectile.durations
            // Eye uses the ancient shadow cast sound (178), not Tumeken's Shadow (6410).
            // https://oldschool.runescape.wiki/w/List_of_in-game_sound_IDs
            val castSound = synths.shadow_cast

            if (manager.rollStaffSplash(this, target, attack)) {
                manager.playSplashFx(
                    this,
                    target,
                    clientDelay,
                    castSound = castSound,
                    soundRadius = 10,
                )
                manager.queueSplashHit(this, target, clientDelay, serverDelay)
            } else {
                val baseMaxHit = (player.magicLvl / 3) - 6
                val damage = manager.rollStaffMaxHit(this, target, baseMaxHit.coerceAtLeast(0))
                manager.playMagicHitFx(
                    this,
                    target,
                    clientDelay,
                    castSound = castSound,
                    soundRadius = 10,
                    hitSpot = EyeOfAyakSpots.impact,
                    hitSpotHeight = 124,
                    hitSound = null,
                )
                manager.giveCombatXp(this, target, attack, damage)
                manager.queueMagicHit(this, target, damage, clientDelay, serverDelay)
            }

            if (chargeResult.fullyUncharged) {
                mes("Your Eye of ayak has run out of charges.")
                manager.stopCombat(this)
            } else {
                manager.continueCombat(this, target)
            }
        }
    }

    private class UnchargedEyeOfAyak(private val manager: WeaponAttackManager) : MagicWeapon {
        override suspend fun ProtectedAccess.attack(
            target: Npc,
            attack: CombatAttack.Staff,
        ): Boolean {
            stop()
            return true
        }

        override suspend fun ProtectedAccess.attack(
            target: Player,
            attack: CombatAttack.Staff,
        ): Boolean {
            stop()
            return true
        }

        private fun ProtectedAccess.stop() {
            mes(
                "Your Eye of ayak has no charges. Charge it with death and chaos runes or demon tears."
            )
            manager.stopCombat(this)
        }
    }
}
