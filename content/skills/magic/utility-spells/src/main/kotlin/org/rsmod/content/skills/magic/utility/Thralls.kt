package org.rsmod.content.skills.magic.utility

import jakarta.inject.Inject
import kotlin.math.min
import org.rsmod.api.combat.commons.PlayerAttackCheck
import org.rsmod.api.combat.commons.PlayerAttackTarget
import org.rsmod.api.combat.manager.MagicRuneManager
import org.rsmod.api.config.refs.objs
import org.rsmod.api.config.refs.params
import org.rsmod.api.config.refs.stats
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.npc.events.NpcHitEvents
import org.rsmod.api.npc.hit.modifier.NpcHitModifier
import org.rsmod.api.npc.hit.queueHit
import org.rsmod.api.npc.isValidTarget
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.player.stat.magicLvl
import org.rsmod.api.player.stat.prayerLvl
import org.rsmod.api.player.vars.intVarBit
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.world.WorldRepository
import org.rsmod.api.route.BoundValidator
import org.rsmod.api.route.RayCastValidator
import org.rsmod.api.route.RouteFactory
import org.rsmod.api.route.StepFactory
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onIfOverlayButton
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.script.onPlayerLogout
import org.rsmod.api.spells.MagicSpellRegistry
import org.rsmod.api.type.builders.proj.ProjAnimBuilder
import org.rsmod.api.type.refs.comp.ComponentReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.proj.ProjAnimReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.api.type.refs.spot.SpotanimReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.events.EventBus
import org.rsmod.game.MapClock
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.PathingEntity
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.npc.NpcMode
import org.rsmod.game.hit.HitType
import org.rsmod.game.interact.InteractionOp
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.Wearpos
import org.rsmod.game.type.seq.SeqType
import org.rsmod.game.type.spot.SpotanimType
import org.rsmod.game.type.synth.SynthType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/** Greater thralls: two ticks per current Magic level, with independent four-tick attacks. */
class Thralls
@Inject
constructor(
    private val protectedAccess: ProtectedAccessLauncher,
    private val spells: MagicSpellRegistry,
    private val runes: MagicRuneManager,
    private val npcTypes: NpcTypeList,
    private val npcs: NpcRepository,
    private val world: WorldRepository,
    private val routes: RouteFactory,
    private val steps: StepFactory,
    private val bounds: BoundValidator,
    private val rays: RayCastValidator,
    private val hitModifier: NpcHitModifier,
    private val random: GameRandom,
    private val events: EventBus,
    private val clock: MapClock,
) : PluginScript() {
    private val players = mutableMapOf<Player, State>()

    override fun ScriptContext.startup() {
        onIfOverlayButton(thrall_components.ghost) {
            protectedAccess.launch(player) { cast(Style.Magic, objs.spell_resurrect_greater_ghost) }
        }
        onIfOverlayButton(thrall_components.skeleton) {
            protectedAccess.launch(player) {
                cast(Style.Ranged, objs.spell_resurrect_greater_skeleton)
            }
        }
        onIfOverlayButton(thrall_components.zombie) {
            protectedAccess.launch(player) {
                cast(Style.Melee, objs.spell_resurrect_greater_zombie)
            }
        }
        onEvent<PlayerAttackTarget> {
            val state = players.getOrPut(player, ::State)
            state.target = target
            state.targetExpiry = clock.cycle + 12
        }
        for (type in listOf(thrall_npcs.ghost, thrall_npcs.skeleton, thrall_npcs.zombie)) {
            onEvent<NpcHitEvents.Modify>(type.id) { hit.damage = 0 }
        }
        onEvent<GameLifecycle.LateCycle> { process() }
        onPlayerLogin { clear(player) }
        onPlayerLogout { clear(player) }
    }

    private fun ProtectedAccess.cast(style: Style, obj: ObjType) {
        val state = players.getOrPut(player, ::State)
        if (clock.cycle < state.cooldown) {
            mes("You can only cast resurrection spells once every 10 seconds.")
            return
        }
        val spell = checkNotNull(spells.getObjSpell(obj))
        if (!runes.canCastSpell(player, spell)) return
        val book = thrall_objs.book
        if (book !in player.inv && player.worn[Wearpos.LeftHand.slot]?.id != book.id) {
            mes("You need to have the Book of the dead with you to resurrect a thrall.")
            return
        }
        if (player.prayerLvl < PRAYER_COST) {
            mes("You need at least 6 Prayer points to resurrect a greater thrall.")
            return
        }
        if (runes.attemptCast(player, spell) !is MagicRuneManager.CastResult.Success) return

        dismiss(player, state)
        statSub(stats.prayer, PRAYER_COST, 0)
        val npc = Npc(npcTypes[style.npc], player.coords)
        npc.mode = NpcMode.None
        npc.setHunt(0)
        npc.aiTimer = 0
        npc.hideAllOps()
        npcs.add(npc, Int.MAX_VALUE)
        npc.respawns = false
        npc.anim(style.spawn)
        npc.spotanim(style.impact)
        world.soundArea(npc, style.summonSound, radius = 10)
        state.thrall = npc
        state.style = style
        // Apply the doubled duration from master Combat Achievements to every player.
        state.expiry = clock.cycle + player.magicLvl * 2
        state.nextAttack = clock.cycle + 4
        state.cooldown = clock.cycle + 16
        state.wanderCentre = npc.coords
        state.nextWander = clock.cycle + 4
        player.thrallActive = 1
        player.thrallCooldown = 1
        anim(thrall_seqs.cast)
        spotanim(style.cast)
        statAdvance(stats.magic, spell.castXp)
    }

    private fun process() {
        for ((player, state) in players) {
            if (clock.cycle >= state.cooldown) player.thrallCooldown = 0
            val npc = state.thrall ?: continue
            if (
                !npc.isSlotAssigned ||
                    clock.cycle >= state.expiry ||
                    player.hitpoints <= 0 ||
                    player.coords.level != npc.coords.level ||
                    npc.distanceTo(player) > 32
            ) {
                dismiss(player, state)
                continue
            }
            val target =
                state.target?.takeIf {
                    clock.cycle < state.targetExpiry &&
                        it !== npc &&
                        it.isValidTarget() &&
                        it.visType.hasOp(InteractionOp.Op2) &&
                        it.visType.param(params.thrall_immunity) == 0 &&
                        it.coords.level == npc.coords.level &&
                        npc.distanceTo(it) <= 32
                }
            if (target == null) {
                state.target = null
                wander(npc, state)
                continue
            }
            state.wanderCentre = null
            val style = state.style
            npc.faceNpc(target)
            val inRange =
                if (style == Style.Melee) {
                    bounds.touches(npc.avatar, target.avatar)
                } else {
                    npc.distanceTo(target) <= 6 &&
                        rays.hasLineOfSight(
                            npc.coords,
                            target.coords,
                            destWidth = target.size,
                            destLength = target.size,
                        )
                }
            if (!inRange) {
                follow(npc, target)
                continue
            }
            npc.abortRoute()
            if (clock.cycle < state.nextAttack) continue
            state.nextAttack = clock.cycle + 4
            val check = PlayerAttackCheck(player, target, style.hitType, isThrall = true)
            events.publish(check)
            npc.anim(style.attack)
            val delay =
                when (style) {
                    Style.Melee -> 1
                    Style.Ranged ->
                        world
                            .projAnim(npc, target, thrall_spots.ranged, thrall_projs.ranged)
                            .durations
                            .serverDelay
                    Style.Magic ->
                        world
                            .projAnim(npc, target, thrall_spots.magic, thrall_projs.magic)
                            .durations
                            .serverDelay
                }
            // Attribute damage and kill credit to the owner without XP, accuracy, or retaliation.
            val damage = if (check.allowed) random.of(0..3) else 0
            val modifier = NpcHitModifier { target ->
                isThrall = true
                with(hitModifier) { modify(target) }
                if (!check.allowed) this.damage = 0
            }
            val hit = target.queueHit(player, delay, style.hitType, damage, modifier)
            target.heroPoints(player, min(hit.damage, target.hitpoints))
        }
    }

    private fun follow(npc: Npc, target: PathingEntity) {
        npc.abortRoute()
        val collision = npc.collisionStrategy ?: return
        npc.moveSpeed = npc.defaultMoveSpeed
        for (waypoint in routes.create(npc.avatar, target.avatar, collision)) {
            npc.routeDestination.add(CoordGrid(waypoint.x, waypoint.z, waypoint.level))
        }
    }

    private fun wander(npc: Npc, state: State) {
        val centre =
            state.wanderCentre
                ?: npc.coords.also {
                    state.wanderCentre = it
                    state.nextWander = clock.cycle + 4
                    npc.abortRoute()
                    npc.resetFaceEntity()
                }
        if (clock.cycle < state.nextWander) return
        state.nextWander = clock.cycle + random.of(4..8)
        val destination = random.of(centre, radius = 2)
        if (destination == npc.coords) return
        val collision = npc.collisionStrategy ?: return
        val step = steps.validated(npc, npc.coords, destination, collision)
        if (step != CoordGrid.NULL && step.chebyshevDistance(centre) <= 2) npc.walk(step)
    }

    private fun dismiss(player: Player, state: State) {
        state.thrall?.takeIf { it.isSlotAssigned }?.let { npcs.del(it, Int.MAX_VALUE) }
        state.thrall = null
        player.thrallActive = 0
    }

    private fun clear(player: Player) {
        players.remove(player)?.let { dismiss(player, it) }
        player.thrallActive = 0
        player.thrallCooldown = 0
    }

    private class State {
        var target: Npc? = null
        var targetExpiry = 0
        var thrall: Npc? = null
        var style = Style.Magic
        var expiry = 0
        var cooldown = 0
        var nextAttack = 0
        var wanderCentre: CoordGrid? = null
        var nextWander = 0
    }

    private enum class Style(
        val hitType: HitType,
        val npc: NpcType,
        val spawn: SeqType,
        val attack: SeqType,
        val cast: SpotanimType,
        val impact: SpotanimType,
        val summonSound: SynthType,
    ) {
        Melee(
            HitType.Melee,
            thrall_npcs.zombie,
            thrall_seqs.zombieSpawn,
            thrall_seqs.zombieAttack,
            thrall_spots.zombieCast,
            thrall_spots.zombieSpawn,
            thrall_synths.zombie,
        ),
        Ranged(
            HitType.Ranged,
            thrall_npcs.skeleton,
            thrall_seqs.skeletonSpawn,
            thrall_seqs.skeletonAttack,
            thrall_spots.skeletonCast,
            thrall_spots.skeletonSpawn,
            thrall_synths.skeleton,
        ),
        Magic(
            HitType.Magic,
            thrall_npcs.ghost,
            thrall_seqs.ghostSpawn,
            thrall_seqs.ghostAttack,
            thrall_spots.ghostCast,
            thrall_spots.ghostSpawn,
            thrall_synths.ghost,
        ),
    }

    private companion object {
        const val PRAYER_COST = 6
        var Player.thrallActive by intVarBit(thrall_varbits.active)
        var Player.thrallCooldown by intVarBit(thrall_varbits.cooldown)
    }
}

