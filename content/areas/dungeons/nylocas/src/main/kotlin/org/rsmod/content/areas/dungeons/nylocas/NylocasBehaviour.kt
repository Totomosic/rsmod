package org.rsmod.content.areas.dungeons.nylocas

import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.rsmod.api.combat.commons.PlayerAttackCheck
import org.rsmod.api.combat.commons.PlayerMaxHitCheck
import org.rsmod.api.combat.commons.PlayerSpecialAttackCheck
import org.rsmod.api.combat.commons.player.queueCombatRetaliate
import org.rsmod.api.combat.formulas.AccuracyFormulae
import org.rsmod.api.combat.formulas.MaxHitFormulae
import org.rsmod.api.combat.weapon.WeaponSpeeds
import org.rsmod.api.config.constants
import org.rsmod.api.config.refs.objs
import org.rsmod.api.config.refs.params
import org.rsmod.api.config.refs.queues
import org.rsmod.api.player.clearInteractionRoute
import org.rsmod.api.player.hit.queueHit
import org.rsmod.api.player.isValidTarget
import org.rsmod.api.player.output.soundSynth
import org.rsmod.api.random.GameRandom
import org.rsmod.api.registry.region.RegionRegistry
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.world.WorldRepository
import org.rsmod.api.route.BoundValidator
import org.rsmod.api.route.RayCastValidator
import org.rsmod.api.route.RouteFactory
import org.rsmod.api.route.StepFactory
import org.rsmod.api.script.onAiTimer
import org.rsmod.api.script.onEvent
import org.rsmod.game.MapClock
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.NpcAvatar
import org.rsmod.game.entity.PathingEntityAvatar
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.entity.npc.NpcMode
import org.rsmod.game.entity.npc.NpcStateEvents
import org.rsmod.game.hit.HitType
import org.rsmod.game.interact.InteractionNpc
import org.rsmod.game.inv.isAnyType
import org.rsmod.game.map.collision.get
import org.rsmod.game.map.collision.remove
import org.rsmod.game.movement.MoveRestrict
import org.rsmod.game.movement.MoveSpeed
import org.rsmod.game.region.Region
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.map.zone.ZoneKey
import org.rsmod.plugin.scripts.ScriptContext
import org.rsmod.routefinder.Route
import org.rsmod.routefinder.collision.CollisionFlagMap
import org.rsmod.routefinder.flag.CollisionFlag

