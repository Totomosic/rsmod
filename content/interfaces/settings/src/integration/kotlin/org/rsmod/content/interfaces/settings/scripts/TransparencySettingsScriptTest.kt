package org.rsmod.content.interfaces.settings.scripts

import jakarta.inject.Inject
import java.nio.file.Files
import java.sql.DriverManager
import net.rsprot.protocol.game.incoming.misc.user.ClientCheat
import net.rsprot.protocol.game.outgoing.varp.VarpLarge
import net.rsprot.protocol.game.outgoing.varp.VarpSmall
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
import org.rsmod.api.testing.capture.CaptureClient
import org.rsmod.content.interfaces.settings.configs.setting_varbits
import org.rsmod.content.other.login.LoginScript
import org.rsmod.game.entity.player.SessionStateEvent
import org.rsmod.game.type.varp.VarpLifetime

class TransparencySettingsScriptTest {
    class Dependencies
    @Inject
    constructor(
        val handler: ClientCheatHandler,
        val repository: CharacterAccountRepository,
        val applier: CharacterAccountApplier,
    )

    @Test
    fun GameTestState.`toggles sync independently and preserve adjacent packed settings`() =
        runInjectedGameTest(Dependencies::class, null, TransparencySettingsScript::class) { deps ->
            player.modLevel = cacheTypes.modLevels[modlevels.player]
            advance(2)
            VarPlayerIntMapSetter.set(player, varbits.camera_zoom_mouse_disabled, 1)
            VarPlayerIntMapSetter.set(player, setting_varbits.hotkey_cannot_close_sidepanel, 1)
            val inventory = setting_varbits.side_transparency
            val chatbox = varbits.chatbox_transparency

            for ((name, preference, other) in
                listOf(
                    Triple("inventory", inventory, chatbox),
                    Triple("chatbox", chatbox, inventory),
                )) {
                assertEquals(VarpLifetime.Perm, preference.baseVar.scope)
                for (value in listOf(1, 0)) {
                    deps.handler.handle(player, ClientCheat("localscape_transparency $name $value"))
                    assertEquals(value, player.vars[preference])
                    assertEquals(0, player.vars[other])
                    assertEquals(1, player.vars[varbits.camera_zoom_mouse_disabled])
                    assertEquals(1, player.vars[setting_varbits.hotkey_cannot_close_sidepanel])
                    assertTrue(
                        VarpLarge(preference.baseVar.id, player.vars[preference.baseVar]) in client
                    )
                }
            }
        }

    @Test
    fun GameTestState
        .`queued saves survive closing settings and invalid requests change nothing`() =
        runInjectedGameTest(Dependencies::class, null, TransparencySettingsScript::class) { deps ->
            player.ifOpenOverlay(interfaces.settings, components.toplevel_target_floater)
            advance(2)
            player.ifCloseOverlay(interfaces.settings, eventBus)
            deps.handler.handle(player, ClientCheat("localscape_transparency inventory 1"))
            deps.handler.handle(player, ClientCheat("localscape_transparency chatbox 1"))
            assertEquals(1, player.vars[setting_varbits.side_transparency])
            assertEquals(1, player.vars[varbits.chatbox_transparency])

            val original = player.vars.backing.toMap()
            for (args in
                listOf(
                    "",
                    "inventory",
                    "other 1",
                    "inventory -1",
                    "chatbox 2",
                    "chatbox nope",
                    "chatbox 0 1",
                )) {
                deps.handler.handle(player, ClientCheat("localscape_transparency $args"))
            }
            assertEquals(original, player.vars.backing.toMap())
        }

    @Test
    fun GameTestState.`enabled disabled and mixed preferences survive database reload and login`() =
        runInjectedGameTest(
            Dependencies::class,
            null,
            TransparencySettingsScript::class,
            LoginScript::class,
        ) { deps ->
            player.modLevel = cacheTypes.modLevels[modlevels.player]
            val database = Files.createTempFile("transparency-settings-", ".db")
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
                            deps.repository.insertOrSelectAccountId(
                                connection,
                                "transparency",
                                "test",
                            )
                        )
                    val character =
                        checkNotNull(
                            deps.repository.insertAndSelectCharacterId(connection, account)
                        )
                    for ((inventory, chatbox) in listOf(1 to 1, 0 to 1, 1 to 0, 0 to 0)) {
                        deps.handler.handle(
                            player,
                            ClientCheat("localscape_transparency inventory $inventory"),
                        )
                        deps.handler.handle(
                            player,
                            ClientCheat("localscape_transparency chatbox $chatbox"),
                        )
                        deps.repository.save(connection, player, account, character)
                        val loaded =
                            checkNotNull(
                                deps.repository.selectAndCreateMetadataList(
                                    connection,
                                    "transparency",
                                )
                            )
                        val restoredClient = CaptureClient()
                        val restored = registerPlayer(client = restoredClient)
                        deps.applier.apply(restored, loaded.accountData)
                        eventBus.publish(SessionStateEvent.Login(restored))
                        restoredClient.clearOutgoing()
                        eventBus.publish(SessionStateEvent.EngineLogin(restored))

                        assertEquals(inventory, restored.vars[setting_varbits.side_transparency])
                        assertEquals(chatbox, restored.vars[varbits.chatbox_transparency])
                        for (preference in
                            listOf(
                                setting_varbits.side_transparency,
                                varbits.chatbox_transparency,
                            )) {
                            val id = preference.baseVar.id
                            val value = restored.vars[preference.baseVar]
                            val message =
                                if (value in Byte.MIN_VALUE..Byte.MAX_VALUE) VarpSmall(id, value)
                                else VarpLarge(id, value)
                            assertTrue(message in restoredClient)
                        }
                    }
                }
            } finally {
                Files.deleteIfExists(database)
            }
        }
}
