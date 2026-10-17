package org.rsmod.content.other.commands

import com.google.inject.AbstractModule
import com.google.inject.Scopes
import jakarta.inject.Inject
import net.rsprot.protocol.game.incoming.misc.user.ClientCheat
import org.junit.jupiter.api.Test
import org.rsmod.api.combat.commons.magic.Spellbook
import org.rsmod.api.combat.manager.MagicRuneManager
import org.rsmod.api.config.refs.modlevels
import org.rsmod.api.config.refs.objs
import org.rsmod.api.config.refs.varbits
import org.rsmod.api.net.rsprot.handlers.ClientCheatHandler
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.spells.MagicSpellModule
import org.rsmod.api.spells.MagicSpellRegistry
import org.rsmod.api.spells.runes.combo.ComboRuneRepository
import org.rsmod.api.spells.runes.combo.ComboRuneScript
import org.rsmod.api.spells.runes.compact.CompactRuneRepository
import org.rsmod.api.spells.runes.compact.CompactRuneScript
import org.rsmod.api.spells.runes.fake.FakeRuneRepository
import org.rsmod.api.spells.runes.fake.FakeRuneScript
import org.rsmod.api.spells.runes.staves.StaffSubstituteRepository
import org.rsmod.api.spells.runes.staves.StaffSubstituteScript
import org.rsmod.api.spells.runes.subs.RuneSubstituteRepository
import org.rsmod.api.spells.runes.subs.RuneSubstituteScript
import org.rsmod.api.spells.runes.unlimited.UnlimitedRuneRepository
import org.rsmod.api.spells.runes.unlimited.UnlimitedRuneScript
import org.rsmod.api.testing.GameTestState
import org.rsmod.game.inv.InvObj
import org.rsmod.game.type.mod.ModLevelTypeList

class SpellbookCommandTest {
    private object RuneTestModule : AbstractModule() {
        override fun configure() {
            install(MagicSpellModule())
            bind(ComboRuneRepository::class.java).`in`(Scopes.SINGLETON)
            bind(CompactRuneRepository::class.java).`in`(Scopes.SINGLETON)
            bind(FakeRuneRepository::class.java).`in`(Scopes.SINGLETON)
            bind(StaffSubstituteRepository::class.java).`in`(Scopes.SINGLETON)
            bind(RuneSubstituteRepository::class.java).`in`(Scopes.SINGLETON)
            bind(UnlimitedRuneRepository::class.java).`in`(Scopes.SINGLETON)
        }
    }

    class Dependencies
    @Inject
    constructor(
        val handler: ClientCheatHandler,
        val modLevels: ModLevelTypeList,
        val runes: MagicRuneManager,
        val spells: MagicSpellRegistry,
    )

    @Test
    fun GameTestState.`admin can select each book and switching clears autocast`() =
        runInjectedGameTest(Dependencies::class, null, SpellbookCommand::class) { deps ->
            player.modLevel = deps.modLevels[modlevels.admin]
            for ((name, book) in
                listOf(
                    "ancient" to Spellbook.Ancients,
                    "arceuus" to Spellbook.Arceuus,
                    "lunar" to Spellbook.Lunars,
                    "standard" to Spellbook.Standard,
                )) {
                VarPlayerIntMapSetter.set(player, varbits.autocast_set, 1)
                VarPlayerIntMapSetter.set(player, varbits.autocast_spell, 1)
                VarPlayerIntMapSetter.set(player, varbits.autocast_defmode, 1)
                deps.handler.handle(player, ClientCheat("spellbook $name"))
                assertEquals(book.varValue, player.vars[varbits.spellbook])
                assertEquals(0, player.vars[varbits.autocast_set])
                assertEquals(0, player.vars[varbits.autocast_spell])
                assertEquals(0, player.vars[varbits.autocast_defmode])
            }
            for (args in listOf("", "invalid", "4", "ancient lunar")) {
                deps.handler.handle(player, ClientCheat("spellbook $args"))
                assertEquals(0, player.vars[varbits.spellbook])
            }
            player.modLevel = deps.modLevels[modlevels.player]
            deps.handler.handle(player, ClientCheat("spellbook ancient"))
            assertEquals(0, player.vars[varbits.spellbook])
        }

    @Test
    fun GameTestState
        .`all pouch variants supply unlimited runes and removal restores requirements`() =
        runInjectedGameTest(
            Dependencies::class,
            RuneTestModule,
            ComboRuneScript::class,
            CompactRuneScript::class,
            FakeRuneScript::class,
            StaffSubstituteScript::class,
            RuneSubstituteScript::class,
            UnlimitedRuneScript::class,
        ) { deps ->
            val spells =
                listOf(
                    checkNotNull(deps.spells.getObjSpell(objs.spell_fire_surge)),
                    checkNotNull(deps.spells.getObjSpell(objs.spell_ice_barrage)),
                    checkNotNull(deps.spells.getObjSpell(objs.spell_vengeance)),
                    deps.spells.combatSpells().first { it.spellbook == Spellbook.Arceuus },
                )
            for (pouch in
                listOf(
                    objs.rune_pouch,
                    objs.rune_pouch_l,
                    objs.divine_rune_pouch,
                    objs.divine_rune_pouch_l,
                    objs.lms_rune_pouch,
                )) {
                player.inv[0] = InvObj(pouch)
                player.inv[1] = InvObj(objs.blighted_ancient_ice_sack, 5)
                player.inv[2] = InvObj(objs.fire_rune, 10)
                for (spell in spells) {
                    assertEquals(
                        MagicRuneManager.CastResult.Success.AllUnlimited,
                        deps.runes.delReqs(player, spell),
                    )
                }
                assertEquals(InvObj(pouch), player.inv[0])
                assertEquals(InvObj(objs.blighted_ancient_ice_sack, 5), player.inv[1])
                assertEquals(InvObj(objs.fire_rune, 10), player.inv[2])
                player.inv[0] = null
                assertFalse(deps.runes.hasRunes(player, spells.first()))
            }
            // A pouch supplies runes, but never replaces a required staff.
            player.inv[0] = InvObj(objs.rune_pouch)
            val iban = checkNotNull(deps.spells.getObjSpell(objs.spell_iban_blast))
            assertFalse(deps.runes.hasRunes(player, iban))
        }
}
