package org.rsmod.api.combat

import com.google.inject.AbstractModule
import com.google.inject.Scopes
import jakarta.inject.Inject
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.rsmod.api.combat.scripts.PvNCombatScript
import org.rsmod.api.combat.weapon.scripts.WeaponAttackStylesScript
import org.rsmod.api.combat.weapon.scripts.WeaponAttackTypesScript
import org.rsmod.api.combat.weapon.styles.AttackStyles
import org.rsmod.api.combat.weapon.types.AttackTypes
import org.rsmod.api.config.refs.stats
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.spells.MagicSpellModule
import org.rsmod.api.testing.GameTestState
import org.rsmod.content.areas.dungeons.nylocas.NylocasBehaviour
import org.rsmod.content.areas.dungeons.nylocas.NylocasEncounter
import org.rsmod.content.areas.dungeons.nylocas.NylocasPillar
import org.rsmod.content.areas.dungeons.nylocas.NylocasRoom
import org.rsmod.content.areas.dungeons.nylocas.NylocasSize
import org.rsmod.content.areas.dungeons.nylocas.NylocasSlot
import org.rsmod.content.areas.dungeons.nylocas.NylocasSpawn
import org.rsmod.content.areas.dungeons.nylocas.NylocasStyle
import org.rsmod.content.areas.dungeons.nylocas.nylocas_npcs
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.npc.NpcMode
import org.rsmod.game.interact.InteractionNpc
import org.rsmod.game.type.npc.NpcTypeList

class NylocasInteractionTest {
    class Dependencies
    @Inject
    constructor(
        val behaviour: NylocasBehaviour,
        val npcRepo: NpcRepository,
        val npcTypes: NpcTypeList,
    )

    @ParameterizedTest
    @CsvSource(
        "Small, Melee, Ranged, false",
        "Small, Ranged, Magic, false",
        "Small, Magic, Melee, false",
        "Big, Melee, Ranged, false",
        "Big, Ranged, Magic, false",
        "Big, Magic, Melee, false",
        "Small, Melee, Ranged, true",
        "Small, Ranged, Magic, true",
        "Small, Magic, Melee, true",
        "Big, Melee, Ranged, true",
        "Big, Ranged, Magic, true",
        "Big, Magic, Melee, true",
    )
    fun `a flicker cancels an attack waiting for cooldown until the player clicks again`(
        size: NylocasSize,
        initialStyle: NylocasStyle,
        nextStyle: NylocasStyle,
        pillar: Boolean,
        state: GameTestState,
    ) =
        state.runInjectedGameTest(
            Dependencies::class,
            object : AbstractModule() {
                override fun configure() {
                    bind(AttackStyles::class.java).`in`(Scopes.SINGLETON)
                    bind(AttackTypes::class.java).`in`(Scopes.SINGLETON)
                    install(MagicSpellModule())
                }
            },
            NylocasEncounter::class,
            PvNCombatScript::class,
            WeaponAttackStylesScript::class,
            WeaponAttackTypesScript::class,
        ) { deps ->
            val room = createRegion(NylocasRoom.template)
            player.telejump(room.normal[NylocasRoom.bridge])
            player.setBaseLevel(stats.hitpoints, 99)
            player.setCurrentLevel(stats.hitpoints, 99)
            val spawn =
                NylocasSpawn(
                    size,
                    initialStyle,
                    if (pillar) NylocasPillar.NorthWest else null,
                    styleAt26 = nextStyle,
                )
            val npc =
                Npc(
                    deps.npcTypes[nylocas_npcs.incoming(size, initialStyle)],
                    room.normal[NylocasSlot.WestNorth.entry],
                )
            npc.mode = NpcMode.None
            npc.setHunt(0)
            deps.behaviour.add(npc, room, NylocasSlot.WestNorth, spawn, 1)
            deps.npcRepo.add(npc, Int.MAX_VALUE)
            npc.respawns = false
            advance(ticks = 25)
            player.telejump(npc.coords.translateX(npc.size))
            val cooldown = player.currentMapClock + 4
            player.actionDelay = cooldown
            player.opNpc2(npc)
            val teammate = registerPlayer(coords = player.coords)
            teammate.actionDelay = cooldown
            teammate.opNpc2(npc)
            val otherNpc = Npc(npc.type, player.coords.translateZ(1))
            otherNpc.mode = NpcMode.None
            otherNpc.setHunt(0)
            otherNpc.aiTimer(0)
            deps.npcRepo.add(otherNpc, Int.MAX_VALUE)
            val bystander = registerPlayer(coords = player.coords)
            bystander.actionDelay = cooldown + 20
            bystander.opNpc2(otherNpc)
            advance()
            assertEquals(npc, (player.interaction as? InteractionNpc)?.target)
            assertEquals(npc, (teammate.interaction as? InteractionNpc)?.target)
            assertEquals(otherNpc, (bystander.interaction as? InteractionNpc)?.target)
            assertEquals(cooldown, player.actionDelay)

            advance()
            val expected =
                if (pillar) nylocas_npcs.incoming(size, nextStyle)
                else nylocas_npcs.fighting(size, nextStyle)
            assertEquals(expected.id, npc.visType.id)
            assertNull(player.interaction)
            assertNull(teammate.interaction)
            assertEquals(otherNpc, (bystander.interaction as? InteractionNpc)?.target)
            assertNull(player.routeRequest)
            assertTrue(player.routeDestination.isEmpty())
            assertEquals(cooldown, player.actionDelay)
            advance(ticks = 4)
            assertNull(player.interaction)
            assertEquals(cooldown, player.actionDelay)

            player.opNpc2(npc)
            advance(ticks = 2)
            assertTrue(player.actionDelay > cooldown)
        }
}
