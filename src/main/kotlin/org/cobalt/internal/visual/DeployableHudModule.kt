package org.cobalt.internal.visual

import java.awt.Color
import java.util.Base64
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponents
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.TickEvent
import org.cobalt.api.event.impl.render.GuiRenderContext
import org.cobalt.api.hud.HudAnchor
import org.cobalt.api.hud.HudModuleManager
import org.cobalt.api.hud.hudElement
import org.cobalt.api.module.Module
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.module.setting.impl.ModeSetting
import org.cobalt.api.ui.theme.ThemeManager
import org.cobalt.api.util.ui.NVGRenderer
import org.cobalt.api.util.ui.helper.Gradient

object DeployableHudModule : Module("Deployable HUD") {

  private val mc = Minecraft.getInstance()

  private val enabledSetting = CheckboxSetting(
    "Enabled",
    "Show the active SkyBlock deployable HUD.",
    true
  )

  private val displayStyle = ModeSetting(
    "Style",
    "Choose the deployable HUD layout.",
    0,
    arrayOf("Compact", "Detailed")
  )

  private val liveIconSetting = CheckboxSetting(
    "Live Icon",
    "Use the detected deployable head item when available.",
    true
  )

  private val previewInEditor = CheckboxSetting(
    "Editor Preview",
    "Show a preview deployable while the HUD editor is open.",
    true
  )

  private val showStatsInCompact = CheckboxSetting(
    "Compact Subtitle",
    "Show the first bonus line under the name in compact mode.",
    true
  )

  private enum class DeployableCategory {
    ORB,
    FLARE,
    LANTERN,
    TOTEM,
    MISC
  }

  private data class DeployableSpec(
    val key: String,
    val displayPrefix: String,
    val compactName: String,
    val category: DeployableCategory,
    val priority: Int,
    val rangeSq: Double,
    val accent: Int,
    val statLines: List<String>,
    val flareTextureId: String? = null,
    val previewItem: ItemStack = ItemStack(Items.BEACON),
  )

  private data class ActiveDeployable(
    val spec: DeployableSpec,
    val seconds: Int,
    val iconStack: ItemStack,
    val sourceId: java.util.UUID? = null,
  )

  @Volatile
  private var activeDeployable: ActiveDeployable? = null

  fun isLanternActive(): Boolean =
    activeDeployable?.spec?.category == DeployableCategory.LANTERN

  fun getLanternStatusLabel(): String? {
    val active = activeDeployable ?: return null
    if (active.spec.category != DeployableCategory.LANTERN) return null
    return "${active.spec.displayPrefix.trim()} ${formatSeconds(active.seconds)}"
  }

