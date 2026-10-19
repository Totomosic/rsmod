package org.rsmod.content.areas.dungeons.nylocas

import jakarta.inject.Inject
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.hit.plugin.NpcHitScript
import org.rsmod.api.npc.events.NpcHitEvents
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

/** Independent timing probes: impact, animation and deletion all use the live MapClock. */
class NylocasDeathTimingReviewTest {
    class Dependencies
    @Inject
    constructor(
        val types: NpcTypeList,
        val npcs: NpcRepository,
        val events: EventBus,
        val clock: MapClock,
    )

    @ParameterizedTest(name = "{0}, {1}, fighting={2}, moving={3}, {4}")
    @MethodSource("cases")
    fun `death timing includes hit order animation and final step`(
        size: NylocasSize,
        style: NylocasStyle,
        fighting: Boolean,
        moving: Boolean,
        pattern: String,
        state: GameTestState,
    ) =
        state.runInjectedGameTest(
            Dependencies::class,
            null,
            NpcHitScript::class,
            NylocasDeaths::class,
        ) { deps ->
            player.telejump(CoordGrid(512, 512, 3))
            val type =
                if (fighting) nylocas_npcs.fighting(size, style)
                else nylocas_npcs.incoming(size, style)
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
            val beforeImpact = npc.coords
            val hp = npc.hitpoints
            val damages =
                when (pattern) {
                    "exact" -> listOf(hp)
                    "large-single" -> listOf(hp * 4)
                    "lethal-last" -> listOf(hp - 1, 1)
                    "lethal-last-large" -> listOf(hp - 1, hp * 4)
                    "zero-before" -> listOf(0, hp)
                    "extra-positive" -> listOf(hp, 1)
                    "extra-zero" -> listOf(hp, 0)
                    "lethal-middle" -> listOf(hp - 1, 1, 1)
                    "three-extra" -> listOf(hp, 1, 1, 1)
                    else -> error(pattern)
                }
            val accelerated = pattern in setOf("extra-positive", "lethal-middle", "three-extra")
            // Ordinary offsets come from Blert's appendix; acceleration is tested separately
            // as simulator behavior because the appendix does not describe extra hitsplats.
            val ordinaryRemoval = if (size == NylocasSize.Small) 2 else if (moving) 7 else 6
            val animationLength = if (size == NylocasSize.Small) 1 else 5
            var lethalTick = -1
            var animationTick = -1
            var removalTick = -1
            val impacts = mutableListOf<Int>()
            deps.events.subscribeKeyed(NpcHitEvents.Impact::class.java, type.id.toLong()) {
                if (this.npc === npc) {
                    assertEquals(moving, npc.hasMovedPreviousCycle)
                    impacts += hit.damage
                    if (npc.hitpoints == 0 && lethalTick == -1) lethalTick = deps.clock.cycle
                }
            }
            deps.events.subscribeUnbound(GameLifecycle.NpcsProcessed::class.java) {
                if (npc.pendingSequence != EntitySeq.NULL && animationTick == -1)
                    animationTick = deps.clock.cycle
            }
            deps.events.subscribeUnbound(NpcStateEvents.Delete::class.java) {
                if (this.npc === npc) removalTick = deps.clock.cycle
            }
            val hitType =
                when (style) {
                    NylocasStyle.Melee -> HitType.Melee
                    NylocasStyle.Ranged -> HitType.Ranged
                    NylocasStyle.Magic -> HitType.Magic
                }
            for (damage in damages) npc.queueHit(player, delay = 1, hitType, damage)
            advanceUntil(predicate = { !npc.isSlotAssigned }, timeoutTicks = 12)
            var remaining = hp
            val expectedImpacts =
                damages.map { damage -> damage.coerceAtMost(remaining).also { remaining -= it } }
            assertEquals(expectedImpacts, impacts)
            assertTrue(lethalTick >= 0)
            val expectedRemoval = ordinaryRemoval - if (accelerated) 1 else 0
            assertEquals(expectedRemoval, removalTick - lethalTick)
            assertEquals(expectedRemoval - animationLength, animationTick - lethalTick)
            assertEquals(animationLength, removalTick - animationTick)
            assertEquals(beforeImpact.translateX(if (moving && !accelerated) 1 else 0), npc.coords)
        }

