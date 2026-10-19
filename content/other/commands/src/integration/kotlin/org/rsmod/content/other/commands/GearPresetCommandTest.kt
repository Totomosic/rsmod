package org.rsmod.content.other.commands

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.inject.AbstractModule
import com.google.inject.Provides
import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.nio.file.Path
import java.util.HexFormat
import java.util.UUID
import net.rsprot.protocol.game.incoming.misc.user.ClientCheat
import net.rsprot.protocol.game.outgoing.misc.player.MessageGame
import net.rsprot.protocol.game.outgoing.misc.player.UpdateRunEnergy
import net.rsprot.protocol.game.outgoing.varp.VarpLarge
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.rsmod.api.config.constants
import org.rsmod.api.config.refs.modlevels
import org.rsmod.api.config.refs.objs
import org.rsmod.api.config.refs.stats
import org.rsmod.api.config.refs.varps
import org.rsmod.api.net.rsprot.handlers.ClientCheatHandler
import org.rsmod.api.parsers.json.Json
import org.rsmod.api.player.events.interact.HeldEquipEvents
import org.rsmod.api.player.stat.stat
import org.rsmod.api.player.stat.statBase
import org.rsmod.api.testing.GameTestState
import org.rsmod.content.areas.dungeons.nylocas.TheatreRaid
import org.rsmod.events.EventBus
import org.rsmod.game.inv.InvObj
import org.rsmod.game.type.mod.ModLevelTypeList
import org.rsmod.game.type.obj.Wearpos

class GearPresetCommandTest {
    @TempDir lateinit var directory: Path

    class Dependencies
    @Inject
    constructor(
        val handler: ClientCheatHandler,
        val store: GearPresetStore,
        val modLevels: ModLevelTypeList,
        val events: EventBus,
        @Json val mapper: ObjectMapper,
    )

    private fun module() =
        object : AbstractModule() {
            @Provides
            @Singleton
            fun store(@Json mapper: ObjectMapper): GearPresetStore =
                GearPresetStore(mapper, directory)
        }

    @Test
    fun GameTestState
        .`saved setups survive a fresh store and restore slots quantities and charges`() =
        runInjectedGameTest(Dependencies::class, module(), GearPresetCommand::class) { deps ->
            player.modLevel = deps.modLevels[modlevels.admin]
            player.displayName = "Alice"
            advance(ticks = 2)
            player.setCurrentLevel(stats.hitpoints, 99)
            player.worn[Wearpos.RightHand.slot] = InvObj(objs.toxic_blowpipe, 1, 1234)
            player.worn[Wearpos.Quiver.slot] = InvObj(objs.rune_arrow, 250)
            player.inv[0] = InvObj(objs.coins, 100_000)
            player.inv[7] = InvObj(objs.abyssal_whip)
            player.inv[27] = InvObj(objs.toxic_blowpipe, 1, 5678)
            val equipment = player.worn.toList()
            val inventory = player.inv.toList()
            val name = HexFormat.of().formatHex("Nylo Duo".toByteArray())
            deps.handler.handle(player, ClientCheat("gearsetups save 1234abcd $name"))
            val preset = deps.store.list().single()
            assertEquals("[Alice] Nylo Duo", preset.name)
            assertEquals(preset, GearPresetStore(deps.mapper, directory).list().single())
            assertTrue(
                client.filterIsInstance<MessageGame>().any {
                    it.message.startsWith("LGP1 1234abcd item ${preset.id} ")
                }
            )

            player.worn.fillNulls()
            player.inv.fillNulls()
            player.worn[Wearpos.Torso.slot] = InvObj(objs.rune_platebody)
            player.inv[3] = InvObj(objs.coins, 1)
            val changed = mutableListOf<Wearpos>()
            deps.events.subscribeUnbound(HeldEquipEvents.WearposChange::class.java) {
                changed += wearpos
            }
            for (stat in cacheTypes.stats.values) {
                player.setBaseLevel(stat, 80)
                player.setCurrentLevel(stat, if (stat == stats.attack) 110 else 5)
            }
            val xp = cacheTypes.stats.values.associateWith { player.statMap.getFineXP(it) }
            player.runEnergy = 0
            player.setVarp(varps.sa_energy, 0)
            client.clearOutgoing()
            deps.handler.handle(player, ClientCheat("gearsetups load 1234abcd ${preset.id}"))
            assertEquals(constants.run_max_energy, player.runEnergy)
            assertEquals(constants.sa_max_energy, player.vars[varps.sa_energy])
            assertTrue(UpdateRunEnergy(constants.run_max_energy) in client)
            assertTrue(VarpLarge(varps.sa_energy.id, constants.sa_max_energy) in client)
            for (stat in cacheTypes.stats.values) {
                assertEquals(80, player.stat(stat))
                assertEquals(80, player.statBase(stat))
                assertEquals(xp[stat], player.statMap.getFineXP(stat))
            }
            assertEquals(equipment, player.worn.toList())
            assertEquals(inventory, player.inv.toList())
            assertTrue(
                changed.containsAll(listOf(Wearpos.Torso, Wearpos.RightHand, Wearpos.Quiver))
            )
            assertTrue(player.worn.hasModifiedSlots())
            assertTrue(player.inv.hasModifiedSlots())
        }

