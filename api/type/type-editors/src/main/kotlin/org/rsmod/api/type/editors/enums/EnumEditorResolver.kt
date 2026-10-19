package org.rsmod.api.type.editors.enums

import jakarta.inject.Inject
import org.rsmod.api.type.editors.TypeEditor
import org.rsmod.api.type.editors.resolver.TypeEditorResolver
import org.rsmod.api.type.editors.resolver.TypeEditorResult
import org.rsmod.api.type.editors.resolver.TypeEditorResult.CachePackRequired
import org.rsmod.api.type.editors.resolver.TypeEditorResult.CacheTypeDoesNotExit
import org.rsmod.api.type.editors.resolver.TypeEditorResult.FullSuccess
import org.rsmod.api.type.editors.resolver.TypeEditorResult.NameNotFound
import org.rsmod.api.type.editors.resolver.err
import org.rsmod.api.type.editors.resolver.ok
import org.rsmod.api.type.editors.resolver.update
import org.rsmod.api.type.symbols.name.NameMapping
import org.rsmod.game.type.TypeResolver
import org.rsmod.game.type.enums.EnumTypeBuilder
import org.rsmod.game.type.enums.EnumTypeList
import org.rsmod.game.type.enums.UnpackedEnumType

public class EnumEditorResolver
@Inject
constructor(private val types: EnumTypeList, private val nameMapping: NameMapping) :
    TypeEditorResolver<UnpackedEnumType<*, *>> {
    override fun resolve(editors: TypeEditor<UnpackedEnumType<*, *>>): List<TypeEditorResult> =
        editors.cache.map { it.resolve() }

    @Suppress("UNCHECKED_CAST")
    private fun UnpackedEnumType<*, *>.resolve(): TypeEditorResult {
        val internalId = nameMapping.enums[internalName] ?: return err(NameNotFound(internalName))
        TypeResolver[this] = internalId
        val base = types[internalId] ?: return err(CacheTypeDoesNotExit)
        val overrides = this as UnpackedEnumType<Any, Any>
        val merged =
            EnumTypeBuilder.merge(this, base)
                .copy(primitiveMap = base.primitiveMap + primitiveMap, typedMap = base + overrides)
        TypeResolver[merged] = internalId
        return if (merged != base) merged.update(CachePackRequired) else merged.ok(FullSuccess)
    }
}
