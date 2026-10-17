package org.rsmod.content.other.special.weapons.ranged

import jakarta.inject.Inject
import kotlin.math.abs
import kotlin.math.min
import org.rsmod.api.area.checker.WildernessArea
import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.combat.commons.ranged.RangedAmmunition
import org.rsmod.api.config.constants
import org.rsmod.api.config.refs.areas
import org.rsmod.api.config.refs.objs
import org.rsmod.api.config.refs.params
import org.rsmod.api.hunt.NpcSearch
import org.rsmod.api.hunt.PlayerSearch
import org.rsmod.api.npc.isValidTarget
import org.rsmod.api.player.isValidTarget
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.righthand
import org.rsmod.api.weapons.RangedWeapon
import org.rsmod.api.weapons.WeaponAttackManager
import org.rsmod.api.weapons.WeaponMap
import org.rsmod.api.weapons.WeaponRepository
import org.rsmod.content.other.special.weapons.configs.BlackChinchompaSynths
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.PathingEntity
import org.rsmod.game.entity.Player
import org.rsmod.game.interact.InteractionOp
import org.rsmod.game.type.hunt.HuntVis
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.obj.Wearpos

class BlackChinchompaWeapons
@Inject
constructor(
    private val objTypes: ObjTypeList,
    private val npcs: NpcSearch,
    private val players: PlayerSearch,
    private val eventBus: EventBus,
) : WeaponMap {
    override fun WeaponRepository.register(manager: WeaponAttackManager) {
        register(objs.black_chinchompa, BlackChinchompa(manager, objTypes, npcs, players, eventBus))
    }

    private class BlackChinchompa(
        private val manager: WeaponAttackManager,
        private val objTypes: ObjTypeList,
        private val npcs: NpcSearch,
        private val players: PlayerSearch,
        private val eventBus: EventBus,
    ) : RangedWeapon {
        override suspend fun ProtectedAccess.attack(
            target: Npc,
            attack: CombatAttack.Ranged,
        ): Boolean {
            throwChinchompa(target, attack)
            return true
        }

        override suspend fun ProtectedAccess.attack(
            target: Player,
            attack: CombatAttack.Ranged,
        ): Boolean {
            throwChinchompa(target, attack)
            return true
        }

        private fun ProtectedAccess.throwChinchompa(
            primary: PathingEntity,
            attack: CombatAttack.Ranged,
        ) {
            val weaponType = objTypes[attack.weapon]
            val travel = weaponType.param(params.proj_travel)
            val projectileType = weaponType.param(params.proj_type)

            // Materialize the search at launch: movement during flight cannot change the victims.
            val targets = listOf(primary) + splashTargets(primary)
            manager.playWeaponFx(this, attack)
            spotanim(
                weaponType.paramOrNull(params.proj_launch),
                height = 96,
                slot = constants.spotanim_slot_combat,
            )
            val projectile = manager.spawnProjectile(this, primary, travel, projectileType)
            val (serverDelay, clientDelay) = projectile.durations
            manager.soundArea(
                primary,
                BlackChinchompaSynths.explode,
                delay = clientDelay,
                radius = 10,
            )

            val accurate =
                manager.rollRangedAccuracy(
                    this,
                    primary,
                    attack.type,
                    attack.style,
                    attack.type,
                    1.0,
                )
            for (target in targets) {
                val damage =
                    if (accurate) {
                        manager.rollRangedMaxHit(this, target, attack.type, attack.style, 1.0, 0)
                    } else {
                        0
                    }
                manager.giveCombatXp(this, target, attack, damage)
                manager.queueRangedHit(this, target, null, damage, clientDelay, serverDelay)
            }

            // Chinchompas explode. Consume one per throw, without recovery or a ground drop.
            // Keep it equipped until all rolls and hit snapshots include the final chinchompa.
            RangedAmmunition.detractAmmo(player, Wearpos.RightHand, weaponType, 1, eventBus)
            if (player.righthand == null) {
                mes("That was your last one!")
                manager.stopCombat(this)
            } else {
                manager.continueCombat(this, primary)
            }
        }

        private fun ProtectedAccess.splashTargets(primary: PathingEntity): List<PathingEntity> {
            if (!mapMultiway() || !inArea(areas.multiway, primary.coords)) {
                return emptyList()
            }

            // Hunt tests only southwest coordinates, including stacked targets, and casts LOS
            // from the primary's southwest tile rather than the attacker's position.
            return when (primary) {
                is Npc ->
                    npcs
                        .findAllAny(primary.coords, distance = 1, vis = HuntVis.LineOfSight)
                        .filter {
                            it != primary &&
                                it.isValidTarget() &&
                                it.visType.hasOp(InteractionOp.Op2) &&
                                inArea(areas.multiway, it.coords)
                        }
                        .toList()
                is Player ->
                    players
                        .findAll(primary.coords, maxDistance = 1, vis = HuntVis.LineOfSight)
                        .filter {
                            it != primary &&
                                it != player &&
                                it.isValidTarget() &&
                                inArea(areas.multiway, it.coords) &&
                                canSplashPlayer(it)
                        }
                        .toList()
            }
        }

        private fun ProtectedAccess.canSplashPlayer(target: Player): Boolean {
            if (!WildernessArea.contains(coords) || !WildernessArea.contains(target.coords)) {
                return false
            }
            val difference = abs(player.combatLevel - target.combatLevel)
            val levels = min(WildernessArea.level(coords), WildernessArea.level(target.coords))
            return difference <= levels
        }
    }
}
