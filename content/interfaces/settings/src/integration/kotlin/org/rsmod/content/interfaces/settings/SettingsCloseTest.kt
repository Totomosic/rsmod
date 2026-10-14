package org.rsmod.content.interfaces.settings

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.rsmod.api.config.refs.components
import org.rsmod.api.config.refs.interfaces
import org.rsmod.api.testing.GameTestState
import org.rsmod.content.interfaces.settings.configs.setting_components
import org.rsmod.content.interfaces.settings.scripts.SettingsSideScript
import org.rsmod.game.type.interf.IfButtonOp

class SettingsCloseTest {
    @Test
    fun GameTestState.`close button closes all settings`() =
        runGameTest(SettingsSideScript::class) {
            player.ifOpenOverlay(interfaces.settings, components.toplevel_target_floater)
            assertTrue(player.ui.containsOverlay(interfaces.settings))
            player.ifButton(setting_components.settings_close)
            advance(ticks = 1)
            assertFalse(player.ui.containsOverlay(interfaces.settings))
        }

    @Test
    fun GameTestState.`escape key closes all settings`() =
        runGameTest(SettingsSideScript::class) {
            player.ifOpenOverlay(interfaces.settings, components.toplevel_target_floater)
            player.ifButton(setting_components.settings_close, op = IfButtonOp.Op2)
            advance(ticks = 1)
            assertFalse(player.ui.containsOverlay(interfaces.settings))
        }
}
