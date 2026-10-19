package org.rsmod.content.areas.dungeons.nylocas

import jakarta.inject.Inject
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.hit.plugin.NpcHitScript
import org.rsmod.api.npc.events.NpcHitEvents
import org.rsmod.api.npc.hit.modifier.NpcHitModifier
import org.rsmod.api.npc.hit.queueHit
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.testing.GameTestState
import org.rsmod.events.EventBus
import org.rsmod.game.MapClock
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.npc.NpcMode
import org.rsmod.game.entity.npc.NpcStateEvents
import org.rsmod.game.hit.HitType
import org.rsmod.game.movement.MoveRestrict
import org.rsmod.game.movement.MoveSpeed
import org.rsmod.game.seq.EntitySeq
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.map.CoordGrid

class NylocasOverkillTest {
    class Dependencies
    @Inject
    constructor(
        val types: NpcTypeList,
        val npcs: NpcRepository,
        val modifier: NpcHitModifier,
        val events: EventBus,
        val clock: MapClock,
    )

    @ParameterizedTest(name = "{0}, moving={1}, {2}")
    @MethodSource("cases")
    fun `only an actual zero clamped splat after a player kill accelerates removal`(
        size: NylocasSize,
        moving: Boolean,
        scenario: String,
        state: GameTestState,
    ) =
        state.runInjectedGameTest(
            Dependencies::class,
            null,
            NpcHitScript::class,
            NylocasDeaths::class,
        ) { deps ->
            player.telejump(CoordGrid(512, 512, 3))
            val type = nylocas_npcs.incoming(size, NylocasStyle.Melee)
            val npc = Npc(deps.types[type], player.coords.translateX(2))
            npc.mode = NpcMode.None
            npc.setHunt(0)
            npc.aiTimer(0)
            npc.moveRestrict = MoveRestrict.Normal
            npc.defaultMoveSpeed = MoveSpeed.Walk
            npc.collisionSize = 1
            for (x in 0..12) allocZoneCollision(npc.coords.translateX(x))
            deps.npcs.add(npc, Int.MAX_VALUE)
            npc.respawns = false
            advance(ticks = 2)
            if (moving) {
                npc.walk(npc.coords.translateX(10))
                advance()
                assertTrue(npc.hasMovedThisCycle)
            }
            val hp = npc.hitpoints
            // Triple: scheduled impact delay, unclamped damage, thrall attribution.
            val hits =
                when (scenario) {
                    "player-extra" -> listOf(Triple(1, hp, false), Triple(1, 1, false))
                    "thrall-extra" -> listOf(Triple(1, hp, false), Triple(1, 1, true))
                    "thrall-kill" -> listOf(Triple(1, hp, true), Triple(1, 1, false))
                    "late-thrall-kill" ->
                        listOf(
                            Triple(1, hp, true),
                            Triple(if (size == NylocasSize.Small) 2 else 3, 1, false),
                        )
                    "thrall-finisher" -> listOf(Triple(1, hp - 1, false), Triple(1, 1, true))
                    "player-finisher" -> listOf(Triple(1, hp - 1, true), Triple(1, 1, false))
                    "natural-zero" -> listOf(Triple(1, hp, false), Triple(1, 0, false))
                    "thrall-zero" -> listOf(Triple(1, hp, false), Triple(1, 0, true))
                    "future" -> listOf(Triple(1, hp, false), Triple(10, 1, false))
                    "during-animation" ->
                        listOf(
                            Triple(1, hp, false),
                            Triple(if (size == NylocasSize.Small) 2 else 3, 1, false),
                        )
                    "late-thrall" ->
                        listOf(
                            Triple(1, hp, false),
                            Triple(if (size == NylocasSize.Small) 2 else 3, 1, true),
                        )
                    "late-zero" ->
                        listOf(
                            Triple(1, hp, false),
                            Triple(if (size == NylocasSize.Small) 2 else 3, 0, false),
                        )
                    "late-multiple" ->
                        listOf(Triple(1, hp, false)) +
                            List(3) { Triple(if (size == NylocasSize.Small) 2 else 3, 1, false) }
                    "arrival-only" -> listOf(Triple(1, hp, false), Triple(2, 1, false))
                    "spread-extras" ->
                        listOf(
                            Triple(1, hp, false),
                            Triple(3, 1, false),
                            Triple(4, 1, false),
                            Triple(5, 1, false),
                        )
                    "last-animation-tick" ->
                        listOf(Triple(1, hp, false), Triple(if (moving) 7 else 6, 1, false))
                    else -> error(scenario)
                }
            val accelerated =
                scenario in
                    setOf(
                        "player-extra",
                        "thrall-extra",
                        "during-animation",
                        "late-thrall",
                        "late-multiple",
                        "spread-extras",
                        "last-animation-tick",
                    )
            val ordinaryRemoval = if (size == NylocasSize.Small) 2 else if (moving) 7 else 6
            var lethalTick = -1
            var removalTick = -1
            val impacts = mutableListOf<Triple<Int, Int, Boolean>>()
            val animations = mutableListOf<Int>()
            deps.events.subscribeKeyed(NpcHitEvents.Impact::class.java, type.id.toLong()) {
                if (this.npc === npc) {
                    if (npc.hitpoints == 0 && lethalTick == -1) lethalTick = deps.clock.cycle
                    impacts += Triple(deps.clock.cycle, hit.damage, hit.isThrall)
                }
            }
            deps.events.subscribeUnbound(NpcStateEvents.Delete::class.java) {
                if (this.npc === npc) removalTick = deps.clock.cycle
            }
            deps.events.subscribeUnbound(GameLifecycle.NpcsProcessed::class.java) {
                if (npc.pendingSequence != EntitySeq.NULL) animations += npc.pendingSequence.id
            }
            for ((delay, damage, thrall) in hits) {
                val modifier = NpcHitModifier { target ->
                    isThrall = thrall
                    with(deps.modifier) { modify(target) }
                }
                npc.queueHit(player, delay, HitType.Melee, damage, modifier)
            }
            advanceUntil(predicate = { !npc.isSlotAssigned }, timeoutTicks = 12)
            var remaining = hp
            val expectedHits =
                hits
                    .filter { it.first < 10 }
                    .map { (delay, damage, thrall) ->
                        val actual = minOf(remaining, damage)
                        remaining -= actual
                        Triple(lethalTick + delay - 1, actual, thrall)
                    }
            assertEquals(expectedHits, impacts)
            assertEquals(ordinaryRemoval - if (accelerated) 1 else 0, removalTick - lethalTick)
            assertEquals(listOf(8005), animations)
        }

    companion object {
        @JvmStatic
        fun cases(): List<Arguments> = buildList {
            for (size in NylocasSize.entries) for (moving in listOf(false, true)) {
                for (scenario in
                    listOf(
                        "player-extra",
                        "thrall-extra",
                        "thrall-kill",
                        "late-thrall-kill",
                        "thrall-finisher",
                        "player-finisher",
                        "natural-zero",
                        "thrall-zero",
                        "future",
                        "during-animation",
                        "late-thrall",
                        "late-zero",
                        "late-multiple",
                    )) add(Arguments.of(size, moving, scenario))
            }
            add(Arguments.of(NylocasSize.Big, true, "arrival-only"))
            for (moving in listOf(false, true)) {
                add(Arguments.of(NylocasSize.Big, moving, "spread-extras"))
                add(Arguments.of(NylocasSize.Big, moving, "last-animation-tick"))
            }
        }
    }
}
