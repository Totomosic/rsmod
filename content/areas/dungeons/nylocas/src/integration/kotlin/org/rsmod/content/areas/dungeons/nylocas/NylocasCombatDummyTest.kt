package org.rsmod.content.areas.dungeons.nylocas

import com.google.inject.AbstractModule
import com.google.inject.Scopes
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.combat.commons.CombatStance
import org.rsmod.api.combat.commons.magic.Spellbook
import org.rsmod.api.combat.commons.styles.MagicAttackStyle
import org.rsmod.api.combat.commons.styles.MeleeAttackStyle
import org.rsmod.api.combat.commons.styles.RangedAttackStyle
import org.rsmod.api.combat.commons.types.MeleeAttackType
import org.rsmod.api.combat.commons.types.RangedAttackType
import org.rsmod.api.combat.manager.PlayerAttackManager
import org.rsmod.api.combat.weapon.scripts.WeaponAttackStylesScript
import org.rsmod.api.combat.weapon.styles.AttackStyles
import org.rsmod.api.config.refs.objs
import org.rsmod.api.config.refs.queues
import org.rsmod.api.config.refs.stats
import org.rsmod.api.hit.plugin.NpcHitScript
import org.rsmod.api.registry.region.RegionRegistry
import org.rsmod.api.testing.GameTestState
import org.rsmod.game.entity.NpcList
import org.rsmod.game.entity.npc.NpcInfoProtocol
import org.rsmod.game.entity.npc.NpcMode
import org.rsmod.game.hit.Hitmark
import org.rsmod.game.inv.InvObj
import org.rsmod.game.map.collision.get
import org.rsmod.game.type.obj.Wearpos
import org.rsmod.map.CoordGrid
import org.rsmod.routefinder.collision.CollisionFlagMap
import org.rsmod.routefinder.flag.CollisionFlag

class NylocasCombatDummyTest {
    class Dependencies
    @Inject
    constructor(
        val npcs: NpcList,
        val regions: RegionRegistry,
        val attacks: PlayerAttackManager,
        val collision: CollisionFlagMap,
    )

    @Test
    fun GameTestState.`each visited room gets one accessible dummy outside the barrier`() =
        runInjectedGameTest(Dependencies::class, null, NylocasCombatDummy::class) { deps ->
            val first = createRegion(NylocasRoom.template)
            player.telejump(first.normal[NylocasRoom.bridge])
            advance()
            val dummy = deps.npcs.single()
            assertEquals(first.normal[NylocasRoom.combatDummy], dummy.coords)
            assertEquals("Attack", dummy.visType.op[1])
            assertTrue(dummy.visType.models.isNotEmpty())
            val blocked =
                CollisionFlag.LOC or CollisionFlag.BLOCK_WALK or CollisionFlag.GROUND_DECOR
            assertEquals(0, deps.collision[dummy.coords] and blocked)
            assertEquals(0, deps.collision[dummy.coords.translateX(-1)] and blocked)
            assertTrue(NylocasRoom.combatDummy.z > 4255)
            val other = registerPlayer(coords = first.normal[NylocasRoom.bridge])
            advance(ticks = 10)
            assertEquals(1, deps.npcs.count())
            assertEquals(first.normal[NylocasRoom.combatDummy], dummy.coords)

            val second = createRegion(BloatExitRoom.template)
            other.telejump(second.normal[NylocasRoom.bridgeStart])
            advance()
            assertEquals(2, deps.npcs.count())
            assertEquals(1, deps.npcs.count { deps.regions[it.coords]?.uid == second.uid })
            player.telejump(CoordGrid(3222, 3218, 0))
            advance()
            removeInactiveRegions()
            advance()
            assertFalse(dummy.isSlotAssigned)
            assertEquals(1, deps.npcs.count())
        }

