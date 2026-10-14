package org.rsmod.content.interfaces.settings.scripts

import net.rsprot.protocol.game.outgoing.interfaces.IfCloseSub
import org.junit.jupiter.api.Test
import org.rsmod.api.config.refs.components
import org.rsmod.api.config.refs.interfaces
import org.rsmod.api.testing.GameTestState
import org.rsmod.content.interfaces.settings.configs.setting_components
import org.rsmod.game.ui.UserInterface

class SettingsSideScriptTest {
    @Test
    fun GameTestState.`close all settings through button packet`() =
        runGameTest(SettingsSideScript::class) {
            player.ifOpenOverlay(interfaces.settings_side, components.toplevel_target_side11)
            player.ifButton(setting_components.settings_open)
            advance()
            assertTrue(player.ui.containsOverlay(interfaces.settings))
            val target =
                checkNotNull(player.ui.overlays.getComponent(UserInterface(interfaces.settings.id)))

            player.ifButton(setting_components.settings_close)
            advance()

            assertFalse(player.ui.containsOverlay(interfaces.settings))
            assertTrue(player.ui.containsOverlay(interfaces.settings_side))
            assertTrue(IfCloseSub(target.parent, target.child) in client)
        }

    @Test
    fun GameTestState.`reopen all settings after closing`() =
        runGameTest(SettingsSideScript::class) {
            player.ifOpenOverlay(interfaces.settings_side, components.toplevel_target_side11)

            repeat(2) {
                player.ifButton(setting_components.settings_open)
                advance()
                assertTrue(player.ui.containsOverlay(interfaces.settings))

                player.ifButton(setting_components.settings_close)
                advance()
                assertFalse(player.ui.containsOverlay(interfaces.settings))
                assertTrue(client.hasAny<IfCloseSub>())
            }
        }
}
