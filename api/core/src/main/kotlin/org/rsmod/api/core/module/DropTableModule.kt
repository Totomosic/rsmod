package org.rsmod.api.core.module

import com.google.inject.multibindings.Multibinder
import org.rsmod.api.drop.table.NpcDropTableProvider
import org.rsmod.api.drop.table.NpcDropTables
import org.rsmod.module.ExtendedModule

public object DropTableModule : ExtendedModule() {
    override fun bind() {
        Multibinder.newSetBinder(binder(), NpcDropTableProvider::class.java)
        bindInstance<NpcDropTables>()
    }
}
