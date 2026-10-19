package org.rsmod.game.entity.npc

/** Tracks the standard death sequence separately from unrelated NPC delays and queued hits. */
public class NpcDeathState(public val queuedCycle: Int, public val playerKill: Boolean = false) {
    public var sequenceStarted: Boolean = false
    public var animationStarted: Boolean = false
    public var accelerated: Boolean = false
}
