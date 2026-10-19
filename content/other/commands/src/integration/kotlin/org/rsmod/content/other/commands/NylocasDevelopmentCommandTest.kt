package org.rsmod.content.other.commands

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.inject.AbstractModule
import com.google.inject.Provides
import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.nio.file.Path
import net.rsprot.protocol.game.incoming.misc.user.ClientCheat
import net.rsprot.protocol.game.outgoing.misc.player.UpdateRunEnergy
import net.rsprot.protocol.game.outgoing.varp.VarpLarge
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.rsmod.api.config.constants
import org.rsmod.api.config.refs.modlevels
import org.rsmod.api.config.refs.stats
import org.rsmod.api.config.refs.varps
import org.rsmod.api.net.rsprot.handlers.ClientCheatHandler
import org.rsmod.api.parsers.json.Json
import org.rsmod.api.player.stat.baseHitpointsLvl
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.player.stat.stat
import org.rsmod.api.player.stat.statBase
import org.rsmod.api.registry.region.RegionRegistry
import org.rsmod.api.testing.GameTestState
import org.rsmod.content.areas.dungeons.nylocas.NyloMaxHit
import org.rsmod.content.areas.dungeons.nylocas.TheatreRaid
import org.rsmod.content.areas.dungeons.nylocas.TheatreRaidOptions
import org.rsmod.content.areas.dungeons.nylocas.tob_interfaces
import org.rsmod.content.areas.dungeons.nylocas.tob_varbits
import org.rsmod.game.map.collision.get
import org.rsmod.game.region.Region
import org.rsmod.game.type.mod.ModLevelTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.map.zone.ZoneKey
import org.rsmod.routefinder.collision.CollisionFlagMap
import org.rsmod.routefinder.flag.CollisionFlag

