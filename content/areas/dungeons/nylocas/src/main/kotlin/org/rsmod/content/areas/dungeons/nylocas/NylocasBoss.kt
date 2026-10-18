package org.rsmod.content.areas.dungeons.nylocas

import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.rsmod.annotations.InternalApi
import org.rsmod.api.combat.commons.player.queueCombatRetaliate
import org.rsmod.api.combat.formulas.AccuracyFormulae
import org.rsmod.api.combat.formulas.MaxHitFormulae
import org.rsmod.api.config.refs.hitmark_groups
import org.rsmod.api.config.refs.params
import org.rsmod.api.config.refs.queues
import org.rsmod.api.death.NpcDeath
import org.rsmod.api.npc.access.StandardNpcAccess
import org.rsmod.api.player.clearInteractionRoute
import org.rsmod.api.player.hit.queueHit
import org.rsmod.api.player.isValidTarget
import org.rsmod.api.random.GameRandom
import org.rsmod.api.registry.region.RegionRegistry
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.route.BoundValidator
import org.rsmod.api.route.RayCastValidator
import org.rsmod.api.route.RouteFactory
import org.rsmod.api.script.onAiTimer
import org.rsmod.api.script.onNpcQueue
import org.rsmod.api.script.onNpcQueueWithArgs
import org.rsmod.game.MapClock
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.entity.npc.NpcMode
import org.rsmod.game.headbar.Headbar
import org.rsmod.game.hit.Hit
import org.rsmod.game.hit.HitType
import org.rsmod.game.interact.InteractionNpc
import org.rsmod.game.movement.MoveRestrict
import org.rsmod.game.movement.MoveSpeed
import org.rsmod.game.region.Region
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.ScriptContext

