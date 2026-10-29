@file:OptIn(org.rsmod.annotations.InternalApi::class, org.rsmod.game.type.util.UncheckedType::class)

package org.rsmod.api.combat

import jakarta.inject.Inject
import java.util.IdentityHashMap
import org.localscape.nylocas.NylocasFlickers
import org.localscape.nylocas.PrayerPolicy
import org.localscape.nylocas.Tile
import org.localscape.nylocas.mage.MageStrategy
import org.localscape.nylocas.ranger.RangerStrategy
import org.rsmod.api.combat.commons.PlayerAttackTarget
import org.rsmod.api.combat.commons.PlayerDamageRoll
import org.rsmod.api.config.refs.objs
import org.rsmod.api.config.refs.queues
import org.rsmod.api.config.refs.varbits
import org.rsmod.api.config.refs.varps
import org.rsmod.api.player.clearInteractionRoute
import org.rsmod.api.player.events.interact.HeldEquipEvents
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.player.stat.prayerLvl
import org.rsmod.api.player.worn.Blowpipe
import org.rsmod.api.registry.region.RegionRegistry
import org.rsmod.api.route.BoundValidator
import org.rsmod.api.route.RayCastValidator
import org.rsmod.api.route.StepFactory
import org.rsmod.api.specials.SpecialAttackManager
import org.rsmod.api.specials.SpecialAttackRepository
import org.rsmod.api.spells.MagicSpellRegistry
import org.rsmod.api.spells.attack.SpellAttackManager
import org.rsmod.api.spells.attack.SpellAttackRepository
import org.rsmod.api.testing.scope.GameTestScope
import org.rsmod.api.weapons.WeaponAttackManager
import org.rsmod.api.weapons.WeaponRepository
import org.rsmod.content.areas.dungeons.nylocas.TheatreRaid
import org.rsmod.content.areas.dungeons.nylocas.tob_varbits
import org.rsmod.content.interfaces.prayer.tab.PrayerRepository
import org.rsmod.content.other.special.attacks.melee.DinhsBulwarkSpecialAttack
import org.rsmod.content.other.special.attacks.melee.DragonClawsSpecialAttack
import org.rsmod.content.other.special.weapons.magic.EyeOfAyakWeapons
import org.rsmod.content.other.special.weapons.magic.TumekensShadowWeapons
import org.rsmod.content.other.special.weapons.melee.ScytheOfViturWeapons
import org.rsmod.content.other.special.weapons.melee.SulphurBladesWeapons
import org.rsmod.content.other.special.weapons.ranged.BlackChinchompaWeapons
import org.rsmod.content.other.special.weapons.ranged.ToxicBlowpipeWeapons
import org.rsmod.content.skills.magic.spell.attacks.ancient.AncientSpells
import org.rsmod.game.MapClock
import org.rsmod.game.area.AreaIndex
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.NpcList
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.util.ShuffledPlayerList
import org.rsmod.game.inv.InvObj
import org.rsmod.game.region.Region
import org.rsmod.game.type.obj.Wearpos
import org.rsmod.game.type.varbit.VarBitType
import org.rsmod.map.CoordGrid

internal class NylocasSimulationDependencies
@Inject
constructor(
    val prayers: PrayerRepository,
    val shuffled: ShuffledPlayerList,
    val raid: TheatreRaid,
    val clock: MapClock,
    val regions: RegionRegistry,
    val steps: StepFactory,
    val npcs: NpcList,
    val areas: AreaIndex,
    val rays: RayCastValidator,
    val bounds: BoundValidator,
    val weaponRepo: WeaponRepository,
    val weaponManager: WeaponAttackManager,
    val chins: BlackChinchompaWeapons,
    val scythe: ScytheOfViturWeapons,
    val sulphur: SulphurBladesWeapons,
    val eye: EyeOfAyakWeapons,
    val shadow: TumekensShadowWeapons,
    val pipe: ToxicBlowpipeWeapons,
    val bulwark: DinhsBulwarkSpecialAttack,
    val claws: DragonClawsSpecialAttack,
    val specialRepo: SpecialAttackRepository,
    val specialManager: SpecialAttackManager,
    val ancients: AncientSpells,
    val spellRepo: SpellAttackRepository,
    val spellManager: SpellAttackManager,
    val spells: MagicSpellRegistry,
) {
    fun registerWeapons() {
        for (map in listOf(chins, scythe, sulphur, eye, shadow, pipe)) with(map) {
            weaponRepo.register(weaponManager)
        }
        with(bulwark) { specialRepo.register(specialManager) }
        with(claws) { specialRepo.register(specialManager) }
        with(ancients) { spellRepo.register(spellManager) }
    }
}

