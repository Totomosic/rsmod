package org.rsmod.content.other.special.weapons.configs

import org.rsmod.api.config.refs.objs
import org.rsmod.api.type.editors.enums.EnumEditor
import org.rsmod.api.type.refs.enums.EnumReferences
import org.rsmod.game.type.obj.ObjType

internal object AscensionCrossbowEnums : EnumReferences() {
    val energy_requirements = find<ObjType, Int>("sa_energy_requirements")
}

/** The native combat tab and special orb both resolve energy through this transmitted enum. */
internal object AscensionCrossbowEnumEdits : EnumEditor() {
    init {
        edit(AscensionCrossbowEnums.energy_requirements) {
            transmit = true
            this[objs.blazing_blowpipe] = 0
        }
    }
}