    @ParameterizedTest
    @ValueSource(strings = ["melee", "ranged", "spell", "staff"])
    fun `attacks always land at their calculated maximum and the dummy never retaliates`(
        style: String,
        state: GameTestState,
    ) =
        state.runInjectedGameTest(
            Dependencies::class,
            CombatModule,
            NylocasCombatDummy::class,
            NpcHitScript::class,
            WeaponAttackStylesScript::class,
        ) { deps ->
            val room = createRegion(NylocasRoom.template)
            player.telejump(room.normal[NylocasRoom.combatDummy.translateX(-1)])
            for (stat in
                listOf(stats.attack, stats.strength, stats.ranged, stats.magic, stats.hitpoints)) {
                player.setBaseLevel(stat, 99)
                player.setCurrentLevel(stat, 99)
            }
            advance()
            val dummy = deps.npcs.single()
            val impacts = mutableListOf<Int>()
            dummy.infoProtocol =
                object : NpcInfoProtocol by dummy.infoProtocol {
                    override fun showHitmark(hitmark: Hitmark) {
                        impacts += hitmark.damage
                    }
                }
            val melee =
                CombatAttack.Melee(
                    null,
                    MeleeAttackType.Crush,
                    MeleeAttackStyle.Aggressive,
                    CombatStance.Stance1,
                )
            val ranged =
                CombatAttack.Ranged(
                    InvObj(objs.shortbow),
                    RangedAttackType.Standard,
                    RangedAttackStyle.Accurate,
                )
            if (style == "ranged") {
                player.worn[Wearpos.RightHand.slot] = InvObj(objs.shortbow)
                player.worn[Wearpos.Quiver.slot] = InvObj(objs.rune_arrow, 100)
            }
            val manager = deps.attacks
            val maximum =
                when (style) {
                    "melee" ->
                        manager.calculateMeleeMaxHit(player, dummy, melee.type, melee.style, 1.0)
                    "ranged" ->
                        manager.calculateRangedMaxHit(
                            player,
                            dummy,
                            ranged.type,
                            ranged.style,
                            1.0,
                            0,
                        )
                    "spell" ->
                        manager
                            .calculateSpellMaxHit(
                                player,
                                dummy,
                                objs.spell_fire_wave,
                                Spellbook.Standard,
                                20,
                                5,
                                false,
                            )
                            .last
                    "staff" -> manager.calculateStaffMaxHit(player, dummy, 30, 1.0)
                    else -> error(style)
                }
            assertTrue(maximum > 1)
            repeat(8) {
                // This roll would miss on an ordinary NPC; damage rolls would also vary.
                random.next = 9999
                val damage =
                    when (style) {
                        "melee" -> manager.rollMeleeDamage(player, dummy, melee)
                        "ranged" -> manager.rollRangedDamage(player, dummy, ranged)
                        "spell" -> {
                            assertTrue(
                                manager.rollSpellAccuracy(
                                    player,
                                    dummy,
                                    objs.spell_fire_wave,
                                    Spellbook.Standard,
                                    false,
                                )
                            )
                            manager.rollSpellMaxHit(
                                player,
                                dummy,
                                objs.spell_fire_wave,
                                Spellbook.Standard,
                                20,
                                5,
                                false,
                            )
                        }
                        "staff" -> {
                            assertTrue(
                                manager.rollStaffAccuracy(
                                    player,
                                    dummy,
                                    MagicAttackStyle.Accurate,
                                    1.0,
                                )
                            )
                            manager.rollStaffMaxHit(player, dummy, 30, 1.0)
                        }
                        else -> error(style)
                    }
                assertEquals(maximum, damage)
                when (style) {
                    "melee" -> manager.queueMeleeHit(player, dummy, damage)
                    "ranged" -> manager.queueRangedDamage(player, dummy, objs.rune_arrow, damage, 1)
                    else -> manager.queueMagicHit(player, dummy, null, damage, 0, 1)
                }
                assertFalse(queues.com_retaliate_player in dummy.queueList)
                advance()
                assertEquals(dummy.baseHitpointsLvl, dummy.hitpoints)
                assertEquals(NpcMode.None, dummy.mode)
                assertEquals(room.normal[NylocasRoom.combatDummy], dummy.coords)
                assertEquals(99, player.stats[stats.hitpoints])
            }
            assertEquals(List(8) { maximum }, impacts)
            // Several simultaneous impacts must also heal separately and retain their full splats.
            repeat(3) { manager.queueMeleeHit(player, dummy, 1000) }
            advance()
            assertEquals(List(3) { 1000 }, impacts.takeLast(3))
            assertEquals(dummy.baseHitpointsLvl, dummy.hitpoints)
            assertTrue(dummy.isSlotAssigned)
            assertFalse(queues.death in dummy.queueList)
        }

    private object CombatModule : AbstractModule() {
        override fun configure() {
            bind(AttackStyles::class.java).`in`(Scopes.SINGLETON)
        }
    }
}