/** Converts authoritative server observations to the exact policy models used by RuneLite. */
internal class NylocasServerAgents(
    val scope: GameTestScope,
    val deps: NylocasSimulationDependencies,
    val rangerPlayer: Player,
    val magePlayer: Player,
    val region: Region,
) {
    private val ranger = RangerStrategy()
    private val mage = MageStrategy()
    private val rangedRoom = RangerStrategy.Room()
    private val magicRoom = MageStrategy.Room()
    private val rangedCrabs = IdentityHashMap<Npc, RangerStrategy.Crab>()
    private val magicCrabs = IdentityHashMap<Npc, MageStrategy.Crab>()
    private val gear = arrayOf(mutableMapOf<Int, InvObj>(), mutableMapOf<Int, InvObj>())
    private val nextDrink = IntArray(2)
    private val offensiveStyle = intArrayOf(1, 2)
    private var serial = 0
    private var tick = 0
    private var rangedPending: RangerStrategy.Decision? = null
    private var magicPending: MageStrategy.Decision? = null
    val attacks = IntArray(2)
    val idle = IntArray(2)
    val readyIdle = IntArray(2)
    val targets = IdentityHashMap<Npc, Int>()
    private val tickTargets = IdentityHashMap<Npc, Int>()
    private val attackedThisTick = BooleanArray(2)
    var doubleUps = 0
    var positiveRolls = 0
    private val barrageId =
        (1..255).first { deps.spells.getAutocastSpell(it)?.obj?.id == objs.spell_ice_barrage.id }

    init {
        scope.eventBus.subscribeUnbound(PlayerAttackTarget::class.java) {
            val role =
                when (player) {
                    rangerPlayer -> 0
                    magePlayer -> 1
                    else -> return@subscribeUnbound
                }
            val bit = 1 shl role
            val before = tickTargets[target] ?: 0
            if (before != 0 && before and bit == 0) doubleUps++
            tickTargets[target] = before or bit
            targets[target] = (targets[target] ?: 0) or bit
            attacks[role]++
            attackedThisTick[role] = true
            if (role == 0) {
                rangedPending
                    ?.takeIf { rangedCrabs[target] === it.target }
                    ?.let { ranger.attacked(it, tick + 1) }
                magicCrabs[target]?.let {
                    it.claimedUntil = tick + if (it.style == MageStrategy.Style.MELEE) 3 else 5
                }
            } else {
                magicPending
                    ?.takeIf { magicCrabs[target] === it.target }
                    ?.let { mage.attacked(it, tick + 1, tick) }
                rangedCrabs[target]?.let {
                    it.claimedUntil = tick + if (it.style == RangerStrategy.Style.MELEE) 3 else 5
                }
            }
        }
        scope.eventBus.subscribeUnbound(PlayerDamageRoll::class.java) {
            if ((source === rangerPlayer || source === magePlayer) && damage > 0) positiveRolls++
        }
    }

    fun step(at: Int) =
        with(scope) {
            tick = at
            tickTargets.clear()
            attackedThisTick.fill(false)
            val live = deps.npcs.filter { it.visType.id in 8342..8353 }
            rangedCrabs.keys.retainAll(live.toSet())
            magicCrabs.keys.retainAll(live.toSet())
            val wave = rangerPlayer.vars[tob_varbits.progress_value].coerceIn(0, 31)
            if (wave > rangedRoom.wave) {
                rangedRoom.spawns[wave] = tick
                magicRoom.spawns[wave] = tick
            }
            rangedRoom.wave = wave
            magicRoom.wave = wave
            rangedRoom.player = tile(rangerPlayer.coords)
            magicRoom.player = tile(magePlayer.coords)
            rangedRoom.specialEnergy = rangerPlayer.vars[varps.sa_energy]
            magicRoom.specialEnergy = magePlayer.vars[varps.sa_energy]
            rangedRoom.obstructed.clear()
            magicRoom.obstructed.clear()
            for (npc in live) {
                val point = tile(npc.coords)
                val id = npc.visType.id - 8342
                val lane =
                    when {
                        point.x == 3281 -> 0
                        point.x in 3309..3310 -> 2
                        point.y == 4233 -> 1
                        else -> 3
                    }
                val slot =
                    if (lane == 1) {
                        if (point.x == 3295) 'W' else 'E'
                    } else if (lane != 3) {
                        if (point.y == 4249) 'N' else 'S'
                    } else 0.toChar()
                if (!rangedCrabs.containsKey(npc)) {
                    val key = ++serial
                    rangedCrabs[npc] =
                        RangerStrategy.Crab(
                            key,
                            npc.slotId,
                            tick,
                            wave,
                            RangerStrategy.Lane.values()[lane],
                            slot,
                            id % 6 >= 3,
                            RangerStrategy.Style.values()[id % 3],
                            point,
                        )
                    magicCrabs[npc] =
                        MageStrategy.Crab(
                            key,
                            npc.slotId,
                            tick,
                            wave,
                            MageStrategy.Lane.values()[lane],
                            slot,
                            id % 6 >= 3,
                            MageStrategy.Style.values()[id % 3],
                            point,
                        )
                }
                rangedCrabs.getValue(npc).also {
                    it.point = point
                    it.style = RangerStrategy.Style.values()[id % 3]
                    it.hp = npc.hitpoints
                    it.dying = npc.hitpoints <= 0 || queues.death in npc.queueList
                    if (
                        !deps.rays.hasLineOfSight(
                            rangerPlayer.coords,
                            npc.coords,
                            destWidth = npc.size,
                            destLength = npc.size,
                        )
                    )
                        rangedRoom.obstructed.add(it.key)
                }
                magicCrabs.getValue(npc).also {
                    it.point = point
                    it.style = MageStrategy.Style.values()[id % 3]
                    it.hp = npc.hitpoints
                    it.fighting = id >= 6
                    it.dying = npc.hitpoints <= 0 || queues.death in npc.queueList
                    if (
                        !deps.rays.hasLineOfSight(
                            magePlayer.coords,
                            npc.coords,
                            destWidth = npc.size,
                            destLength = npc.size,
                        )
                    )
                        magicRoom.obstructed.add(it.key)
                }
            }
            // Identity maps must not make equal-score decisions depend on JVM identity hashes.
            rangedRoom.crabs.clear()
            rangedRoom.crabs.addAll(rangedCrabs.values.sortedBy { it.key })
            magicRoom.crabs.clear()
            magicRoom.crabs.addAll(magicCrabs.values.sortedBy { it.key })
            val ready = booleanArrayOf(tick >= rangedRoom.ready, tick >= magicRoom.ready)
            val r = ranger.decide(rangedRoom, tick)
            val m = mage.decide(magicRoom, tick)
            if (r.target != null && ready[0]) offensiveStyle[0] = r.weapon.style.ordinal
            if (m.target != null && ready[1]) offensiveStyle[1] = m.weapon.style.ordinal
            support(rangerPlayer, 0, offensiveStyle[0])
            support(magePlayer, 1, offensiveStyle[1])
            rangerPlayer.clearInteractionRoute()
            magePlayer.clearInteractionRoute()
            equip(rangerPlayer, 0, r.weapon.itemId, r.weapon.stance, r.weapon.style.ordinal, false)
            equip(
                magePlayer,
                1,
                m.weapon.itemId,
                m.weapon.stance,
                m.weapon.style.ordinal,
                m.weapon == MageStrategy.Weapon.BARRAGE,
            )
            rangedPending = null
            magicPending = null
            val rn = rangedCrabs.entries.firstOrNull { it.value === (r.target ?: r.approach) }?.key
            val mn = magicCrabs.entries.firstOrNull { it.value === (m.target ?: m.approach) }?.key
            if (r.target != null && ready[0] && rn != null) {
                rangerPlayer.setVarp(varps.sa_attack, if (r.special) 1 else 0)
                rangerPlayer.opNpc2(rn)
                rangedPending = r
            } else if (rn != null && r.approach != null)
                rangerPlayer.moveGameClick(approach(rangerPlayer, rn, r.weapon.range, r.tile))
            else rangerPlayer.moveGameClick(coords(r.tile))
            if (m.target != null && ready[1] && mn != null) {
                magePlayer.setVarp(varps.sa_attack, if (m.special) 1 else 0)
                magePlayer.opNpc2(mn)
                magicPending = m
            } else if (mn != null && m.approach != null)
                magePlayer.moveGameClick(approach(magePlayer, mn, m.weapon.range, m.tile))
            else magePlayer.moveGameClick(coords(m.tile))
            advance()
            if (attackedThisTick[0]) rangedRoom.ready = tick + r.weapon.cooldown
            if (attackedThisTick[1]) magicRoom.ready = tick + m.weapon.cooldown
            for (role in 0..1) if (!attackedThisTick[role]) {
                idle[role]++
                if (ready[role]) readyIdle[role]++
            }
        }

    private fun support(player: Player, role: Int, style: Int) =
        with(scope) {
            if (PrayerPolicy.restore(player.prayerLvl, 99, 25, tick, nextDrink[role])) {
                val slot =
                    listOf(143, 141, 139, 2434).firstNotNullOfOrNull { id ->
                        player.inv.indices.firstOrNull { player.inv[it]?.id == id }
                    }
                if (slot != null) {
                    player.opHeld(slot)
                    nextDrink[role] = tick + 3
                }
            }
            val offensive = listOf(varbits.piety, varbits.rigour, varbits.augury)
            val protective =
                listOf(
                    varbits.protect_from_melee,
                    varbits.protect_from_missiles,
                    varbits.protect_from_magic,
                )
            var current = protective.indexOfFirst { player.vars[it] == 1 }
            val bigs = IntArray(3)
            val smalls = IntArray(3)
            for ((npc, crab) in rangedCrabs) {
                if (
                    npc.visType.id !in 8348..8353 ||
                        crab.dying ||
                        !npc.faceEntity.isPlayer ||
                        npc.faceEntity.playerSlot != player.slotId
                )
                    continue
                val distance = crab.distance(tile(player.coords))
                val kind = crab.style.ordinal
                if (
                    NylocasFlickers.passiveMelee(
                        crab.wave,
                        crab.lane.ordinal,
                        crab.slot,
                        kind,
                        tick - crab.born,
                    )
                )
                    continue
                if (kind == 0) {
                    if (!deps.bounds.touches(npc.avatar, player.avatar)) continue
                } else if (distance !in 1..2) continue
                if (
                    !deps.rays.hasLineOfSight(
                        npc.coords,
                        player.coords,
                        srcWidth = npc.size,
                        srcLength = npc.size,
                    )
                )
                    continue
                (if (crab.big) bigs else smalls)[kind]++
            }
            current = PrayerPolicy.protection(bigs, smalls, current)
            fun activate(prayer: VarBitType) {
                if (player.vars[prayer] == 0 && player.prayerLvl > 0) {
                    val component =
                        deps.prayers.prayerComponents.entries
                            .single { it.value.enabled.id == prayer.id }
                            .key
                    player.ifButton(component)
                }
            }
            if (current >= 0) activate(protective[current])
            activate(offensive[style])
        }

    private fun approach(player: Player, npc: Npc, range: Int, stage: Tile): CoordGrid {
        val start = player.coords
        val queue = ArrayDeque<Pair<CoordGrid, Int>>()
        val visited = mutableSetOf(start)
        queue.add(start to 0)
        var destination = coords(stage)
        var bestCost = Int.MAX_VALUE
        while (queue.isNotEmpty()) {
            val (point, steps) = queue.removeFirst()
            if (steps * 100 > bestCost) break
            val normalized = tile(point)
            val target = tile(npc.coords)
            val distance =
                maxOf(
                    target.x - normalized.x,
                    normalized.x - target.x - npc.size + 1,
                    target.y - normalized.y,
                    normalized.y - target.y - npc.size + 1,
                )
            if (
                distance in 1..range &&
                    deps.rays.hasLineOfSight(
                        point,
                        npc.coords,
                        destWidth = npc.size,
                        destLength = npc.size,
                    )
            ) {
                val cost = steps * 100 + normalized.distanceTo(stage)
                if (cost < bestCost) {
                    bestCost = cost
                    destination = point
                }
            }
            for (dx in -1..1) for (dy in -1..1) {
                if (dx == 0 && dy == 0) continue
                val next = point.translate(dx, dy)
                val source = tile(next)
                if (source.x !in 3280..3311 || source.y !in 4232..4254 || next in visited) continue
                if (deps.steps.validated(point, next) != next) continue
                visited += next
                queue.add(next to steps + 1)
            }
        }
        return destination
    }

    private fun tile(coords: CoordGrid): Tile =
        deps.regions.normalizeCoords(coords).let { Tile(it.x, it.z, it.level) }

    private fun coords(tile: Tile): CoordGrid = region.normal[CoordGrid(tile.x, tile.y, tile.plane)]

    private fun equip(
        player: Player,
        role: Int,
        id: Int,
        stance: Int,
        style: Int,
        barrage: Boolean,
    ) =
        with(scope) {
            val old = player.worn[Wearpos.RightHand.slot]
            if (old != null) gear[role][old.id] = old
            val weapon =
                gear[role].getOrPut(id) {
                    when (id) {
                        12926 -> Blowpipe.withAmmo(InvObj(id, 1), 16000, objs.dragon_dart, 16000)
                        31113,
                        27275,
                        22325 -> InvObj(id, 1, vars = 10000)
                        11959,
                        11230 -> InvObj(id, 10000)
                        else -> InvObj(id, 1)
                    }
                }
            fun wear(slot: Wearpos, obj: InvObj) {
                val previous = player.worn[slot.slot]
                player.worn[slot.slot] = obj
                if (previous?.id != obj.id) {
                    eventBus.publish(HeldEquipEvents.WearposChange(player, slot, objTypes[obj]))
                }
            }
            wear(Wearpos.RightHand, weapon)
            player.worn[Wearpos.Quiver.slot] = InvObj(objs.dragon_arrow, 10000)
            // Fixed, documented style sets make trials comparable without a saved client profile.
            val ids =
                when (style) {
                    0 -> intArrayOf(26382, 21295, 19553, 26384, 26386, 22981, 13239, 28307)
                    1 -> intArrayOf(27226, 22109, 19547, 27229, 27232, 26235, 13237, 28310)
                    else -> intArrayOf(21018, 21795, 12002, 21021, 21024, 19544, 13235, 28313)
                }
            val slots =
                listOf(
                    Wearpos.Hat,
                    Wearpos.Back,
                    Wearpos.Front,
                    Wearpos.Torso,
                    Wearpos.Legs,
                    Wearpos.Hands,
                    Wearpos.Feet,
                    Wearpos.Ring,
                )
            for ((slot, item) in slots.zip(ids.toList())) wear(slot, InvObj(item, 1))
            player.setVarp(varps.com_mode, stance)
            player.setVarBit(varbits.autocast_set, if (barrage) 1 else 0)
            if (barrage) player.setVarBit(varbits.autocast_spell, barrageId)
        }
}
