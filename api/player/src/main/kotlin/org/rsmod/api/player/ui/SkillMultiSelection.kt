package org.rsmod.api.player.ui

import org.rsmod.game.type.obj.ObjType

/**
 * The result of a `skillmulti` (Make-X) selection.
 *
 * @param obj the selected [ObjType] option.
 * @param quantity the quantity chosen by the player, ranging from `1` to `28`.
 */
public data class SkillMultiSelection(val obj: ObjType, val quantity: Int)