/** Normal-mode Vasilias. Timing evidence: docs/nylocas-boss-evidence.md. */
@Singleton
class NylocasBoss
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val npcs: NpcRepository,
    private val players: PlayerList,
    private val regions: RegionRegistry,
    private val clock: MapClock,
    private val random: GameRandom,
    private val bounds: BoundValidator,
    private val rays: RayCastValidator,
    private val routes: RouteFactory,
    private val accuracy: AccuracyFormulae,
    private val maxHits: MaxHitFormulae,
    private val death: NpcDeath,
    private val raid: TheatreRaid,
) {
    private val active = mutableMapOf<Npc, State>()

    fun register(context: ScriptContext) =
        with(context) {
            for (type in
                listOf(nylocas_npcs.boss_spawning) + NylocasStyle.entries.map(nylocas_npcs::boss)) {
                onAiTimer(type) { attack(npc) }
                // Check the visible form at impact, including missiles launched before a switch.
                onNpcQueueWithArgs<Hit>(type, nylocas_queues.hit) { takeHit(it.args) }
                onNpcQueue(type, queues.death) {
                    val state = active[npc] ?: return@onNpcQueue
                    death.deathNoDrops(
                        this,
                        animation = nylocas_seqs.death(state.style),
                        animationTicks = 5,
                    )
                    active.remove(npc)
                    raid.completeRoom(state.region, clock.cycle - state.roomStart)
                }
            }
        }

    fun spawn(region: Region, teamSize: Int, roomStart: Int): Npc {
        val npc = Npc(npcTypes[nylocas_npcs.boss_spawning], region.normal[CoordGrid(3294, 4247)])
        npc.mode = NpcMode.None
        npc.setHunt(0)
        npc.aiTimer = 0
        val hp =
            when (teamSize) {
                4 -> 2187
                5 -> 2500
                else -> 1875
            }
        npc.baseHitpointsLvl = hp
        npc.hitpoints = hp
        npcs.add(npc, Int.MAX_VALUE)
        npc.respawns = false
        npc.anim(nylocas_seqs.boss_spawn)
        active[npc] =
            State(region, roomStart, clock.cycle, nextSwitch = clock + 11, nextAttack = clock + 3)
        return npc
    }

    fun process() {
        val iterator = active.iterator()
        while (iterator.hasNext()) {
            val (npc, state) = iterator.next()
            if (state.region.slot == Region.INVALID_SLOT || !npc.isSlotAssigned) {
                if (npc.isSlotAssigned) npcs.del(npc, Int.MAX_VALUE)
                iterator.remove()
                continue
            }
            if (npc.hitpoints <= 0) continue
            if (!state.attackable && clock >= state.spawnCycle + 2) {
                state.attackable = true
                transform(npc, state)
                npc.aiTimer(1)
            }
            if (clock >= state.nextSwitch) {
                val alternatives = NylocasStyle.entries.filter { it != state.style }
                state.style = alternatives[random.of(0..1)]
                state.nextSwitch += 10
                state.nextAttack = clock + 2
                state.attacks = 0
                transform(npc, state)
                // A style change requires a fresh attack click, as for the wave nylocas.
                for (player in players) {
                    if ((player.interaction as? InteractionNpc)?.target != npc) continue
                    player.clearInteractionRoute()
                    player.resetFaceEntity()
                }
            }
            if (state.attackable) raid.bossHealth(state.region, npc.hitpoints, npc.baseHitpointsLvl)
        }
    }

    private fun transform(npc: Npc, state: State) {
        val type = npcTypes[nylocas_npcs.boss(state.style)]
        npc.transmog(type, Int.MAX_VALUE)
        // Visual transmogs preserve HP, but combat levels belong to the active form.
        val hp = npc.hitpoints
        val maxHp = npc.baseHitpointsLvl
        npc.copyStats(type)
        npc.hitpoints = hp
        npc.baseHitpointsLvl = maxHp
        npc.moveRestrict = MoveRestrict.Normal
        npc.walk(npc.coords)
    }

    private fun attack(npc: Npc) {
        val state = active[npc] ?: return
        if (!state.attackable || npc.hitpoints <= 0 || !npc.canProcess) return
        npc.queueList.removeAll(queues.com_retaliate_player)
        npc.noneMode()
        if (state.attacks >= 2) return
        val candidates = players.filter { validTarget(it, state.region) }
        val target =
            state.target?.takeIf { it in candidates }
                ?: candidates.takeIf { it.isNotEmpty() }?.let { it[random.of(it.indices)] }
                ?: return
        state.target = target
        npc.facePlayer(target)
        val inRange =
            when (state.style) {
                NylocasStyle.Melee -> bounds.touches(npc.avatar, target.avatar)
                else ->
                    npc.isWithinDistance(target, 8) &&
                        !bounds.collides(npc.avatar, target.avatar) &&
                        rays.hasLineOfSight(npc.coords, target.coords, npc.size, npc.size)
            }
        if (!inRange) {
            val strategy = npc.collisionStrategy ?: return
            npc.moveSpeed = MoveSpeed.Walk
            for (waypoint in routes.create(npc.avatar, target.avatar, strategy)) {
                npc.routeDestination.add(CoordGrid(waypoint.x, waypoint.z, waypoint.level))
            }
            return
        }
        if (clock < state.nextAttack) return
        val accurate =
            when (state.style) {
                NylocasStyle.Melee -> accuracy.rollMeleeAccuracy(npc, target, null, random)
                NylocasStyle.Ranged -> accuracy.rollRangedAccuracy(npc, target, random)
                NylocasStyle.Magic -> accuracy.rollMagicAccuracy(npc, target, random)
            }
        val maxHit =
            when (state.style) {
                NylocasStyle.Melee -> maxHits.getMeleeMaxHit(npc, target, null)
                NylocasStyle.Ranged -> maxHits.getRangedMaxHit(npc, target)
                NylocasStyle.Magic -> maxHits.getMagicMaxHit(npc, target)
            }
        npc.anim(nylocas_seqs.attack(state.style))
        target.queueCombatRetaliate(npc)
        target.queueHit(npc, 1, state.style.hitType, if (accurate) random.of(0..maxHit) else 0)
        state.nextAttack = clock + 4
        state.attacks++
        state.target = null
    }

    private fun validTarget(player: Player, region: Region): Boolean {
        if (
            !player.isValidTarget() ||
                !raid.isStarted(player, region) ||
                regions[player.coords]?.uid != region.uid
        )
            return false
        val source = regions.normalizeCoords(player.coords)
        return source.level == 0 && source.x in 3289..3302 && source.z in 4242..4254
    }

    @OptIn(InternalApi::class)
    private fun StandardNpcAccess.takeHit(hit: Hit) {
        val state = active[npc]
        if (state == null || !state.attackable || npc.hitpoints <= 0) return
        if (hit.type == state.style.hitType) {
            processQueuedHit(hit)
        } else {
            npc.hitpoints = (npc.hitpoints + hit.damage).coerceAtMost(npc.baseHitpointsLvl)
            val mark = hitmark_groups.heal.lit.id
            npc.showHitmark(
                hit.hitmark.copy(
                    self = mark,
                    source = mark,
                    public = if (hit.hitmark.isPrivate) null else mark,
                )
            )
            val headbar = npc.visHeadbar(params.headbar)
            val fill = (npc.hitpoints * headbar.segments / npc.baseHitpointsLvl).coerceAtLeast(1)
            npc.showHeadbar(Headbar.fromNoSource(headbar.id, headbar.id, fill, fill, 0, 0))
            if (hit.isFromPlayer && hit.damage > 0) {
                // Reflection bypasses protection prayers and is not capped by missing boss HP.
                hit.resolvePlayerSource(players)?.queueHit(npc, 1, HitType.Typeless, hit.damage)
            }
        }
        raid.bossHealth(state.region, npc.hitpoints, npc.baseHitpointsLvl)
    }

    private class State(
        val region: Region,
        val roomStart: Int,
        val spawnCycle: Int,
        var nextSwitch: Int,
        var nextAttack: Int,
        var attackable: Boolean = false,
        var style: NylocasStyle = NylocasStyle.Melee,
        var attacks: Int = 0,
        var target: Player? = null,
    )
}