  private val deployableSpecs = listOf(
    DeployableSpec(
      key = "radiant",
      displayPrefix = "Radiant ",
      compactName = "RADIANT",
      category = DeployableCategory.ORB,
      priority = 1,
      rangeSq = 18.0 * 18.0,
      accent = 0xFFFF8A4C.toInt(),
      statLines = listOf("+1% HP/s"),
      previewItem = ItemStack(Items.BEACON)
    ),
    DeployableSpec(
      key = "mana_flux",
      displayPrefix = "Mana Flux ",
      compactName = "MANA FLUX",
      category = DeployableCategory.ORB,
      priority = 2,
      rangeSq = 18.0 * 18.0,
      accent = 0xFF48D8FF.toInt(),
      statLines = listOf("+2% HP/s", "+50% Mana Regen", "+10 Strength"),
      previewItem = ItemStack(Items.BEACON)
    ),
    DeployableSpec(
      key = "overflux",
      displayPrefix = "Overflux ",
      compactName = "OVERFLUX",
      category = DeployableCategory.ORB,
      priority = 3,
      rangeSq = 18.0 * 18.0,
      accent = 0xFFAE7BFF.toInt(),
      statLines = listOf("+2.5% HP/s", "+100% Mana Regen", "+25 Strength", "+5 Vitality", "+5 Mending"),
      previewItem = ItemStack(Items.BEACON)
    ),
    DeployableSpec(
      key = "plasmaflux",
      displayPrefix = "Plasmaflux ",
      compactName = "PLASMAFLUX",
      category = DeployableCategory.ORB,
      priority = 4,
      rangeSq = 20.0 * 20.0,
      accent = 0xFFFF5D8F.toInt(),
      statLines = listOf("+3% HP/s", "+125% Mana Regen", "+35 Strength", "+7.5 Vitality", "+7.5 Mending"),
      previewItem = ItemStack(Items.BEACON)
    ),
    DeployableSpec(
      key = "warning_flare",
      displayPrefix = "Warning",
      compactName = "WARNING",
      category = DeployableCategory.FLARE,
      priority = 5,
      rangeSq = 40.0 * 40.0,
      accent = 0xFFFFCC4D.toInt(),
      statLines = listOf("+10 Vitality", "+10 True Def"),
      flareTextureId = "22e2bf6c1ec330247927ba63479e5872ac66b06903c86c82b52dac9f1c971458",
      previewItem = ItemStack(Items.BLAZE_POWDER)
    ),
    DeployableSpec(
      key = "alert_flare",
      displayPrefix = "Alert",
      compactName = "ALERT",
      category = DeployableCategory.FLARE,
      priority = 6,
      rangeSq = 40.0 * 40.0,
      accent = 0xFFFF8547.toInt(),
      statLines = listOf("+50% Mana Regen", "+20 Vitality", "+20 True Def", "+10 Ferocity"),
      flareTextureId = "9d2bf9864720d87fd06b84efa80b795c48ed539b16523c3b1f1990b40c003f6b",
      previewItem = ItemStack(Items.FIRE_CHARGE)
    ),
    DeployableSpec(
      key = "sos_flare",
      displayPrefix = "SOS",
      compactName = "SOS",
      category = DeployableCategory.FLARE,
      priority = 7,
      rangeSq = 40.0 * 40.0,
      accent = 0xFFFF4545.toInt(),
      statLines = listOf("+125% Mana Regen", "+30 Vitality", "+25 True Def", "+10 Ferocity", "+5 Bonus AS"),
      flareTextureId = "c0062cc98ebda72a6a4b89783adcef2815b483a01d73ea87b3df76072a89d13b",
      previewItem = ItemStack(Items.FIRE_CHARGE)
    ),
    DeployableSpec(
      key = "umberella",
      displayPrefix = "Umberella ",
      compactName = "UMBERELLA",
      category = DeployableCategory.MISC,
      priority = 8,
      rangeSq = 30.0 * 30.0,
      accent = 0xFF59E1A7.toInt(),
      statLines = listOf("+5 Trophy Fish"),
      previewItem = ItemStack(Items.FISHING_ROD)
    ),
    DeployableSpec(
      key = "totem_of_corruption",
      displayPrefix = "Totem of Corruption",
      compactName = "TOTEM",
      category = DeployableCategory.TOTEM,
      priority = 9,
      rangeSq = 30.0 * 30.0,
      accent = 0xFF8E5CFF.toInt(),
      statLines = listOf("Own Totem Active"),
      previewItem = ItemStack(Items.WITHER_ROSE)
    ),
    DeployableSpec(
      key = "dwarven_lantern",
      displayPrefix = "Dwarven Lantern ",
      compactName = "DWARVEN",
      category = DeployableCategory.LANTERN,
      priority = 10,
      rangeSq = 30.0 * 30.0,
      accent = 0xFFFFD36A.toInt(),
      statLines = listOf("+20 Mining Speed"),
      previewItem = ItemStack(Items.LANTERN)
    ),
    DeployableSpec(
      key = "mithril_lantern",
      displayPrefix = "Mithril Lantern ",
      compactName = "MITHRIL",
      category = DeployableCategory.LANTERN,
      priority = 11,
      rangeSq = 30.0 * 30.0,
      accent = 0xFF63D3FF.toInt(),
      statLines = listOf("+40 Mining Speed", "+10 Mining Fortune"),
      previewItem = ItemStack(Items.SOUL_LANTERN)
    ),
    DeployableSpec(
      key = "titanium_lantern",
      displayPrefix = "Titanium Lantern ",
      compactName = "TITANIUM",
      category = DeployableCategory.LANTERN,
      priority = 12,
      rangeSq = 30.0 * 30.0,
      accent = 0xFFC7D2E2.toInt(),
      statLines = listOf("+60 Mining Speed", "+15 Mining Fortune", "+5 Heat Res"),
      previewItem = ItemStack(Items.SOUL_LANTERN)
    ),
    DeployableSpec(
      key = "glacite_lantern",
      displayPrefix = "Glacite Lantern ",
      compactName = "GLACITE",
      category = DeployableCategory.LANTERN,
      priority = 13,
      rangeSq = 30.0 * 30.0,
      accent = 0xFF86EEFF.toInt(),
      statLines = listOf("+80 Mining Speed", "+20 Mining Fortune", "+10 Heat Res", "+5 Cold Res"),
      previewItem = ItemStack(Items.SOUL_LANTERN)
    ),
    DeployableSpec(
      key = "will_o_wisp",
      displayPrefix = "Will-o'-wisp ",
      compactName = "WISP",
      category = DeployableCategory.LANTERN,
      priority = 14,
      rangeSq = 30.0 * 30.0,
      accent = 0xFF65FFB4.toInt(),
      statLines = listOf("+100 Mining Speed", "+25 Mining Fortune", "+20 Heat Res", "+10 Cold Res", "+2.5 Gem Spread"),
      previewItem = ItemStack(Items.SOUL_LANTERN)
    )
  )