internal object thrall_synths {
    // Cache sounds absent from the synth symbol table; IDs from the spell audio metadata.
    // https://oldschool.runescape.wiki/w/File:Resurrect_Greater_Ghost.ogg
    // https://oldschool.runescape.wiki/w/File:Resurrect_Greater_Skeleton.ogg
    // https://oldschool.runescape.wiki/w/File:Resurrect_Greater_Zombie.ogg
    val ghost = SynthType(5063, "resurrect_greater_ghost_cast_and_fire")
    val skeleton = SynthType(5048, "resurrect_greater_skele_cast_and_fire")
    val zombie = SynthType(5058, "resurrect_greater_zombie_cast_and_fire")
}

object thrall_components : ComponentReferences() {
    val ghost = find("magic_spellbook:resurrect_greater_ghost")
    val skeleton = find("magic_spellbook:resurrect_greater_skeleton")
    val zombie = find("magic_spellbook:resurrect_greater_zombie")
}

internal object thrall_npcs : NpcReferences() {
    val ghost = find("arceuus_thrall_ghost_greater")
    val skeleton = find("arceuus_thrall_skeleton_greater")
    val zombie = find("arceuus_thrall_zombie_greater")
}

internal object thrall_objs : ObjReferences() {
    val book = find("book_of_the_dead")
}

