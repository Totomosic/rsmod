package org.rsmod.content.interfaces.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.rsmod.api.config.refs.varbits
import org.rsmod.api.player.vars.intVarBit
import org.rsmod.api.testing.GameTestState
import org.rsmod.content.interfaces.settings.scripts.EscClosesInterfaceScript
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.player.SessionStateEvent

class EscClosesInterfaceTest {
    @Test
    fun GameTestState.`esc closes interfaces is enabled once per account`() =
        runGameTest(EscClosesInterfaceScript::class) {
            eventBus.publish(SessionStateEvent.Login(player))
            assertEquals(1, player.escSetting)

            // A player who turns it off keeps it off on later logins.
            player.escSetting = 0
            eventBus.publish(SessionStateEvent.Login(player))
            assertEquals(0, player.escSetting)
        }
}

private var Player.escSetting by intVarBit(varbits.keybinding_esc_to_close)
