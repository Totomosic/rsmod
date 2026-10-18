package org.rsmod.game.proj

import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.PathingEntity
import org.rsmod.game.entity.Player
import org.rsmod.game.type.proj.UnpackedProjAnimType
import org.rsmod.map.CoordGrid
import org.rsmod.map.util.Bounds
import org.rsmod.map.zone.ZoneGrid

public data class ProjAnim(
    public val spotanim: Int,
    public val startHeight: Int,
    public val endHeight: Int,
    public val startTime: Int,
    public val endTime: Int,
    public val angle: Int,
    public val progress: Int,
    public val sourceIndex: Int,
    public val targetIndex: Int,
    public val startCoord: CoordGrid,
    public val endCoord: CoordGrid,
) {
    public val zoneGridX: Int
        get() = startCoord.x and ZoneGrid.X_BIT_MASK

    public val zoneGridZ: Int
        get() = startCoord.z and ZoneGrid.Z_BIT_MASK

    public val clientCycles: Int
        get() = endTime

    public val serverCycles: Int
        get() = calculateHitDelay(endTime)

    /** Raw projectile timing; player magic attacks use [magicDurations]. */
    public val durations: Duration
        get() = Duration(serverDelay = serverCycles, clientDelay = clientCycles)

    public val rangedServerCycles: Int
        get() = calculateRangedHitDelay(endTime)

    public val rangedDurations: Duration
        get() = Duration(serverDelay = rangedServerCycles, clientDelay = clientCycles)

    public val magicServerCycles: Int
        get() = calculateMagicHitDelay(endTime)

    public val magicDurations: Duration
        get() = Duration(serverDelay = magicServerCycles, clientDelay = clientCycles)

    public data class Duration(val serverDelay: Int, val clientDelay: Int)

    public companion object {
        /** Converts raw 20 ms projectile cycles to whole ticks, with a minimum of one. */
        public fun calculateHitDelay(clientCycles: Int): Int = (clientCycles / 30).coerceAtLeast(1)

        /** Ranged hits retain one queue tick in addition to their projectile travel ticks. */
        public fun calculateRangedHitDelay(clientCycles: Int): Int = 1 + clientCycles / 30

        /** Magic hits include one queue tick after the projectile's whole travel ticks. */
        public fun calculateMagicHitDelay(clientCycles: Int): Int = 1 + clientCycles / 30

        private val noSourceSlot: Int
            get() = 0

        private val Player.projAnimSlot: Int
            get() = -(slotId + 1)

        private val Npc.projAnimSlot: Int
            get() = slotId + 1

        public fun calculateEndTime(type: UnpackedProjAnimType, distance: Int): Int {
            return type.delay + type.lengthAdjustment + (type.stepMultiplier * distance)
        }

        private fun calculateEndTime(
            source: Bounds,
            target: CoordGrid,
            type: UnpackedProjAnimType,
        ): Int = calculateEndTime(type, source.distanceTo(Bounds(target)))

        private fun calculateEndTime(
            source: Bounds,
            target: PathingEntity,
            type: UnpackedProjAnimType,
        ): Int = calculateEndTime(type, source.distanceTo(target.bounds()))

        private fun calculateEndTime(
            source: PathingEntity,
            target: CoordGrid,
            type: UnpackedProjAnimType,
        ): Int = calculateEndTime(type, source.distanceTo(target))

        private fun calculateEndTime(
            source: PathingEntity,
            target: PathingEntity,
            type: UnpackedProjAnimType,
        ): Int = calculateEndTime(type, source.distanceTo(target))

        public fun fromBoundsToCoord(
            source: Bounds,
            target: CoordGrid,
            spotanim: Int,
            type: UnpackedProjAnimType,
        ): ProjAnim =
            ProjAnim(
                spotanim = spotanim,
                startHeight = type.startHeight,
                endHeight = type.endHeight,
                startTime = type.delay,
                endTime = calculateEndTime(source, target, type),
                angle = type.angle,
                progress = type.progress,
                sourceIndex = noSourceSlot,
                targetIndex = noSourceSlot,
                startCoord = source.coords,
                endCoord = target,
            )

        public fun fromBoundsToNpc(
            source: Bounds,
            target: Npc,
            spotanim: Int,
            type: UnpackedProjAnimType,
        ): ProjAnim =
            ProjAnim(
                spotanim = spotanim,
                startHeight = type.startHeight,
                endHeight = type.endHeight,
                startTime = type.delay,
                endTime = calculateEndTime(source, target, type),
                angle = type.angle,
                progress = type.progress,
                sourceIndex = noSourceSlot,
                targetIndex = target.projAnimSlot,
                startCoord = source.coords,
                endCoord = target.coords,
            )

        public fun fromBoundsToPlayer(
            source: Bounds,
            target: Player,
            spotanim: Int,
            type: UnpackedProjAnimType,
        ): ProjAnim =
            ProjAnim(
                spotanim = spotanim,
                startHeight = type.startHeight,
                endHeight = type.endHeight,
                startTime = type.delay,
                endTime = calculateEndTime(source, target, type),
                angle = type.angle,
                progress = type.progress,
                sourceIndex = noSourceSlot,
                targetIndex = target.projAnimSlot,
                startCoord = source.coords,
                endCoord = target.coords,
            )

        public fun fromNpcToCoord(
            source: Npc,
            target: CoordGrid,
            spotanim: Int,
            type: UnpackedProjAnimType,
        ): ProjAnim =
            ProjAnim(
                spotanim = spotanim,
                startHeight = type.startHeight,
                endHeight = type.endHeight,
                startTime = type.delay,
                endTime = calculateEndTime(source, target, type),
                angle = type.angle,
                progress = type.progress,
                sourceIndex = source.projAnimSlot,
                targetIndex = noSourceSlot,
                startCoord = source.coords,
                endCoord = target,
            )

        public fun fromNpcToNpc(
            source: Npc,
            target: Npc,
            spotanim: Int,
            type: UnpackedProjAnimType,
        ): ProjAnim =
            ProjAnim(
                spotanim = spotanim,
                startHeight = type.startHeight,
                endHeight = type.endHeight,
                startTime = type.delay,
                endTime = calculateEndTime(source, target, type),
                angle = type.angle,
                progress = type.progress,
                sourceIndex = source.projAnimSlot,
                targetIndex = target.projAnimSlot,
                startCoord = source.coords,
                endCoord = target.coords,
            )

        public fun fromNpcToPlayer(
            source: Npc,
            target: Player,
            spotanim: Int,
            type: UnpackedProjAnimType,
        ): ProjAnim =
            ProjAnim(
                spotanim = spotanim,
                startHeight = type.startHeight,
                endHeight = type.endHeight,
                startTime = type.delay,
                endTime = calculateEndTime(source, target, type),
                angle = type.angle,
                progress = type.progress,
                sourceIndex = source.projAnimSlot,
                targetIndex = target.projAnimSlot,
                startCoord = source.coords,
                endCoord = target.coords,
            )

        public fun fromPlayerToCoord(
            source: Player,
            target: CoordGrid,
            spotanim: Int,
            type: UnpackedProjAnimType,
        ): ProjAnim =
            ProjAnim(
                spotanim = spotanim,
                startHeight = type.startHeight,
                endHeight = type.endHeight,
                startTime = type.delay,
                endTime = calculateEndTime(source, target, type),
                angle = type.angle,
                progress = type.progress,
                sourceIndex = source.projAnimSlot,
                targetIndex = noSourceSlot,
                startCoord = source.coords,
                endCoord = target,
            )

        public fun fromPlayerToNpc(
            source: Player,
            target: Npc,
            spotanim: Int,
            type: UnpackedProjAnimType,
        ): ProjAnim =
            ProjAnim(
                spotanim = spotanim,
                startHeight = type.startHeight,
                endHeight = type.endHeight,
                startTime = type.delay,
                endTime = calculateEndTime(source, target, type),
                angle = type.angle,
                progress = type.progress,
                sourceIndex = source.projAnimSlot,
                targetIndex = target.projAnimSlot,
                startCoord = source.coords,
                endCoord = target.coords,
            )

        public fun fromPlayerToPlayer(
            source: Player,
            target: Player,
            spotanim: Int,
            type: UnpackedProjAnimType,
        ): ProjAnim =
            ProjAnim(
                spotanim = spotanim,
                startHeight = type.startHeight,
                endHeight = type.endHeight,
                startTime = type.delay,
                endTime = calculateEndTime(source, target, type),
                angle = type.angle,
                progress = type.progress,
                sourceIndex = source.projAnimSlot,
                targetIndex = target.projAnimSlot,
                startCoord = source.coords,
                endCoord = target.coords,
            )

        public fun fromCoordToCoord(
            source: CoordGrid,
            target: CoordGrid,
            spotanim: Int,
            type: UnpackedProjAnimType,
        ): ProjAnim = fromBoundsToCoord(Bounds(source), target, spotanim, type)

        public fun fromCoordToNpc(
            source: CoordGrid,
            target: Npc,
            spotanim: Int,
            type: UnpackedProjAnimType,
        ): ProjAnim = fromBoundsToNpc(Bounds(source), target, spotanim, type)

        public fun fromCoordToPlayer(
            source: CoordGrid,
            target: Player,
            spotanim: Int,
            type: UnpackedProjAnimType,
        ): ProjAnim = fromBoundsToPlayer(Bounds(source), target, spotanim, type)
    }
}