    @Test
    fun GameTestState.`ordinary players share setups and update only their own named setup`() =
        runInjectedGameTest(Dependencies::class, module(), GearPresetCommand::class) { deps ->
            player.modLevel = deps.modLevels[modlevels.player]
            player.displayName = "Alice"
            val character = checkNotNull(player.uuid)
            val first =
                deps.store.save(
                    character,
                    "Alice",
                    "Nylo Duo",
                    List(14) { null },
                    List(28) { null },
                )
            player.inv[4] = InvObj(objs.coins, 200)
            val name = HexFormat.of().formatHex("NYLO DUO".toByteArray())
            deps.handler.handle(player, ClientCheat("gearsetups save abcdef01 $name"))
            val updated = deps.store.list().single()
            assertEquals(first.id, updated.id)
            assertEquals("[Alice] NYLO DUO", updated.name)
            assertEquals(200, updated.inventory[4]?.count)
            val other = registerPlayer(client = client)
            other.modLevel = deps.modLevels[modlevels.player]
            other.displayName = "Bob"
            other.setCurrentLevel(stats.hitpoints, 99)
            deps.handler.handle(other, ClientCheat("gearsetups list abcdef02"))
            assertTrue(
                client.filterIsInstance<MessageGame>().any {
                    it.message.startsWith("LGP1 abcdef02 item ${first.id} ")
                }
            )
            other.inv[0] = InvObj(objs.coins, 1)
            deps.handler.handle(other, ClientCheat("gearsetups load abcdef01 ${first.id}"))
            assertEquals(player.inv.toList(), other.inv.toList())
            assertEquals(player.worn.toList(), other.worn.toList())
            other.inv[4] = InvObj(objs.coins, 300)
            deps.handler.handle(other, ClientCheat("gearsetups save abcdef03 $name"))
            val shared = GearPresetStore(deps.mapper, directory).list()
            assertEquals(2, shared.size)
            assertEquals(updated, shared.single { it.id == first.id })
            val bobs = shared.single { it.id != first.id }
            assertEquals("[Bob] NYLO DUO", bobs.name)
            assertEquals(300, bobs.inventory[4]?.count)
        }

    @Test
    fun GameTestState.`invalid setups are rejected before either live container changes`() =
        runInjectedGameTest(Dependencies::class, module(), GearPresetCommand::class) { deps ->
            player.modLevel = deps.modLevels[modlevels.admin]
            player.setCurrentLevel(stats.hitpoints, 5)
            player.setCurrentLevel(stats.prayer, 0)
            player.runEnergy = 1234
            player.setVarp(varps.sa_energy, 250)
            player.worn[Wearpos.RightHand.slot] = InvObj(objs.abyssal_whip)
            player.inv[0] = InvObj(objs.coins, 100)
            val equipment = player.worn.toList()
            val inventory = player.inv.toList()
            for ((name, worn, inv) in
                listOf(
                    Triple(
                        "Bad item",
                        List(14) { null },
                        List(28) { if (it == 27) GearPresetItem(Int.MAX_VALUE, 1, 0) else null },
                    ),
                    Triple(
                        "Bad slot",
                        List(14) {
                            if (it == 0) GearPresetItem(objs.abyssal_whip.id, 1, 0) else null
                        },
                        List(28) { null },
                    ),
                    Triple("Bad size", List(13) { null }, List(28) { null }),
                )) {
                val invalid = deps.store.save(checkNotNull(player.uuid), "Alice", name, worn, inv)
                deps.handler.handle(player, ClientCheat("gearsetups load 1234abcd ${invalid.id}"))
                assertEquals(equipment, player.worn.toList())
                assertEquals(inventory, player.inv.toList())
                assertEquals(5, player.stat(stats.hitpoints))
                assertEquals(0, player.stat(stats.prayer))
                assertEquals(1234, player.runEnergy)
                assertEquals(250, player.vars[varps.sa_energy])
            }
        }

