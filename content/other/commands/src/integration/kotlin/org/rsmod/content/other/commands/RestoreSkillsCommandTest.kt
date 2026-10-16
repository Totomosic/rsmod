package org.rsmod.content.other.commands

import com.google.inject.AbstractModule
import jakarta.inject.Inject
import net.rsprot.protocol.game.incoming.misc.user.ClientCheat
import net.rsprot.protocol.game.outgoing.misc.player.UpdateRunEnergy
import net.rsprot.protocol.game.outgoing.varp.VarpLarge
import org.junit.jupiter.api.Test
import org.rsmod.api.config.constants
import org.rsmod.api.config.refs.modlevels
import org.rsmod.api.config.refs.varps
import org.rsmod.api.net.rsprot.handlers.ClientCheatHandler
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.testing.GameTestState
import org.rsmod.game.type.mod.ModLevelTypeList
import org.rsmod.game.type.spot.SpotanimTypeList

class RestoreSkillsCommandTest {
    class Dependencies
    @Inject
    constructor(val handler: ClientCheatHandler, val modLevels: ModLevelTypeList)

    @Test
    fun GameTestState.`restore skills refills both energy bars and updates the client`() =
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
            advance(ticks = 2)
            player.runEnergy = 0
            VarPlayerIntMapSetter.set(player, varps.sa_energy, 0)
            deps.handler.handle(player, ClientCheat("master"))

            assertEquals(constants.run_max_energy, player.runEnergy)
            assertEquals(constants.sa_max_energy, player.vars[varps.sa_energy])
            assertTrue(VarpLarge(varps.sa_energy.id, constants.sa_max_energy) in client)
            // At full energy, passive regeneration won't change the value or send an update.
            assertTrue(UpdateRunEnergy(constants.run_max_energy) in client)
            advance()
            assertEquals(constants.run_max_energy, player.runEnergy)
            assertEquals(constants.sa_max_energy, player.vars[varps.sa_energy])
        }
}
