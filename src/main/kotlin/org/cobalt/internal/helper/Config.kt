package org.cobalt.internal.helper

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import net.minecraft.client.Minecraft
import org.cobalt.api.hud.HudAnchor
import org.cobalt.api.module.Module
import org.cobalt.api.module.ModuleManager
import org.cobalt.api.ui.theme.ThemeManager
import org.cobalt.api.ui.theme.impl.CustomTheme
import org.cobalt.internal.loader.AddonLoader
import org.cobalt.internal.ui.theme.ThemeSerializer

internal object Config {

  private const val BUILTIN_ADDON_ID = "cobalt"

  private val mc: Minecraft = Minecraft.getInstance()
  private val gson = GsonBuilder().setPrettyPrinting().create()
  private val modulesFile = File(mc.gameDirectory, "config/cobalt/addons.json")
  private val themesFile = File(mc.gameDirectory, "config/cobalt/themes.json")

  private fun buildGroupedModules(): Map<String, List<Module>> {
    val addonModules = mutableSetOf<Module>()
    val grouped = mutableMapOf<String, MutableList<Module>>()

    AddonLoader.getAddons().forEach { (metadata, addon) ->
      val modules = addon.getModules()
      addonModules.addAll(modules)
      if (modules.isNotEmpty()) {
        grouped[metadata.id] = modules.toMutableList()
      }
    }

    val builtinModules = ModuleManager.getModules().filter { it !in addonModules }
    if (builtinModules.isNotEmpty()) {
      grouped[BUILTIN_ADDON_ID] = builtinModules.toMutableList()
    }

    return grouped
  }

  fun loadModulesConfig() {
    loadThemesConfig()
    if (!modulesFile.exists()) {
      modulesFile.parentFile?.mkdirs()
      modulesFile.createNewFile()
      return
    }

    val text = modulesFile.bufferedReader().use { it.readText() }
    if (text.isEmpty()) return

    val grouped = buildGroupedModules()

    runCatching {
      JsonParser.parseString(text).asJsonArray
    }.getOrNull()?.forEach { element ->
      val addonObj = element.asJsonObject
      val addonId = addonObj.get("addon").asString
      val modules = grouped[addonId] ?: return@forEach

      val modulesMap = modules.associateBy { it.name }

      addonObj.getAsJsonArray("modules")?.forEach { moduleElement ->
        val moduleObj = moduleElement.asJsonObject
        val moduleName = moduleObj.get("name").asString
        val module = modulesMap[moduleName] ?: return@forEach

        val settingsMap = module.getSettings().associateBy { it.name }
        moduleObj.getAsJsonObject("settings")?.entrySet()?.forEach { (key, value) ->
          runCatching { settingsMap[key]?.read(value) }
        }

        val hudElementsMap = module.getHudElements().associateBy { it.id }
        moduleObj.getAsJsonArray("hudElements")?.forEach { hudEl ->
          val hudObj = hudEl.asJsonObject
          val hudId = hudObj.get("id")?.asString ?: return@forEach
          val hudElement = hudElementsMap[hudId] ?: return@forEach

          hudElement.enabled = hudObj.get("enabled")?.asBoolean ?: true
          hudElement.anchor = hudObj.get("anchor")?.asString?.let {
            runCatching { HudAnchor.valueOf(it) }.getOrNull()
          } ?: HudAnchor.TOP_LEFT
          hudElement.offsetX = hudObj.get("offsetX")?.asFloat ?: 10f
          hudElement.offsetY = hudObj.get("offsetY")?.asFloat ?: 10f
          hudElement.scale = hudObj.get("scale")?.asFloat?.coerceIn(0.5f, 3.0f) ?: 1.0f

          val hudSettingsObj = hudObj.getAsJsonObject("settings")
          if (hudSettingsObj != null) {
            hudElement.getSettings().forEach { setting ->
              hudSettingsObj.get(setting.name)?.let { jsonEl ->
                runCatching { setting.read(jsonEl) }
              }
            }
          }
        }
      }
    }
  }

  fun saveModulesConfig() {
    val jsonArray = JsonArray()

    buildGroupedModules().forEach { (addonId, modules) ->
      val addonObject = JsonObject()
      addonObject.addProperty("addon", addonId)

      val modulesArray = JsonArray()
      modules.forEach { module ->
        val moduleObject = JsonObject()
        moduleObject.addProperty("name", module.name)

        val settingsObject = JsonObject()
        module.getSettings().forEach { setting ->
          settingsObject.add(setting.name, setting.write())
        }
        moduleObject.add("settings", settingsObject)

        val hudElementsArray = JsonArray()
        module.getHudElements().forEach { hudElement ->
          val hudObj = JsonObject()
          hudObj.addProperty("id", hudElement.id)
          hudObj.addProperty("enabled", hudElement.enabled)
          hudObj.addProperty("anchor", hudElement.anchor.name)
          hudObj.addProperty("offsetX", hudElement.offsetX)
          hudObj.addProperty("offsetY", hudElement.offsetY)
          hudObj.addProperty("scale", hudElement.scale)

          val hudSettingsObj = JsonObject()
          hudElement.getSettings().forEach { setting ->
            hudSettingsObj.add(setting.name, setting.write())
          }
          hudObj.add("settings", hudSettingsObj)
          hudElementsArray.add(hudObj)
        }
        moduleObject.add("hudElements", hudElementsArray)

        modulesArray.add(moduleObject)
      }

      addonObject.add("modules", modulesArray)
      jsonArray.add(addonObject)
    }

    modulesFile.bufferedWriter().use { it.write(gson.toJson(jsonArray)) }
    saveThemesConfig()
  }

  private fun loadThemesConfig() {
    if (!themesFile.exists()) {
      themesFile.parentFile?.mkdirs()
      themesFile.createNewFile()
      return
    }

    val text = themesFile.bufferedReader().use { it.readText() }
    if (text.isEmpty()) return

    runCatching {
      JsonParser.parseString(text).asJsonObject
    }.getOrNull()?.let { root ->
      root.getAsJsonArray("themes")?.forEach { element ->
        ThemeManager.registerTheme(ThemeSerializer.fromJson(element.asJsonObject))
      }

      root.get("currentTheme")?.asString?.let { themeName ->
        ThemeManager.getThemes().firstOrNull { it.name == themeName }?.let {
          ThemeManager.setTheme(it)
        }
      }
    }
  }

  private fun saveThemesConfig() {
    val themeArray = JsonArray()
    ThemeManager.getThemes().forEach { theme ->
      if (theme is CustomTheme) {
        themeArray.add(ThemeSerializer.toJson(theme))
      }
    }

    val root = JsonObject()
    root.add("themes", themeArray)
    root.addProperty("currentTheme", ThemeManager.currentTheme.name)

    themesFile.parentFile?.mkdirs()
    themesFile.bufferedWriter().use { it.write(gson.toJson(root)) }
  }
}