  private val deployableByPrefix = deployableSpecs.associateBy { it.displayPrefix.lowercase(Locale.ROOT) }
  private val deployableByFlareTexture = deployableSpecs
    .mapNotNull { spec -> spec.flareTextureId?.let { it to spec } }
    .toMap()

  private val previewDeployable = ActiveDeployable(
    spec = deployableSpecs.last(),
    seconds = 300,
    iconStack = deployableSpecs.last().previewItem.copy()
  )

  private val deployableHud = hudElement("deployable-hud", "Deployable HUD", "Shows the active SkyBlock deployable") {
    anchor = HudAnchor.TOP_RIGHT
    offsetX = 10f
    offsetY = 96f

    width { baseWidthFor(currentDisplayDeployable()) }
    height { baseHeightFor(currentDisplayDeployable()) }

    render { _, _, _ ->
      if (!enabledSetting.value) return@render
      val active = currentDisplayDeployable() ?: return@render
      renderHudCard(active)
    }

    postRender { screenX, screenY, scale ->
      if (!enabledSetting.value) return@postRender
      val active = currentDisplayDeployable() ?: return@postRender
      renderHudIcon(active, screenX, screenY, scale)
    }
  }

  init {
    addSetting(enabledSetting, displayStyle, liveIconSetting, previewInEditor, showStatsInCompact)
    EventBus.register(this)
  }

  @SubscribeEvent
  fun onTick(@Suppress("UNUSED_PARAMETER") event: TickEvent.End) {
    if (!enabledSetting.value) {
      activeDeployable = null
      return
    }

    val player = mc.player
    val level = mc.level
    if (player == null || level == null) {
      activeDeployable = null
      return
    }

    var best: ActiveDeployable? = null
    for (entity in level.entitiesForRendering()) {
      val stand = entity as? ArmorStand ?: continue
      val detected = detectDeployable(stand, player) ?: continue
      val currentBest = best
      if (currentBest == null ||
        detected.spec.priority > currentBest.spec.priority ||
        (detected.spec.priority == currentBest.spec.priority && detected.seconds > currentBest.seconds)
      ) {
        best = detected
      }
    }
    activeDeployable = best
  }

  private fun currentDisplayDeployable(): ActiveDeployable? {
    return activeDeployable ?: if (HudModuleManager.isEditorOpen && previewInEditor.value) previewDeployable else null
  }

