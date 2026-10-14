package org.rsmod.api.freeze

import jakarta.inject.Inject
import org.rsmod.api.config.refs.timers
import org.rsmod.api.config.refs.walktriggers
import org.rsmod.api.player.output.clearMapFlag
import org.rsmod.api.player.output.mes
import org.rsmod.api.script.onNpcWalkTrigger
import org.rsmod.api.script.onPlayerSoftTimer
import org.rsmod.api.script.onPlayerWalkTrigger
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.type.walktrig.WalkTriggerType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Blocks movement for frozen entities.
 *
 * The movement processors clear an entity's walk trigger right before publishing it, so while the
 * entity is still frozen the trigger is set again to keep blocking future movement.
 */
public class FreezeScript @Inject constructor(private val playerList: PlayerList) : PluginScript() {
    override fun ScriptContext.startup() {
        onPlayerWalkTrigger(walktriggers.frozen) { player.frozenWalk() }
        onPlayerWalkTrigger(walktriggers.pvp_frozen) { player.pvpFrozenWalk() }
        onNpcWalkTrigger(walktriggers.frozen) { npc.frozenWalk() }
        onPlayerSoftTimer(timers.freeze_thaw) { player.thaw() }
    }

    private fun Player.frozenWalk() {
        if (isFrozen()) {
            blockMovement(walktriggers.frozen)
        }
    }

    private fun Player.pvpFrozenWalk() {
        if (!isFrozen()) {
            return
        }
        val source = resolveFreezeSource(playerList)
        if (source == null || !isWithinPvpFreezeRange(source)) {
            endFreeze()
            return
        }
        blockMovement(walktriggers.pvp_frozen)
    }

    private fun Player.blockMovement(trigger: WalkTriggerType) {
        abortRoute()
        clearMapFlag()
        walkTrigger(trigger)
        mes(FROZEN_MESSAGE)
    }

    private fun Npc.frozenWalk() {
        if (isFrozen()) {
            abortRoute()
            walkTrigger(walktriggers.frozen)
        }
    }

    private fun Player.thaw() {
        val remaining = remainingFreezeCycles()
        if (remaining > 0) {
            softTimer(timers.freeze_thaw, remaining)
            return
        }
        clearSoftTimer(timers.freeze_thaw)
        if (walkTrigger.isFreezeTrigger()) {
            clearWalkTrigger()
        }
    }

    private companion object {
        const val FROZEN_MESSAGE = "A magical force stops you from moving."
    }
}
