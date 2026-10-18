package org.rsmod.content.interfaces.settings.scripts

import jakarta.inject.Inject
import java.nio.file.Files
import java.sql.DriverManager
import net.rsprot.protocol.game.incoming.misc.user.ClientCheat
import net.rsprot.protocol.game.outgoing.varp.VarpLarge
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.rsmod.api.account.character.main.CharacterAccountApplier
import org.rsmod.api.account.character.main.CharacterAccountRepository
import org.rsmod.api.config.refs.components
import org.rsmod.api.config.refs.interfaces
import org.rsmod.api.config.refs.modlevels
import org.rsmod.api.config.refs.varbits
import org.rsmod.api.db.DatabaseConnection
import org.rsmod.api.net.rsprot.handlers.ClientCheatHandler
import org.rsmod.api.player.ui.ifCloseOverlay
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.testing.GameTestState
import org.rsmod.content.interfaces.settings.configs.keybind_varbits
import org.rsmod.content.interfaces.settings.configs.setting_varbits
import org.rsmod.game.entity.player.SessionStateEvent
import org.rsmod.game.type.varp.VarpLifetime

class KeybindSettingsScriptTest {
    class Dependencies @Inject constructor(val handler: ClientCheatHandler)

    @Test
    fun GameTestState.`assignment clears every conflicting menu across packed varps`() =
        runInjectedGameTest(Dependencies::class, null, KeybindSettingsScript::class) { deps ->
            player.ifOpenOverlay(interfaces.settings, components.toplevel_target_floater)
            VarPlayerIntMapSetter.set(player, keybind_varbits.stats, 2)
            VarPlayerIntMapSetter.set(player, keybind_varbits.magic, 2)
            VarPlayerIntMapSetter.set(player, keybind_varbits.music, 2)
            deps.handler.handle(player, ClientCheat("localscape_keybind 25 2"))

            assertEquals(2, player.vars[keybind_varbits.inventory])
            assertEquals(0, player.vars[keybind_varbits.stats])
            assertEquals(0, player.vars[keybind_varbits.magic])
            assertEquals(0, player.vars[keybind_varbits.music])
            assertEquals(1, player.vars[keybind_varbits.combat])
        }

    class PersistenceDependencies
    @Inject
    constructor(
        val handler: ClientCheatHandler,
        val repository: CharacterAccountRepository,
        val applier: CharacterAccountApplier,
    )

    @Test
    fun GameTestState.`reassigned keys and None survive account save load and login`() =
        runInjectedGameTest(PersistenceDependencies::class, null, KeybindSettingsScript::class) {
            deps ->
            player.modLevel = cacheTypes.modLevels[modlevels.player]
            player.ifOpenOverlay(interfaces.settings, components.toplevel_target_floater)
            advance(2)
            deps.handler.handle(player, ClientCheat("localscape_keybind 25 2"))
            deps.handler.handle(player, ClientCheat("localscape_keybind 20 12"))
            deps.handler.handle(player, ClientCheat("localscape_keybind 27 1"))
            val saved = player.vars.backing.toMap()

            val database = Files.createTempFile("keybind-settings-", ".db")
            val restored = registerPlayer()
            try {
                val url = "jdbc:sqlite:$database"
                Flyway.configure()
                    .dataSource(url, "", "")
                    .locations("classpath:db/migration")
                    .load()
                    .migrate()
                DriverManager.getConnection(url).use { raw ->
                    val connection = DatabaseConnection(raw)
                    val account =
                        checkNotNull(
                            deps.repository.insertOrSelectAccountId(connection, "keybinds", "test")
                        )
                    val character =
                        checkNotNull(
                            deps.repository.insertAndSelectCharacterId(connection, account)
                        )
                    deps.repository.save(connection, player, account, character)
                    val loaded =
                        checkNotNull(
                            deps.repository.selectAndCreateMetadataList(connection, "keybinds")
                        )
                    deps.applier.apply(restored, loaded.accountData)
                    eventBus.publish(SessionStateEvent.Login(restored))
                }
            } finally {
                Files.deleteIfExists(database)
            }

            for (binding in
                listOf(
                    keybind_varbits.inventory,
                    keybind_varbits.stats,
                    keybind_varbits.magic,
                    keybind_varbits.emotes,
                    keybind_varbits.music,
                    keybind_varbits.combat,
                )) {
                assertEquals(saved[binding.baseVar.id], restored.vars[binding.baseVar])
            }
            assertEquals(2, restored.vars[keybind_varbits.inventory])
            assertEquals(0, restored.vars[keybind_varbits.stats])
            assertEquals(12, restored.vars[keybind_varbits.magic])
            assertEquals(0, restored.vars[keybind_varbits.emotes])
            assertEquals(1, restored.vars[keybind_varbits.music])
            assertEquals(0, restored.vars[keybind_varbits.combat])
        }

