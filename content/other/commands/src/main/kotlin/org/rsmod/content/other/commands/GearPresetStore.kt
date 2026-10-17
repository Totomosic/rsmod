package org.rsmod.content.other.commands

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID
import org.rsmod.api.parsers.json.Json

data class GearPresetItem(val id: Int, val count: Int, val vars: Int)

data class GearPreset(
    val id: String,
    val name: String,
    val equipment: List<GearPresetItem?>,
    val inventory: List<GearPresetItem?>,
)

data class GearPresetFile(val version: Int = 1, val setups: List<GearPreset> = emptyList())

/** Character-owned snapshots, including empty slots and per-item charge variables. */
@Singleton
class GearPresetStore constructor(private val mapper: ObjectMapper, private val root: Path) {
    @Inject constructor(@Json mapper: ObjectMapper) : this(mapper, Path.of(".data/gear-presets"))

    fun list(characterId: Long): List<GearPreset> {
        val file = root.resolve("$characterId.json")
        if (!Files.exists(file)) return emptyList()
        val data = mapper.readValue(file.toFile(), GearPresetFile::class.java)
        check(data.version == 1) { "Unsupported gear preset version: ${data.version}" }
        return data.setups.sortedBy { it.name.lowercase() }
    }

    fun save(
        characterId: Long,
        name: String,
        equipment: List<GearPresetItem?>,
        inventory: List<GearPresetItem?>,
    ): GearPreset {
        require(validName(name)) {
            "Use 1–24 letters, numbers, spaces, apostrophes, dots, parentheses, underscores or hyphens."
        }
        val setups = list(characterId).toMutableList()
        val index = setups.indexOfFirst { it.name.equals(name, ignoreCase = true) }
        require(index >= 0 || setups.size < 100) { "You can save up to 100 setups." }
        val preset =
            GearPreset(
                if (index >= 0) setups[index].id else UUID.randomUUID().toString(),
                name,
                equipment.toList(),
                inventory.toList(),
            )
        if (index >= 0) setups[index] = preset else setups += preset
        Files.createDirectories(root)
        val temporary = Files.createTempFile(root, "$characterId-", ".tmp")
        try {
            mapper.writeValue(temporary.toFile(), GearPresetFile(setups = setups))
            val destination = root.resolve("$characterId.json")
            try {
                Files.move(temporary, destination, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, destination, REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
        return preset
    }

    companion object {
        fun validName(name: String): Boolean =
            name.matches(Regex("[A-Za-z0-9 ._()'-]{1,24}")) && name.isNotBlank()
    }
}
