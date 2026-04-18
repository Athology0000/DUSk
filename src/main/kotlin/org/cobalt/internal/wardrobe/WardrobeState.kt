package org.cobalt.internal.wardrobe

import net.minecraft.world.item.ItemStack

data class WardrobeSet(
    val id: Int,
    val vanillaPage: Int,    // 1–3
    val inventorySlot: Int,  // slot index in container to click to equip (36–44)
    val armor: List<ItemStack?>,  // [helmet, chestplate, leggings, boots], null = empty
    val locked: Boolean,
) {
    fun isEmpty() = armor.all { it == null || it.isEmpty }
}

object WardrobeState {
    var isOpen = false
    var currentVanillaPage: Int? = null
    var equippedSlotId: Int? = null

    // 27 sets, pre-populated with defaults. Mutated in-place as vanilla pages are parsed.
    val sets: MutableList<WardrobeSet> = MutableList(27) { i ->
        WardrobeSet(
            id           = i + 1,
            vanillaPage  = i / 9 + 1,
            inventorySlot = 36 + i % 9,
            armor        = listOf(null, null, null, null),
            locked       = false,
        )
    }

    val favorites: MutableSet<Int> = mutableSetOf()

    /**
     * Resets wardrobe open/parse state when the wardrobe screen is closed.
     * [favorites] is intentionally preserved — it persists until explicitly cleared.
     */
    fun reset() {
        isOpen = false
        currentVanillaPage = null
        equippedSlotId = null
        for (i in sets.indices) {
            sets[i] = sets[i].copy(armor = listOf(null, null, null, null), locked = false)
        }
    }

    /**
     * Update the [sets] entries for the given vanilla [page] (1–3) with parsed armor,
     * equipped slot, and locked slots from a ContainerSetContent packet.
     */
    fun updatePage(
        page: Int,
        armorBySetId: Map<Int, List<ItemStack?>>,
        equippedId: Int?,
        lockedIds: Set<Int>,
    ) {
        if (equippedId != null) equippedSlotId = equippedId
        val startId = (page - 1) * 9 + 1
        for (id in startId until startId + 9) {
            val idx = id - 1
            if (idx in sets.indices) {
                sets[idx] = sets[idx].copy(
                    armor  = armorBySetId[id] ?: listOf(null, null, null, null),
                    locked = id in lockedIds,
                )
            }
        }
    }
}

private val REFORGE_LABELS = mapOf(
    "Mossy"  to "Farming",
    "Mantid" to "Pest",
    "Jaded"  to "Mining",
)

fun WardrobeSet.displayName(): String {
    val firstWord = armor
        .filterNotNull()
        .firstOrNull { !it.isEmpty }
        ?.hoverName
        ?.string
        ?.replace(Regex("\u00A7[0-9a-fk-or]"), "")
        ?.trim()
        ?.split(" ")
        ?.firstOrNull()
    return REFORGE_LABELS[firstWord] ?: "Set $id"
}
