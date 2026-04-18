package org.cobalt.internal.wardrobe

import com.mojang.authlib.GameProfile
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack
import java.util.UUID

/**
 * A fake client player used exclusively for rendering armor previews in the wardrobe overlay.
 * Never added to the world — only used as an argument to [net.minecraft.client.gui.screens.inventory.InventoryScreen.renderEntityInInventory].
 */
class WardrobeFakePlayer(level: ClientLevel) :
    AbstractClientPlayer(level, GameProfile(UUID.randomUUID(), "WardrobePreview")) {

    fun applyArmor(armor: List<ItemStack?>) {
        setItemSlot(EquipmentSlot.HEAD,   armor.getOrNull(0)?.takeIf { !it.isEmpty } ?: ItemStack.EMPTY)
        setItemSlot(EquipmentSlot.CHEST,  armor.getOrNull(1)?.takeIf { !it.isEmpty } ?: ItemStack.EMPTY)
        setItemSlot(EquipmentSlot.LEGS,   armor.getOrNull(2)?.takeIf { !it.isEmpty } ?: ItemStack.EMPTY)
        setItemSlot(EquipmentSlot.FEET,   armor.getOrNull(3)?.takeIf { !it.isEmpty } ?: ItemStack.EMPTY)
    }
}

/**
 * Cache of [WardrobeFakePlayer] instances keyed by wardrobe set ID.
 * Invalidates an entry when the armor fingerprint (item registry names) changes.
 */
object WardrobeFakePlayerCache {

    private data class Entry(val player: WardrobeFakePlayer, val fingerprint: String)
    private val cache = mutableMapOf<Int, Entry>()

    /**
     * Return a [WardrobeFakePlayer] for the given set, re-creating it only if the armor changed.
     */
    fun get(setId: Int, armor: List<ItemStack?>, level: ClientLevel): WardrobeFakePlayer {
        val fingerprint = armor.joinToString("|") { it?.item?.toString() ?: "null" }
        val entry = cache[setId]
        if (entry != null && entry.fingerprint == fingerprint) return entry.player
        val player = WardrobeFakePlayer(level).also { it.applyArmor(armor) }
        cache[setId] = Entry(player, fingerprint)
        return player
    }

    /** Clear all cached players — call when the wardrobe is closed. */
    fun clear() = cache.clear()
}
