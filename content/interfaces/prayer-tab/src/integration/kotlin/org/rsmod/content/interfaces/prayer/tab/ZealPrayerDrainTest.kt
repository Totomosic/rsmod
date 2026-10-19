package org.rsmod.content.interfaces.prayer.tab

import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import org.rsmod.api.config.refs.components
import org.rsmod.api.config.refs.interfaces
import org.rsmod.api.config.refs.stats
import org.rsmod.api.config.refs.varbits
import org.rsmod.api.config.refs.varps
import org.rsmod.api.player.stat.prayerLvl
import org.rsmod.api.testing.GameTestState
import org.rsmod.content.interfaces.prayer.tab.scripts.PrayerDrainScript
import org.rsmod.content.interfaces.prayer.tab.scripts.PrayerTabScript

class ZealPrayerDrainTest {
    class Dependencies @Inject constructor(val prayers: PrayerRepository)

    @Test
    fun GameTestState.`Zeal and Piety activate the same prayer and drain identically`() =
        runInjectedGameTest(
            Dependencies::class,
            PrayerModule(),
            PrayerTabScript::class,
            PrayerDrainScript::class,
        ) { deps ->
            val other = registerPlayer(coords = player.coords)
            for (member in listOf(player, other)) {
                member.setBaseLevel(stats.prayer, 99)
                member.setCurrentLevel(stats.prayer, 99)
                member.setBaseLevel(stats.defence, 99)
                member.setVarBit(varbits.kr_knightwaves_state, 8)
                member.setVarBit(varbits.prayer_drain_resistance, 60)
                member.ifOpenOverlay(interfaces.prayerbook, components.toplevel_target_side5)
            }
            advance()
            other.setVarp(varps.zeal, 1)
            val piety =
                deps.prayers.prayerComponents.entries
                    .single { it.value.enabled.isType(varbits.piety) }
                    .key
            player.ifButton(piety)
            other.ifButton(piety)
            advance()
            assertEquals(1, player.vars[varbits.piety])
            assertEquals(1, other.vars[varbits.piety])
            repeat(20) {
                advance()
                assertEquals(player.prayerLvl, other.prayerLvl)
                assertEquals(
                    player.vars[varbits.prayer_drain_counter],
                    other.vars[varbits.prayer_drain_counter],
                )
            }
            assertTrue(player.prayerLvl < 99)
        }
}