    @Test
    fun GameTestState.`ordinary players can save and apply empty setups to clear every slot`() =
        runInjectedGameTest(Dependencies::class, module(), GearPresetCommand::class) { deps ->
            player.modLevel = deps.modLevels[modlevels.player]
            player.displayName = "Alice"
            val name = HexFormat.of().formatHex("Empty".toByteArray())
            player.setCurrentLevel(stats.hitpoints, 99)
            deps.handler.handle(player, ClientCheat("gearsetups save 1234abcd $name"))
            val preset = deps.store.list().single()
            player.inv[27] = InvObj(objs.coins, 500)
            player.worn[Wearpos.RightHand.slot] = InvObj(objs.abyssal_whip)
            deps.handler.handle(player, ClientCheat("gearsetups load 1234abcd ${preset.id}"))
            assertTrue(player.inv.isEmpty())
            assertTrue(player.worn.isEmpty())
        }

    @Test
    fun GameTestState.`existing character files are shared and resaving adds the creator prefix`() =
        runInjectedGameTest(Dependencies::class, module(), GearPresetCommand::class) { deps ->
            player.displayName = "Alice"
            val legacy =
                GearPreset(
                    UUID.randomUUID().toString(),
                    "Mage",
                    List(14) { null },
                    List(28) { null },
                )
            deps.mapper.writeValue(
                directory.resolve("${player.uuid}.json").toFile(),
                GearPresetFile(setups = listOf(legacy)),
            )
            assertEquals(listOf(legacy), deps.store.list())
            val other = registerPlayer()
            other.modLevel = deps.modLevels[modlevels.player]
            other.setCurrentLevel(stats.hitpoints, 99)
            other.inv[0] = InvObj(objs.coins, 1)
            deps.handler.handle(other, ClientCheat("gearsetups load abcdef01 ${legacy.id}"))
            assertTrue(other.inv.isEmpty())
            val saved =
                deps.store.save(
                    checkNotNull(player.uuid),
                    "Alice",
                    "Mage",
                    List(14) { null },
                    List(28) { null },
                )
            assertEquals(legacy.id, saved.id)
            assertEquals("[Alice] Mage", saved.name)
            assertEquals(listOf(saved), GearPresetStore(deps.mapper, directory).list())
        }

    @Test
    fun GameTestState
        .`defaults are per character persist across saves and can use shared setups or none`() =
        runInjectedGameTest(Dependencies::class, module(), GearPresetCommand::class) { deps ->
            val owner = checkNotNull(player.uuid)
            val other = registerPlayer()
            val guest = checkNotNull(other.uuid)
            val preset =
                deps.store.save(owner, "Alice", "Nylo", List(14) { null }, List(28) { null })
            assertNull(deps.store.defaultSetupId(owner))
            deps.handler.handle(other, ClientCheat("gearsetups default 1234abcd ${preset.id}"))
            assertEquals(preset.id, deps.store.defaultSetupId(guest))
            assertNull(deps.store.defaultSetupId(owner))
            deps.store.save(guest, "Bob", "Other", List(14) { null }, List(28) { null })
            assertEquals(preset.id, GearPresetStore(deps.mapper, directory).defaultSetupId(guest))
            client.clearOutgoing()
            deps.handler.handle(player, ClientCheat("gearsetups default abcdef01 ${preset.id}"))
            assertTrue(
                client.filterIsInstance<MessageGame>().any {
                    it.message == "LGP1 abcdef01 default ${preset.id}"
                }
            )
            deps.handler.handle(
                player,
                ClientCheat("gearsetups default abcdef02 ${UUID.randomUUID()}"),
            )
            assertEquals(preset.id, deps.store.defaultSetupId(owner))
            deps.handler.handle(player, ClientCheat("gearsetups default abcdef03 none"))
            assertNull(GearPresetStore(deps.mapper, directory).defaultSetupId(owner))
            assertEquals(preset.id, deps.store.defaultSetupId(guest))
            assertEquals(2, deps.store.list().size)
        }