    @Test
    fun GameTestState.`queued keybind save survives settings closing before delivery`() =
        runInjectedGameTest(Dependencies::class, null, KeybindSettingsScript::class) { deps ->
            player.ifOpenOverlay(interfaces.settings, components.toplevel_target_floater)
            advance(2)
            player.ifCloseOverlay(interfaces.settings, eventBus)
            deps.handler.handle(player, ClientCheat("localscape_keybind 25 2"))
            eventBus.publish(SessionStateEvent.Login(player))

            assertEquals(2, player.vars[keybind_varbits.inventory])
            assertEquals(0, player.vars[keybind_varbits.stats])
        }

    @Test
    fun GameTestState
        .`side panel hotkey preference syncs and survives login and keybind changes`() =
        runInjectedGameTest(Dependencies::class, null, KeybindSettingsScript::class) { deps ->
            player.ifOpenOverlay(interfaces.settings, components.toplevel_target_floater)
            advance(2)
            val preference = setting_varbits.hotkey_cannot_close_sidepanel
            deps.handler.handle(player, ClientCheat("localscape_sidepanel_hotkeys 1"))

            assertEquals(1, player.vars[preference])
            assertTrue(VarpLarge(preference.baseVar.id, player.vars[preference.baseVar]) in client)
            assertEquals(VarpLifetime.Perm, preference.baseVar.scope)
            deps.handler.handle(player, ClientCheat("localscape_keybind 25 2"))
            deps.handler.handle(player, ClientCheat("localscape_keybind_reset"))
            eventBus.publish(SessionStateEvent.Login(player))
            assertEquals(1, player.vars[preference])

            deps.handler.handle(player, ClientCheat("localscape_sidepanel_hotkeys 0"))
            eventBus.publish(SessionStateEvent.Login(player))
            assertEquals(0, player.vars[preference])
        }

    @Test
    fun GameTestState
        .`side panel hotkey preference preserves packed settings and rejects invalid requests`() =
        runInjectedGameTest(Dependencies::class, null, KeybindSettingsScript::class) { deps ->
            val preference = setting_varbits.hotkey_cannot_close_sidepanel
            deps.handler.handle(player, ClientCheat("localscape_sidepanel_hotkeys 1"))
            assertEquals(0, player.vars[preference])

            player.ifOpenOverlay(interfaces.settings, components.toplevel_target_floater)
            VarPlayerIntMapSetter.set(player, varbits.keybinding_esc_to_close, 1)
            deps.handler.handle(player, ClientCheat("localscape_sidepanel_hotkeys 1"))
            deps.handler.handle(player, ClientCheat("localscape_sidepanel_hotkeys 1"))
            assertEquals(1, player.vars[preference])
            assertEquals(1, player.vars[varbits.keybinding_esc_to_close])
            assertEquals(4, player.vars[keybind_varbits.inventory])

            val original = player.vars.backing.toMap()
            for (args in listOf("", "-1", "2", "nope", "1 0")) {
                deps.handler.handle(player, ClientCheat("localscape_sidepanel_hotkeys $args"))
            }
            assertEquals(original, player.vars.backing.toMap())
        }

    @Test
    fun GameTestState.`keybind selection syncs and replaces duplicate binding`() =
        runInjectedGameTest(Dependencies::class, null, KeybindSettingsScript::class) { deps ->
            player.ifOpenOverlay(interfaces.settings, components.toplevel_target_floater)
            advance(2)
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
            deps.handler.handle(player, ClientCheat("localscape_keybind 238 2"))
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
