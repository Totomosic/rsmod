package org.rsmod.api.game.process.npc

import jakarta.inject.Inject
import org.rsmod.api.config.refs.queues
import org.rsmod.api.npc.access.StandardNpcAccessLauncher
import org.rsmod.api.npc.events.NpcQueueEvents
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Npc
import org.rsmod.game.hit.Hit
import org.rsmod.game.queue.NpcQueueList
import org.rsmod.game.type.npc.UnpackedNpcType

public class NpcQueueProcessor
@Inject
constructor(private val eventBus: EventBus, private val accessLauncher: StandardNpcAccessLauncher) {
    public fun process(npc: Npc) {
        // An existing death starts before impacts due on its animation's first tick.
        npc.publishExpiredQueues(Pass.Death)
        npc.publishExpiredQueues(Pass.All)
    }

    public fun processDeathHits(npc: Npc) {
        if (npc.deathState?.animationStarted == true) npc.publishExpiredQueues(Pass.DeathHits)
    }

    private fun Npc.publishExpiredQueues(pass: Pass) {
        while (queueList.isNotEmpty) {
            var processedNone = true

            val iterator = queueList.iterator() ?: break
            while (iterator.hasNext()) {
                val queue = iterator.next()
                if (pass == Pass.Death && queue.id != queues.death.id) continue
                if (pass == Pass.DeathHits && queue.args !is Hit) continue

                if (queue.processedCycle != currentMapClock) {
                    queue.processedCycle = currentMapClock
                    queue.remainingCycles--
                }

                if (queue.remainingCycles > 0) {
                    continue
                }

                val death = deathState
                if (
                    queue.id == queues.death.id &&
                        death != null &&
                        death.queuedCycle == currentMapClock &&
                        !death.accelerated
                )
                    continue

                val hit = queue.args as? Hit
                val deathHit = death?.sequenceStarted == true && hitpoints == 0 && hit != null
                if (!isBusy || deathHit) {
                    processedNone = false
                    iterator.remove()
                    if (deathHit) accessLauncher.processDeathHit(this, checkNotNull(hit))
                    else publishEvent(queue)
                }
            }
            iterator.cleanUp()

            if (processedNone || queueList.size == 1) {
                break
            }
        }
    }

    private enum class Pass {
        Death,
        DeathHits,
        All,
    }

    private fun Npc.publishEvent(queue: NpcQueueList.Queue, type: UnpackedNpcType = visType) {
        val packedType = EventBus.composeLongKey(type.id, queue.id)
        val typeTrigger = eventBus.suspend[NpcQueueEvents.Type::class.java, packedType]
        if (typeTrigger != null) {
            val event = NpcQueueEvents.Type(this, queue.args, queue.id)
            accessLauncher.launch(this) { typeTrigger(event) }
            return
        }

        if (type.contentGroup != -1) {
            val packedContentGroup = EventBus.composeLongKey(type.contentGroup, queue.id)
            val contentTrigger =
                eventBus.suspend[NpcQueueEvents.Content::class.java, packedContentGroup]
            if (contentTrigger != null) {
                val event = NpcQueueEvents.Content(this, queue.args, type.contentGroup, queue.id)
                accessLauncher.launch(this) { contentTrigger(event) }
                return
            }
        }

        val event = NpcQueueEvents.Default(this, queue.args, queue.id)
        accessLauncher.launch(this) { eventBus.publish(this, event) }
    }
}
