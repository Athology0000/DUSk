package org.cobalt.api.hud.modules

import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.TickEvent
import org.cobalt.api.hud.HudAnchor
import org.cobalt.api.hud.HudModuleManager
import org.cobalt.api.hud.hudElement
import org.cobalt.api.module.Module
import org.cobalt.api.module.ModuleManager
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.module.setting.impl.ColorSetting
import org.cobalt.api.module.setting.impl.KeyBindSetting
import org.cobalt.api.module.setting.impl.TextSetting
import org.cobalt.api.ui.theme.ThemeGradient
import org.cobalt.api.ui.theme.ThemeManager
import org.cobalt.api.ui.theme.ThemeSurface
import org.cobalt.api.util.ui.NVGRenderer
import org.cobalt.api.util.ui.helper.Gradient

class WatermarkModule : Module("Watermark") {

  private data class SegmentSpec(
    val primary: String,
    val suffix: String? = null,
    val primaryColor: Int,
    val suffixColor: Int = MUTED_TEXT,
    val primarySize: Float = SEGMENT_VALUE_SIZE,
    val suffixSize: Float = SEGMENT_LABEL_SIZE,
    val glowColor: Int? = null,
  )

  private data class MacroRow(
    val label: String,
    val keybind: String,
  )

  private data class Layout(
    val width: Float,
    val barHeight: Float,
    val macroFullHeight: Float,
    val macroVisibleHeight: Float,
  ) {
    val totalHeight: Float
      get() = barHeight + macroVisibleHeight
  }

  companion object {
    private const val SIMPLE_TEXT_SIZE = 18f
    private const val CARD_WIDTH = 428f
    private const val MAIN_BAR_HEIGHT = 30f
    private const val CARD_CORNER = 12f
    private const val MAIN_BAR_PAD_X = 10f
    private const val BADGE_SIZE = 18f
    private const val BADGE_GAP = 9f
    private const val SEGMENT_GAP = 10f
    private const val SEGMENT_VALUE_SIZE = 11.6f
    private const val SEGMENT_LABEL_SIZE = 10.3f
    private const val SEGMENT_INNER_GAP = 4f
    private const val SERVER_SEGMENT_MAX_WIDTH = 94f
    private const val PANEL_PAD_X = 12f
    private const val PANEL_PAD_Y = 10f
    private const val PANEL_HEADER_HEIGHT = 22f
    private const val PANEL_ROW_HEIGHT = 17f
    private const val PANEL_ROW_GAP = 6f
    private const val HEADER_TITLE_SIZE = 10.5f
    private const val HEADER_SUBTITLE_SIZE = 9f
    private const val ROW_LABEL_SIZE = 10.8f
    private const val KEY_PILL_TEXT_SIZE = 9.4f
    private const val WHITE_TEXT = 0xFFF2EFF6.toInt()
    private const val MUTED_TEXT = 0x998E8995.toInt()
  }

  private val mc = Minecraft.getInstance()
  private val clientVersion =
    FabricLoader.getInstance()
      .getModContainer("cobalt")
      .map { it.metadata.version.friendlyString }
      .orElse("dev")

  private var smoothedTps = 20f
  private var lastTickNs = 0L
  private var smoothedFps = 60f
  private var lastFrameNs = 0L
  private var macroExpand = 0f
  private var lastMacroAnimNs = 0L
  private var retainedMacroRows = emptyList<MacroRow>()

  init {
    EventBus.register(this)
  }

  @SubscribeEvent
  fun onTick(@Suppress("UNUSED_PARAMETER") event: TickEvent.Start) {
    val level = mc.level
    if (level == null) {
      smoothedTps = 20f
      lastTickNs = 0L
      return
    }

    val now = System.nanoTime()
    if (lastTickNs != 0L) {
      val deltaNs = now - lastTickNs
      val deltaMs = deltaNs / 1_000_000.0
      if (deltaMs in 5.0..250.0) {
        val sample = (1000.0 / deltaMs).toFloat().coerceIn(0f, 20f)
        smoothedTps = smoothedTps * 0.82f + sample * 0.18f
      }
    }
    lastTickNs = now
  }

