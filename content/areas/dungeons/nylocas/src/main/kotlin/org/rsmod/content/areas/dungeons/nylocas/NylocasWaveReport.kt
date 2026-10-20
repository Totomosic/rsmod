package org.rsmod.content.areas.dungeons.nylocas

import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.util.Base64
import java.util.UUID
import org.rsmod.api.combat.commons.PlayerAttackTarget
import org.rsmod.api.combat.commons.PlayerDamageRoll
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.npc.events.NpcHitEvents
import org.rsmod.api.script.onEvent
import org.rsmod.game.MapClock
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.entity.npc.NpcStateEvents
import org.rsmod.game.hit.DamageRoll
import org.rsmod.game.region.Region
import org.rsmod.plugin.scripts.ScriptContext

/** Authoritative party statistics, sent over the existing console-message panel channel. */
@Singleton
class NylocasWaveReport
@Inject
constructor(
    private val raid: TheatreRaid,
    private val clock: MapClock,
    private val players: PlayerList,
) {
    private val rooms = mutableMapOf<Int, Room>()
    private val bodies = mutableMapOf<Npc, Body>()
    private val launchedAttacks = mutableMapOf<Int, Long>()
    private var nextAttackId = 0L

    fun register(context: ScriptContext) =
        with(context) {
            onEvent<NylocasRoomStart> {
                bodies.entries.removeAll { it.value.region.uid == region.uid }
                val room = Room(region, clock.cycle)
                for (member in raid.reportMembers(region)) {
                    room.stats.member(
                        member.uid.packed,
                        member.displayName.ifBlank { member.username },
                    )
                }
                rooms[region.uid] = room
            }
            onEvent<PlayerAttackTarget> { launchedAttacks[player.uid.packed] = ++nextAttackId }
            onEvent<PlayerDamageRoll> {
                val body = bodies[target] ?: return@onEvent
                val attackId = launchedAttacks[source.uid.packed] ?: ++nextAttackId
                body.attacks.getOrPut(attackId) { NylocasAttack() }.rolls += distribution
                body.rolls += Rolled(source.uid.packed, damage, distribution, attackId)
            }
            for (type in 8342..8353) {
                onEvent<NpcHitEvents.Modify>(type) {
                    val body = bodies[npc] ?: return@onEvent
                    val index =
                        body.rolls.indexOfFirst {
                            it.source == hit.sourceUid && it.damage == hit.damage
                        }
                    if (hit.isThrall) return@onEvent
                    if (index >= 0) {
                        val roll = body.rolls.removeAt(index)
                        hit.damageRoll = roll.distribution
                        hit.attackId = roll.attackId
                    } else {
                        val attackId = launchedAttacks[hit.sourceUid] ?: ++nextAttackId
                        hit.attackId = attackId
                        val attack = body.attacks.getOrPut(attackId) { NylocasAttack() }
                        if (hit.damage > 0) attack.measured = false
                    }
                }
            }
            onEvent<NpcStateEvents.Delete> {
                val body = bodies.remove(npc) ?: return@onEvent
                rooms[body.region.uid]?.stats?.removed(body.damage, clock.cycle)
            }
            onEvent<NpcHitEvents.Applied> {
                val body = bodies[npc] ?: return@onEvent
                val room = rooms[body.region.uid] ?: return@onEvent
                val style =
                    NylocasStyle.entries.first {
                        npc.visType.id == nylocas_npcs.incoming(body.damage.size, it).id ||
                            npc.visType.id == nylocas_npcs.fighting(body.damage.size, it).id
                    }
                room.stats.impact(
                    body.damage,
                    style,
                    hit.damage,
                    npc.hitpoints,
                    hit.isThrall,
                    clock.cycle,
                    hit.damageRoll,
                    body.attacks[hit.attackId],
                    if (hit.isFromPlayer) hit.resolvePlayerSource(players)?.uid?.packed else null,
                )
            }
            onEvent<GameLifecycle.EndCycle> {
                bodies.values.forEach { it.rolls.clear() }
                launchedAttacks.clear()
                rooms.values.removeAll { it.region.slot == Region.INVALID_SLOT }
                bodies.entries.removeAll { it.value.region.slot == Region.INVALID_SLOT }
                for (room in rooms.values) {
                    val population = bodies.values.count { it.region.uid == room.region.uid }
                    val status =
                        when {
                            room.stats.wave == 0 -> "starting"
                            room.stats.wave < 31 -> "waves"
                            population > 0 -> "cleanup"
                            else -> "complete"
                        }
                    val payload =
                        room.stats.payload(population, status) + " " + room.stats.playerPayload()
                    if (payload != room.lastPayload) {
                        raid.sendWaveReport(room.region, "LNW4 ${room.id} $payload")
                        room.lastPayload = payload
                    }
                }
            }
        }

    fun spawned(npc: Npc, region: Region, size: NylocasSize, splitStyle: NylocasStyle? = null) {
        val room = rooms[region.uid] ?: return
        bodies[npc] = Body(region, NylocasWaveStats.Damage(size))
        if (splitStyle != null) room.stats.split(splitStyle)
    }

    fun waveSpawned(region: Region, wave: Int) {
        rooms[region.uid]?.stats?.wave = wave
    }

    fun admission(region: Region, wave: Int, population: Int, cap: Int) {
        val room = rooms[region.uid] ?: return
        room.stats.admission(population, cap, clock.cycle)
        if (room.checkWave == wave) room.attempt++ else room.attempt = 0
        room.checkWave = wave
        val tick = clock.cycle - room.startCycle
        raid.sendWaveReport(
            region,
            "LNC1 ${room.id} ${++room.sequence} $tick $wave ${room.attempt} $population $cap",
        )
    }

    private class Rolled(
        val source: Int,
        val damage: Int,
        val distribution: DamageRoll,
        val attackId: Long,
    )

    private class Body(val region: Region, val damage: NylocasWaveStats.Damage) {
        val rolls = mutableListOf<Rolled>()
        val attacks = mutableMapOf<Long, NylocasAttack>()
    }

    private class Room(val region: Region, val startCycle: Int) {
        var checkWave = 0
        var attempt = 0
        var sequence = 0
        val id = UUID.randomUUID().toString()
        val stats = NylocasWaveStats()
        var lastPayload: String? = null
    }
}

