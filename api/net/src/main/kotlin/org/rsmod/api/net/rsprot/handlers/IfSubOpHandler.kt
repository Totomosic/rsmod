package org.rsmod.api.net.rsprot.handlers

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import net.rsprot.protocol.game.incoming.buttons.IfSubOp
import org.rsmod.api.net.rsprot.player.InterfaceEvents
import org.rsmod.api.player.ui.IfOverlaySubOp
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Player
import org.rsmod.game.type.comp.ComponentTypeList
import org.rsmod.game.type.interf.IfButtonOp
import org.rsmod.game.type.interf.IfEvent
import org.rsmod.game.type.interf.InterfaceTypeList
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.ui.Component

/**
 * Handles `IF_SUBOP`: the client sends it when a player picks an entry from a right-click submenu
 * on an interface component, such as "Rub" → "Edgeville" on an inventory amulet of glory (the obj's
 * `isubop` entries). [IfSubOp.op] is the parent interface op (encoded like `If3Button.op`) and
 * [IfSubOp.subop] is the 1-based sub-op id.
 */
class IfSubOpHandler
@Inject
constructor(
    private val eventBus: EventBus,
    private val interfaceTypes: InterfaceTypeList,
    private val componentTypes: ComponentTypeList,
    private val objTypes: ObjTypeList,
) : MessageHandler<IfSubOp> {
    private val logger = InlineLogger()

    private val IfSubOp.asComponent: Component
        get() = Component(interfaceId, componentId)

    override fun handle(player: Player, message: IfSubOp) {
        val buttonOp = IfButtonOp.entries.firstOrNull { it.slot == message.op }
        if (buttonOp == null || message.subop <= 0) {
            logger.debug { "Invalid IfSubOp: $message" }
            return
        }
        val componentType = componentTypes[message.asComponent]
        val interfaceType = interfaceTypes[message.asComponent]
        val opEnabled =
            InterfaceEvents.isEnabled(
                player.ui,
                componentType,
                message.sub,
                IfEvent.entries.first { it.name == buttonOp.name },
            )
        if (!opEnabled) {
            return
        }
        if (player.ui.containsOverlay(interfaceType) || player.ui.containsTopLevel(interfaceType)) {
            val event =
                IfOverlaySubOp(
                    player = player,
                    component = componentType,
                    comsub = message.sub,
                    obj = objTypes[message.obj],
                    op = buttonOp,
                    subop = message.subop,
                )
            logger.debug { "[Overlay] IfSubOp: $message (event=$event)" }
            eventBus.publish(event)
            return
        }
        logger.debug { "[Unhandled] IfSubOp: $message" }
    }
}
