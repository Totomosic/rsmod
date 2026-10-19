package org.rsmod.api.npc.access

import jakarta.inject.Inject
import org.rsmod.annotations.InternalApi
import org.rsmod.game.entity.Npc
import org.rsmod.game.hit.Hit

public class StandardNpcAccessLauncher
@Inject
constructor(private val contextFactory: StandardNpcAccessContextFactory) {
    /** Applies an already queued impact without replacing the suspended death coroutine. */
    @OptIn(InternalApi::class)
    public fun processDeathHit(npc: Npc, hit: Hit) {
        val coroutine = checkNotNull(npc.activeCoroutine)
        val context = contextFactory.create()
        StandardNpcAccess(npc, coroutine, context).processQueuedHit(hit)
    }

    public fun launch(npc: Npc, block: suspend StandardNpcAccess.() -> Unit) {
        check(!npc.isBusy) { "Npc must not be busy: $npc" }
        val context = contextFactory.create()
        npc.launch {
            val standardAccess = StandardNpcAccess(npc, this, context)
            block(standardAccess)
        }
    }
}
