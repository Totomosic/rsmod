package org.rsmod.content.interfaces.settings.scripts

import jakarta.inject.Inject
import net.rsprot.protocol.game.incoming.misc.user.ClientCheat
import net.rsprot.protocol.game.outgoing.varp.VarpLarge
import org.junit.jupiter.api.Test
import org.rsmod.api.config.refs.components
import org.rsmod.api.config.refs.interfaces
import org.rsmod.api.config.refs.varbits
import org.rsmod.api.net.rsprot.handlers.ClientCheatHandler
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.testing.GameTestState
import org.rsmod.content.interfaces.settings.configs.keybind_varbits
import org.rsmod.game.entity.player.SessionStateEvent
import org.rsmod.game.type.varp.VarpLifetime

class KeybindSettingsScriptTest {
    class Dependencies @Inject constructor(val handler: ClientCheatHandler)

    @Test
    fun GameTestState.`keybind selection syncs and replaces duplicate binding`() =
        runInjectedGameTest(Dependencies::class, null, KeybindSettingsScript::class) { deps ->
            player.ifOpenOverlay(interfaces.settings, components.toplevel_target_floater)
            advance()
            deps.handler.handle(player, ClientCheat("localscape_keybind 25 2"))

            assertEquals(2, player.vars[keybind_varbits.inventory])
            assertEquals(0, player.vars[keybind_varbits.stats])
            assertEquals(1, player.vars[keybind_varbits.combat])
            val varp = keybind_varbits.inventory.baseVar
            assertTrue(VarpLarge(varp.id, player.vars[varp]) in client)
            assertEquals(VarpLifetime.Perm, varp.scope)
        }

    @Test
    fun GameTestState.`unbinding survives login and preserves escape preference`() =
        runInjectedGameTest(Dependencies::class, null, KeybindSettingsScript::class) { deps ->
            player.ifOpenOverlay(interfaces.settings, components.toplevel_target_floater)
            advance()
            VarPlayerIntMapSetter.set(player, varbits.keybinding_esc_to_close, 1)
            deps.handler.handle(player, ClientCheat("localscape_keybind 25 0"))
            deps.handler.handle(player, ClientCheat("localscape_keybind 20 12"))
            eventBus.publish(SessionStateEvent.Login(player))

            assertEquals(0, player.vars[keybind_varbits.inventory])
            assertEquals(12, player.vars[keybind_varbits.magic])
            assertEquals(0, player.vars[keybind_varbits.emotes])
            assertEquals(1, player.vars[varbits.keybinding_esc_to_close])
        }

    @Test
    fun GameTestState.`rejects invalid keybind requests`() =
        runInjectedGameTest(Dependencies::class, null, KeybindSettingsScript::class) { deps ->
            val original = player.vars.backing.toMap()
            deps.handler.handle(player, ClientCheat("localscape_keybind 25 2"))
            assertEquals(original, player.vars.backing.toMap())

            player.ifOpenOverlay(interfaces.settings, components.toplevel_target_floater)
            for (args in listOf("25 -1", "25 14", "238 2", "25", "25 2 3", "25 nope")) {
                deps.handler.handle(player, ClientCheat("localscape_keybind $args"))
            }
            assertEquals(original, player.vars.backing.toMap())
        }

    @Test
    fun GameTestState.`restore defaults keeps unrelated packed settings`() =
        runInjectedGameTest(Dependencies::class, null, KeybindSettingsScript::class) { deps ->
            player.ifOpenOverlay(interfaces.settings, components.toplevel_target_floater)
            VarPlayerIntMapSetter.set(player, varbits.keybinding_esc_to_close, 1)
            deps.handler.handle(player, ClientCheat("localscape_keybind 25 13"))
            assertEquals(13, player.vars[keybind_varbits.inventory])
            deps.handler.handle(player, ClientCheat("localscape_keybind_reset"))

            assertEquals(4, player.vars[keybind_varbits.inventory])
            assertEquals(2, player.vars[keybind_varbits.stats])
            assertEquals(6, player.vars[keybind_varbits.prayer])
            assertEquals(0, player.vars[keybind_varbits.music])
            assertEquals(1, player.vars[varbits.keybinding_esc_to_close])
        }
}
