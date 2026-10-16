package org.rsmod.content.areas.dungeons.nylocas

import jakarta.inject.Inject
import net.rsprot.protocol.game.outgoing.interfaces.IfOpenSub
import net.rsprot.protocol.game.outgoing.misc.player.RunClientScript
import net.rsprot.protocol.game.outgoing.varp.VarpLarge
import net.rsprot.protocol.game.outgoing.varp.VarpSmall
import org.junit.jupiter.api.Test
import org.rsmod.api.config.refs.components
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.registry.region.RegionRegistry
import org.rsmod.api.testing.GameTestState
import org.rsmod.game.entity.NpcList
import org.rsmod.game.entity.player.SessionStateEvent
import org.rsmod.map.CoordGrid

class TheatreRaidTest {
    class Dependencies
    @Inject
    constructor(val raid: TheatreRaid, val regions: RegionRegistry, val npcs: NpcList)

    @Test
    fun GameTestState
        .`start opens native HUD and initializes a solo party without moving or spawning`() =
        runInjectedGameTest(Dependencies::class, null, TheatreRaid::class) { deps ->
            val room = createRegion(NylocasRoom.template)
            val start = room.normal[NylocasRoom.bridge]
            player.telejump(start)
            player.displayName = "Test Raider"
            player.setCurrentLevel(stats.hitpoints, 99)
            player.setBaseLevel(stats.hitpoints, 99)
            advance(ticks = 2)
            deps.raid.start(player)

            assertEquals(start, player.coords)
            assertEquals(2, player.vars[tob_varbits.party_status])
            assertEquals(1, player.vars[tob_varbits.party_slot])
            assertEquals(listOf(27, 0, 0, 0, 0), tob_varbits.party_hp.map { player.vars[it] })
            assertEquals(0, player.vars[tob_varbits.progress_type])
            assertTrue(player.ui.containsOverlay(tob_interfaces.hud))
            val target = components.toplevel_target_overlay_hud
            assertTrue(IfOpenSub(target.interfaceId, target.component, 28, 1) in client)
            assertTrue(RunClientScript(2301, listOf("Test Raider", "", "", "", "")) in client)
            val base = tob_varbits.party_status.baseVar
            assertTrue(
                VarpLarge(base.id, player.vars[base]) in client ||
                    VarpSmall(base.id, player.vars[base]) in client
            )
            advance(ticks = 8)
            assertEquals(0, deps.npcs.count())
        }

    @Test
    fun GameTestState.`party slots names and live health synchronize for every member`() =
        runInjectedGameTest(Dependencies::class, null, TheatreRaid::class) { deps ->
            val room = createRegion(NylocasRoom.template)
            player.telejump(room.normal[NylocasRoom.bridge])
            player.displayName = "Leader"
            val other = registerPlayer(coords = room.normal[NylocasRoom.bridge.translateX(1)])
            other.displayName = "Partner"
            for (member in listOf(player, other)) {
                member.setCurrentLevel(stats.hitpoints, 99)
                member.setBaseLevel(stats.hitpoints, 99)
            }
            advance(ticks = 2)
            deps.raid.start(player)
            assertEquals(1, player.vars[tob_varbits.party_slot])
            assertEquals(2, other.vars[tob_varbits.party_slot])
            assertEquals(27, other.vars[tob_varbits.party_hp[0]])
            assertEquals(27, player.vars[tob_varbits.party_hp[1]])

            other.setCurrentLevel(stats.hitpoints, 49)
            advance()
            val expected = 1 + (other.hitpoints * 26 + 98) / 99
            assertEquals(expected, player.vars[tob_varbits.party_hp[1]])
            assertEquals(expected, other.vars[tob_varbits.party_hp[1]])

            other.telejump(CoordGrid(3222, 3218, 0))
            advance()
            assertEquals(0, other.vars[tob_varbits.party_status])
            assertFalse(other.ui.containsOverlay(tob_interfaces.hud))
            assertEquals(0, player.vars[tob_varbits.party_hp[1]])
            assertEquals(2, player.vars[tob_varbits.party_status])
            assertTrue(RunClientScript(2301, listOf("Leader", "", "", "", "")) in client)
        }

    @Test
    fun GameTestState.`start is idempotent and leaving clears all raid data`() =
        runInjectedGameTest(Dependencies::class, null, TheatreRaid::class) { deps ->
            val room = createRegion(NylocasRoom.template)
            player.telejump(room.normal[NylocasRoom.bridge])
            advance(ticks = 2)
            deps.raid.start(player)
            deps.raid.beginRoom(room)
            deps.raid.waveSpawned(room)
            deps.raid.start(player)
            assertEquals(2, player.vars[tob_varbits.progress_type])
            assertEquals(1, player.vars[tob_varbits.progress_value])

            player.telejump(CoordGrid(3222, 3218, 0))
            advance()
            assertEquals(0, player.vars[tob_varbits.party_status])
            assertEquals(0, player.vars[tob_varbits.party_slot])
            assertTrue(tob_varbits.party_hp.all { player.vars[it] == 0 })
            assertFalse(player.ui.containsOverlay(tob_interfaces.hud))
            assertTrue(RunClientScript(2301, List(5) { "" }) in client)
        }

