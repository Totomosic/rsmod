package org.rsmod.content.areas.city.sinhaza

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.inject.AbstractModule
import com.google.inject.Provides
import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.nio.file.Path
import net.rsprot.protocol.game.incoming.misc.user.ClientCheat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.rsmod.api.config.refs.components
import org.rsmod.api.config.refs.interfaces
import org.rsmod.api.config.refs.modlevels
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.net.rsprot.handlers.ClientCheatHandler
import org.rsmod.api.parsers.json.Json
import org.rsmod.api.registry.region.RegionRegistry
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.testing.GameTestState
import org.rsmod.api.testing.scope.GameTestScope
import org.rsmod.content.areas.dungeons.nylocas.BloatExitRoom
import org.rsmod.content.areas.dungeons.nylocas.TheatreRaid
import org.rsmod.content.interfaces.bank.scripts.BankLocsScript
import org.rsmod.content.interfaces.character.creator.CharacterCreatorScript
import org.rsmod.content.interfaces.character.creator.TUTORIAL_COMPLETE
import org.rsmod.content.interfaces.character.creator.configs.creator_components
import org.rsmod.content.interfaces.character.creator.configs.creator_interfaces
import org.rsmod.content.interfaces.character.creator.configs.creator_varps
import org.rsmod.content.other.commands.GearPresetStore
import org.rsmod.game.entity.NpcList
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.map.collision.get
import org.rsmod.game.type.mod.ModLevelTypeList
import org.rsmod.map.zone.ZoneKey
import org.rsmod.routefinder.collision.CollisionFlagMap
import org.rsmod.routefinder.flag.CollisionFlag

class VerSinhazaLobbyTest {
    @TempDir lateinit var directory: Path

    private fun module() =
        object : AbstractModule() {
            @Provides
            @Singleton
            fun store(@Json mapper: ObjectMapper): GearPresetStore =
                GearPresetStore(mapper, directory)
        }

    class Dependencies
    @Inject
    constructor(
        val handler: ClientCheatHandler,
        val regions: RegionRegistry,
        val raid: TheatreRaid,
        val npcs: NpcList,
        val locs: LocRepository,
        val collision: CollisionFlagMap,
        val modLevels: ModLevelTypeList,
    )

    @Test
    fun GameTestState.`lobby arrival guide and bank occupy open courtyard tiles`() =
        runLobbyTest { deps ->
            val blocked =
                CollisionFlag.BLOCK_WALK or CollisionFlag.LOC or CollisionFlag.GROUND_DECOR
            assertEquals(0, collision[VerSinhaza.arrival] and blocked)
            assertEquals(0, collision[VerSinhaza.guide] and blocked)
            assertEquals(0, collision[VerSinhaza.bank] and blocked)
            assertEquals(1, deps.npcs.count { it.spawnCoords == VerSinhaza.guide })
            val chest = checkNotNull(deps.locs.findExact(VerSinhaza.bank, sinhaza_locs.bank))
            val bound = BoundLocInfo(chest, cacheTypes.locs[sinhaza_locs.bank])
            for (x in 0 until bound.adjustedWidth) {
                for (z in 0 until bound.adjustedLength) {
                    assertEquals(0, collision[VerSinhaza.bank.translate(x, z)] and blocked)
                }
            }
            advance(ticks = 10)
            assertEquals(VerSinhaza.guide, deps.npcs.single().coords)
        }

    @Test
    fun GameTestState.`ordinary players can teleport to the lobby`() = runLobbyTest { deps ->
        player.modLevel = deps.modLevels[modlevels.player]
        deps.handler.handle(player, ClientCheat("sinhaza"))
        advance()
        assertEquals(VerSinhaza.arrival, player.coords)
        assertNull(deps.regions[player.coords])
    }

    @Test
    fun GameTestState.`guide starts a solo raid at the Bloat exit`() = runLobbyTest { deps ->
        choose(deps, 1)
        val room = checkNotNull(deps.regions[player.coords])
        assertEquals(BloatExitRoom.doorstep, deps.regions.normalizeCoords(player.coords))
        assertTrue(deps.raid.isStarted(player, room))
        assertEquals(1, deps.raid.teamSize(room))
    }

