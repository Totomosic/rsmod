package org.rsmod.content.other.commands

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.inject.AbstractModule
import com.google.inject.Provides
import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.nio.file.Path
import java.util.HexFormat
import net.rsprot.protocol.game.incoming.misc.user.ClientCheat
import net.rsprot.protocol.game.outgoing.misc.player.MessageGame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.rsmod.api.config.refs.modlevels
import org.rsmod.api.config.refs.objs
import org.rsmod.api.config.refs.stats
import org.rsmod.api.net.rsprot.handlers.ClientCheatHandler
import org.rsmod.api.parsers.json.Json
import org.rsmod.api.player.events.interact.HeldEquipEvents
import org.rsmod.api.testing.GameTestState
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
            val preset = deps.store.list(checkNotNull(player.uuid)).single()
            assertEquals("Nylo Duo", preset.name)
            assertEquals(
                preset,
                GearPresetStore(deps.mapper, directory).list(checkNotNull(player.uuid)).single(),
            )
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
            deps.handler.handle(player, ClientCheat("gearsetups load 1234abcd ${preset.id}"))
            assertEquals(equipment, player.worn.toList())
            assertEquals(inventory, player.inv.toList())
            assertTrue(
                changed.containsAll(listOf(Wearpos.Torso, Wearpos.RightHand, Wearpos.Quiver))
            )
            assertTrue(player.worn.hasModifiedSlots())
            assertTrue(player.inv.hasModifiedSlots())
        }

    @Test
    fun GameTestState.`saving a name again updates it while other characters stay isolated`() =
        runInjectedGameTest(Dependencies::class, module(), GearPresetCommand::class) { deps ->
            player.modLevel = deps.modLevels[modlevels.admin]
            val character = checkNotNull(player.uuid)
            val first = deps.store.save(character, "Nylo Duo", List(14) { null }, List(28) { null })
            player.inv[4] = InvObj(objs.coins, 200)
            val name = HexFormat.of().formatHex("NYLO DUO".toByteArray())
            deps.handler.handle(player, ClientCheat("gearsetups save abcdef01 $name"))
            val updated = deps.store.list(character).single()
            assertEquals(first.id, updated.id)
            assertEquals("NYLO DUO", updated.name)
            assertEquals(200, updated.inventory[4]?.count)
            val other = registerPlayer()
            other.modLevel = deps.modLevels[modlevels.admin]
            other.inv[0] = InvObj(objs.coins, 1)
            deps.handler.handle(other, ClientCheat("gearsetups load abcdef01 ${first.id}"))
            assertEquals(1, other.inv[0]?.count)
            assertTrue(deps.store.list(checkNotNull(other.uuid)).isEmpty())
        }

    @Test
    fun GameTestState.`invalid setups are rejected before either live container changes`() =
        runInjectedGameTest(Dependencies::class, module(), GearPresetCommand::class) { deps ->
            player.modLevel = deps.modLevels[modlevels.admin]
            player.setCurrentLevel(stats.hitpoints, 99)
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
                val invalid = deps.store.save(checkNotNull(player.uuid), name, worn, inv)
                deps.handler.handle(player, ClientCheat("gearsetups load 1234abcd ${invalid.id}"))
                assertEquals(equipment, player.worn.toList())
                assertEquals(inventory, player.inv.toList())
            }
        }

    @Test
    fun GameTestState
        .`ordinary players cannot spawn gear through presets and empty setups clear every slot`() =
        runInjectedGameTest(Dependencies::class, module(), GearPresetCommand::class) { deps ->
            player.modLevel = deps.modLevels[modlevels.player]
            val name = HexFormat.of().formatHex("Empty".toByteArray())
            deps.handler.handle(player, ClientCheat("gearsetups save 1234abcd $name"))
            assertTrue(deps.store.list(checkNotNull(player.uuid)).isEmpty())
            assertTrue(
                client.filterIsInstance<MessageGame>().any {
                    it.message.startsWith("LGP1 1234abcd error ")
                }
            )
            player.modLevel = deps.modLevels[modlevels.admin]
            player.setCurrentLevel(stats.hitpoints, 99)
            deps.handler.handle(player, ClientCheat("gearsetups save 1234abcd $name"))
            val preset = deps.store.list(checkNotNull(player.uuid)).single()
            player.inv[27] = InvObj(objs.coins, 500)
            player.worn[Wearpos.RightHand.slot] = InvObj(objs.abyssal_whip)
            deps.handler.handle(player, ClientCheat("gearsetups load 1234abcd ${preset.id}"))
            assertTrue(player.inv.isEmpty())
            assertTrue(player.worn.isEmpty())
        }
}