    @Test
    fun GameTestState.`login clears stale raid flags and HUD`() =
        runInjectedGameTest(Dependencies::class, null, TheatreRaid::class) { deps ->
            val room = createRegion(NylocasRoom.template)
            player.telejump(room.normal[NylocasRoom.bridge])
            advance(ticks = 2)
            deps.raid.start(player)
            eventBus.publish(SessionStateEvent.Logout(player))
            assertEquals(0, player.vars[tob_varbits.party_status])
            assertFalse(player.ui.containsOverlay(tob_interfaces.hud))

            player.setVarBit(tob_varbits.party_status, 2)
            player.setVarBit(tob_varbits.party_hp[0], 27)
            eventBus.publish(SessionStateEvent.Login(player))
            assertEquals(0, player.vars[tob_varbits.party_status])
            assertEquals(0, player.vars[tob_varbits.party_hp[0]])
        }

    @Test
    fun GameTestState.`all five party slots receive names health and their own slot number`() =
        runInjectedGameTest(Dependencies::class, null, TheatreRaid::class) { deps ->
            val room = createRegion(NylocasRoom.template)
            player.telejump(room.normal[NylocasRoom.bridge])
            val members =
                listOf(player) +
                    List(4) { registerPlayer(coords = room.normal[NylocasRoom.bridge]) }
            for ((index, member) in members.withIndex()) {
                member.displayName = "Raider ${index + 1}"
                member.setBaseLevel(stats.hitpoints, 99)
                member.setCurrentLevel(stats.hitpoints, 99)
            }
            advance(ticks = 2)
            deps.raid.start(player)
            for ((index, member) in members.withIndex()) {
                assertEquals(index + 1, member.vars[tob_varbits.party_slot])
                assertEquals(List(5) { 27 }, tob_varbits.party_hp.map { member.vars[it] })
                assertTrue(member.ui.containsOverlay(tob_interfaces.hud))
            }
        }

    @Test
    fun GameTestState.`teleporting and starting another raid in one tick preserves the new HUD`() =
        runInjectedGameTest(Dependencies::class, null, TheatreRaid::class) { deps ->
            val first = createRegion(NylocasRoom.template)
            player.telejump(first.normal[NylocasRoom.bridge])
            player.displayName = "Raider"
            advance(ticks = 2)
            deps.raid.start(player)
            val second = createRegion(NylocasRoom.template)
            player.telejump(second.normal[NylocasRoom.bridge])
            deps.raid.start(player)
            advance()

            assertEquals(2, player.vars[tob_varbits.party_status])
            assertTrue(player.ui.containsOverlay(tob_interfaces.hud))
            assertFalse(RunClientScript(2301, List(5) { "" }) in client)
            assertTrue(deps.raid.isStarted(player, second))
            assertFalse(deps.raid.isStarted(player, first))
        }

    @Test
    fun GameTestState.`start rejects unrelated locations and parties larger than five`() =
        runInjectedGameTest(Dependencies::class, null, TheatreRaid::class) { deps ->
            advance(ticks = 2)
            deps.raid.start(player)
            assertEquals(0, player.vars[tob_varbits.party_status])
            assertFalse(player.ui.containsOverlay(tob_interfaces.hud))

            val room = createRegion(NylocasRoom.template)
            player.telejump(room.normal[NylocasRoom.bridge])
            repeat(5) { registerPlayer(coords = room.normal[NylocasRoom.bridge]) }
            advance()
            deps.raid.start(player)
            assertFalse(deps.raid.isStarted(player, room))
            assertEquals(0, player.vars[tob_varbits.party_status])
        }

    @Test
    fun GameTestState.`barrier refuses entry before start and preserves the four tick countdown`() =
        runInjectedGameTest(
            Dependencies::class,
            null,
            TheatreRaid::class,
            NylocasEncounter::class,
        ) { deps ->
            val source = placeMapLoc(CoordGrid(3295, 4255, 0), nylocas_locs.barrier)
            val room = createRegion(NylocasRoom.template)
            val outside = room.normal[CoordGrid(3295, 4256, 0)]
            val barrier = source.copy(coords = room.normal[source.coords])
            player.telejump(outside)
            advance(ticks = 2)
            player.opLoc1(barrier)
            advance(ticks = 6)
            assertEquals(outside, player.coords)
            assertEquals(0, deps.npcs.count())

            deps.raid.start(player)
            player.opLoc1(barrier)
            advance()
            player.resumePauseButton(components.chatmenu_pbutton, sub = 1)
            advance()
            assertEquals(1, player.vars[tob_varbits.progress_type])
            advance(ticks = 3)
            assertEquals(0, deps.npcs.count())
            advance()
            assertEquals(3, deps.npcs.count())
            assertEquals(2, player.vars[tob_varbits.progress_type])
            assertEquals(1, player.vars[tob_varbits.progress_value])
        }
}