  val watermark = hudElement("watermark", "Watermark", "Displays Dutt Client branding") {
    anchor = HudAnchor.TOP_LEFT
    offsetX = 10f
    offsetY = 10f
    blurBackground = true
    blurStrength = 14.0

    val text = setting(TextSetting("Text", "Display text", "Dutt Client"))
    val color = setting(ColorSetting("Color", "Accent color", ThemeManager.currentTheme.accent))
    val shadow = setting(CheckboxSetting("Shadow", "Show shadow on the brand text.", true))
    val background = setting(CheckboxSetting("Background", "Show the glass watermark card.", true))
    val macroPanel = setting(CheckboxSetting("Macro Panel", "Expand the watermark to show active macros and keybinds.", true))

    width {
      computeLayout(text.value, macroPanel.value, background.value).width
    }

    height {
      computeLayout(text.value, macroPanel.value, background.value).totalHeight
    }

    render { screenX, screenY, _ ->
      sampleFrameRate()

      if (!background.value) {
        renderSimpleText(screenX, screenY, text.value, color.value, shadow.value)
        return@render
      }

      val liveRows = resolveMacroRows(macroPanel.value)
      if (liveRows.isNotEmpty()) {
        retainedMacroRows = liveRows
      }

      val expandTarget = macroPanel.value && liveRows.isNotEmpty()
      val expansion = updateMacroExpansion(expandTarget)
      val rows = when {
        liveRows.isNotEmpty() -> liveRows
        expansion > 0.01f && retainedMacroRows.isNotEmpty() -> retainedMacroRows
        else -> emptyList()
      }

      if (!expandTarget && expansion <= 0.01f) {
        retainedMacroRows = emptyList()
      }

      val layout = computeLayout(text.value, macroPanel.value, true, rows)
      renderWatermarkCard(screenX, screenY, layout, text.value, color.value, rows)
    }

  }

  private fun computeLayout(
    brand: String,
    showPanel: Boolean,
    backgroundEnabled: Boolean,
    rows: List<MacroRow> = macroRowsForLayout(showPanel),
  ): Layout {
    if (!backgroundEnabled) {
      return Layout(
        width = NVGRenderer.textWidth(brand, SIMPLE_TEXT_SIZE),
        barHeight = SIMPLE_TEXT_SIZE,
        macroFullHeight = 0f,
        macroVisibleHeight = 0f,
      )
    }

    val macroFullHeight = if (showPanel && rows.isNotEmpty()) macroContentHeight(rows) else 0f
    return Layout(
      width = CARD_WIDTH,
      barHeight = MAIN_BAR_HEIGHT,
      macroFullHeight = macroFullHeight,
      macroVisibleHeight = macroFullHeight * macroExpand,
    )
  }

  private fun buildSegments(brand: String, accent: Int): List<SegmentSpec> {
    val theme = ThemeManager.currentTheme
    val fpsText = currentFpsText()
    val tpsText = currentTpsText()
    val host = ellipsize(currentServerHost(), SERVER_SEGMENT_MAX_WIDTH, SEGMENT_VALUE_SIZE)
    val ping = currentPingValueText()

    return listOf(
      SegmentSpec(
        primary = ellipsize(brand, 92f, 12.3f),
        primaryColor = accent,
        primarySize = 12.3f,
        glowColor = accent,
      ),
      SegmentSpec(
        primary = "prod",
        suffix = "v$clientVersion",
        primaryColor = theme.accentSecondary,
        glowColor = theme.accentSecondary,
      ),
      SegmentSpec(
        primary = fpsText,
        suffix = "FPS",
        primaryColor = WHITE_TEXT,
      ),
      SegmentSpec(
        primary = tpsText,
        suffix = "TPS",
        primaryColor = WHITE_TEXT,
      ),
      SegmentSpec(
        primary = ping,
        suffix = "MS",
        primaryColor = WHITE_TEXT,
      ),
      SegmentSpec(
        primary = host,
        suffix = "SERVER",
        primaryColor = WHITE_TEXT,
      ),
    )
  }

  private fun renderSimpleText(x: Float, y: Float, brand: String, accent: Int, shadow: Boolean) {
    if (shadow) {
      NVGRenderer.textShadow(brand, x, y, SIMPLE_TEXT_SIZE, accent)
    } else {
      NVGRenderer.text(brand, x, y, SIMPLE_TEXT_SIZE, accent)
    }
  }

