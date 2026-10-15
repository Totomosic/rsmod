package org.rsmod.content.other.commands

import com.google.inject.AbstractModule
import jakarta.inject.Inject
import net.rsprot.protocol.game.incoming.misc.user.ClientCheat
import net.rsprot.protocol.game.outgoing.misc.player.UpdateRunEnergy
import org.junit.jupiter.api.Test
import org.rsmod.api.config.constants
import org.rsmod.api.config.refs.modlevels
import org.rsmod.api.net.rsprot.handlers.ClientCheatHandler
import org.rsmod.api.testing.GameTestState
import org.rsmod.game.type.mod.ModLevelTypeList
import org.rsmod.game.type.spot.SpotanimTypeList

class RestoreSkillsCommandTest {
    class Dependencies
    @Inject
    constructor(val handler: ClientCheatHandler, val modLevels: ModLevelTypeList)

    @Test
    fun GameTestState.`restore skills refills run energy and updates the client immediately`() =
        runInjectedGameTest(
            Dependencies::class,
            object : AbstractModule() {
                override fun configure() {
                    bind(SpotanimTypeList::class.java).toInstance(cacheTypes.spotanims)
                }
            },
            AdminCommands::class,
        ) { deps ->
            player.modLevel = deps.modLevels[modlevels.admin]
            player.runEnergy = 0
            deps.handler.handle(player, ClientCheat("master"))

            assertEquals(constants.run_max_energy, player.runEnergy)
            // At full energy, passive regeneration won't change the value or send an update.
            assertTrue(UpdateRunEnergy(constants.run_max_energy) in client)
            advance()
            assertEquals(constants.run_max_energy, player.runEnergy)
        }
}
