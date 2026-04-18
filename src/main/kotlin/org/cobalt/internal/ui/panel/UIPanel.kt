package org.cobalt.internal.ui.panel

import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import org.cobalt.internal.ui.UIComponent

internal abstract class UIPanel(
  x: Float,
  y: Float,
  width: Float,
  height: Float,
) : UIComponent(x, y, width, height) {

  val components = mutableListOf<UIComponent>()

  override fun mouseClicked(button: Int) =
    components.any { it.mouseClicked(button) }

  override fun mouseReleased(button: Int) =
    components.any { it.mouseReleased(button) }

  override fun mouseDragged(button: Int, offsetX: Double, offsetY: Double) =
    components.any { it.mouseDragged(button, offsetX, offsetY) }

  override fun charTyped(input: CharacterEvent) =
    components.any { it.charTyped(input) }

  override fun keyPressed(input: KeyEvent) =
    components.any { it.keyPressed(input) }

}
