package org.rsmod.content.areas.dungeons.nylocas

import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.util.UUID
import org.rsmod.api.combat.commons.PlayerDamageRoll
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.npc.events.NpcHitEvents
import org.rsmod.api.script.onEvent
import org.rsmod.game.MapClock
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.npc.NpcStateEvents
import org.rsmod.game.hit.DamageRoll
import org.rsmod.game.region.Region
import org.rsmod.plugin.scripts.ScriptContext

/** Authoritative party statistics, sent over the existing console-message panel channel. */
@Singleton
class NylocasWaveReport
@Inject
constructor(private val raid: TheatreRaid, private val clock: MapClock) {
    private val rooms = mutableMapOf<Int, Room>()
    private val bodies = mutableMapOf<Npc, Body>()

    fun register(context: ScriptContext) =
        with(context) {
            onEvent<NylocasRoomStart> {
                bodies.entries.removeAll { it.value.region.uid == region.uid }
                rooms[region.uid] = Room(region, clock.cycle)
            }
            onEvent<PlayerDamageRoll> {
                val body = bodies[target] ?: return@onEvent
                if (damage > 0) body.rolls += Rolled(source.uid.packed, damage, distribution)
            }
            for (type in 8342..8353) {
                onEvent<NpcHitEvents.Modify>(type) {
                    val body = bodies[npc] ?: return@onEvent
                    val index =
                        body.rolls.indexOfFirst {
                            it.source == hit.sourceUid && it.damage == hit.damage
                        }
                    if (index >= 0) hit.damageRoll = body.rolls.removeAt(index).distribution
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
                )
            }
            onEvent<GameLifecycle.EndCycle> {
                bodies.values.forEach { it.rolls.clear() }
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
                    val payload = room.stats.payload(population, status)
                    if (payload != room.lastPayload) {
                        raid.sendWaveReport(room.region, "LNW2 ${room.id} $payload")
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

    private class Rolled(val source: Int, val damage: Int, val distribution: DamageRoll)

    private class Body(val region: Region, val damage: NylocasWaveStats.Damage) {
        val rolls = mutableListOf<Rolled>()
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
    private val luck = Array(4) { Luck() }
    private var unmeasured = 0
    private var removalCycle = -1
    private var populationWithoutOverkillDelta = 0

    fun impact(
        body: Damage,
        style: NylocasStyle,
        damage: Int,
        hp: Int,
        thrall: Boolean,
        cycle: Int,
        distribution: DamageRoll? = null,
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
        if (body.positiveHits == 0) {
            if (distribution != null && !thrall) {
                val group = luck[(if (wave >= 21) 2 else 0) + body.size.ordinal]
                group.attempts++
                group.expected += distribution.chanceAtLeast(hp + damage)
                if (hp == 0) group.actual++
            } else {
                unmeasured++
            }
        }
        body.positiveHits++
        if (hp != 0) return
        body.killCycle = cycle
        body.deathStyle = style
        kills[body.size.ordinal]++
        styleKills[style.ordinal]++
        if (body.positiveHits == 1) oneShots[body.size.ordinal]++
        if (thrall) thralls++
    }

    fun split(style: NylocasStyle) {
        val phase = if (wave >= 31) 2 else if (wave >= 21) 1 else 0
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
                unmeasured)
            .joinToString(" ")

    private class Luck {
        var attempts = 0
        var actual = 0
        var expected = 0.0
    }

    class Damage(val size: NylocasSize) {
        var positiveHits = 0
        var killCycle: Int? = null
        var deathStyle: NylocasStyle? = null
        var overkilled = false
    }
}