/** Counts kills once; misses and subsequent corpse hits cannot create additional kills. */
class NylocasWaveStats {
    var wave = 0
    private var stalls = 0
    private var saved = 0
    private var thralls = 0
    private val kills = IntArray(2)
    private val oneShots = IntArray(2)
    private val styleKills = IntArray(3)
    private val overkills = IntArray(3)
    private val splits = Array(3) { IntArray(3) }
    // Pre/post cap, then small/big. Cleanup belongs to post-cap.
    private val luck = Array(4) { NylocasLuck() }
    private val wholeLuck = NylocasLuck()
    private var unmeasured = 0
    private val players = linkedMapOf<Int, PlayerLuck>()
    private var removalCycle = -1
    private var populationWithoutOverkillDelta = 0

    fun member(source: Int, name: String) {
        players.putIfAbsent(source, PlayerLuck(name))
    }

    fun impact(
        body: Damage,
        style: NylocasStyle,
        damage: Int,
        hp: Int,
        thrall: Boolean,
        cycle: Int,
        distribution: DamageRoll? = null,
        attack: NylocasAttack? = null,
        source: Int? = null,
    ) {
        if (body.killCycle != null) {
            // Only an extra splat on the lethal tick advances the death queue by one tick.
            if (!body.overkilled && body.killCycle == cycle) {
                body.overkilled = true
                overkills[checkNotNull(body.deathStyle).ordinal]++
            }
            return
        }
        if (damage <= 0) return
        val originatingAttack =
            attack ?: NylocasAttack(distribution?.let { mutableListOf(it) } ?: mutableListOf())
        if (body.firstAttack == null) {
            body.firstAttack = originatingAttack
            body.luckOwner = source
            val player = players[source]
            val probability = if (thrall) null else originatingAttack.chanceAtLeast(hp + damage)
            if (probability != null) {
                val group = (if (wave >= 21) 2 else 0) + body.size.ordinal
                body.luckGroup = group
                luck[group].attempt(probability)
                wholeLuck.attempt(probability)
                player?.groups?.get(group)?.attempt(probability)
                player?.whole?.attempt(probability)
            } else {
                unmeasured++
                if (player != null) player.unmeasured++
            }
        }
        if (body.firstAttack !== originatingAttack) body.multipleAttacks = true
        if (hp != 0) return
        body.killCycle = cycle
        body.deathStyle = style
        kills[body.size.ordinal]++
        styleKills[style.ordinal]++
        if (!body.multipleAttacks) {
            oneShots[body.size.ordinal]++
            body.luckGroup?.let {
                luck[it].actual++
                wholeLuck.actual++
                players[body.luckOwner]?.let { player ->
                    player.groups[it].actual++
                    player.whole.actual++
                }
            }
        }
        if (thrall) thralls++
    }

