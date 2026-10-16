package org.rsmod.content.areas.dungeons.nylocas

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.rsmod.api.combat.commons.npc.combatPlayDefendAnim
import org.rsmod.api.config.refs.npcs
import org.rsmod.api.config.refs.params
import org.rsmod.api.testing.GameTestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.seq.EntitySeq

class NylocasAnimationTest {
    @ParameterizedTest
    @CsvSource(
        "8342, 8004, 8005",
        "8343, 7999, 7998",
        "8344, 7989, 7991",
        "8345, 8004, 8005",
        "8346, 7999, 7998",
        "8347, 7989, 7991",
        "8348, 8004, 8005",
        "8349, 7999, 7998",
        "8350, 7989, 7991",
        "8351, 8004, 8005",
        "8352, 7999, 7998",
        "8353, 7989, 7991",
    )
    fun `every wave form uses native attack and death sequences`(
        id: Int,
        attack: Int,
        death: Int,
        state: GameTestState,
    ) {
        state.runGameTest {
            val type = checkNotNull(state.cacheTypes.npcs[id])
            assertEquals(attack, type.param(params.attack_anim).id)
            assertEquals(death, type.param(params.death_anim).id)
            val npc = Npc(type, player.coords)
            npc.combatPlayDefendAnim()
            assertEquals(EntitySeq.NULL, npc.pendingSequence)
        }
    }

    @Test
    fun GameTestState.`nylo hit reactions do not interrupt an active native attack`() {
        runGameTest {
            for (id in 8342..8353) {
                val npc = Npc(checkNotNull(cacheTypes.npcs[id]), player.coords)
                npc.anim(npc.visType.param(params.attack_anim))
                val attack = npc.pendingSequence
                npc.combatPlayDefendAnim(clientDelay = 15)
                assertEquals(attack, npc.pendingSequence)
            }
        }
    }

    @Test
    fun GameTestState.`ordinary NPCs retain their configured hit reactions`() {
        runGameTest {
            val type = cacheTypes.npcs[npcs.cow]
            val npc = Npc(type, player.coords)
            npc.combatPlayDefendAnim()
            assertEquals(type.param(params.defend_anim).id, npc.pendingSequence.id)
        }
    }
}
