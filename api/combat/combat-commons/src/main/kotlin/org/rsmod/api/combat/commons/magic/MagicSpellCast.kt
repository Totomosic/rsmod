package org.rsmod.api.combat.commons.magic

import org.rsmod.events.UnboundEvent
import org.rsmod.game.entity.Player

/** Published once a spell is committed, including combat splashes and rune-free spells. */
public data class MagicSpellCast(public val player: Player, public val spellbook: Spellbook?) :
    UnboundEvent
