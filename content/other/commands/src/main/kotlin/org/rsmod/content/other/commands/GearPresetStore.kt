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

data class GearPresetFile(
    val version: Int = 1,
    val setups: List<GearPreset> = emptyList(),
    val defaultSetupId: String? = null,
)

/** Shared snapshots, stored per creator so saving cannot overwrite another player's setup. */
@Singleton
class GearPresetStore constructor(private val mapper: ObjectMapper, private val root: Path) {
    @Inject constructor(@Json mapper: ObjectMapper) : this(mapper, Path.of(".data/gear-presets"))

    fun list(): List<GearPreset> {
        if (!Files.isDirectory(root)) return emptyList()
        return Files.list(root).use { files ->
            files
                .filter { it.fileName.toString().matches(Regex("-?[0-9]+\\.json")) }
                .flatMap { read(it).setups.stream() }
                .toList()
                .sortedBy { it.name.lowercase() }
        }
    }

    private fun read(file: Path): GearPresetFile {
        if (!Files.exists(file)) return GearPresetFile()
        val data = mapper.readValue(file.toFile(), GearPresetFile::class.java)
        check(data.version == 1) { "Unsupported gear preset version: ${data.version}" }
        return data
    }

    fun defaultSetupId(characterId: Long): String? =
        read(root.resolve("$characterId.json")).defaultSetupId

    fun setDefault(characterId: Long, setupId: String?) {
        require(setupId == null || list().any { it.id == setupId }) {
            "That setup is no longer available. Refresh the list."
        }
        val data = read(root.resolve("$characterId.json"))
        write(characterId, data.copy(defaultSetupId = setupId))
    }

    fun save(
        characterId: Long,
        characterName: String,
        name: String,
        equipment: List<GearPresetItem?>,
        inventory: List<GearPresetItem?>,
    ): GearPreset {
        require(validName(name)) {
            "Use 1–24 letters, numbers, spaces, apostrophes, dots, parentheses, underscores or hyphens."
        }
        val displayName = "[$characterName] $name"
        val data = read(root.resolve("$characterId.json"))
        val setups = data.setups.toMutableList()
        val index =
            setups.indexOfFirst {
                // Also update setups saved before creator prefixes were added.
                it.name.substringAfter("] ", it.name).equals(name, ignoreCase = true)
            }
        require(index >= 0 || setups.size < 100) { "You can save up to 100 setups." }
        val preset =
            GearPreset(
                if (index >= 0) setups[index].id else UUID.randomUUID().toString(),
                displayName,
                equipment.toList(),
                inventory.toList(),
            )
        if (index >= 0) setups[index] = preset else setups += preset
        write(characterId, data.copy(setups = setups))
        return preset
    }

    private fun write(characterId: Long, data: GearPresetFile) {
        Files.createDirectories(root)
        val temporary = Files.createTempFile(root, "$characterId-", ".tmp")
        try {
            mapper.writeValue(temporary.toFile(), data)
            val destination = root.resolve("$characterId.json")
            try {
                Files.move(temporary, destination, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, destination, REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    companion object {
        fun validName(name: String): Boolean =
            name.matches(Regex("[A-Za-z0-9 ._()'-]{1,24}")) && name.isNotBlank()
    }
}