  private fun renderWatermarkCard(
    x: Float,
    y: Float,
    layout: Layout,
    brand: String,
    accent: Int,
    rows: List<MacroRow>,
  ) {
    val theme = ThemeManager.currentTheme
    val now = System.currentTimeMillis()
    val width = layout.width
    val totalHeight = layout.totalHeight.coerceAtLeast(MAIN_BAR_HEIGHT)
    val shiftX = cos((now % 10000L).toFloat() / 10000f * (Math.PI * 2).toFloat()) * (width * 0.32f)
    val (gradientStart, gradientEnd) = ThemeGradient.colors()

    NVGRenderer.rect(x + 3f, y + 2f, width, totalHeight, 0x0A000000, CARD_CORNER + 1f)
    NVGRenderer.rect(x, y, width, totalHeight, ThemeSurface.panel(0x34), CARD_CORNER)
    NVGRenderer.gradientRect(
      x,
      y,
      width,
      totalHeight,
      ThemeSurface.overlay(0x18),
      ThemeSurface.inset(0x12),
      Gradient.TopToBottom,
      CARD_CORNER,
    )
    NVGRenderer.gradientRect(
      x,
      y,
      width,
      MAIN_BAR_HEIGHT + layout.macroVisibleHeight * 0.55f,
      ThemeSurface.overlay(0x1E),
      0x00000000,
      Gradient.TopToBottom,
      CARD_CORNER,
    )
    NVGRenderer.hollowGradientRectShifted(
      x,
      y,
      width,
      totalHeight,
      1.2f,
      ThemeGradient.withAlpha(gradientStart, 96),
      ThemeGradient.withAlpha(gradientEnd, 82),
      Gradient.LeftToRight,
      CARD_CORNER,
      shiftX,
      0f,
    )

    if (layout.macroVisibleHeight > 0.75f) {
      NVGRenderer.rect(
        x + 14f,
        y + MAIN_BAR_HEIGHT - 0.5f,
        width - 28f,
        1f,
        withAlpha(accent, (28f + 42f * macroExpand).toInt()),
        0.5f,
      )
    }

    val badgeCenterX = x + MAIN_BAR_PAD_X + BADGE_SIZE / 2f
    val badgeCenterY = y + MAIN_BAR_HEIGHT / 2f
    renderBadge(badgeCenterX, badgeCenterY, BADGE_SIZE, accent, brand)

    var cursorX = x + MAIN_BAR_PAD_X + BADGE_SIZE + BADGE_GAP
    val segments = buildSegments(brand, accent)
    segments.forEachIndexed { index, segment ->
      renderSegment(cursorX, y + 9f, segment)
      cursorX += segmentWidth(segment)
      if (index != segments.lastIndex) {
        val lineX = cursorX + SEGMENT_GAP * 0.5f
        NVGRenderer.rect(lineX, y + 7f, 1f, MAIN_BAR_HEIGHT - 14f, 0x19FFFFFF, 0.5f)
        cursorX += SEGMENT_GAP
      }
    }

    if (rows.isNotEmpty() && layout.macroVisibleHeight > 0.5f) {
      renderMacroSection(
        x,
        y + MAIN_BAR_HEIGHT,
        width,
        layout.macroVisibleHeight,
        rows,
        accent,
      )
    }

    if (layout.macroVisibleHeight > 0f) {
      NVGRenderer.circle(
        x + width - 34f,
        y + totalHeight - 18f,
        9f,
        withAlpha(theme.accentSecondary, (8f + 12f * macroExpand).toInt()),
      )
    }
  }

  private fun renderBadge(cx: Float, cy: Float, size: Float, accent: Int, brand: String) {
    val radius = size / 2f
    val ringColor = withAlpha(accent, 90)
    val innerColor = 0xA6221D26u.toInt()
    val initial = brand.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "C"

    NVGRenderer.circle(cx, cy, radius + 2.3f, 0x17000000)
    NVGRenderer.circle(cx, cy, radius + 0.8f, ringColor)
    NVGRenderer.circle(cx, cy, radius - 0.8f, innerColor)
    NVGRenderer.circle(cx - radius * 0.28f, cy - radius * 0.28f, radius * 0.42f, 0x2AFFFFFF)
    NVGRenderer.circle(cx + radius * 0.06f, cy + radius * 0.12f, radius * 0.78f, 0x0E000000)

    val glyphWidth = NVGRenderer.textWidth(initial, 10.8f)
    NVGRenderer.text(initial, cx - glyphWidth / 2f, cy - 5.2f, 10.8f, WHITE_TEXT)
  }

