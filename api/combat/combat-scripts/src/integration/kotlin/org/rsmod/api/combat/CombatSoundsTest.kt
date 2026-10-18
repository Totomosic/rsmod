package org.rsmod.api.combat

import jakarta.inject.Inject
import net.rsprot.protocol.game.outgoing.sound.SynthSound
import net.rsprot.protocol.game.outgoing.zone.payload.SoundArea
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.combat.commons.CombatStance
import org.rsmod.api.combat.manager.PlayerAttackManager
import org.rsmod.api.config.refs.objs
import org.rsmod.api.config.refs.synths
import org.rsmod.api.registry.zone.ZoneUpdateMap
import org.rsmod.api.testing.GameTestState
import org.rsmod.api.testing.factory.npcTypeFactory
import org.rsmod.game.entity.Npc
import org.rsmod.game.inv.InvObj
import org.rsmod.map.zone.ZoneGrid
import org.rsmod.map.zone.ZoneKey

class CombatSoundsTest {
    @ParameterizedTest
    @CsvSource("false, false", "false, true", "true, false", "true, true")
    fun `magic casts are audible in the world on hits and splashes against either target type`(
        playerTarget: Boolean,
        splash: Boolean,
        state: GameTestState,
    ) =
        state.runInjectedGameTest(Dependencies::class) { deps ->
            val targetCoords = player.coords.translateZ(3)
            val target =
                if (playerTarget) registerPlayer(targetCoords)
                else Npc(npcTypeFactory.create {}, targetCoords)
            if (splash) {
                deps.attacks.playMagicSplashFx(player, target, 60, synths.ice_cast, 8)
            } else {
                deps.attacks.playMagicHitFx(player, target, 60, synths.ice_cast, 8, null, 0, null)
            }
            val sound =
                deps.zones[ZoneKey.from(player.coords)]?.filterIsInstance<SoundArea>()?.single {
                    it.id == synths.ice_cast.id
                }
            assertNotNull(sound)
            assertEquals(0, sound!!.delay)
            assertEquals(ZoneGrid.from(player.coords).x, sound.xInZone)
            assertEquals(ZoneGrid.from(player.coords).z, sound.zInZone)
            assertTrue(client.hasNone<SynthSound>())
        }

    @Test
    fun GameTestState.`ordinary melee and ranged attacks emit one shared sound each`() =
        runInjectedGameTest(Dependencies::class) { deps ->
            deps.attacks.playWeaponFx(
                player,
                CombatAttack.Melee(null, null, null, CombatStance.Stance1),
            )
            val melee =
                checkNotNull(deps.zones[ZoneKey.from(player.coords)])
                    .filterIsInstance<SoundArea>()
                    .single()
            assertEquals(synths.human_unarmedpunch.id, melee.id)
            deps.zones.clear()
            deps.attacks.playWeaponFx(
                player,
                CombatAttack.Ranged(InvObj(objs.magic_shortbow), null, null),
            )
            val ranged =
                checkNotNull(deps.zones[ZoneKey.from(player.coords)])
                    .filterIsInstance<SoundArea>()
                    .single()
            assertEquals(0, ranged.delay)
            assertTrue(client.hasNone<SynthSound>())
        }

    class Dependencies
    @Inject
    constructor(val attacks: PlayerAttackManager, val zones: ZoneUpdateMap)
}