  private fun detectDeployable(stand: ArmorStand, player: Player): ActiveDeployable? {
    val customName = sanitizeName(stand.customName?.string ?: "")
    if (customName.isNotBlank()) {
      return detectNamedDeployable(stand, player, customName)
    }
    if (!stand.isInvisible) return null
    return detectFlareDeployable(stand, player)
  }

  private fun detectNamedDeployable(stand: ArmorStand, player: Player, displayName: String): ActiveDeployable? {
    val lowered = displayName.lowercase(Locale.ROOT)
    val spec = deployableByPrefix.entries.firstOrNull { lowered.startsWith(it.key) }?.value ?: return null
    if (player.distanceToSqr(stand) > spec.rangeSq) return null

    return if (spec.category == DeployableCategory.TOTEM) {
      detectTotemDeployable(stand, player, spec)
    } else {
      val seconds = DEPLOYABLE_SECONDS_REGEX.find(displayName)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return null
      val icon = findNearbyHeadItem(stand) ?: spec.previewItem.copy()
      ActiveDeployable(spec, seconds, icon, stand.uuid)
    }
  }

  private fun detectTotemDeployable(stand: ArmorStand, player: Player, spec: DeployableSpec): ActiveDeployable? {
    val level = mc.level ?: return null
    val ownerLine = "owner: ${player.gameProfile.name.lowercase(Locale.ROOT)}"

    var seconds: Int? = null
    var ownedByPlayer = false
    var icon: ItemStack? = null

    for (entity in level.entitiesForRendering()) {
      val nearby = entity as? ArmorStand ?: continue
      if (abs(nearby.x - stand.x) > 0.15 || abs(nearby.z - stand.z) > 0.15 || abs(nearby.y - stand.y) > 1.2) continue

      val text = sanitizeName(nearby.customName?.string ?: "")
      if (text.isNotBlank()) {
        val match = TOTEM_SECONDS_REGEX.find(text)
        if (match != null) {
          val minutes = match.groupValues.getOrNull(1)?.toIntOrNull() ?: 0
          val secs = match.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
          seconds = minutes * 60 + secs
        }
        if (text.lowercase(Locale.ROOT) == ownerLine) {
          ownedByPlayer = true
        }
      }

      val head = nearby.getItemBySlot(EquipmentSlot.HEAD)
      if (!head.isEmpty && icon == null) {
        icon = head.copy()
      }
    }

    if (!ownedByPlayer || seconds == null) return null
    return ActiveDeployable(spec, seconds, icon ?: spec.previewItem.copy(), stand.uuid)
  }

  private fun detectFlareDeployable(stand: ArmorStand, player: Player): ActiveDeployable? {
    val head = stand.getItemBySlot(EquipmentSlot.HEAD)
    if (head.isEmpty) return null
    val textureId = extractSkullTextureId(head) ?: return null
    val spec = deployableByFlareTexture[textureId] ?: return null
    if (player.distanceToSqr(stand) > spec.rangeSq) return null
    val seconds = (180 - (stand.tickCount / 20)).coerceAtLeast(0)
    return ActiveDeployable(spec, seconds, head.copy(), stand.uuid)
  }

  private fun findNearbyHeadItem(anchor: ArmorStand): ItemStack? {
    val level = mc.level ?: return null
    for (entity in level.entitiesForRendering()) {
      val stand = entity as? ArmorStand ?: continue
      if (abs(stand.x - anchor.x) > 0.15 || abs(stand.z - anchor.z) > 0.15 || abs(stand.y - anchor.y) > 1.1) continue
      val head = stand.getItemBySlot(EquipmentSlot.HEAD)
      if (!head.isEmpty) return head.copy()
    }
    return null
  }

  private fun sanitizeName(raw: String): String {
    return (ChatFormatting.stripFormatting(raw) ?: raw).trim()
  }