  private fun renderSegment(x: Float, y: Float, segment: SegmentSpec) {
    if (segment.glowColor != null) {
      renderAnimatedGlowText(
        segment.primary,
        x,
        y,
        segment.primarySize,
        segment.primaryColor,
        segment.glowColor,
      )
    } else {
      NVGRenderer.text(segment.primary, x, y, segment.primarySize, segment.primaryColor)
    }

    val suffix = segment.suffix?.takeIf { it.isNotBlank() } ?: return
    val primaryWidth = NVGRenderer.textWidth(segment.primary, segment.primarySize)
    NVGRenderer.text(
      suffix,
      x + primaryWidth + SEGMENT_INNER_GAP,
      y + (segment.primarySize - segment.suffixSize) * 0.35f,
      segment.suffixSize,
      segment.suffixColor,
    )
  }

  private fun renderAnimatedGlowText(
    text: String,
    x: Float,
    y: Float,
    size: Float,
    color: Int,
    glowColor: Int,
  ) {
    val pulse =
      ((sin((System.currentTimeMillis() % 2300L).toFloat() / 2300f * (Math.PI * 2).toFloat()) + 1f) * 0.5f)
    val glowAlpha = (22f + pulse * 22f).toInt()
    val shimmerAlpha = (12f + pulse * 14f).toInt()

    NVGRenderer.text(text, x - 0.7f, y, size, withAlpha(glowColor, glowAlpha))
    NVGRenderer.text(text, x + 0.7f, y, size, withAlpha(glowColor, glowAlpha))
    NVGRenderer.text(text, x, y - 0.7f, size, withAlpha(glowColor, glowAlpha))
    NVGRenderer.text(text, x, y + 0.7f, size, withAlpha(glowColor, glowAlpha))
    NVGRenderer.text(text, x, y - 0.1f, size, withAlpha(WHITE_TEXT, shimmerAlpha))
    NVGRenderer.text(text, x, y, size, color)
  }

  private fun renderMacroSection(
    x: Float,
    y: Float,
    width: Float,
    visibleHeight: Float,
    rows: List<MacroRow>,
    accent: Int,
  ) {
    val contentAlpha = macroExpand.coerceIn(0f, 1f)

    NVGRenderer.push()
    NVGRenderer.pushScissor(x + 1f, y, width - 2f, visibleHeight)
    NVGRenderer.globalAlpha(contentAlpha)

    val headerY = y + PANEL_PAD_Y
    NVGRenderer.circle(x + PANEL_PAD_X + 3f, headerY + 5.5f, 2.5f, withAlpha(accent, 176))
    NVGRenderer.text("ACTIVE MACROS", x + PANEL_PAD_X + 11f, headerY, HEADER_TITLE_SIZE, MUTED_TEXT)
    renderHeaderPill(
      rightX = x + width - PANEL_PAD_X,
      y = headerY - 2f,
      text = if (rows.size == 1) "1 LIVE" else "${rows.size} LIVE",
      accent = accent,
    )

    var rowY = y + PANEL_PAD_Y + PANEL_HEADER_HEIGHT
    rows.forEachIndexed { index, row ->
      renderMacroRow(x + PANEL_PAD_X, rowY, width - PANEL_PAD_X * 2f, row, accent)
      rowY += PANEL_ROW_HEIGHT
      if (index != rows.lastIndex) {
        NVGRenderer.rect(
          x + PANEL_PAD_X,
          rowY + 1f,
          width - PANEL_PAD_X * 2f,
          1f,
          ThemeSurface.overlay(0x10),
          0.5f,
        )
        rowY += PANEL_ROW_GAP
      }
    }

    NVGRenderer.popScissor()
    NVGRenderer.pop()
  }

