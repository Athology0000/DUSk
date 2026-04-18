package org.cobalt.internal.ui.panel.panels

import org.cobalt.api.addon.Addon
import org.cobalt.api.addon.AddonMetadata
import org.cobalt.api.module.Module
import org.cobalt.api.module.ModuleManager
import net.minecraft.client.Minecraft
import org.cobalt.api.ui.theme.ThemeManager
import org.cobalt.api.util.ui.NVGRenderer
import org.cobalt.api.util.ui.helper.Image
import org.cobalt.internal.ui.UIComponent
import org.cobalt.internal.ui.components.tooltips.TooltipPosition
import org.cobalt.internal.ui.components.tooltips.UITooltip
import org.cobalt.internal.ui.components.tooltips.impl.UITextTooltip
import org.cobalt.internal.ui.panel.UIPanel
import org.cobalt.internal.ui.screen.UIConfig
import org.cobalt.internal.ui.screen.UIHudEditor
import org.cobalt.internal.ui.util.isHoveringOver

internal class UISidebar : UIPanel(
  x = 0F,
  y = 0F,
  width = 70F,
  height = 600F
) {

  private val moduleButton = UIButton("/assets/cobalt/textures/ui/box.svg", "Modules") {
    UIConfig.swapBodyPanel(UIAddonList())
  }

  private val hudButton = UIButton("/assets/cobalt/textures/ui/palette.svg", "HUD Editor") {
    UIHudEditor().openUI()
  }

  private val designButton = UIButton("/assets/cobalt/textures/ui/interface.svg", "Design") {
    UIConfig.swapBodyPanel(UIThemeSelector())
  }

  private val routesButton = UIButton("/assets/cobalt/textures/ui/routes.svg", "Routes") {
    UIConfig.swapBodyPanel(UIRoutesPanel())
  }

  private val macrosButton = UIButton("/assets/cobalt/textures/ui/macros.svg", "Macros") {
    openQuickModules(
      "cobalt-quick-macros",
      "Macros",
      ModuleManager.getModules().filter { it.name.contains("macro", ignoreCase = true) }
    )
  }

  private val navButtons = listOf(
    moduleButton,
    hudButton,
    designButton,
    routesButton,
    macrosButton
  )

  private val steveIcon = NVGRenderer.createImage("/assets/cobalt/textures/steve.png")
  private var cachedProfileId = Minecraft.getInstance().user.profileId.toString()
  private var userIcon = loadUserIcon(cachedProfileId)

  private val userIconTooltip = UITooltip(
    content = { UITextTooltip("Manage Accounts (${Minecraft.getInstance().user.name})") },
    position = TooltipPosition.BELOW
  )

  init {
    components.addAll(navButtons)
  }

  override fun render() {
    refreshUserIcon()
    NVGRenderer.rect(x, y, width, height, ThemeManager.currentTheme.background, 10F)
    NVGRenderer.text("cb", x + width / 2F - 15F, y + 25F, 25F, ThemeManager.currentTheme.text)

    navButtons.forEachIndexed { index, button ->
      val buttonX = x + (width / 2F) - (button.width / 2F)
      val buttonY = y + 75F + index * NAV_BUTTON_GAP

      button
        .setSelected(index == 0 || isHoveringOver(buttonX, buttonY, button.width, button.height))
        .updateBounds(buttonX, buttonY)
        .render()
    }

    val userIconX = x + (width / 2F) - 16F
    val userIconY = y + height - 32F - 20F

    NVGRenderer.image(
      userIcon,
      userIconX,
      userIconY,
      32F,
      32F,
      radius = 10F
    )

    userIconTooltip.updateBounds(userIconX, userIconY, 32F, 32F)
  }

  override fun mouseClicked(button: Int): Boolean {
    val userIconX = x + (width / 2F) - 16F
    val userIconY = y + height - 32F - 20F

    if (isHoveringOver(userIconX, userIconY, 32F, 32F) && button == 0) {
      UIConfig.swapBodyPanel(UIAccountManagerPanel())
      return true
    }

    return super.mouseClicked(button)
  }

  private fun refreshUserIcon() {
    val profileId = Minecraft.getInstance().user.profileId.toString()
    if (profileId == cachedProfileId) return
    cachedProfileId = profileId
    userIcon = loadUserIcon(profileId)
  }

  private fun loadUserIcon(profileId: String): Image {
    return try {
      NVGRenderer.createImage("https://mc-heads.net/avatar/$profileId/100/face.png")
    } catch (_: Exception) {
      steveIcon
    }
  }

  private fun openQuickModules(
    id: String,
    name: String,
    modules: List<Module>,
  ) {
    if (modules.isEmpty()) return

    UIConfig.swapBodyPanel(
      UIModuleList(
        AddonMetadata(id, name, "builtin", emptyList(), emptyList()),
        object : Addon() {
          override fun onLoad() {}
          override fun onUnload() {}
          override fun getModules(): List<Module> = modules
        }
      )
    )
  }

  private class UIButton(
    iconPath: String,
    label: String,
    private val onClick: () -> Unit,
  ) : UIComponent(0f, 0f, 22F, 22F) {

    val image = NVGRenderer.createImage(iconPath)
    private var selected = false
    private val tooltip = UITooltip(
      content = { UITextTooltip(label) },
      position = TooltipPosition.RIGHT
    )

    fun setSelected(selected: Boolean): UIComponent {
      this.selected = selected
      return this
    }

    override fun render() {
      val hovering = isHoveringOver(x, y, width, height)

      NVGRenderer.image(
        image,
        x, y, width, height,
        colorMask = if (hovering || selected)
          ThemeManager.currentTheme.accent
        else
          ThemeManager.currentTheme.textSecondary
      )

      tooltip.updateBounds(x, y, width, height)
    }

    override fun mouseClicked(button: Int): Boolean {
      if (isHoveringOver(x, y, width, height) && button == 0) {
        onClick.invoke()
        return true
      }

      return false
    }

  }

  companion object {
    private const val NAV_BUTTON_GAP = 35F
  }

}