    @Test
    fun GameTestState
        .`create and join apply each players default but none and failed joins preserve gear`() =
        runInjectedGameTest(
            Dependencies::class,
            module(),
            GearPresetCommand::class,
            NylocasDevelopmentCommand::class,
            TheatreRaid::class,
        ) { deps ->
            player.modLevel = deps.modLevels[modlevels.player]
            player.displayName = "Host"
            val hostId = checkNotNull(player.uuid)
            val hostPreset =
                deps.store.save(
                    hostId,
                    "Host",
                    "Ranged",
                    List(14) {
                        if (it == Wearpos.RightHand.slot)
                            GearPresetItem(objs.toxic_blowpipe.id, 1, 1234)
                        else null
                    },
                    List(28) { if (it == 7) GearPresetItem(objs.coins.id, 123, 0) else null },
                )
            deps.store.setDefault(hostId, hostPreset.id)
            player.inv[0] = InvObj(objs.coins, 1)
            deps.handler.handle(player, ClientCheat("theatrecreate"))
            advance()
            assertEquals(InvObj(objs.toxic_blowpipe, 1, 1234), player.worn[Wearpos.RightHand.slot])
            assertEquals(InvObj(objs.coins, 123), player.inv[7])
            assertNull(player.inv[0])
            val guest = registerPlayer()
            guest.modLevel = deps.modLevels[modlevels.player]
            val guestId = checkNotNull(guest.uuid)
            val guestPreset =
                deps.store.save(
                    guestId,
                    "Guest",
                    "Melee",
                    List(14) {
                        if (it == Wearpos.RightHand.slot) GearPresetItem(objs.abyssal_whip.id, 1, 0)
                        else null
                    },
                    List(28) { null },
                )
            deps.store.setDefault(guestId, guestPreset.id)
            guest.inv[27] = InvObj(objs.coins, 10)
            deps.handler.handle(guest, ClientCheat("theatrejoin host"))
            assertEquals(player.coords, guest.coords)
            assertEquals(InvObj(objs.abyssal_whip), guest.worn[Wearpos.RightHand.slot])
            assertTrue(guest.inv.isEmpty())
            assertEquals(InvObj(objs.toxic_blowpipe, 1, 1234), player.worn[Wearpos.RightHand.slot])
            // Starting the raid must not reapply a setup over changes made in the lobby.
            guest.inv[2] = InvObj(objs.coins, 7)
            deps.handler.handle(player, ClientCheat("theatrestart"))
            assertEquals(InvObj(objs.coins, 7), guest.inv[2])
            val inventory = guest.inv.toList()
            val equipment = guest.worn.toList()
            deps.handler.handle(guest, ClientCheat("theatrejoin host"))
            deps.handler.handle(guest, ClientCheat("theatrejoin missing"))
            assertEquals(inventory, guest.inv.toList())
            assertEquals(equipment, guest.worn.toList())
            deps.store.setDefault(hostId, null)
            player.inv[1] = InvObj(objs.coins, 555)
            val kept = player.inv.toList()
            deps.handler.handle(player, ClientCheat("theatrecreate"))
            advance()
            assertEquals(kept, player.inv.toList())
            deps.store.setDefault(guestId, null)
            deps.handler.handle(guest, ClientCheat("theatrejoin host"))
            assertEquals(player.coords, guest.coords)
            assertEquals(inventory, guest.inv.toList())
            assertEquals(equipment, guest.worn.toList())
        }

    @Test
    fun GameTestState
        .`invalid or missing default snapshots leave gear intact without blocking lobby entry`() =
        runInjectedGameTest(
            Dependencies::class,
            module(),
            NylocasDevelopmentCommand::class,
            TheatreRaid::class,
        ) { deps ->
            player.modLevel = deps.modLevels[modlevels.player]
            val character = checkNotNull(player.uuid)
            val preset =
                deps.store.save(
                    character,
                    "Alice",
                    "Invalid",
                    List(14) { null },
                    List(28) { if (it == 27) GearPresetItem(Int.MAX_VALUE, 1, 0) else null },
                )
            deps.store.setDefault(character, preset.id)
            player.inv[0] = InvObj(objs.coins, 12)
            player.worn[Wearpos.RightHand.slot] = InvObj(objs.abyssal_whip)
            val inventory = player.inv.toList()
            val equipment = player.worn.toList()
            val origin = player.coords
            deps.handler.handle(player, ClientCheat("theatrecreate"))
            advance()
            assertNotEquals(origin, player.coords)
            assertEquals(inventory, player.inv.toList())
            assertEquals(equipment, player.worn.toList())
            deps.mapper.writeValue(
                directory.resolve("$character.json").toFile(),
                GearPresetFile(defaultSetupId = preset.id),
            )
            val firstLobby = player.coords
            deps.handler.handle(player, ClientCheat("theatrecreate"))
            advance()
            assertNotEquals(firstLobby, player.coords)
            assertEquals(inventory, player.inv.toList())
            assertEquals(equipment, player.worn.toList())
        }
}