  private fun renderMacroRow(x: Float, y: Float, width: Float, row: MacroRow, accent: Int) {
    val pillWidth = keyPillWidth(row.keybind)
    val pillX = x + width - pillWidth
    val labelMaxWidth = width - pillWidth - 12f
    val label = ellipsize(row.label, labelMaxWidth, ROW_LABEL_SIZE)

    NVGRenderer.text(label, x, y + 1f, ROW_LABEL_SIZE, WHITE_TEXT)

    NVGRenderer.rect(pillX, y - 2f, pillWidth, 15f, withAlpha(accent, 18), 7f)
    NVGRenderer.hollowRect(pillX, y - 2f, pillWidth, 15f, 1f, withAlpha(accent, 78), 7f)
    NVGRenderer.text(
      row.keybind,
      pillX + (pillWidth - NVGRenderer.textWidth(row.keybind, KEY_PILL_TEXT_SIZE)) / 2f,
      y + 1f,
      KEY_PILL_TEXT_SIZE,
      WHITE_TEXT,
    )
  }

  private fun renderHeaderPill(rightX: Float, y: Float, text: String, accent: Int) {
    val pillWidth = NVGRenderer.textWidth(text, KEY_PILL_TEXT_SIZE) + 14f
    val pillX = rightX - pillWidth
    NVGRenderer.rect(pillX, y, pillWidth, 15f, withAlpha(accent, 16), 7f)
    NVGRenderer.hollowRect(pillX, y, pillWidth, 15f, 1f, withAlpha(accent, 72), 7f)
    NVGRenderer.text(
      text,
      pillX + (pillWidth - NVGRenderer.textWidth(text, KEY_PILL_TEXT_SIZE)) / 2f,
      y + 3f,
      KEY_PILL_TEXT_SIZE,
      WHITE_TEXT,
    )
  }

  private fun resolveMacroRows(showPanel: Boolean): List<MacroRow> {
    if (!showPanel) return emptyList()

    val macroModules = ModuleManager.getModules().filter(::isMacroCandidate)
    val activeRows =
      macroModules
        .mapNotNull { module ->
          if (!isModuleActive(module)) return@mapNotNull null
          MacroRow(
            label = module.name,
            keybind = resolveKeybind(module),
          )
        }

    if (activeRows.isNotEmpty()) return activeRows
    if (!HudModuleManager.isEditorOpen) return emptyList()

    val previewRows =
      macroModules
        .take(3)
        .map { module ->
          MacroRow(
            label = module.name,
            keybind = resolveKeybind(module),
          )
        }

    return if (previewRows.isNotEmpty()) previewRows else listOf(MacroRow("Mining Macro", "R"))
  }

  private fun isMacroCandidate(module: Module): Boolean {
    val name = module.name.lowercase(Locale.US)
    return name.contains("macro") ||
      name.contains("patrol") ||
      name.contains("forge") ||
      name == "routes"
  }

  private fun isModuleActive(module: Module): Boolean {
    readBooleanFlag(module, "isRunning")?.let { return it }
    readBooleanFlag(module, "isActive")?.let { return it }
    return module.getSettings()
      .filterIsInstance<CheckboxSetting>()
      .firstOrNull { it.name.equals("Enabled", ignoreCase = true) }
      ?.value == true
  }

  private fun readBooleanFlag(module: Module, methodName: String): Boolean? {
    return runCatching {
      module.javaClass.methods
        .firstOrNull {
          it.name == methodName &&
            it.parameterCount == 0 &&
            (it.returnType == Boolean::class.javaPrimitiveType || it.returnType == Boolean::class.java)
        }
        ?.invoke(module) as? Boolean
    }.getOrNull()
  }

  private fun resolveKeybind(module: Module): String {
    val keybind =
      module.getSettings()
        .filterIsInstance<KeyBindSetting>()
        .firstOrNull { it.name.contains("Toggle", ignoreCase = true) }
        ?: module.getSettings().filterIsInstance<KeyBindSetting>().firstOrNull()

    return when {
      keybind == null -> "MANUAL"
      keybind.keyName.equals("None", ignoreCase = true) -> "NONE"
      else -> keybind.keyName.uppercase(Locale.US)
    }
  }

  private fun currentFpsText(): String {
    val fps = if (lastFrameNs != 0L) smoothedFps else if (HudModuleManager.isEditorOpen) 10f else -1f
    return if (fps > 0f) fps.toInt().toString() else "--"
  }

