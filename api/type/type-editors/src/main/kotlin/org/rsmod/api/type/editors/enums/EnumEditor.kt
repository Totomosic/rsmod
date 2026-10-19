package org.rsmod.api.type.editors.enums

import org.rsmod.api.type.editors.TypeEditor
import org.rsmod.api.type.script.dsl.EnumPluginBuilder
import org.rsmod.game.type.enums.EnumType
import org.rsmod.game.type.enums.EnumTypeBuilder
import org.rsmod.game.type.enums.UnpackedEnumType

/** Edits individual enum entries, preserving entries that are not explicitly overridden. */
public abstract class EnumEditor : TypeEditor<UnpackedEnumType<*, *>>() {
    public inline fun <reified K : Any, reified V : Any> edit(
        type: EnumType<K, V>,
        noinline init: EnumPluginBuilder<K, V>.() -> Unit,
    ) {
        val edit =
            EnumPluginBuilder(K::class, V::class, type.internalNameValue)
                .apply { transmit = EnumTypeBuilder.DEFAULT_TRANSMIT }
                .apply(init)
        addEdit(edit.build(id = -1))
    }

    @PublishedApi
    internal fun addEdit(edit: UnpackedEnumType<*, *>) {
        cache += edit
    }
}