    fun split(style: NylocasStyle) {
        val phase = if (wave >= 29) 2 else if (wave >= 21) 1 else 0
        splits[phase][style.ordinal]++
    }

    fun removed(body: Damage, cycle: Int) {
        if (removalCycle != cycle) {
            removalCycle = cycle
            populationWithoutOverkillDelta = 0
        }
        if (!body.overkilled) return
        // Without overkill a small would still occupy one slot. A big would still
        // occupy one slot INSTEAD OF its two newborn children, reducing occupancy.
        populationWithoutOverkillDelta += if (body.size == NylocasSize.Small) 1 else -1
    }

    fun admission(population: Int, cap: Int, cycle: Int) {
        if (population >= cap) stalls++
        else if (removalCycle == cycle && population + populationWithoutOverkillDelta >= cap)
            saved++
    }

    fun payload(population: Int, status: String): String =
        (listOf(status, wave, population, stalls, saved, thralls) +
                kills.indices.flatMap { listOf(kills[it], oneShots[it]) } +
                styleKills.toList() +
                overkills.toList() +
                splits.flatMap { it.toList() } +
                luck.flatMap {
                    listOf(
                        it.attempts,
                        it.actual,
                        kotlin.math.round(it.expected * 1000000).toLong(),
                    )
                } +
                listOf(
                    unmeasured,
                    if (wholeLuck.attempts == 0) 0L
                    else kotlin.math.round(wholeLuck.percentile() * 1000000).toLong(),
                ))
            .joinToString(" ")

    /** LNW4 appends a count, then a base64url UTF-8 name and 14 luck counters per member. */
    fun playerPayload(): String =
        (listOf(players.size.toString()) +
                players.values.flatMap { player ->
                    listOf(
                        Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(player.name.toByteArray(Charsets.UTF_8))
                    ) +
                        player.groups.flatMap {
                            listOf(
                                it.attempts.toString(),
                                it.actual.toString(),
                                kotlin.math.round(it.expected * 1000000).toLong().toString(),
                            )
                        } +
                        listOf(
                            player.unmeasured.toString(),
                            if (player.whole.attempts == 0) "0"
                            else
                                kotlin.math
                                    .round(player.whole.percentile() * 1000000)
                                    .toLong()
                                    .toString(),
                        )
                })
            .joinToString(" ")

    private class PlayerLuck(val name: String) {
        val groups = Array(4) { NylocasLuck() }
        val whole = NylocasLuck()
        var unmeasured = 0
    }

    class Damage(val size: NylocasSize) {
        var firstAttack: NylocasAttack? = null
        var multipleAttacks = false
        var luckGroup: Int? = null
        var luckOwner: Int? = null
        var killCycle: Int? = null
        var deathStyle: NylocasStyle? = null
        var overkilled = false
    }
}