class NylocasDevelopmentCommandTest {
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
        val modLevels: ModLevelTypeList,
        val collision: CollisionFlagMap,
        val raid: TheatreRaid,
    )

    @Test
    fun GameTestState.`raid options validate exclusive modes and set party current HP once`() =
        runInjectedGameTest(
            Dependencies::class,
            module(),
            NylocasDevelopmentCommand::class,
            TheatreRaid::class,
        ) { deps ->
            player.modLevel = deps.modLevels[modlevels.player]
            deps.handler.handle(player, ClientCheat("theatrecreate"))
            advance()
            val room = checkNotNull(deps.regions[player.coords])
            val guest = registerPlayer(coords = player.coords)
            val outsider = registerPlayer(coords = CoordGrid(3222, 3218, 0))
            for (member in listOf(player, guest, outsider)) {
                member.setBaseLevel(stats.hitpoints, 99)
                member.setCurrentLevel(stats.hitpoints, 99)
            }
            for (invalid in listOf("1 maxhit-all maxhit-slow", "1 bogus", "21 hp250 bad")) {
                deps.handler.handle(player, ClientCheat("theatrestart $invalid"))
                assertFalse(deps.raid.isStarted(player, room))
                assertEquals(99, guest.hitpoints)
            }
            deps.handler.handle(player, ClientCheat("theatrestart 21 maxhit-slow hp250 zeal"))
            assertEquals(
                TheatreRaidOptions(21, NyloMaxHit.SlowWeapons, true, true),
                deps.raid.options(room),
            )
            for (member in listOf(player, guest)) {
                assertEquals(1, member.vars[varps.zeal])
                assertEquals(250, member.hitpoints)
                assertEquals(99, member.baseHitpointsLvl)
            }
            assertEquals(99, outsider.hitpoints)
            assertEquals(0, outsider.vars[varps.zeal])
            guest.setCurrentLevel(stats.hitpoints, 200)
            deps.handler.handle(player, ClientCheat("theatrestart 1 maxhit-all hp250"))
            assertEquals(200, guest.hitpoints)
            assertEquals(NyloMaxHit.SlowWeapons, deps.raid.options(room).nyloMaxHit)
        }

    @Test
    fun GameTestState.`starting wave is validated and only the host can freeze raid options`() =
        runInjectedGameTest(
            Dependencies::class,
            module(),
            NylocasDevelopmentCommand::class,
            TheatreRaid::class,
        ) { deps ->
            player.modLevel = deps.modLevels[modlevels.player]
            player.displayName = "Host"
            deps.handler.handle(player, ClientCheat("theatrecreate"))
            advance()
            val room = checkNotNull(deps.regions[player.coords])
            val guest = registerPlayer(coords = player.coords)
            guest.modLevel = deps.modLevels[modlevels.player]
            for (invalid in listOf("0", "32", "-1", "1.5", "abc", "21 22", "9999999999999")) {
                deps.handler.handle(player, ClientCheat("theatrestart $invalid"))
                assertFalse(deps.raid.isStarted(player, room))
            }
            deps.handler.handle(guest, ClientCheat("theatrestart 31"))
            assertFalse(deps.raid.isStarted(player, room))
            deps.handler.handle(player, ClientCheat("theatrestart 21"))
            assertTrue(deps.raid.isStarted(player, room))
            assertTrue(deps.raid.isStarted(guest, room))
            assertEquals(TheatreRaidOptions(21), deps.raid.options(room))
            deps.handler.handle(player, ClientCheat("theatrestart 1"))
            deps.handler.handle(guest, ClientCheat("theatrestart 31"))
            assertEquals(TheatreRaidOptions(21), deps.raid.options(room))
        }

    @Test
    fun GameTestState.`every account role can create a lobby and teleport to the door`() =
        runInjectedGameTest(
            Dependencies::class,
            module(),
            NylocasDevelopmentCommand::class,
            TheatreRaid::class,
        ) { deps ->
            for (role in
                listOf(modlevels.player, modlevels.moderator, modlevels.admin, modlevels.owner)) {
                player.modLevel = deps.modLevels[role]
                player.telejump(CoordGrid(3222, 3218, 0))
                deps.handler.handle(player, ClientCheat("theatrecreate"))
                advance()
                assertNotNull(deps.regions[player.coords])
                assertEquals(CoordGrid(3322, 4447, 0), deps.regions.normalizeCoords(player.coords))
            }
        }

    @Test
    fun GameTestState.`ordinary players create join and start a shared lobby by host name`() =
        runInjectedGameTest(
            Dependencies::class,
            module(),
            NylocasDevelopmentCommand::class,
            TheatreRaid::class,
        ) { deps ->
            player.modLevel = deps.modLevels[modlevels.player]
            player.displayName = "Raid Host"
            advance(ticks = 2)
            for (stat in cacheTypes.stats.values) {
                player.setBaseLevel(stat, 80)
                player.setCurrentLevel(stat, if (stat == stats.attack) 110 else 10)
            }
            val hostXp = cacheTypes.stats.values.associateWith { player.statMap.getFineXP(it) }
            player.runEnergy = 0
            player.setVarp(varps.sa_energy, 0)
            client.clearOutgoing()
            deps.handler.handle(player, ClientCheat("theatrecreate"))
            assertEquals(constants.run_max_energy, player.runEnergy)
            assertEquals(constants.sa_max_energy, player.vars[varps.sa_energy])
            assertTrue(UpdateRunEnergy(constants.run_max_energy) in client)
            assertTrue(VarpLarge(varps.sa_energy.id, constants.sa_max_energy) in client)
            advance()
            for (stat in cacheTypes.stats.values) {
                assertEquals(80, player.stat(stat))
                assertEquals(80, player.statBase(stat))
                assertEquals(hostXp[stat], player.statMap.getFineXP(stat))
            }
            val room = checkNotNull(deps.regions[player.coords])
            assertEquals(CoordGrid(3322, 4447, 0), deps.regions.normalizeCoords(player.coords))
            val guest = registerPlayer(coords = CoordGrid(3222, 3218, 0), client = client)
            guest.modLevel = deps.modLevels[modlevels.player]
            advance()
            for (stat in cacheTypes.stats.values) {
                guest.setBaseLevel(stat, 70)
                guest.setCurrentLevel(stat, 5)
            }
            val guestXp = cacheTypes.stats.values.associateWith { guest.statMap.getFineXP(it) }
            guest.runEnergy = 0
            guest.setVarp(varps.sa_energy, 0)
            client.clearOutgoing()
            deps.handler.handle(guest, ClientCheat("theatrejoin rAiD hOsT"))
            assertEquals(player.coords, guest.coords)
            assertEquals(constants.run_max_energy, guest.runEnergy)
            assertEquals(constants.sa_max_energy, guest.vars[varps.sa_energy])
            assertTrue(UpdateRunEnergy(constants.run_max_energy) in client)
            assertTrue(VarpLarge(varps.sa_energy.id, constants.sa_max_energy) in client)
            for (stat in cacheTypes.stats.values) {
                assertEquals(70, guest.stat(stat))
                assertEquals(70, guest.statBase(stat))
                assertEquals(guestXp[stat], guest.statMap.getFineXP(stat))
            }
            deps.handler.handle(guest, ClientCheat("theatrestart"))
            assertEquals(0, guest.vars[tob_varbits.party_status])
            deps.handler.handle(player, ClientCheat("theatrestart"))
            assertEquals(TheatreRaidOptions(), deps.raid.options(room))
            assertEquals(2, player.vars[tob_varbits.party_status])
            assertEquals(2, guest.vars[tob_varbits.party_status])
            assertEquals(2, guest.vars[tob_varbits.party_slot])
            val late = registerPlayer(coords = CoordGrid(3222, 3218, 0))
            late.modLevel = deps.modLevels[modlevels.player]
            late.setCurrentLevel(stats.hitpoints, 5)
            late.setCurrentLevel(stats.prayer, 0)
            late.runEnergy = 1234
            late.setVarp(varps.sa_energy, 250)
            deps.handler.handle(late, ClientCheat("theatrejoin raid host"))
            assertEquals(5, late.hitpoints)
            assertEquals(0, late.stat(stats.prayer))
            assertEquals(1234, late.runEnergy)
            assertEquals(250, late.vars[varps.sa_energy])
            assertNull(deps.regions[late.coords])
            assertEquals(room.uid, deps.regions[guest.coords]?.uid)
        }

    @Test
    fun GameTestState
        .`full missing and abandoned lobbies reject joins and instances stay isolated`() =
        runInjectedGameTest(
            Dependencies::class,
            module(),
            NylocasDevelopmentCommand::class,
            TheatreRaid::class,
        ) { deps ->
            player.modLevel = deps.modLevels[modlevels.player]
            player.displayName = "Host"
            deps.handler.handle(player, ClientCheat("theatrecreate"))
            advance()
            val first = checkNotNull(deps.regions[player.coords])
            val guests = List(5) { registerPlayer(coords = CoordGrid(3222, 3218, 0)) }
            for (guest in guests) {
                guest.modLevel = deps.modLevels[modlevels.player]
                deps.handler.handle(guest, ClientCheat("theatrejoin host"))
            }
            assertTrue(guests.take(4).all { deps.regions[it.coords]?.uid == first.uid })
            assertNull(deps.regions[guests.last().coords])
            deps.handler.handle(guests.last(), ClientCheat("theatrejoin missing"))
            assertNull(deps.regions[guests.last().coords])
            deps.handler.handle(player, ClientCheat("theatrecreate"))
            advance()
            val second = checkNotNull(deps.regions[player.coords])
            assertNotEquals(first.uid, second.uid)
            deps.handler.handle(guests.last(), ClientCheat("theatrejoin host"))
            assertEquals(second.uid, deps.regions[guests.last().coords]?.uid)
            player.telejump(CoordGrid(3222, 3218, 0))
            deps.handler.handle(guests.first(), ClientCheat("theatrejoin host"))
            assertEquals(first.uid, deps.regions[guests.first().coords]?.uid)
        }

    @Test
    fun GameTestState.`start raid command initializes the HUD in the current room`() =
        runInjectedGameTest(
            Dependencies::class,
            module(),
            NylocasDevelopmentCommand::class,
            TheatreRaid::class,
        ) { deps ->
            player.modLevel = deps.modLevels[modlevels.admin]
            deps.handler.handle(player, ClientCheat("bloatexit"))
            advance(ticks = 2)
            val original = player.coords
            deps.handler.handle(player, ClientCheat("starttob"))

            assertEquals(original, player.coords)
            assertEquals(2, player.vars[tob_varbits.party_status])
            assertEquals(1, player.vars[tob_varbits.party_slot])
            assertTrue(player.vars[tob_varbits.party_hp[0]] > 0)
            assertTrue(player.ui.containsOverlay(tob_interfaces.hud))
            deps.handler.handle(player, ClientCheat("starttob"))
            assertEquals(original, player.coords)
            assertEquals(2, player.vars[tob_varbits.party_status])
        }

    @Test
    fun GameTestState.`admin enters an instance preserving plugin source coordinates`() =
        runInjectedGameTest(Dependencies::class, module(), NylocasDevelopmentCommand::class) { deps
            ->
            player.modLevel = deps.modLevels[modlevels.admin]
            deps.handler.handle(player, ClientCheat("nylocas"))
            advance()

            val region = checkNotNull(deps.regions[player.coords])
            assertEquals(CoordGrid(3295, 4248, 0), deps.regions.normalizeCoords(player.coords))
            assertEquals(0, deps.collision[player.coords] and CollisionFlag.BLOCK_WALK)

            deps.handler.handle(player, ClientCheat("bloatexit"))
            advance()
            assertNotNull(deps.regions[player.coords])
            assertEquals(CoordGrid(3322, 4447, 0), deps.regions.normalizeCoords(player.coords))
            val blocked =
                CollisionFlag.BLOCK_WALK or CollisionFlag.LOC or CollisionFlag.GROUND_DECOR
            assertEquals(0, deps.collision[player.coords] and blocked)

            // Nyloer's three incoming lanes and ToB QoL's entry line must resolve to source
            // tiles, including tiles away from the player's current chunk.
            val sourceTiles =
                listOf(
                    CoordGrid(3281, 4249, 0),
                    CoordGrid(3295, 4233, 0),
                    CoordGrid(3310, 4248, 0),
                    CoordGrid(3304, 4274, 0),
                )
            for (source in sourceTiles) {
                assertEquals(source, deps.regions.normalizeCoords(region.normal[source]))
            }
            for (level in 0..3) {
                val source = CoordGrid(3295, 4248, level)
                val copy = deps.regions[ZoneKey.from(region.normal[source])]
                assertEquals(ZoneKey.from(source), copy.normalZone())
                assertEquals(0, copy.rotation)
            }

            deps.handler.handle(player, ClientCheat("nylocasbridge"))
            advance()
            assertNotNull(deps.regions[player.coords])
            assertEquals(CoordGrid(3295, 4264, 0), deps.regions.normalizeCoords(player.coords))
            assertEquals(0, deps.collision[player.coords] and CollisionFlag.BLOCK_WALK)
        }

    @Test
    fun GameTestState.`ordinary players cannot allocate or enter the development room`() =
        runInjectedGameTest(Dependencies::class, module(), NylocasDevelopmentCommand::class) { deps
            ->
            player.modLevel = deps.modLevels[modlevels.player]
            val original = player.coords
            for (command in listOf("nylocas", "nylocasbridge", "bloatexit", "starttob")) {
                deps.handler.handle(player, ClientCheat(command))
                advance()

                assertEquals(original, player.coords)
                assertNull(deps.regions[player.coords])
            }
        }

    @Test
    fun GameTestState.`empty instances are reclaimed after the player leaves`() =
        runInjectedGameTest(Dependencies::class, module(), NylocasDevelopmentCommand::class) { deps
            ->
            player.modLevel = deps.modLevels[modlevels.admin]
            deps.handler.handle(player, ClientCheat("nylocas"))
            advance()
            val first = checkNotNull(deps.regions[player.coords])

            deps.handler.handle(player, ClientCheat("nylocas"))
            advance()
            val second = checkNotNull(deps.regions[player.coords])
            assertNotEquals(first.uid, second.uid)
            removeInactiveRegions()
            assertEquals(Region.INVALID_SLOT, first.slot)
            assertNotEquals(Region.INVALID_SLOT, second.slot)

            player.telejump(CoordGrid(3222, 3218, 0))
            advance()
            removeInactiveRegions()
            assertEquals(Region.INVALID_SLOT, second.slot)
        }
}
