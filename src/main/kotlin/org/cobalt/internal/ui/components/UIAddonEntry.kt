package org.cobalt.internal.ui.components

import org.cobalt.api.addon.Addon
import org.cobalt.api.addon.AddonMetadata
import org.cobalt.api.ui.theme.ThemeManager
import org.cobalt.api.util.ui.NVGRenderer
import org.cobalt.internal.loader.AddonLoader
import org.cobalt.internal.ui.UIComponent
import org.cobalt.internal.ui.panel.panels.UIModuleList
import org.cobalt.internal.ui.screen.UIConfig
import org.cobalt.internal.ui.util.isHoveringOver

internal class UIAddonEntry(
  val metadata: AddonMetadata,
  private val addon: Addon,
) : UIComponent(
  x = 0F,
  y = 0F,
  width = 270F,
  height = 70F,
) {

  val addonIcon = AddonLoader.getAddonIcon(metadata.id) ?: boxIcon

  override fun render() {
    NVGRenderer.rect(
      x, y, width, height,
      ThemeManager.currentTheme.panel, 10F
    )

    NVGRenderer.hollowRect(
      x, y, width, height,
      1F, ThemeManager.currentTheme.controlBorder, 10F
    )

    NVGRenderer.rect(x + 10F, y + height / 2F - 25F, 50F, 50F, ThemeManager.currentTheme.inset, 5F)

    NVGRenderer.image(
      addonIcon, x + 20F, y + height / 2F - 15F, 30F, 30F,
      colorMask = if (addonIcon == boxIcon) ThemeManager.currentTheme.controlBorder else 0
    )

    NVGRenderer.text(
      metadata.name,
      x + 75F,
      y + (height - 29F) / 2F,
      14F,
      ThemeManager.currentTheme.text
    )

    NVGRenderer.text(
      "v${metadata.version}",
      x + 75F,
      y + (height - 29F) / 2F + 17F,
      12F,
      ThemeManager.currentTheme.textSecondary
    )
  }

  override fun mouseClicked(button: Int): Boolean {
    if (isHoveringOver(x, y, width, height) && (button == 0 || button == 1)) {
      UIConfig.swapBodyPanel(UIModuleList(metadata, addon))
      return true
    }

    return false
  }

  companion object {
    private val boxIcon = NVGRenderer.createImage("/assets/cobalt/textures/ui/box.svg")
  }

}
