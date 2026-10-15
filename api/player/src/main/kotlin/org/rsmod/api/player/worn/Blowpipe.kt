package org.rsmod.api.player.worn

import org.rsmod.api.config.refs.objs
import org.rsmod.api.config.refs.varobjs
import org.rsmod.game.inv.InvObj
import org.rsmod.game.inv.isAnyType
import org.rsmod.game.type.obj.ObjType
import org.rsmod.utils.bits.getBits
import org.rsmod.utils.bits.withBits

/** Shared access to a blowpipe's stored ammunition, including its contribution to worn bonuses. */
public object Blowpipe {
    public const val CAPACITY: Int = 16_383

    public val dartTypes: List<ObjType> =
        listOf(
            objs.bronze_dart,
            objs.iron_dart,
            objs.steel_dart,
            objs.black_dart,
            objs.mithril_dart,
            objs.adamant_dart,
            objs.rune_dart,
            objs.dragon_dart,
            objs.amethyst_dart,
        )

    public fun isLoaded(obj: InvObj?): Boolean =
        obj.isAnyType(objs.toxic_blowpipe, objs.blazing_blowpipe)

    public fun scales(obj: InvObj?): Int =
        obj?.vars?.getBits(varobjs.snakeboss_blowpipe_flakes.bits) ?: 0

    public fun darts(obj: InvObj?): Int =
        obj?.vars?.getBits(varobjs.snakeboss_blowpipe_dartcount.bits) ?: 0

    public fun dartType(obj: InvObj?): ObjType? {
        if (obj == null || darts(obj) == 0) return null
        val index = obj.vars.getBits(varobjs.snakeboss_blowpipe_darrtype.bits) - 1
        return dartTypes.getOrNull(index)
    }

    /**
     * Preserves the cosmetic variant and uses the empty item only when both resources are empty.
     */
    public fun withAmmo(obj: InvObj, scales: Int, dartType: ObjType?, darts: Int): InvObj {
        require(
            obj.isAnyType(
                objs.toxic_blowpipe,
                objs.toxic_blowpipe_uncharged,
                objs.blazing_blowpipe,
                objs.blazing_blowpipe_uncharged,
            )
        )
        require(scales in 0..CAPACITY && darts in 0..CAPACITY)
        val dartIndex = dartTypes.indexOfFirst { it.id == dartType?.id }
        require(darts == 0 || dartIndex >= 0) { "Unsupported blowpipe dart: $dartType" }
        val ornament = obj.isAnyType(objs.blazing_blowpipe, objs.blazing_blowpipe_uncharged)
        val empty = scales == 0 && darts == 0
        val type =
            when {
                ornament && empty -> objs.blazing_blowpipe_uncharged
                ornament -> objs.blazing_blowpipe
                empty -> objs.toxic_blowpipe_uncharged
                else -> objs.toxic_blowpipe
            }
        val vars =
            obj.vars
                .withBits(varobjs.snakeboss_blowpipe_flakes.bits, scales)
                .withBits(varobjs.snakeboss_blowpipe_dartcount.bits, darts)
                .withBits(
                    varobjs.snakeboss_blowpipe_darrtype.bits,
                    if (darts == 0) 0 else dartIndex + 1,
                )
        return InvObj(type, vars = vars)
    }
}