  private fun extractSkullTextureId(stack: ItemStack): String? {
    val profile = stack.get(DataComponents.PROFILE) ?: return null
    val textures = profile.partialProfile().properties()["textures"]
    val value = textures.firstOrNull()?.value() ?: return null
    val decoded = runCatching { String(Base64.getDecoder().decode(value)) }.getOrNull() ?: return null
    val urlKey = "\"url\""
    val keyIndex = decoded.indexOf(urlKey)
    if (keyIndex < 0) return null
    val colonIndex = decoded.indexOf(':', keyIndex)
    val startQuote = decoded.indexOf('"', colonIndex + 1) + 1
    if (startQuote <= 0) return null
    val endQuote = decoded.indexOf('"', startQuote)
    if (endQuote <= startQuote) return null
    val url = decoded.substring(startQuote, endQuote)
    return url.substringAfterLast('/').ifBlank { null }
  }

  private fun baseWidthFor(active: ActiveDeployable?): Float {
    val display = active ?: previewDeployable
    return if (displayStyle.value == 0) {
      max(
        COMPACT_MIN_WIDTH,
        ICON_BOX + PAD * 3f + max(
          NVGRenderer.textWidth(display.spec.compactName, NAME_TEXT_SIZE),
          NVGRenderer.textWidth(formatSeconds(display.seconds), TIME_TEXT_SIZE)
        )
      )
    } else {
      DETAILED_WIDTH
    }
  }

  private fun baseHeightFor(active: ActiveDeployable?): Float {
    val display = active ?: previewDeployable
    return if (displayStyle.value == 0) {
      COMPACT_HEIGHT
    } else {
      val rows = max(1, ceil(display.spec.statLines.size / 2.0).toInt())
      PAD * 2f + HEADER_HEIGHT + if (rows > 0) STAT_TOP_GAP + rows * CHIP_HEIGHT + (rows - 1).coerceAtLeast(0) * CHIP_GAP else 0f
    }
  }

  private fun renderHudCard(active: ActiveDeployable) {
    val theme = ThemeManager.currentTheme
    val accent = active.spec.accent
    val width = baseWidthFor(active)
    val height = baseHeightFor(active)

    NVGRenderer.rect(4f, 5f, width, height, 0x24000000, CORNER + 2f)
    NVGRenderer.rect(2f, 2f, width, height, 0x12000000, CORNER + 1f)

    NVGRenderer.rect(0f, 0f, width, height, 0xEA141924.toInt(), CORNER)
    NVGRenderer.gradientRect(0f, 0f, width, height * 0.55f, withAlpha(accent, 18), 0x00000000, Gradient.TopToBottom, CORNER)
    NVGRenderer.hollowRect(0f, 0f, width, height, 1f, withAlpha(accent, 105), CORNER)

    NVGRenderer.rect(PAD, PAD, ICON_BOX, ICON_BOX, 0xFF222C3C.toInt(), 7f)
    NVGRenderer.hollowRect(PAD, PAD, ICON_BOX, ICON_BOX, 1f, withAlpha(accent, 80), 7f)

    val headerX = PAD + ICON_BOX + 8f
    val headerW = width - headerX - PAD
    NVGRenderer.textShadow(
      ellipsize(active.spec.compactName, headerW, NAME_TEXT_SIZE),
      headerX,
      PAD + 1f,
      NAME_TEXT_SIZE,
      theme.textPrimary
    )

    val timeText = formatSeconds(active.seconds)
    val timeWidth = NVGRenderer.textWidth(timeText, TIME_TEXT_SIZE)
    NVGRenderer.text(
      timeText,
      width - PAD - timeWidth,
      PAD + 2f,
      TIME_TEXT_SIZE,
      accent
    )

    if (displayStyle.value == 0) {
      val subtitle = if (showStatsInCompact.value) {
        active.spec.statLines.firstOrNull().orEmpty()
      } else {
        active.spec.category.name.lowercase(Locale.ROOT).replaceFirstChar {
          if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
        }
      }
      if (subtitle.isNotBlank()) {
        NVGRenderer.text(
          ellipsize(subtitle, headerW, SUBTITLE_TEXT_SIZE),
          headerX,
          PAD + 16f,
          SUBTITLE_TEXT_SIZE,
          theme.textSecondary
        )
      }
      return
    }

    val stats = active.spec.statLines
    if (stats.isEmpty()) return

    val chipYStart = PAD + HEADER_HEIGHT + STAT_TOP_GAP
    val chipWidth = (width - PAD * 2f - CHIP_GAP) / 2f
    for ((index, stat) in stats.withIndex()) {
      val row = index / 2
      val col = index % 2
      val chipX = PAD + col * (chipWidth + CHIP_GAP)
      val chipY = chipYStart + row * (CHIP_HEIGHT + CHIP_GAP)
      renderStatChip(chipX, chipY, chipWidth, stat, accent, theme.text)
    }
  }