    @Test
    fun GameTestState.`guide creates an unstarted party that friends can join through the guide`() =
        runLobbyTest { deps ->
            player.displayName = "Raid Host"
            choose(deps, 2)
            val room = checkNotNull(deps.regions[player.coords])
            assertFalse(deps.raid.isStarted(player, room))
            val host = player
            val guest = registerPlayer(coords = VerSinhaza.guide.translateX(-1))
            guest.ifButton(creator_components.confirm)
            advance()
            guest.opNpc1(deps.npcs.single())
            advance()
            guest.resumePauseButton(components.chatmenu_pbutton, 3)
            advance()
            guest.resumeStringDialog("raid_host")
            advance()
            assertEquals(room.uid, deps.regions[guest.coords]?.uid)
            assertFalse(deps.raid.isStarted(guest, room))
            deps.raid.startLobby(host)
            assertTrue(deps.raid.isStarted(host, room))
            assertTrue(deps.raid.isStarted(guest, room))
            assertEquals(2, deps.raid.teamSize(room))
        }

    @Test
    fun GameTestState.`guide reopens the character creator for an existing character`() =
        runLobbyTest { deps ->
            val start = player.appearance.coloursSnapshot()[4]
            choose(deps, 4)
            assertTrue(player.ui.containsModal(creator_interfaces.player_design))
            player.ifButton(creator_components.skin_right)
            advance()
            assertNotEquals(start, player.appearance.coloursSnapshot()[4])
            player.ifButton(creator_components.confirm)
            advance()
            assertFalse(player.ui.containsModal(creator_interfaces.player_design))
            assertEquals(TUTORIAL_COMPLETE, player.vars[creator_varps.tutorial])
            assertNull(deps.regions[player.coords])
        }

    @Test
    fun GameTestState.`bank chest opens the bank through the existing bank interaction`() =
        runLobbyTest { deps ->
            player.telejump(VerSinhaza.arrival)
            advance()
            val chest = checkNotNull(deps.locs.findExact(VerSinhaza.bank, sinhaza_locs.bank))
            val bound = BoundLocInfo(chest, cacheTypes.locs[sinhaza_locs.bank])
            player.opLoc1(bound)
            advance(ticks = 4)
            assertTrue(player.ui.containsModal(interfaces.bank_main)) {
                "coords=${player.coords} chest=$bound interaction=${player.interaction}"
            }
        }

    @Test
    fun GameTestState.`cancelled conversations leave the player in the courtyard`() =
        runLobbyTest { deps ->
            choose(deps, 5)
            assertNull(deps.regions[player.coords])
            player.opNpc1(deps.npcs.single())
            advance()
            player.ifClose()
            advance()
            assertNull(deps.regions[player.coords])
        }

    private fun GameTestScope.choose(deps: Dependencies, choice: Int) {
        player.opNpc1(deps.npcs.single())
        advance()
        player.resumePauseButton(components.chatmenu_pbutton, choice)
        advance()
    }

    private fun GameTestState.runLobbyTest(test: GameTestScope.(Dependencies) -> Unit) =
        runInjectedGameTest(
            Dependencies::class,
            module(),
            VerSinhazaLobby::class,
            TheatreRaid::class,
            CharacterCreatorScript::class,
            BankLocsScript::class,
        ) { deps ->
            // Test scopes share the original map's tile arrays; detach these zones before spawning.
            for (x in -1..1) {
                for (z in -1..1) {
                    val coords = ZoneKey.from(VerSinhaza.arrival).translate(x, z).toCoords()
                    val map = deps.collision
                    val tiles = map.allocateIfAbsent(coords.x, coords.z, coords.level)
                    map.deallocateIfPresent(coords.x, coords.z, coords.level)
                    tiles.copyInto(map.allocateIfAbsent(coords.x, coords.z, coords.level))
                }
            }
            player.ifButton(creator_components.confirm)
            advance()
            eventBus.publish(GameLifecycle.Startup)
            player.telejump(VerSinhaza.guide.translateX(-1))
            advance()
            test(deps)
        }
}
