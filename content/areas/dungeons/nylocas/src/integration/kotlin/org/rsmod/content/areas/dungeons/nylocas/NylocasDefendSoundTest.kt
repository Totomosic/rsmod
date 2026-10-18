package org.rsmod.content.areas.dungeons.nylocas

import jakarta.inject.Inject
import net.rsprot.protocol.game.outgoing.sound.SynthSound
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.rsmod.api.hit.plugin.NpcHitScript
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.testing.GameTestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.npc.NpcMode
import org.rsmod.game.hit.HitType

class NylocasDefendSoundTest {
    class Dependencies @Inject constructor(val npcs: NpcRepository)

    @ParameterizedTest
    @CsvSource(
        "8342, 8342",
        "8343, 8343",
        "8344, 8344",
        "8345, 8345",
        "8346, 8346",
        "8347, 8347",
        "8348, 8348",
        "8349, 8349",
        "8350, 8350",
        "8351, 8351",
        "8352, 8352",
        "8353, 8353",
        "8342, 8349",
        "8343, 8350",
        "8344, 8348",
        "8345, 8352",
        "8346, 8353",
        "8347, 8351",
    )
    fun `every wave form plays a defend sound on each impact including zero and lethal hits`(
        id: Int,
        visibleId: Int,
        state: GameTestState,
    ) =
        state.runInjectedGameTest(
            Dependencies::class,
            null,
            NpcHitScript::class,
            NylocasDeaths::class,
        ) { deps ->
            val npc = Npc(checkNotNull(state.cacheTypes.npcs[id]), player.coords.translateX(1))
            npc.mode = NpcMode.None
            npc.setHunt(0)
            npc.aiTimer(0)
            npc.respawns = false
            deps.npcs.add(npc, Int.MAX_VALUE)
            if (id != visibleId)
                npc.transmog(checkNotNull(state.cacheTypes.npcs[visibleId]), Int.MAX_VALUE)
            npc.baseHitpointsLvl = 10
            npc.hitpoints = 10
            advance(2)

            npc.queueHit(player, delay = 2, HitType.Melee, damage = 0)
            npc.queueHit(player, delay = 2, HitType.Ranged, damage = 1)
            advance()
            assertTrue(client.hasNone<SynthSound>())
            advance()
            val hits = client.filterIsInstance<SynthSound>()
            assertEquals(listOf(SynthSound(4020, 1, 0), SynthSound(4020, 1, 0)), hits)

            npc.queueHit(player, delay = 1, HitType.Magic, damage = npc.hitpoints)
            advance()
            assertEquals(listOf(SynthSound(4020, 1, 0)), client.filterIsInstance<SynthSound>())
            assertEquals(0, npc.hitpoints)
        }
}