  private fun renderStatChip(x: Float, y: Float, w: Float, value: String, accent: Int, textColor: Int) {
    NVGRenderer.rect(x, y, w, CHIP_HEIGHT, withAlpha(accent, 22), 5f)
    NVGRenderer.hollowRect(x, y, w, CHIP_HEIGHT, 1f, withAlpha(accent, 80), 5f)
    val text = ellipsize(value, w - 8f, CHIP_TEXT_SIZE)
    NVGRenderer.text(text, x + 4f, y + 3f, CHIP_TEXT_SIZE, textColor)
  }

  private fun renderHudIcon(active: ActiveDeployable, screenX: Float, screenY: Float, scale: Float) {
    val graphics = GuiRenderContext.getGraphics() ?: return
    val stack = when {
      liveIconSetting.value && !active.iconStack.isEmpty -> active.iconStack
      else -> active.spec.previewItem
    }
    if (stack.isEmpty) return

    val guiScale = mc.window.guiScale.toFloat()
    if (guiScale <= 0f) return

    val originX = screenX / guiScale
    val originY = screenY / guiScale
    val renderScale = scale / guiScale
    val localX = PAD + (ICON_BOX - 16f) / 2f
    val localY = PAD + (ICON_BOX - 16f) / 2f

    val pose = graphics.pose()
    pose.pushMatrix()
    pose.translate(originX + localX * renderScale, originY + localY * renderScale)
    pose.scale(renderScale, renderScale)
    graphics.renderItem(stack, 0, 0)
    pose.popMatrix()
  }

  private fun ellipsize(text: String, maxWidth: Float, size: Float): String {
    if (NVGRenderer.textWidth(text, size) <= maxWidth) return text
    var end = text.length
    while (end > 1) {
      val candidate = text.substring(0, end).trimEnd() + "..."
      if (NVGRenderer.textWidth(candidate, size) <= maxWidth) {
        return candidate
      }
      end--
    }
    return "..."
  }

  private fun formatSeconds(totalSeconds: Int): String {
    val seconds = totalSeconds.coerceAtLeast(0)
    val minutes = seconds / 60
    val remainder = seconds % 60
    return if (minutes > 0) {
      "%d:%02d".format(minutes, remainder)
    } else {
      "${seconds}s"
    }
  }

  private fun withAlpha(color: Int, alpha: Int): Int {
    val base = Color(color, true)
    return Color(base.red, base.green, base.blue, alpha.coerceIn(0, 255)).rgb
  }

  private val DEPLOYABLE_SECONDS_REGEX = Regex(""".*?(\d+)s$""")
  private val TOTEM_SECONDS_REGEX = Regex("""Remaining:\s*(?:(\d{1,2})m\s*)?(\d{1,2})s""", RegexOption.IGNORE_CASE)

  private const val PAD = 8f
  private const val CORNER = 9f
  private const val ICON_BOX = 28f
  private const val HEADER_HEIGHT = 28f
  private const val STAT_TOP_GAP = 6f
  private const val CHIP_HEIGHT = 16f
  private const val CHIP_GAP = 4f
  private const val COMPACT_HEIGHT = 44f
  private const val COMPACT_MIN_WIDTH = 132f
  private const val DETAILED_WIDTH = 196f
  private const val NAME_TEXT_SIZE = 11f
  private const val TIME_TEXT_SIZE = 13f
  private const val SUBTITLE_TEXT_SIZE = 9f
  private const val CHIP_TEXT_SIZE = 8.5f
}
