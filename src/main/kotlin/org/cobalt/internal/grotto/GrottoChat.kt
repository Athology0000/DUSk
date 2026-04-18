package org.cobalt.internal.grotto

import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

object GrottoChat {

  private val mc: Minecraft = Minecraft.getInstance()

  private val prefix = Component.literal("[Grotto] ")
    .withStyle(ChatFormatting.AQUA)
  private val grottoPrefix = Component.literal("[GrottoScanner] ")
    .withStyle(ChatFormatting.LIGHT_PURPLE)
  private val autoRoutesPrefix = Component.literal("[AutoRoutes] ")
    .withStyle(ChatFormatting.DARK_AQUA)
  private val mhPrefix = Component.literal("[MiningHelper] ")
    .withStyle(ChatFormatting.AQUA)

  @JvmStatic
  fun send(message: Component) {
    if (mc.player == null || mc.level == null) return
    mc.gui.chat.addMessage(Component.empty().append(prefix).append(message))
  }

  private fun sendWithPrefix(prefix: Component, message: Component) {
    if (mc.player == null || mc.level == null) return
    mc.gui.chat.addMessage(Component.empty().append(prefix).append(message))
  }

  @JvmStatic
  fun send(message: String) {
    send(Component.literal(message))
  }

  @JvmStatic
  fun grotto(message: String) {
    sendWithPrefix(grottoPrefix, Component.literal(message))
  }

  @JvmStatic
  fun autoRoutes(message: String) {
    sendWithPrefix(autoRoutesPrefix, Component.literal(message))
  }

  @JvmStatic
  fun mh(message: String) {
    sendWithPrefix(mhPrefix, Component.literal(message))
  }

  @JvmStatic
  fun mhUsage(usage: String) {
    sendWithPrefix(
      mhPrefix,
      Component.literal("Usage: ").withStyle(ChatFormatting.RED)
        .append(Component.literal(usage).withStyle(ChatFormatting.GRAY))
    )
  }

  @JvmStatic
  fun usage(usage: String) {
    send(
      Component.literal("Usage: ").withStyle(ChatFormatting.RED)
        .append(Component.literal(usage).withStyle(ChatFormatting.GRAY))
    )
  }

}
