package org.rsmod.content.interfaces.settings

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
import org.rsmod.content.interfaces.settings.scripts.DisplaySettingsScript
import org.rsmod.content.other.login.LoginScript
import org.rsmod.game.entity.player.SessionStateEvent
import org.rsmod.game.type.varp.VarpLifetime

class ScrollbarPositionTest {
    class Dependencies
    @Inject
    constructor(
        val handler: ClientCheatHandler,
        val repository: CharacterAccountRepository,
        val applier: CharacterAccountApplier,
    )

    @Test
    fun GameTestState.`left and right positions sync without changing adjacent preferences`() =
        runInjectedGameTest(Dependencies::class, null, DisplaySettingsScript::class) { deps ->
            player.modLevel = cacheTypes.modLevels[modlevels.player]
            advance(2)
            val preference = checkNotNull(cacheTypes.varbits[6374])
            assertEquals(VarpLifetime.Perm, preference.baseVar.scope)
            VarPlayerIntMapSetter.set(player, varbits.chatbox_transparency, 1)
            VarPlayerIntMapSetter.set(player, setting_varbits.side_transparency, 1)
            VarPlayerIntMapSetter.set(player, setting_varbits.hotkey_cannot_close_sidepanel, 1)
            for (position in listOf(1, 0)) {
                deps.handler.handle(player, ClientCheat("localscape_scrollbar_position $position"))
                assertEquals(position, player.vars[preference])
                assertEquals(1, player.vars[varbits.chatbox_transparency])
                assertEquals(1, player.vars[setting_varbits.side_transparency])
                assertEquals(1, player.vars[setting_varbits.hotkey_cannot_close_sidepanel])
                assertTrue(
                    VarpLarge(preference.baseVar.id, player.vars[preference.baseVar]) in client
                )
            }
        }

    @Test
    fun GameTestState
        .`queued selection survives closing settings and invalid requests are ignored`() =
        runInjectedGameTest(Dependencies::class, null, DisplaySettingsScript::class) { deps ->
            player.modLevel = cacheTypes.modLevels[modlevels.player]
            player.ifOpenOverlay(interfaces.settings, components.toplevel_target_floater)
            advance(2)
            player.ifCloseOverlay(interfaces.settings, eventBus)
            deps.handler.handle(player, ClientCheat("localscape_scrollbar_position 1"))
            assertEquals(1, player.vars[checkNotNull(cacheTypes.varbits[6374])])
            val original = player.vars.backing.toMap()
            for (args in listOf("", "-1", "2", "nope", "0 1")) {
                deps.handler.handle(player, ClientCheat("localscape_scrollbar_position $args"))
            }
            assertEquals(original, player.vars.backing.toMap())
        }

    @Test
    fun GameTestState.`both scrollbar positions survive database reload and sync on login`() =
        runInjectedGameTest(
            Dependencies::class,
            null,
            DisplaySettingsScript::class,
            LoginScript::class,
        ) { deps ->
            player.modLevel = cacheTypes.modLevels[modlevels.player]
            val preference = checkNotNull(cacheTypes.varbits[6374])
            val database = Files.createTempFile("scrollbar-position-", ".db")
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
                            deps.repository.insertOrSelectAccountId(connection, "scrollbar", "test")
                        )
                    val character =
                        checkNotNull(
                            deps.repository.insertAndSelectCharacterId(connection, account)
                        )
                    for (position in listOf(1, 0)) {
                        deps.handler.handle(
                            player,
                            ClientCheat("localscape_scrollbar_position $position"),
                        )
                        deps.repository.save(connection, player, account, character)
                        val loaded =
                            checkNotNull(
                                deps.repository.selectAndCreateMetadataList(connection, "scrollbar")
                            )
                        val restoredClient = CaptureClient()
                        val restored = registerPlayer(client = restoredClient)
                        deps.applier.apply(restored, loaded.accountData)
                        eventBus.publish(SessionStateEvent.Login(restored))
                        restoredClient.clearOutgoing()
                        eventBus.publish(SessionStateEvent.EngineLogin(restored))
                        assertEquals(position, restored.vars[preference])
                        val id = preference.baseVar.id
                        val value = restored.vars[preference.baseVar]
                        val message =
                            if (value in Byte.MIN_VALUE..Byte.MAX_VALUE) VarpSmall(id, value)
                            else VarpLarge(id, value)
                        assertTrue(message in restoredClient)
                    }
                }
            } finally {
                Files.deleteIfExists(database)
            }
        }
}