  private fun currentTpsText(): String {
    val tps = if (mc.level != null) smoothedTps else if (HudModuleManager.isEditorOpen) 17.3f else -1f
    return if (tps >= 0f) String.format(Locale.US, "%.1f", tps.coerceIn(0f, 20f)) else "--"
  }

  private fun currentServerHost(): String {
    return mc.currentServer?.ip
      ?.substringBefore(':')
      ?.ifBlank { null }
      ?: if (HudModuleManager.isEditorOpen) "mc.hypixel.net" else "local"
  }

  private fun currentPingValueText(): String {
    val player = mc.player
    val ping = if (player != null) {
      player.connection.getPlayerInfo(player.uuid)?.latency
    } else {
      null
    }
    return when {
      ping != null && ping >= 0 -> ping.toString()
      HudModuleManager.isEditorOpen -> "48"
      else -> "--"
    }
  }

  private fun macroRowsForLayout(showPanel: Boolean): List<MacroRow> {
    if (!showPanel) return emptyList()
    val liveRows = resolveMacroRows(true)
    return when {
      liveRows.isNotEmpty() -> liveRows
      macroExpand > 0.01f && retainedMacroRows.isNotEmpty() -> retainedMacroRows
      else -> emptyList()
    }
  }

  private fun macroContentHeight(rows: List<MacroRow>): Float {
    if (rows.isEmpty()) return 0f
    val rowAreaHeight = rows.size * PANEL_ROW_HEIGHT + max(0, rows.size - 1) * PANEL_ROW_GAP
    return PANEL_PAD_Y * 2f + PANEL_HEADER_HEIGHT + rowAreaHeight
  }

  private fun updateMacroExpansion(expanded: Boolean): Float {
    val target = if (expanded) 1f else 0f
    val now = System.nanoTime()
    val deltaSeconds =
      if (lastMacroAnimNs != 0L) {
        ((now - lastMacroAnimNs) / 1_000_000_000f).coerceIn(0f, 0.05f)
      } else {
        1f / 60f
      }
    lastMacroAnimNs = now

    val speed = if (target > macroExpand) 6.2f else 8.4f
    val step = speed * deltaSeconds
    macroExpand =
      when {
        macroExpand < target -> min(target, macroExpand + step)
        macroExpand > target -> max(target, macroExpand - step)
        else -> target
      }.coerceIn(0f, 1f)
    return macroExpand
  }

  private fun segmentWidth(segment: SegmentSpec): Float {
    val suffix = segment.suffix?.takeIf { it.isNotBlank() }
    val primary = NVGRenderer.textWidth(segment.primary, segment.primarySize)
    val suffixWidth = suffix?.let { NVGRenderer.textWidth(it, segment.suffixSize) } ?: 0f
    return primary + if (suffix != null) SEGMENT_INNER_GAP + suffixWidth else 0f
  }

  private fun keyPillWidth(text: String): Float {
    return NVGRenderer.textWidth(text, KEY_PILL_TEXT_SIZE) + 14f
  }

  private fun ellipsize(text: String, maxWidth: Float, size: Float): String {
    if (NVGRenderer.textWidth(text, size) <= maxWidth) return text
    for (length in text.length - 1 downTo 1) {
      val candidate = text.substring(0, length).trimEnd() + "..."
      if (NVGRenderer.textWidth(candidate, size) <= maxWidth) return candidate
    }
    return "..."
  }

  private fun withAlpha(color: Int, alpha: Int): Int {
    return ((alpha.coerceIn(0, 255) shl 24) or (color and 0x00FFFFFF))
  }

  private fun sampleFrameRate() {
    val now = System.nanoTime()
    if (lastFrameNs != 0L) {
      val deltaMs = (now - lastFrameNs) / 1_000_000.0
      if (deltaMs in 2.0..250.0) {
        val sample = (1000.0 / deltaMs).toFloat().coerceIn(1f, 360f)
        smoothedFps = smoothedFps * 0.85f + sample * 0.15f
      }
    }
    lastFrameNs = now
  }

  private fun <T> Iterable<T>.sumOfFloat(selector: (T) -> Float): Float {
    var sum = 0f
    for (value in this) {
      sum += selector(value)
    }
    return sum
  }
}
