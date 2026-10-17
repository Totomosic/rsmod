package org.rsmod.api.combat.commons

import org.rsmod.events.UnboundEvent
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player

/** The primary NPC of a player's attack, before any secondary targets or splats. */
public data class PlayerAttackTarget(public val player: Player, public val target: Npc) :
    UnboundEvent
