@file:OptIn(org.rsmod.annotations.InternalApi::class, org.rsmod.game.type.util.UncheckedType::class)

package org.rsmod.api.combat

import com.google.inject.AbstractModule
import com.google.inject.Scopes
import java.nio.file.Files
import java.nio.file.Path
import kotlin.reflect.KClass
import net.rsprot.protocol.game.outgoing.misc.player.MessageGame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.rsmod.api.combat.scripts.PvNCombatScript
import org.rsmod.api.combat.scripts.misc.DinhsBulwarkScript
import org.rsmod.api.combat.weapon.scripts.WeaponAttackStylesScript
import org.rsmod.api.combat.weapon.scripts.WeaponAttackTypesScript
import org.rsmod.api.combat.weapon.styles.AttackStyles
import org.rsmod.api.combat.weapon.types.AttackTypes
import org.rsmod.api.config.refs.areas
import org.rsmod.api.config.refs.components
import org.rsmod.api.config.refs.interfaces
import org.rsmod.api.config.refs.objs
import org.rsmod.api.config.refs.stats
import org.rsmod.api.config.refs.varbits
import org.rsmod.api.config.refs.varps
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.hit.plugin.NpcHitScript
import org.rsmod.api.hit.plugin.PlayerHitScript
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.random.DefaultGameRandom
import org.rsmod.api.spells.MagicSpellModule
import org.rsmod.api.spells.attack.SpellAttackRegistry
import org.rsmod.api.spells.autocast.scripts.AutocastScript
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
import org.rsmod.api.weapons.WeaponModule
import org.rsmod.content.areas.dungeons.nylocas.NylocasDeaths
import org.rsmod.content.areas.dungeons.nylocas.NylocasEncounter
import org.rsmod.content.areas.dungeons.nylocas.NylocasPillars
import org.rsmod.content.areas.dungeons.nylocas.NylocasRoom
import org.rsmod.content.areas.dungeons.nylocas.TheatreRaid
import org.rsmod.content.areas.dungeons.nylocas.TheatreRaidOptions
import org.rsmod.content.areas.dungeons.nylocas.nylocas_locs
import org.rsmod.content.interfaces.prayer.tab.PrayerModule
import org.rsmod.content.interfaces.prayer.tab.scripts.PrayerDrainScript
import org.rsmod.content.interfaces.prayer.tab.scripts.PrayerTabScript
import org.rsmod.content.other.consumables.ConsumablesScript
import org.rsmod.game.inv.InvObj
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript

/** Explicit offline batch job hosted by the real, unpaced game-cycle harness. */
@EnabledIfSystemProperty(named = "nylo.simulation", matches = "true")
class NylocasSimulationTest {
    @Test
    fun runTrials(state: GameTestState) {
        val trials = System.getProperty("nylo.trials", "100").toInt()
        val seed = System.getProperty("nylo.seed", "1").toLong()
        val maxTicks = System.getProperty("nylo.maxTicks", "2000").toInt()
        require(trials > 0 && maxTicks > 0)
        val output = Path.of(System.getProperty("nylo.output", "build/nylo-trials.csv"))
        output.toAbsolutePath().parent.let(Files::createDirectories)
        Files.newBufferedWriter(output).use { writer ->
            writer.appendLine(
                "trial,seed,status,ticks,ranger_attacks,mage_attacks,ranger_idle_ticks,mage_idle_ticks,ranger_ready_idle_ticks,mage_ready_idle_ticks,same_tick_double_ups,shared_targets,positive_damage_rolls,pre_cap_stalls,post_cap_stalls,pre_29_stalls,wave_29_stalls,wall_seconds"
            )
            repeat(trials) { trial ->
                val started = System.nanoTime()
                state.runInjectedGameTest(
                    NylocasSimulationDependencies::class,
                    SimulationModule,
                    NylocasEncounter::class,
                    NylocasDeaths::class,
                    NylocasPillars::class,
                    TheatreRaid::class,
                    NpcHitScript::class,
                    PlayerHitScript::class,
                    PvNCombatScript::class,
                    DinhsBulwarkScript::class,
                    WeaponAttackStylesScript::class,
                    WeaponAttackTypesScript::class,
                    PrayerTabScript::class,
                    PrayerDrainScript::class,
                    ConsumablesScript::class,
                    AutocastScript::class,
                    script("org.rsmod.api.specials.scripts.SpecialAttackScript"),
                    ComboRuneScript::class,
                    CompactRuneScript::class,
                    FakeRuneScript::class,
                    StaffSubstituteScript::class,
                    RuneSubstituteScript::class,
                    UnlimitedRuneScript::class,
                    coreRandom = DefaultGameRandom(seed + trial),
                ) { deps ->
                    random.reseed(seed + trial)
                    deps.shuffled.reseed(seed + trial)
                    deps.registerWeapons()
                    val sourceBarrier = placeMapLoc(CoordGrid(3295, 4255), nylocas_locs.barrier)
                    val eastSource = placeMapLoc(CoordGrid(3296, 4255), nylocas_locs.barrier)
                    val room = createRegion(NylocasRoom.template)
                    val barrier = sourceBarrier.copy(coords = room.normal[sourceBarrier.coords])
                    val eastBarrier = eastSource.copy(coords = room.normal[eastSource.coords])
                    player.telejump(room.normal[CoordGrid(3295, 4256)])
                    val magePlayer = registerPlayer(coords = room.normal[CoordGrid(3296, 4256)])
                    for ((index, member) in listOf(player, magePlayer).withIndex()) {
                        member.observerUUID = index.toLong() + 1
                        for (stat in
                            listOf(
                                stats.hitpoints,
                                stats.attack,
                                stats.strength,
                                stats.defence,
                                stats.ranged,
                                stats.magic,
                                stats.prayer,
                            )) {
                            member.setBaseLevel(stat, 99)
                            member.setCurrentLevel(stat, 99)
                        }
                        member.enableRun()
                        member.ifOpenOverlay(
                            interfaces.prayerbook,
                            components.toplevel_target_side5,
                        )
                        for (prayer in deps.prayers.prayerList) prayer.unlocked?.let {
                            member.setVarBit(it, prayer.unlockState)
                        }
                        member.setVarBit(varbits.prayer_drain_resistance, 60)
                        for (slot in 3..6) member.inv[slot] = InvObj(2434, 1)
                        member.setVarp(varps.sa_energy, 1000)
                        member.setVarBit(varbits.spellbook, 1)
                        member.inv[0] = InvObj(objs.water_rune, 100000)
                        member.inv[1] = InvObj(objs.death_rune, 100000)
                        member.inv[2] = InvObj(objs.blood_rune, 100000)
                    }
                    advance()
                    // The harness starts with an empty area index; populate real instance multiway
                    // tiles.
                    for (x in 3280..3311) for (z in 4232..4255) {
                        deps.areas.registerAll(
                            room.normal[CoordGrid(x, z)],
                            listOf(areas.multiway.id.toShort()).iterator(),
                        )
                    }
                    deps.raid.start(player, TheatreRaidOptions(startingHitpoints250 = true))
                    player.opLoc1(barrier)
                    magePlayer.opLoc1(eastBarrier)
                    advance()
                    check(player.ui.containsModal(interfaces.chatmenu)) {
                        "Entry dialogue did not open"
                    }
                    player.resumePauseButton(components.chatmenu_pbutton, sub = 1)
                    advance()
                    check(deps.regions.normalizeCoords(player.coords).z == 4254) {
                        "Ranger did not enter"
                    }
                    check(deps.regions.normalizeCoords(magePlayer.coords).z == 4254) {
                        "Mage did not enter"
                    }
                    val agents = NylocasServerAgents(this, deps, player, magePlayer, room)
                    val stalls = NylocasSimulationStalls()
                    eventBus.subscribeUnbound(GameLifecycle.EndCycle::class.java) {
                        client.filterIsInstance<MessageGame>().forEach { stalls.observe(it.message) }
                    }
                    var tick = 0
                    var status = "timeout"
                    while (tick < maxTicks) {
                        if (deps.npcs.any { it.visType.id in 8354..8357 }) {
                            status = "completed"
                            break
                        }
                        if (player.hitpoints <= 0 || magePlayer.hitpoints <= 0) {
                            status = "death"
                            break
                        }
                        agents.step(tick)
                        tick++
                    }
                    if (deps.npcs.any { it.visType.id in 8354..8357 }) status = "completed"
                    else if (player.hitpoints <= 0 || magePlayer.hitpoints <= 0) status = "death"
                    val elapsed = (System.nanoTime() - started) / 1e9
                    writer.appendLine(
                        listOf(
                                trial,
                                seed + trial,
                                status,
                                tick,
                                agents.attacks[0],
                                agents.attacks[1],
                                agents.idle[0],
                                agents.idle[1],
                                agents.readyIdle[0],
                                agents.readyIdle[1],
                                agents.doubleUps,
                                agents.targets.values.count { it == 3 },
                                agents.positiveRolls,
                                stalls.preCap,
                                stalls.postCap,
                                stalls.pre29,
                                stalls.wave29,
                                elapsed,
                            )
                            .joinToString(",")
                    )
                    writer.flush()
                    if ((trial + 1) % 10 == 0 || trial == trials - 1)
                        println("Nylocas: ${trial + 1}/$trials trials; last=$status ${tick} ticks")
                }
            }
        }
    }

    private companion object {
        @Suppress("UNCHECKED_CAST")
        fun script(name: String) = Class.forName(name).kotlin as KClass<out PluginScript>
    }

    private object SimulationModule : AbstractModule() {
        override fun configure() {
            install(PrayerModule())
            install(MagicSpellModule())
            install(WeaponModule())
            install(org.rsmod.api.specials.SpecialAttackModule())
            bind(SpellAttackRegistry::class.java).`in`(Scopes.SINGLETON)
            bind(AttackStyles::class.java).`in`(Scopes.SINGLETON)
            bind(AttackTypes::class.java).`in`(Scopes.SINGLETON)
            bind(ComboRuneRepository::class.java).`in`(Scopes.SINGLETON)
            bind(CompactRuneRepository::class.java).`in`(Scopes.SINGLETON)
            bind(FakeRuneRepository::class.java).`in`(Scopes.SINGLETON)
            bind(StaffSubstituteRepository::class.java).`in`(Scopes.SINGLETON)
            bind(RuneSubstituteRepository::class.java).`in`(Scopes.SINGLETON)
            bind(UnlimitedRuneRepository::class.java).`in`(Scopes.SINGLETON)
        }
    }
}
