package org.cobalt.internal.ui.components

import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import org.cobalt.api.ui.theme.ThemeManager
import org.cobalt.api.util.ui.NVGRenderer
import org.cobalt.internal.ui.UIComponent

internal class UITopbar(
  private var title: String,
) : UIComponent(
  x = 0F,
  y = 0F,
  width = 890F,
  height = 70F,
) {

  private val searchBar = UISearchBar()
  private var onSearchChanged: ((String) -> Unit)? = null
  private var lastSearchText = ""

  override fun render() {
    NVGRenderer.text(title, x + 40F, y + (height / 2) - 10F, 20F, ThemeManager.currentTheme.text)
    NVGRenderer.line(x, y + height, x + width, y + height, 1F, ThemeManager.currentTheme.moduleDivider)

    searchBar
      .updateBounds(x + width - 320F, y + 15F)
      .render()

    val currentSearchText = searchBar.getSearchText()
    if (currentSearchText != lastSearchText) {
      lastSearchText = currentSearchText
      onSearchChanged?.invoke(currentSearchText)
    }
  }

  override fun mouseClicked(button: Int): Boolean {
    return searchBar.mouseClicked(button)
  }

  override fun mouseReleased(button: Int): Boolean {
    return searchBar.mouseReleased(button)
  }

  override fun mouseDragged(button: Int, offsetX: Double, offsetY: Double): Boolean {
    return searchBar.mouseDragged(button, offsetX, offsetY)
  }

  override fun charTyped(input: CharacterEvent): Boolean {
    return searchBar.charTyped(input)
  }

  override fun keyPressed(input: KeyEvent): Boolean {
    return searchBar.keyPressed(input)
  }

  fun getSearchText(): String = searchBar.getSearchText()
  fun clearSearch() = searchBar.clearSearch()
  fun searchChanged(callback: (String) -> Unit) {
    onSearchChanged = callback
  }

}
