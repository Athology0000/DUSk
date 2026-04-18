package org.cobalt.internal.ui.theme

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.*
import org.cobalt.api.ui.theme.impl.CustomTheme

internal object ThemeSerializer {

  private const val THEME_PREFIX = "COBALT_THEME:"

  fun toBase64(theme: CustomTheme): String {
    val json = toJson(theme).toString()
    val encoded = Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
    return "$THEME_PREFIX$encoded"
  }

  fun fromBase64(data: String): CustomTheme? {
    return runCatching {
      require(data.startsWith(THEME_PREFIX)) { "Invalid theme data format" }
      val encoded = data.removePrefix(THEME_PREFIX)
      val decoded = Base64.getDecoder().decode(encoded)
      val json = JsonParser.parseString(String(decoded, Charsets.UTF_8)).asJsonObject
      fromJson(json)
    }.getOrNull()
  }

  fun toJson(theme: CustomTheme): JsonObject = JsonObject().apply {
    addProperty("name", theme.name)
    addProperty("rainbowEnabled", theme.rainbowEnabled)
    addProperty("rainbowSpeed", theme.rainbowSpeed)
    addProperty("rainbowSaturation", theme.rainbowSaturation)
    addProperty("rainbowBrightness", theme.rainbowBrightness)
    addProperty("background", theme.background)
    addProperty("panel", theme.panel)
    addProperty("inset", theme.inset)
    addProperty("overlay", theme.overlay)
    addProperty("text", theme.text)
    addProperty("textPrimary", theme.textPrimary)
    addProperty("textSecondary", theme.textSecondary)
    addProperty("textDisabled", theme.textDisabled)
    addProperty("textPlaceholder", theme.textPlaceholder)
    addProperty("textOnAccent", theme.textOnAccent)
    addProperty("accent", theme.accent)
    addProperty("accentPrimary", theme.accentPrimary)
    addProperty("accentSecondary", theme.accentSecondary)
    addProperty("selection", theme.selection)
    addProperty("controlBg", theme.controlBg)
    addProperty("controlBorder", theme.controlBorder)
    addProperty("inputBg", theme.inputBg)
    addProperty("inputBorder", theme.inputBorder)
    addProperty("success", theme.success)
    addProperty("warning", theme.warning)
    addProperty("error", theme.error)
    addProperty("info", theme.info)
    addProperty("scrollbarThumb", theme.scrollbarThumb)
    addProperty("scrollbarTrack", theme.scrollbarTrack)
    addProperty("sliderTrack", theme.sliderTrack)
    addProperty("sliderFill", theme.sliderFill)
    addProperty("sliderThumb", theme.sliderThumb)
    addProperty("tooltipBackground", theme.tooltipBackground)
    addProperty("tooltipBorder", theme.tooltipBorder)
    addProperty("tooltipText", theme.tooltipText)
    addProperty("notificationBackground", theme.notificationBackground)
    addProperty("notificationBorder", theme.notificationBorder)
    addProperty("notificationText", theme.notificationText)
    addProperty("notificationTextSecondary", theme.notificationTextSecondary)
    addProperty("infoBackground", theme.infoBackground)
    addProperty("infoBorder", theme.infoBorder)
    addProperty("infoIcon", theme.infoIcon)
    addProperty("warningBackground", theme.warningBackground)
    addProperty("warningBorder", theme.warningBorder)
    addProperty("warningIcon", theme.warningIcon)
    addProperty("successBackground", theme.successBackground)
    addProperty("successBorder", theme.successBorder)
    addProperty("successIcon", theme.successIcon)
    addProperty("errorBackground", theme.errorBackground)
    addProperty("errorBorder", theme.errorBorder)
    addProperty("errorIcon", theme.errorIcon)
    addProperty("selectionText", theme.selectionText)
    addProperty("searchPlaceholderText", theme.searchPlaceholderText)
    addProperty("moduleDivider", theme.moduleDivider)
    addProperty("selectedOverlay", theme.selectedOverlay)
    addProperty("white", theme.white)
    addProperty("black", theme.black)
    addProperty("transparent", theme.transparent)
  }

