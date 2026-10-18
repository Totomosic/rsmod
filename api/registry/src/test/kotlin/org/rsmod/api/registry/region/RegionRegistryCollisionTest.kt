package org.rsmod.api.registry.region

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.rsmod.api.registry.npc.NpcRegistry
import org.rsmod.api.testing.factory.collisionFactory
import org.rsmod.api.testing.factory.locRegistryFactory
import org.rsmod.api.testing.factory.locTypeListFactory
import org.rsmod.api.testing.factory.npcFactory
import org.rsmod.api.testing.factory.npcTypeFactory
import org.rsmod.api.testing.factory.regionRegistryFactory
import org.rsmod.events.EventBus
import org.rsmod.game.entity.NpcList
import org.rsmod.game.map.LocZoneStorage
import org.rsmod.game.map.collision.get
import org.rsmod.game.movement.BlockWalk
import org.rsmod.game.region.RegionListSmall
import org.rsmod.game.region.zone.RegionZoneCopy
import org.rsmod.game.region.zone.RegionZoneCopyMap
import org.rsmod.map.zone.ZoneKey
import org.rsmod.routefinder.LineValidator
import org.rsmod.routefinder.StepValidator
import org.rsmod.routefinder.flag.CollisionFlag

class RegionRegistryCollisionTest {
    @Test
    fun `follower leaving a reclaimed instance does not block tiles in its replacement`() {
        val collision = collisionFactory.borrowSharedMap()
        val locZones = LocZoneStorage()
        val locTypes = locTypeListFactory.createDefault()
        val normalLocs = locRegistryFactory.createNormal(collision, locZones, locTypes = locTypes)
        val npcs = NpcRegistry(NpcList(), collision, EventBus())
        val smallRegions = RegionListSmall()
        val regions =
            regionRegistryFactory.create(
                collision,
                normalLocs,
                locTypes,
                locZones,
                smallRegions = smallRegions,
                npcRegistry = npcs,
            )
        val source = ZoneKey(400, 500, 0)
        val sourceBase = source.toCoords()
        collision.allocateIfAbsent(sourceBase.x, sourceBase.z, sourceBase.level)
        val copy = RegionZoneCopy(source, rotation = 0, flag = null)

        val first = regions.registerSmall()
        check(first.isSuccess())
        // Advance the allocator so the next registration reuses the reclaimed slot.
        repeat(smallRegions.capacity - 1) { check(regions.registerSmall().isSuccess()) }
        regions.build(
            first.region,
            RegionZoneCopyMap().apply { this[first.region.southWestZone] = copy },
        )
        val tile = first.region.southWest.translate(2, 2)
        val follower =
            npcFactory.create(
                npcTypeFactory.create {
                    size = 1
                    this.follower = true
                    blockWalk = BlockWalk.Npc
                },
                tile,
            )
        npcs.add(follower)

        // Reclamation deliberately leaves followers alive until their next processing turn.
        check(regions.unregister(first.region).isSuccess())
        check(follower.isSlotAssigned)
        check(!collision.isZoneAllocated(tile.x, tile.z, tile.level))
        follower.telejump(collision, sourceBase)
        npcs.change(follower, follower.lastProcessedZone, source)
        follower.lastProcessedZone = source

        val replacement = regions.registerSmall()
        check(replacement.isSuccess())
        assertEquals(first.region.southWest, replacement.region.southWest)
        regions.build(
            replacement.region,
            RegionZoneCopyMap().apply { this[replacement.region.southWestZone] = copy },
        )

        val steps = StepValidator(collision)
        val lines = LineValidator(collision)
        assertTrue(
            steps.canTravel(
                tile.level,
                tile.x - 1,
                tile.z,
                1,
                0,
                extraFlag = CollisionFlag.BLOCK_PLAYERS,
            )
        )
        assertTrue(
            steps.canTravel(
                tile.level,
                tile.x - 1,
                tile.z,
                1,
                0,
                extraFlag = CollisionFlag.BLOCK_NPCS,
            )
        )
        assertTrue(lines.hasLineOfSight(tile.level, tile.x - 1, tile.z, tile.x + 1, tile.z))
        assertEquals(0, collision[tile])
        assertEquals(0, collision[tile.translate(1, 0)])
        assertFalse(follower.coords == tile)
    }
}
