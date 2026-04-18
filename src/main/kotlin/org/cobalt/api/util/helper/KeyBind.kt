package org.cobalt.api.util.helper

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.Minecraft

class KeyBind(
  var keyCode: Int = -1,
) {

  private var wasPressed = false

  fun isPressed(): Boolean {
    if (keyCode == -1) return false
    val mc = Minecraft.getInstance()

    val isPressed = mc.screen == null
      && InputConstants.isKeyDown(mc.window, keyCode)

    return (isPressed && !wasPressed).also {
      wasPressed = isPressed
    }
  }

}