  fun fromJson(json: JsonObject): CustomTheme {
    val defaults = CustomTheme()
    return CustomTheme(
      name = json.get("name")?.asString ?: defaults.name,
      rainbowEnabled = json.get("rainbowEnabled")?.asBoolean ?: defaults.rainbowEnabled,
      rainbowSpeed = json.get("rainbowSpeed")?.asFloat ?: defaults.rainbowSpeed,
      rainbowSaturation = json.get("rainbowSaturation")?.asFloat ?: defaults.rainbowSaturation,
      rainbowBrightness = json.get("rainbowBrightness")?.asFloat ?: defaults.rainbowBrightness,
      background = json.get("background")?.asInt ?: defaults.background,
      panel = json.get("panel")?.asInt ?: defaults.panel,
      inset = json.get("inset")?.asInt ?: defaults.inset,
      overlay = json.get("overlay")?.asInt ?: defaults.overlay,
      text = json.get("text")?.asInt ?: defaults.text,
      textPrimary = json.get("textPrimary")?.asInt ?: defaults.textPrimary,
      textSecondary = json.get("textSecondary")?.asInt ?: defaults.textSecondary,
      textDisabled = json.get("textDisabled")?.asInt ?: defaults.textDisabled,
      textPlaceholder = json.get("textPlaceholder")?.asInt ?: defaults.textPlaceholder,
      textOnAccent = json.get("textOnAccent")?.asInt ?: defaults.textOnAccent,
      accent = json.get("accent")?.asInt ?: defaults.accent,
      accentPrimary = json.get("accentPrimary")?.asInt ?: defaults.accentPrimary,
      accentSecondary = json.get("accentSecondary")?.asInt ?: defaults.accentSecondary,
      selection = json.get("selection")?.asInt ?: defaults.selection,
      controlBg = json.get("controlBg")?.asInt ?: defaults.controlBg,
      controlBorder = json.get("controlBorder")?.asInt ?: defaults.controlBorder,
      inputBg = json.get("inputBg")?.asInt ?: defaults.inputBg,
      inputBorder = json.get("inputBorder")?.asInt ?: defaults.inputBorder,
      success = json.get("success")?.asInt ?: defaults.success,
      warning = json.get("warning")?.asInt ?: defaults.warning,
      error = json.get("error")?.asInt ?: defaults.error,
      info = json.get("info")?.asInt ?: defaults.info,
      scrollbarThumb = json.get("scrollbarThumb")?.asInt ?: defaults.scrollbarThumb,
      scrollbarTrack = json.get("scrollbarTrack")?.asInt ?: defaults.scrollbarTrack,
      sliderTrack = json.get("sliderTrack")?.asInt ?: defaults.sliderTrack,
      sliderFill = json.get("sliderFill")?.asInt ?: defaults.sliderFill,
      sliderThumb = json.get("sliderThumb")?.asInt ?: defaults.sliderThumb,
      tooltipBackground = json.get("tooltipBackground")?.asInt ?: defaults.tooltipBackground,
      tooltipBorder = json.get("tooltipBorder")?.asInt ?: defaults.tooltipBorder,
      tooltipText = json.get("tooltipText")?.asInt ?: defaults.tooltipText,
      notificationBackground = json.get("notificationBackground")?.asInt ?: defaults.notificationBackground,
      notificationBorder = json.get("notificationBorder")?.asInt ?: defaults.notificationBorder,
      notificationText = json.get("notificationText")?.asInt ?: defaults.notificationText,
      notificationTextSecondary = json.get("notificationTextSecondary")?.asInt ?: defaults.notificationTextSecondary,
      infoBackground = json.get("infoBackground")?.asInt ?: defaults.infoBackground,
      infoBorder = json.get("infoBorder")?.asInt ?: defaults.infoBorder,
      infoIcon = json.get("infoIcon")?.asInt ?: defaults.infoIcon,
      warningBackground = json.get("warningBackground")?.asInt ?: defaults.warningBackground,
      warningBorder = json.get("warningBorder")?.asInt ?: defaults.warningBorder,
      warningIcon = json.get("warningIcon")?.asInt ?: defaults.warningIcon,
      successBackground = json.get("successBackground")?.asInt ?: defaults.successBackground,
      successBorder = json.get("successBorder")?.asInt ?: defaults.successBorder,
      successIcon = json.get("successIcon")?.asInt ?: defaults.successIcon,
      errorBackground = json.get("errorBackground")?.asInt ?: defaults.errorBackground,
      errorBorder = json.get("errorBorder")?.asInt ?: defaults.errorBorder,
      errorIcon = json.get("errorIcon")?.asInt ?: defaults.errorIcon,
      selectionText = json.get("selectionText")?.asInt ?: defaults.selectionText,
      searchPlaceholderText = json.get("searchPlaceholderText")?.asInt ?: defaults.searchPlaceholderText,
      moduleDivider = json.get("moduleDivider")?.asInt ?: defaults.moduleDivider,
      selectedOverlay = json.get("selectedOverlay")?.asInt ?: defaults.selectedOverlay,
      white = json.get("white")?.asInt ?: defaults.white,
      black = json.get("black")?.asInt ?: defaults.black,
      transparent = json.get("transparent")?.asInt ?: defaults.transparent,
    )
  }
}
