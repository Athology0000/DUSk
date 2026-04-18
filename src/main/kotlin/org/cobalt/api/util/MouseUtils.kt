package org.cobalt.api.util

import net.minecraft.client.Minecraft
import org.cobalt.mixin.client.MinecraftAccessor

object MouseUtils {

  private val mc: Minecraft =
    Minecraft.getInstance()

  private var isMouseUngrabbed: Boolean = false

  @JvmStatic
  fun ungrabMouse() {
    isMouseUngrabbed = true
  }

  @JvmStatic
  fun grabMouse() {
    isMouseUngrabbed = false
  }

  @JvmStatic
  fun isMouseUngrabbed(): Boolean {
    return isMouseUngrabbed
  }

  @JvmStatic
  fun leftClick() {
    (mc as MinecraftAccessor).leftClick()
  }

  @JvmStatic
  fun rightClick() {
    (mc as MinecraftAccessor).rightClick()
  }

}
