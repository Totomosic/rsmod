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
import org.rsmod.api.route.StepFactory
import org.rsmod.api.script.onAiTimer
import org.rsmod.game.MapClock
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.NpcAvatar
import org.rsmod.game.entity.PathingEntityAvatar
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.map.collision.get
import org.rsmod.game.movement.MoveRestrict
import org.rsmod.game.movement.MoveSpeed
import org.rsmod.game.region.Region
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.ScriptContext
import org.rsmod.routefinder.Route
import org.rsmod.routefinder.collision.CollisionFlagMap
import org.rsmod.routefinder.collision.CollisionStrategy
import org.rsmod.routefinder.flag.CollisionFlag

class NylocasBehaviour
@Inject
constructor(
    private val regions: RegionRegistry,
    private val players: PlayerList,
    private val npcTypes: NpcTypeList,
    private val routes: RouteFactory,
    private val steps: StepFactory,
    private val collision: CollisionFlagMap,
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
        val tiles = spawn.pillar?.let { pillarTiles(region, it, npc.size) }.orEmpty()
        active[npc] = State(region, region.normal[slot.entry], spawn, pillar, tiles, clock.cycle)
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
            npc.moveRestrict = MoveRestrict.Normal
            npc.transmog(
                npcTypes[nylocas_npcs.fighting(state.spawn.size, state.spawn.style)],
                Int.MAX_VALUE,
            )
        }
        val pillar = state.pillar
        if (pillar != null) {
            npc.faceSquare(pillar.coords, 3, 3)
            approachPillar(npc, state, pillar)
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

    private fun pillarTiles(region: Region, pillar: NylocasPillar, size: Int): List<CoordGrid> {
        // The room exposes two walkable tiles on each of the pillar's two inward-facing sides.
        val source = pillar.source
        val candidates = buildList {
            for (offset in 1 - size until 3) {
                add(source.translate(-size, offset))
                add(source.translate(3, offset))
                add(source.translate(offset, -size))
                add(source.translate(offset, 3))
            }
        }
        return candidates
            .filter { tile ->
                tile.x >= 3289 && tile.x + size <= 3303 && tile.z >= 4242 && tile.z + size <= 4255
            }
            .map { region.normal[it] }
            .filter { tile ->
                (0 until size).all { x ->
                    (0 until size).all { z ->
                        collision[tile.translate(x, z)] and TERRAIN_BLOCKERS == 0
                    }
                }
            }
    }

    private fun approachPillar(npc: Npc, state: State, pillar: NpcAvatar) {
        if (npc.coords in state.pillarTiles) return
        val available = state.pillarTiles.filter { canOccupy(npc, it) }
        // Ignore our own footprint during searches, but retain every other occupancy flag.
        npc.removeBlockWalkCollision(collision, npc.coords)
        try {
            for (tile in available.sortedBy { npc.coords.chebyshevDistance(it) }) {
                val route = routes.create(npc.avatar, tile, PillarCollision)
                if (route.success) {
                    state.waitingReturn = null
                    follow(npc, route)
                    return
                }
            }
            if (state.waitingReturn == null) {
                val approach = routes.create(npc.avatar, pillar, PillarCollision)
                if (
                    approach.isNotEmpty() &&
                        state.pillarTiles.minOf { npc.coords.chebyshevDistance(it) } > 1
                ) {
                    follow(npc, approach)
                    return
                }
            }
        } finally {
            npc.addBlockWalkCollision(collision, npc.coords)
        }
        pace(npc, state)
    }

    private fun canOccupy(npc: Npc, tile: CoordGrid): Boolean =
        (0 until npc.size).all { x ->
            (0 until npc.size).all { z ->
                val coord = tile.translate(x, z)
                val ownTile =
                    coord.x in npc.coords.x until npc.coords.x + npc.size &&
                        coord.z in npc.coords.z until npc.coords.z + npc.size
                val blockers = TERRAIN_BLOCKERS or if (ownTile) 0 else CollisionFlag.BLOCK_NPCS
                collision[coord] and blockers == 0
            }
        }

    private fun pace(npc: Npc, state: State) {
        fun canStep(tile: CoordGrid): Boolean =
            canOccupy(npc, tile) &&
                steps.validated(npc.coords, tile, npc.size, CollisionFlag.BLOCK_NPCS) == tile

        val back = state.waitingReturn
        if (back != null && back != npc.coords && canStep(back)) {
            npc.walk(back)
            return
        }
        state.waitingReturn = null
        val next =
            listOf(
                    npc.coords.translate(-1, 0),
                    npc.coords.translate(1, 0),
                    npc.coords.translate(0, -1),
                    npc.coords.translate(0, 1),
                )
                .sortedByDescending { tile ->
                    state.pillarTiles.minOf { tile.chebyshevDistance(it) }
                }
                .firstOrNull { canStep(it) } ?: return
        state.waitingReturn = npc.coords
        npc.walk(next)
    }

    private fun validTarget(player: Player, region: Region): Boolean {
        if (!player.isValidTarget() || regions[player.coords]?.uid != region.uid) return false
        val source = regions.normalizeCoords(player.coords)
        return source.level == 0 && source.x in 3289..3302 && source.z in 4242..4254
    }

    private fun route(npc: Npc, target: PathingEntityAvatar) {
        val collision = npc.collisionStrategy ?: return
        val route = routes.create(npc.avatar, target, collision)
        follow(npc, route)
    }

    private fun follow(npc: Npc, route: Route) {
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
        val pillarTiles: List<CoordGrid>,
        val spawnCycle: Int,
        var entered: Boolean = false,
        var target: Player? = null,
        var nextAttack: Int = 0,
        var waitingReturn: CoordGrid? = null,
    )

    private object PillarCollision : CollisionStrategy {
        override fun canMove(tileFlag: Int, blockFlag: Int): Boolean =
            CollisionStrategy.Normal.canMove(tileFlag, blockFlag or CollisionFlag.BLOCK_NPCS)
    }

    private companion object {
        const val TERRAIN_BLOCKERS =
            CollisionFlag.LOC or
                CollisionFlag.BLOCK_WALK or
                CollisionFlag.GROUND_DECOR or
                CollisionFlag.BLOCK_PLAYERS
    }
}
