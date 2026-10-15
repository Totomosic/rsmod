package org.rsmod.content.areas.dungeons.nylocas

import jakarta.inject.Inject
import org.rsmod.api.combat.commons.player.queueCombatRetaliate
import org.rsmod.api.combat.formulas.AccuracyFormulae
import org.rsmod.api.combat.formulas.MaxHitFormulae
import org.rsmod.api.config.refs.params
import org.rsmod.api.config.refs.queues
import org.rsmod.api.player.hit.queueHit
import org.rsmod.api.player.isValidTarget
import org.rsmod.api.random.GameRandom
import org.rsmod.api.registry.region.RegionRegistry
import org.rsmod.api.route.BoundValidator
import org.rsmod.api.route.RayCastValidator
import org.rsmod.api.route.RouteFactory
import org.rsmod.api.script.onAiTimer
import org.rsmod.game.MapClock
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.NpcAvatar
import org.rsmod.game.entity.PathingEntityAvatar
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.movement.MoveRestrict
import org.rsmod.game.movement.MoveSpeed
import org.rsmod.game.region.Region
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.ScriptContext

class NylocasBehaviour
@Inject
constructor(
    private val regions: RegionRegistry,
    private val players: PlayerList,
    private val npcTypes: NpcTypeList,
    private val routes: RouteFactory,
    private val bounds: BoundValidator,
    private val rays: RayCastValidator,
    private val accuracy: AccuracyFormulae,
    private val maxHits: MaxHitFormulae,
    private val random: GameRandom,
    private val clock: MapClock,
) {
    private val active = mutableMapOf<Npc, State>()

    fun register(context: ScriptContext) =
        with(context) {
            for (size in NylocasSize.entries) {
                for (style in NylocasStyle.entries) {
                    onAiTimer(nylocas_npcs.incoming(size, style)) { tick(npc) }
                    onAiTimer(nylocas_npcs.fighting(size, style)) { tick(npc) }
                }
            }
        }

    fun add(npc: Npc, region: Region, slot: NylocasSlot, spawn: NylocasSpawn) {
        val pillar = spawn.pillar?.let { NpcAvatar(3).apply { coords = region.normal[it.source] } }
        active[npc] = State(region, region.normal[slot.entry], spawn, pillar, clock.cycle)
        npc.defaultMoveSpeed = MoveSpeed.Walk
        // The incoming lanes are blocked terrain. Only the straight scripted approach uses it.
        npc.moveRestrict = MoveRestrict.BlockedNormal
        npc.aiTimer(1)
    }

    fun prune() {
        active.entries.removeIf { (npc, state) ->
            !npc.isSlotAssigned || npc.hitpoints <= 0 || state.region.slot == Region.INVALID_SLOT
        }
    }

    private fun tick(npc: Npc) {
        val state = active[npc] ?: return
        if (npc.hitpoints <= 0 || !npc.canProcess || clock.cycle <= state.spawnCycle) return
        // Retaliation must not pull a nylo off its lane or assigned pillar.
        npc.queueList.removeAll(queues.com_retaliate_player)
        npc.noneMode()
        if (!state.entered) {
            if (npc.coords != state.entry) {
                npc.walk(state.entry)
                return
            }
            state.entered = true
            // Arena pathing respects terrain and scenery while allowing nylos to stack.
            npc.moveRestrict = MoveRestrict.PassThru
            npc.transmog(
                npcTypes[nylocas_npcs.fighting(state.spawn.size, state.spawn.style)],
                Int.MAX_VALUE,
            )
        }
        val pillar = state.pillar
        if (pillar != null) {
            npc.faceSquare(pillar.coords, 3, 3)
            if (!bounds.touches(npc.avatar, pillar)) route(npc, pillar)
            return
        }
        val candidates = players.filter { validTarget(it, state.region) }
        val target =
            state.target?.takeIf { it in candidates }
                ?: candidates.minByOrNull { npc.coords.chebyshevDistance(it.coords) }
        state.target = target
        if (target == null) return
        npc.facePlayer(target)
        val inRange =
            when (state.spawn.style) {
                NylocasStyle.Melee -> bounds.touches(npc.avatar, target.avatar)
                else ->
                    npc.isWithinDistance(target, 4) &&
                        !bounds.collides(npc.avatar, target.avatar) &&
                        rays.hasLineOfSight(npc.coords, target.coords, npc.size, npc.size)
            }
        if (!inRange) {
            route(npc, target.avatar)
            return
        }
        if (clock.cycle < state.nextAttack) return
        attack(npc, target, state.spawn.style)
        state.nextAttack = clock.cycle + npc.visType.param(params.attackrate).coerceAtLeast(1)
    }

    private fun validTarget(player: Player, region: Region): Boolean {
        if (!player.isValidTarget() || regions[player.coords]?.uid != region.uid) return false
        val source = regions.normalizeCoords(player.coords)
        return source.level == 0 && source.x in 3289..3302 && source.z in 4242..4254
    }

    private fun route(npc: Npc, target: PathingEntityAvatar) {
        val collision = npc.collisionStrategy ?: return
        val route = routes.create(npc.avatar, target, collision)
        npc.moveSpeed = MoveSpeed.Walk
        for (waypoint in route) {
            npc.routeDestination.add(CoordGrid(waypoint.x, waypoint.z, waypoint.level))
        }
    }

    private fun attack(npc: Npc, target: Player, style: NylocasStyle) {
        val accurate =
            when (style) {
                NylocasStyle.Melee -> accuracy.rollMeleeAccuracy(npc, target, null, random)
                NylocasStyle.Ranged -> accuracy.rollRangedAccuracy(npc, target, random)
                NylocasStyle.Magic -> accuracy.rollMagicAccuracy(npc, target, random)
            }
        val maxHit =
            when (style) {
                NylocasStyle.Melee -> maxHits.getMeleeMaxHit(npc, target, null)
                NylocasStyle.Ranged -> maxHits.getRangedMaxHit(npc, target)
                NylocasStyle.Magic -> maxHits.getMagicMaxHit(npc, target)
            }
        npc.visType.paramOrNull(params.attack_anim)?.let { npc.anim(it) }
        target.queueCombatRetaliate(npc)
        target.queueHit(npc, 1, style.hitType, if (accurate) random.of(0..maxHit) else 0)
    }

    private class State(
        val region: Region,
        val entry: CoordGrid,
        val spawn: NylocasSpawn,
        val pillar: NpcAvatar?,
        val spawnCycle: Int,
        var entered: Boolean = false,
        var target: Player? = null,
        var nextAttack: Int = 0,
    )
}
