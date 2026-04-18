package org.cobalt.api.util

import kotlin.math.roundToInt
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor

object ChatUtils {

  private val mc: Minecraft =
    Minecraft.getInstance()

  // used for sendDebug to prevent resending same message multiple times in a row
  private var lastMessage: String = ""

  @JvmStatic
  fun sendDebug(message: String) {
    if (mc.player == null || mc.level == null) return
    if (message == lastMessage) return

    mc.gui.chat.addMessage(
      Component.empty().append(debugPrefix)
        .append(Component.literal("${ChatFormatting.RESET}$message"))
    )

    lastMessage = message
  }

  @JvmStatic
  fun sendMessage(message: String) {
    if (mc.player == null || mc.level == null) return

    mc.gui.chat.addMessage(
      Component.empty().append(prefix)
        .append(Component.literal("${ChatFormatting.RESET}$message"))
    )
  }

  @JvmStatic
  fun buildGradient(text: String, startRgb: Int, endRgb: Int): MutableComponent {
    val result = Component.empty()
    val length = text.length

    if (length <= 1) {
      return Component.literal(text).setStyle(Style.EMPTY.withColor(TextColor.fromRgb(startRgb)))
    }

    val sr = (startRgb shr 16) and 0xFF
    val sg = (startRgb shr 8) and 0xFF
    val sb = startRgb and 0xFF

    val er = (endRgb shr 16) and 0xFF
    val eg = (endRgb shr 8) and 0xFF
    val eb = endRgb and 0xFF

    for (i in text.indices) {
      val ratio = i.toDouble() / (length - 1)

      val r = (sr + ratio * (er - sr)).roundToInt()
      val g = (sg + ratio * (eg - sg)).roundToInt()
      val b = (sb + ratio * (eb - sb)).roundToInt()

      val rgb = (r shl 16) or (g shl 8) or b

      val charText = Component.literal(text[i].toString())
        .setStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb)))

      result.append(charText)
    }

    return result
  }

  private val prefix = Component.literal("${ChatFormatting.DARK_GRAY}[")
    .append(buildGradient("Cobalt", 0x4CADD0, 0xB2F9FF))
    .append(Component.literal("${ChatFormatting.DARK_GRAY}] "))

  private val debugPrefix = Component.literal("${ChatFormatting.DARK_GRAY}[")
    .append(buildGradient("Cobalt Debug", 0x369876, 0x71FF9E))
    .append(Component.literal("${ChatFormatting.DARK_GRAY}] "))

}
