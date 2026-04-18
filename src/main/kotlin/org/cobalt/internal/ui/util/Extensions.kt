package org.cobalt.internal.ui.util

import net.minecraft.client.Minecraft

inline val mouseX: Double
  get() = Minecraft.getInstance().mouseHandler.xpos()

inline val mouseY: Double
  get() = Minecraft.getInstance().mouseHandler.ypos()

fun isHoveringOver(x: Float, y: Float, width: Float, height: Float): Boolean =
  mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height
