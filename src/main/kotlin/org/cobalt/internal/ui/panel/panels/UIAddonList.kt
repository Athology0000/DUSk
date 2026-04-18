package org.cobalt.internal.ui.panel.panels

import net.fabricmc.loader.api.FabricLoader
import org.cobalt.api.addon.Addon
import org.cobalt.api.addon.AddonMetadata
import org.cobalt.api.module.ModuleManager
import org.cobalt.api.ui.theme.ThemeManager
import org.cobalt.api.util.ui.NVGRenderer
import org.cobalt.internal.loader.AddonLoader
import org.cobalt.internal.mining.AutoLanternModule
import org.cobalt.internal.mining.CommissionHudModule
import org.cobalt.api.hud.modules.CommissionMacroModule
import org.cobalt.api.hud.modules.MiningHudModule
import org.cobalt.internal.mining.FairyModule
import org.cobalt.internal.mining.MiningMacroModule
import org.cobalt.internal.mining.MiningModule
import org.cobalt.internal.mining.RoutesModule
import org.cobalt.internal.mining.VeinDirectionModule
import org.cobalt.internal.combat.CombatMacroModule
import org.cobalt.internal.garden.GardenAnalyzerModule
import org.cobalt.internal.garden.GardenMacroModule
import org.cobalt.internal.visual.BlockOutlineModule
import org.cobalt.internal.visual.BlockOverlayModule
import org.cobalt.internal.visual.DarkModeModule
import org.cobalt.internal.visual.FreecamModule
import org.cobalt.internal.visual.FullBrightModule
import org.cobalt.internal.visual.HotbarOverlayModule
import org.cobalt.internal.visual.OrbitFreecamModule
import org.cobalt.internal.visual.PetDisplayModule
import org.cobalt.internal.ui.UIComponent
import org.cobalt.internal.ui.components.UIAddonEntry
import org.cobalt.internal.ui.components.UITopbar
import org.cobalt.internal.ui.panel.UIPanel
import org.cobalt.internal.ui.util.GridLayout
import org.cobalt.internal.ui.util.ScrollHandler
import org.cobalt.internal.ui.util.isHoveringOver

internal class UIAddonList : UIPanel(
  x = 0F,
  y = 0F,
  width = 890F,
  height = 600F
) {

  private val topBar = UITopbar("Addons")
  private val allEntries = buildAddonEntries()
  private var entries = allEntries

  private val gridLayout = GridLayout(
    columns = 3,
    itemWidth = 270F,
    itemHeight = 70F,
    gap = 20F
  )

  private val scrollHandler = ScrollHandler()

  init {
    components.addAll(allEntries)
    components.add(topBar)

    topBar.searchChanged { searchText ->
      entries = if (searchText.isEmpty()) {
        allEntries
      } else {
        allEntries.filter { it.metadata.name.lowercase().contains(searchText.lowercase()) }
      }
    }
  }

  override fun render() {
    NVGRenderer.rect(x, y, width, height, ThemeManager.currentTheme.background, 10F)

    topBar
      .updateBounds(x, y)
      .render()

    val startY = y + topBar.height
    val visibleHeight = height - topBar.height

    scrollHandler.setMaxScroll(gridLayout.contentHeight(entries.size) + 20F, visibleHeight)
    NVGRenderer.pushScissor(x, startY, width, visibleHeight)

    val scrollOffset = scrollHandler.getOffset()
    gridLayout.layout(x + 20F, startY + 20F - scrollOffset, entries)
    entries.forEach(UIComponent::render)

    NVGRenderer.popScissor()
  }

  override fun mouseScrolled(horizontalAmount: Double, verticalAmount: Double): Boolean {
    if (isHoveringOver(x, y, width, height)) {
      scrollHandler.handleScroll(verticalAmount)
      return true
    }

    return false
  }

  private fun buildAddonEntries(): List<UIAddonEntry> {
    val entries = mutableListOf<UIAddonEntry>()
    entries.addAll(AddonLoader.getAddons().map { UIAddonEntry(it.first, it.second) })

    val addonModules = AddonLoader.getAddons().flatMap { it.second.getModules() }.toSet()
    val builtinModules = ModuleManager.getModules().filter { it !in addonModules }

    val miningModules = builtinModules.filter {
      it == MiningModule || it == MiningHudModule || it == MiningMacroModule || it == CommissionHudModule || it == FairyModule ||
        it == RoutesModule || it == CommissionMacroModule ||
        it == VeinDirectionModule || it == AutoLanternModule
    }

    val combatModules = builtinModules.filter {
      it == CombatMacroModule || it.name.equals("Combat HUD", ignoreCase = true)
    }

    val visualModules = builtinModules.filter {
      it == FullBrightModule || it == DarkModeModule || it == FreecamModule ||
        it == OrbitFreecamModule || it == BlockOverlayModule || it == BlockOutlineModule ||
        it == HotbarOverlayModule || it == PetDisplayModule ||
        it.name.equals("Watermark", ignoreCase = true) ||
        it.name.equals("Inventory HUD", ignoreCase = true)
    }

    val gardenModules = builtinModules.filter {
      it == GardenMacroModule || it == GardenAnalyzerModule
    }

    val coreModules = builtinModules.filter {
      it !in miningModules && it !in combatModules && it !in visualModules && it !in gardenModules
    }

    val version = FabricLoader.getInstance()
      .getModContainer("cobalt")
      .map { it.metadata.version.friendlyString }
      .orElse("builtin")

    // Added in reverse display order (each add(0, ...) pushes to front).
    // Final display order: Mining -> Combat -> Visuals -> Core -> external addons

    if (coreModules.isNotEmpty()) {
      val meta = AddonMetadata("cobalt", "Core", version, emptyList(), emptyList())
      entries.add(0, UIAddonEntry(meta, object : Addon() {
        override fun onLoad() {}
        override fun onUnload() {}
        override fun getModules() = coreModules
      }))
    }

    if (visualModules.isNotEmpty()) {
      val meta = AddonMetadata("cobalt-visuals", "Visuals", version, emptyList(), emptyList())
      entries.add(0, UIAddonEntry(meta, object : Addon() {
        override fun onLoad() {}
        override fun onUnload() {}
        override fun getModules() = visualModules
      }))
    }

    if (combatModules.isNotEmpty()) {
      val meta = AddonMetadata("cobalt-combat", "Combat", version, emptyList(), emptyList())
      entries.add(0, UIAddonEntry(meta, object : Addon() {
        override fun onLoad() {}
        override fun onUnload() {}
        override fun getModules() = combatModules
      }))
    }

    if (gardenModules.isNotEmpty()) {
      val meta = AddonMetadata("cobalt-garden", "Garden", version, emptyList(), emptyList())
      entries.add(0, UIAddonEntry(meta, object : Addon() {
        override fun onLoad() {}
        override fun onUnload() {}
        override fun getModules() = gardenModules
      }))
    }

    if (miningModules.isNotEmpty()) {
      val meta = AddonMetadata("cobalt-mining", "Mining", version, emptyList(), emptyList())
      entries.add(0, UIAddonEntry(meta, object : Addon() {
        override fun onLoad() {}
        override fun onUnload() {}
        override fun getModules() = miningModules
      }))
    }

    return entries
  }

}