object thrall_varbits : VarBitReferences() {
    val active = find("arceuus_resurrection_active")
    val cooldown = find("arceuus_resurrection_cooldown")
}

internal object thrall_seqs : SeqReferences() {
    val cast = find("human_spellcast_resurrect")
    val ghostSpawn = find("ghost_update_thrall_spawn")
    val skeletonSpawn = find("skeleton_update_thrall_spawn")
    val zombieSpawn = find("zombie_update_thrawl_spawn")
    // Match the tendril ghost and champion skeleton models used by the native thrall NPCs.
    val ghostAttack = find("ghost_update_tendrill_attack")
    val skeletonAttack = find("skeleton_update_champion_attack")
    val zombieAttack = find("zombie_update_attack_normal")
}

internal object thrall_spots : SpotanimReferences() {
    val ghostCast = find("resurrect_ghost_cast_spotanim")
    val skeletonCast = find("resurrect_skeleton_cast_spotanim")
    val zombieCast = find("resurrect_zombie_cast_spotanim")
    val ghostSpawn = find("resurrect_ghost_impact_spotanim")
    val skeletonSpawn = find("resurrect_skeleton_impact_spotanim")
    val zombieSpawn = find("resurrect_zombie_impact_spotanim")
    val ranged = find("thrall_ranged_travel")
    val magic = find("thrall_magic_travel")
}

internal object thrall_projs : ProjAnimReferences() {
    val ranged = find("thrall_ranged")
    val magic = find("thrall_magic")
}

internal object ThrallProjBuilds : ProjAnimBuilder() {
    init {
        // The ghost releases its projectile one tick before the skeleton.
        build("thrall_magic") {
            startHeight = 120
            endHeight = 100
            delay = 10
            angle = 0
            stepMultiplier = 5
        }
        build("thrall_ranged") {
            startHeight = 120
            endHeight = 100
            delay = 40
            angle = 15
            stepMultiplier = 5
        }
    }
}