@Singleton
class NylocasBehaviour
@Inject
constructor(
    private val raid: TheatreRaid,
    private val weaponSpeeds: WeaponSpeeds,
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
    private val npcRepo: NpcRepository,
    private val world: WorldRepository,
) {
    private val active = mutableMapOf<Npc, State>()

    fun register(context: ScriptContext) =
        with(context) {
            onEvent<NpcStateEvents.Delete> {
                val state = active[npc] ?: return@onEvent
                if (state.pillar == null || state.spawn.size != NylocasSize.Big) return@onEvent
                // Ordinary bigs move/reserve at their SW anchor, but removal can
                // leave living neighbours nonblocking across the native footprint.
                // Blert 1073635f: big62545 removed183, small63282 holds, arrival193.
                for (x in 0 until npc.size) for (z in 0 until npc.size) {
                    collision.remove(npc.coords.translate(x, z), CollisionFlag.BLOCK_NPCS)
                }
            }
            for (size in NylocasSize.entries) {
                for (style in NylocasStyle.entries) {
                    for (type in
                        listOf(
                            nylocas_npcs.incoming(size, style),
                            nylocas_npcs.fighting(size, style),
                        )) {
                        onAiTimer(type) { tick(npc) }
                        onEvent<PlayerAttackCheck>(type.id) { checkAttack(this) }
                        onEvent<PlayerMaxHitCheck>(type.id) {
                            val state = active[target] ?: return@onEvent
                            val required =
                                when (state.style) {
                                    NylocasStyle.Melee -> HitType.Melee
                                    NylocasStyle.Ranged -> HitType.Ranged
                                    NylocasStyle.Magic -> HitType.Magic
                                }
                            if (this.style != required || source.uuid in state.nullFeedbackCycles)
                                return@onEvent
                            if (!raid.isStarted(source, state.region)) return@onEvent
                            forced =
                                when (raid.options(state.region).nyloMaxHit) {
                                    NyloMaxHit.Off -> false
                                    NyloMaxHit.AllWeapons -> true
                                    NyloMaxHit.SlowWeapons ->
                                        (attackRate ?: weaponSpeeds.actual(source)) >= 5
                                }
                        }
                        onEvent<PlayerSpecialAttackCheck>(type.id) {
                            if (weapon.isAnyType(objs.dinhs_bulwark, objs.dinhs_blazing_bulwark)) {
                                val source = regions.normalizeCoords(target.coords)
                                // Use the southwest anchor for both sizes, matching lane entry.
                                allowed =
                                    source.level == 0 &&
                                        source.x in 3290..3301 &&
                                        source.z in 4243..4254
                            }
                        }
                    }
                }
            }
        }

    private fun checkAttack(check: PlayerAttackCheck) {
        val state = active[check.target] ?: return
        val playerId = checkNotNull(check.source.uuid)
        val requiredStyle =
            when (state.style) {
                NylocasStyle.Melee -> HitType.Melee
                NylocasStyle.Ranged -> HitType.Ranged
                NylocasStyle.Magic -> HitType.Magic
            }
        if (check.isThrall) {
            check.allowed = check.style == requiredStyle
            return
        }
        // Remember the account on this individual NPC, including through flickers and re-entry.
        // Check before accuracy so even a wrong-style miss permanently nulls the player's target.
        if (check.style != requiredStyle) {
            // Suppress every splat of the attack that first nulls this player/NPC pair.
            state.nullFeedbackCycles.putIfAbsent(playerId, clock.cycle)
        }
        val lastFeedback = state.nullFeedbackCycles[playerId]
        check.allowed = lastFeedback == null
        if (lastFeedback != null && lastFeedback != clock.cycle) {
            // Multi-hit weapons produce one cue per attacker/target this tick. Keep the shield
            // separate from spell impacts and splashes, which use the default spotanim slot.
            state.nullFeedbackCycles[playerId] = clock.cycle
            // Synth packets have no gain control. Mix two synchronized copies for a louder
            // cue; increasing loops would only repeat it for longer.
            repeat(2) { check.source.soundSynth(NylocasSynths.nullWarning) }
            check.target.spotanim(nylocas_spots.shielded, slot = constants.spotanim_slot_combat)
        }
    }

    fun add(npc: Npc, region: Region, slot: NylocasSlot, spawn: NylocasSpawn, teamSize: Int) {
        npc.collisionSize = 1
        val pillar = spawn.pillar?.let { NpcAvatar(2).apply { coords = region.normal[it.source] } }
        val tiles = spawn.pillar?.let { pillarTiles(region, it, npc.size) }.orEmpty()
        // Wave 9's west small shares the big's south lane despite its separate table slot.
        val entry = slot.entry.translateZ(spawn.source(slot).z - slot.source.z)
        active[npc] =
            State(region, region.normal[entry], spawn, pillar, tiles, clock.cycle, teamSize)
        npc.defaultMoveSpeed = MoveSpeed.Walk
        // The incoming lanes are blocked terrain. Only the straight scripted approach uses it.
        npc.moveRestrict = MoveRestrict.BlockedNormal
        npc.aiTimer(1)
    }

    fun processLifetimes() {
        // Run independently of AI timers: expiry must continue while an NPC is delayed
        // and after the final wave, including the positive-HP explosion animation.
        val splits = mutableListOf<Pair<Npc, State>>()
        val iterator = active.entries.iterator()
        while (iterator.hasNext()) {
            val (npc, state) = iterator.next()
            if (!npc.isSlotAssigned || state.region.slot == Region.INVALID_SLOT) {
                iterator.remove()
                continue
            }
            val age = clock.cycle - state.spawnCycle
            if (state.expiring) {
                if (age >= state.spawn.size.expiryRemovalAge) {
                    if (state.spawn.size == NylocasSize.Small) {
                        // The detached visual survives removal; blast damage already landed.
                        world.spotanimMap(nylocas_spots.explosion(state.style), npc.coords)
                    }
                    npcRepo.del(npc, Int.MAX_VALUE)
                    iterator.remove()
                    if (state.spawn.size == NylocasSize.Big) splits += npc to state
                }
                continue
            }
            if (npc.hitpoints <= 0 || queues.death in npc.queueList) {
                // A player kill follows its own death sequence, never a later expiry.
                continue
            }
            if (age >= state.spawn.size.expiryAnimationAge) {
                state.expiring = true
                npc.cancelActiveCoroutine()
                npc.noneMode()
                npc.hideAllOps()
                npc.queueList.clear()
                npc.aiTimer(0)
                // Preserve HP, but reject new attacks and queued hits until removal.
                npc.delay = state.spawnCycle + state.spawn.size.expiryRemovalAge + 1
                npc.resetAnim()
                npc.anim(nylocas_seqs.expiry(state.spawn.size, state.style))
                explode(npc, state)
            }
        }
        // Children extend active: add them only after the lifetime iteration finishes.
        for ((npc, state) in splits) split(npc, state)
    }

    fun completeDeath(npc: Npc) {
        val state = active.remove(npc) ?: return
        if (state.spawn.size == NylocasSize.Big) split(npc, state)
    }

    private fun split(parent: Npc, state: State) {
        if (state.region.slot == Region.INVALID_SLOT) return
        // The corpus's usual birth pair is SW/NE on the removal tick. Rare shifted
        // replay anchors have no established selection rule; do not randomize offsets
        // or invent a nearest-free-tile search. Births may overlap existing actors.
        for (offset in 0..1) {
            val style = random.pick(NylocasStyle.entries)
            val pillar = random.pick(NylocasPillar.entries)
            val spawn = NylocasSpawn(NylocasSize.Small, style, pillar)
            val coords = parent.coords.translate(offset, offset)
            val npc = Npc(npcTypes[nylocas_npcs.incoming(NylocasSize.Small, style)], coords)
            npc.baseHitpointsLvl = NylocasSize.Small.hitpoints(state.teamSize)
            npc.hitpoints = npc.baseHitpointsLvl
            npc.mode = NpcMode.None
            npc.setHunt(0)
            npc.aiTimer(0)
            npc.defaultMoveSpeed = MoveSpeed.Walk
            // A child born in a lane still needs its scripted approach into the room.
            val source = regions.normalizeCoords(coords)
            val entry =
                when {
                    source.x < 3289 -> CoordGrid(3290, source.z, source.level)
                    source.x > 3302 -> CoordGrid(3301, source.z, source.level)
                    source.z < 4242 -> CoordGrid(source.x, 4243, source.level)
                    else -> null
                }
            val entered = entry == null
            npc.moveRestrict = if (entered) MoveRestrict.PassThru else MoveRestrict.BlockedNormal
            npcRepo.add(npc, Int.MAX_VALUE)
            npc.respawns = false
            active[npc] =
                State(
                    state.region,
                    entry?.let { state.region.normal[it] } ?: coords,
                    spawn,
                    NpcAvatar(2).apply { this.coords = state.region.normal[pillar.source] },
                    pillarTiles(state.region, pillar, npc.size),
                    clock.cycle,
                    state.teamSize,
                    entered = entered,
                )
            npc.aiTimer(1)
        }
    }

    private fun explode(npc: Npc, state: State) {
        // User-confirmed timing: blast once when the natural-expiry animation starts.
        // Provisional damage/radius model, pending recording-backed measurements:
        // a uniform 0..18/21 typeless roll within one tile of the
        // NPC's footprint (including diagonals). Protection prayers do not reduce it.
        val maxHit = if (state.spawn.size == NylocasSize.Small) 18 else 21
        for (player in players) {
            if (!validTarget(player, state.region) || player.coords.level != npc.coords.level)
                continue
            val inBlast =
                player.coords.x in
                    npc.coords.x - EXPLOSION_RADIUS until
                        npc.coords.x + npc.size + EXPLOSION_RADIUS &&
                    player.coords.z in
                        npc.coords.z - EXPLOSION_RADIUS until
                            npc.coords.z + npc.size + EXPLOSION_RADIUS
            // Player queues process later in this cycle; one is their minimum delay.
            if (inBlast) player.queueHit(npc, 1, HitType.Typeless, random.of(0..maxHit))
        }
    }

    private fun tick(npc: Npc) {
        val state = active[npc] ?: return
        if (state.expiring || npc.hitpoints <= 0 || clock.cycle <= state.spawnCycle) return
        val age = clock.cycle - state.spawnCycle
        val previousStyle = state.style
        state.style = state.spawn.styleAtAge(age)
        updateAppearance(npc, state)
        if (state.style != previousStyle) cancelPlayerAttacks(npc)
        if (!npc.canProcess) return
        // Retaliation must not pull a nylo off its lane or assigned pillar.
        npc.queueList.removeAll(queues.com_retaliate_player)
        npc.noneMode()
        if (!state.entered) {
            if (npc.coords != state.entry) {
                npc.walk(state.entry)
                return
            }
            state.entered = true
            // Pillar AI validates NPC collision at the destination itself. Shared
            // movement must not reapply NPC blockers to diagonal side tiles.
            npc.moveRestrict =
                if (state.pillar != null) MoveRestrict.PassThru else MoveRestrict.Normal
            updateAppearance(npc, state)
        }
        val pillar = state.pillar
        if (pillar != null) {
            npc.faceSquare(pillar.coords, 2, 2)
            approachPillar(npc, state, pillar)
            return
        }
        val candidates = players.filter { validTarget(it, state.region) }
        // A pillar arrival leaving a player's tile clears their shared collision bit.
        // Reapply player reservations before fighting nylos validate their movement.
        for (player in candidates) player.addBlockWalkCollision(collision, player.coords)
        // Roll independently every tick, including during cooldown and with repeats allowed.
        val target = random.pickOrNull(candidates) ?: return
        npc.facePlayer(target)
        val inRange =
            when (state.style) {
                NylocasStyle.Melee -> bounds.touches(npc.avatar, target.avatar)
                else ->
                    // Solo replay approaches stop two tiles from the nearest footprint edge.
                    npc.isWithinDistance(target, 2) &&
                        !bounds.collides(npc.avatar, target.avatar) &&
                        rays.hasLineOfSight(npc.coords, target.coords, npc.size, npc.size)
            }
        if (!inRange) {
            route(npc, target.avatar)
            return
        }
        // Age-26 melee flickers still choose and chase players, but cannot attack.
        if (age >= 26 && state.spawn.styleAt26 == NylocasStyle.Melee) return
        if (clock.cycle < state.nextAttack) return
        attack(npc, target, state.style)
        state.nextAttack = clock.cycle + npc.visType.param(params.attackrate).coerceAtLeast(1)
    }

    private fun updateAppearance(npc: Npc, state: State) {
        // The cache's "fighting" forms are player aggros. Pillar attackers retain
        // ordinary IDs, including after entering the arena and during style swaps.
        val fighting = state.entered && state.pillar == null
        npc.resizeCollision(collision, if (fighting) npc.size else 1)
        val type =
            if (fighting) {
                nylocas_npcs.fighting(state.spawn.size, state.style)
            } else {
                nylocas_npcs.incoming(state.spawn.size, state.style)
            }
        if (npc.visType.id == type.id) return
        npc.transmog(npcTypes[type], Int.MAX_VALUE)
    }

    private fun cancelPlayerAttacks(npc: Npc) {
        // Style flickers require a fresh click, including attacks waiting on cooldown.
        // Entering the arena only changes the form and must preserve those attacks.
        for (player in players) {
            val interaction = player.interaction as? InteractionNpc ?: continue
            if (interaction.target != npc) continue
            player.clearInteractionRoute()
            player.resetFaceEntity()
        }
    }

    private fun pillarTiles(region: Region, pillar: NylocasPillar, size: Int): List<CoordGrid> {
        // Contact uses the full footprint; terrain is checked only at each southwest anchor.
        val source = pillar.source
        val candidates = buildList {
            for (offset in 1 - size until 2) {
                add(source.translate(-size, offset))
                add(source.translate(2, offset))
                add(source.translate(offset, -size))
                add(source.translate(offset, 2))
            }
        }
        return candidates
            .filter { tile -> tile.x in 3289..3302 && tile.z in 4242..4254 }
            .map { region.normal[it] }
            .filter { tile -> collision[tile] and TERRAIN_BLOCKERS == 0 }
    }

    private fun approachPillar(npc: Npc, state: State, pillar: NpcAvatar) {
        if (npc.coords in state.pillarTiles) return
        val centre = state.region.normal[NylocasRoom.arena]
        // Aim at the pillar's outer corner, aligned to the native footprint.
        // This is a direction reference inside the pillar; contact stops us first.
        val goal =
            pillar.coords.translate(
                if (pillar.coords.x > centre.x) 2 - npc.size else 0,
                if (pillar.coords.z > centre.z) 2 - npc.size else 0,
            )
        // Take a reachable adjacent contact before falling back to another
        // component of the preferred direction. This lets an inner slot fill
        // when the outer endpoint is occupied, without searching a distant face.
        val adjacent =
            state.pillarTiles
                .filter { npc.coords.chebyshevDistance(it) == 1 }
                .sortedBy { kotlin.math.abs(it.x - goal.x) + kotlin.math.abs(it.z - goal.z) }
                .firstOrNull { pillarStep(npc, it) == it }
        if (adjacent != null) {
            npc.walk(adjacent)
            return
        }
        while (state.terrainRoute.firstOrNull() == npc.coords) state.terrainRoute.removeFirst()
        val destination = state.terrainRoute.firstOrNull() ?: goal
        val direct = pillarStep(npc, destination)
        if (direct != CoordGrid.NULL) {
            npc.walk(direct)
            return
        }
        // Detour around static terrain, but do not chase another face merely
        // because NPCs occupy this approach. A moveNear alternative is not contact.
        if (
            state.terrainRoute.isEmpty() &&
                steps.validated(npc.coords, goal, npc.collisionSize) == CoordGrid.NULL
        ) {
            val contact =
                state.pillarTiles.minWithOrNull(
                    compareBy<CoordGrid> { npc.coords.chebyshevDistance(it) }
                        .thenBy { it.chebyshevDistance(goal) }
                )
            if (contact != null) {
                val route = routes.create(movementAvatar(npc), contact)
                if (route.success && !route.alternative && route.isNotEmpty()) {
                    state.terrainRoute.addAll(route.map { CoordGrid(it.x, it.z, it.level) })
                    val first = state.terrainRoute.first()
                    val step = pillarStep(npc, first)
                    if (step != CoordGrid.NULL) {
                        npc.walk(step)
                        return
                    }
                }
            }
        }

        val retries = buildList {
            for (x in -1..1) for (z in -1..1) {
                if (x == 0 && z == 0) continue
                val tile = npc.coords.translate(x, z)
                if (pillarStep(npc, tile) == tile) {
                    add(tile)
                }
            }
        }
        // Recordings establish mixed local retries, not their exact selector.
        // Sample legal neighbours rather than imposing an alternating pace timer.
        val next = random.pickOrNull(retries) ?: return
        npc.walk(next)
    }

    private fun pillarStep(npc: Npc, destination: CoordGrid): CoordGrid {
        val dx = (destination.x - npc.coords.x).compareTo(0)
        val dz = (destination.z - npc.coords.z).compareTo(0)
        // Keep terrain corner checks, but NPCs only obstruct the destination.
        // Blert 1073635f: small61705 moves (3298,4243)->(3299,4244) at89
        // beside stationary small61590 at (3299,4243).
        val candidates =
            listOf(
                npc.coords.translate(dx, dz),
                npc.coords.translate(dx, 0),
                npc.coords.translate(0, dz),
            )
        return candidates.firstOrNull { tile ->
            tile != npc.coords &&
                !pillarBlocked(tile) &&
                steps.validated(npc.coords, tile, npc.collisionSize) == tile
        } ?: CoordGrid.NULL
    }

    private fun pillarBlocked(tile: CoordGrid): Boolean {
        if (collision[tile] and CollisionFlag.BLOCK_NPCS == 0) return false
        // Players share BLOCK_NPCS with NPCs, but only NPC occupancy blocks pillar arrivals.
        if (players.none { it.isVisible && it.coords == tile }) return true
        // A player may stand underneath an NPC; keep that NPC's reservation, including
        // the full footprint of fighting bigs. Do not remove flags from the shared map.
        return npcRepo.findAll(ZoneKey.from(tile), zoneRadius = 1).any { other ->
            other.isVisible &&
                (other.blockWalkCollisionFlag ?: 0) and CollisionFlag.BLOCK_NPCS != 0 &&
                tile.level == other.coords.level &&
                tile.x in other.coords.x until other.coords.x + other.collisionSize &&
                tile.z in other.coords.z until other.coords.z + other.collisionSize
        }
    }

    private fun validTarget(player: Player, region: Region): Boolean {
        if (!player.isValidTarget() || regions[player.coords]?.uid != region.uid) return false
        val source = regions.normalizeCoords(player.coords)
        return source.level == 0 && source.x in 3289..3302 && source.z in 4242..4254
    }

    private fun route(npc: Npc, target: PathingEntityAvatar) {
        val collision = npc.collisionStrategy ?: return
        val route = routes.create(movementAvatar(npc), target, collision)
        follow(npc, route)
    }

    private fun movementAvatar(npc: Npc): NpcAvatar =
        NpcAvatar(npc.collisionSize).apply { coords = npc.coords }

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
        val teamSize: Int,
        var style: NylocasStyle = spawn.style,
        var entered: Boolean = false,
        var nextAttack: Int = 0,
        val terrainRoute: ArrayDeque<CoordGrid> = ArrayDeque(),
        var expiring: Boolean = false,
        // Presence records permanent nulling; the value is the last suppressed/played cue tick.
        val nullFeedbackCycles: MutableMap<Long, Int> = mutableMapOf(),
    )

    private companion object {
        const val EXPLOSION_RADIUS = 1
        const val TERRAIN_BLOCKERS =
            CollisionFlag.LOC or
                CollisionFlag.BLOCK_WALK or
                CollisionFlag.GROUND_DECOR or
                CollisionFlag.BLOCK_PLAYERS
    }
}
