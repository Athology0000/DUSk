package org.cobalt.api.util

import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack

fun ItemStack.getLoreLines(): List<Component> {
  val lore = this.get(DataComponents.LORE) ?: return emptyList()
  return lore.lines()
}