    @ParameterizedTest
    @CsvSource("Small, false, 2", "Small, true, 2", "Big, false, 6", "Big, true, 7")
    fun `a future queued hit does not accelerate death without a second impact`(
        size: NylocasSize,
        moving: Boolean,
        expectedRemoval: Int,
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
            var lethalTick = -1
            var removalTick = -1
            val impacts = mutableListOf<Int>()
            deps.events.subscribeKeyed(NpcHitEvents.Impact::class.java, type.id.toLong()) {
                if (this.npc === npc) {
                    if (lethalTick == -1) lethalTick = deps.clock.cycle
                    impacts += hit.damage
                }
            }
            deps.events.subscribeUnbound(NpcStateEvents.Delete::class.java) {
                if (this.npc === npc) removalTick = deps.clock.cycle
            }
            val hp = npc.hitpoints
            npc.queueHit(player, delay = 1, HitType.Melee, damage = hp)
            npc.queueHit(player, delay = 10, HitType.Melee, damage = 1)
            advanceUntil(predicate = { !npc.isSlotAssigned }, timeoutTicks = 12)
            assertEquals(listOf(hp), impacts)
            assertEquals(expectedRemoval, removalTick - lethalTick)
        }

    @ParameterizedTest
    @CsvSource(
        "Small, false, false, 2",
        "Small, false, true, 1",
        "Small, true, false, 2",
        "Small, true, true, 1",
        "Big, false, false, 7",
        "Big, false, true, 5",
        "Big, true, false, 6",
        "Big, true, true, 6",
    )
    fun `starting or finishing movement changes the big death branch`(
        size: NylocasSize,
        justArrived: Boolean,
        extraHit: Boolean,
        expectedRemoval: Int,
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
            npc.walk(npc.coords.translateX(if (justArrived) 1 else 10))
            if (justArrived) {
                advance()
                assertTrue(npc.hasMovedThisCycle)
            } else {
                assertFalse(npc.hasMovedThisCycle)
            }
            val beforeImpact = npc.coords
            var lethalTick = -1
            var removalTick = -1
            deps.events.subscribeKeyed(NpcHitEvents.Impact::class.java, type.id.toLong()) {
                if (this.npc === npc && lethalTick == -1) lethalTick = deps.clock.cycle
            }
            deps.events.subscribeUnbound(NpcStateEvents.Delete::class.java) {
                if (this.npc === npc) removalTick = deps.clock.cycle
            }
            npc.queueHit(player, delay = 1, HitType.Melee, damage = npc.hitpoints)
            if (extraHit) npc.queueHit(player, delay = 1, HitType.Melee, damage = 1)
            advanceUntil(predicate = { !npc.isSlotAssigned }, timeoutTicks = 12)
            assertEquals(expectedRemoval, removalTick - lethalTick)
            assertEquals(
                beforeImpact.translateX(if (!justArrived && !extraHit) 1 else 0),
                npc.coords,
            )
        }

    companion object {
        @JvmStatic
        fun cases(): List<Arguments> = buildList {
            for (size in NylocasSize.entries) for (style in NylocasStyle.entries) for (fighting in
                listOf(false, true)) for (moving in listOf(false, true)) for (pattern in
                listOf(
                    "exact",
                    "large-single",
                    "lethal-last",
                    "lethal-last-large",
                    "zero-before",
                    "extra-positive",
                    "extra-zero",
                    "lethal-middle",
                    "three-extra",
                )) add(Arguments.of(size, style, fighting, moving, pattern))
        }
    }
}
