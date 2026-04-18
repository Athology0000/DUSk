package org.cobalt.internal.ui.components.settings

import net.minecraft.client.Minecraft
import org.cobalt.api.ui.theme.ThemeManager
import org.cobalt.api.util.ui.NVGRenderer
import org.cobalt.api.util.ui.helper.Image
import org.cobalt.internal.combat.PowerOrbCard
import org.cobalt.internal.combat.SlayerLoadoutSetting
import org.cobalt.internal.combat.SpiderBowCard
import org.cobalt.internal.combat.SpiderSwordCard
import org.cobalt.internal.combat.UtilityCard
import org.cobalt.internal.ui.UIComponent
import org.cobalt.internal.ui.util.isHoveringOver

internal class UISlayerLoadoutSetting(
  private val setting: SlayerLoadoutSetting,
) : UIComponent(
  x = 0F,
  y = 0F,
  width = 627.5F,
  height = 860F,
) {

  private data class CardHitbox(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val onClick: () -> Unit,
  )

  private val mc = Minecraft.getInstance()
  private val imageCache = mutableMapOf<String, Image?>()
  private var cardHitboxes: List<CardHitbox> = emptyList()

  override fun render() {
    height = if (setting.selectedLoadoutType() == SPIDER_TYPE_INDEX) 860F else 620F
    cardHitboxes = buildList {
      NVGRenderer.rect(x, y, width, height, ThemeManager.currentTheme.controlBg, 10F)
      NVGRenderer.hollowRect(x, y, width, height, 1F, ThemeManager.currentTheme.controlBorder, 10F)

      val contentX = x + 20F
      val contentWidth = width - 40F
      var cursorY = y + 18F

      NVGRenderer.text("Visual Loadout", contentX, cursorY, 16F, ThemeManager.currentTheme.text)
      NVGRenderer.text(
        "Use the cards below to pick common items without editing keyword fields.",
        contentX,
        cursorY + 20F,
        12F,
        ThemeManager.currentTheme.textSecondary
      )
      cursorY += 48F

      if (setting.selectedLoadoutType() == SPIDER_TYPE_INDEX) {
        cursorY = renderChoiceSection(
          title = "Spider Sword",
          subtitle = "Pick an exact spider sword or let the macro use the left-most sword.",
          cards = SpiderSwordCard.entries.map { card ->
            VisualCard(
              label = card.label,
              imagePath = card.imagePath,
              selected = setting.selectedSpiderSword() == card,
              detected = hotbarHasAny(card.hotbarKeywords),
              badge = if (card == SpiderSwordCard.AUTODETECT) "Left-most sword" else "Exact item",
              interactive = true,
              onClick = { setting.selectSpiderSword(card) }
            )
          },
          startY = cursorY,
          columns = 4,
          cardWidth = 132F,
          cardHeight = 118F,
          contentX = contentX,
          hitboxes = this
        )

        cursorY = renderChoiceSection(
          title = "Spider Bow",
          subtitle = "Pick a bow card or let the macro use the left-most bow in your hotbar.",
          cards = SpiderBowCard.entries.map { card ->
            VisualCard(
              label = card.label,
              imagePath = card.imagePath,
              selected = setting.selectedSpiderBow() == card,
              detected = hotbarHasAny(card.hotbarKeywords),
              badge = if (card == SpiderBowCard.AUTODETECT) "Left-most bow" else "Exact item",
              interactive = true,
              onClick = { setting.selectSpiderBow(card) }
            )
          },
          startY = cursorY,
          columns = 4,
          cardWidth = 132F,
          cardHeight = 118F,
          contentX = contentX,
          hitboxes = this
        )
      } else {
        cursorY = renderInfoBlock(
          contentX = contentX,
          contentWidth = contentWidth,
          startY = cursorY,
          title = "Type-Specific Cards",
          text = "Spider loadout cards are wired now. Other slayers still use the Slayer Weapons tab for exact weapon picks."
        )
      }

      cursorY = renderChoiceSection(
        title = "Power Orb",
        subtitle = "Select one orb card to use on boss spawn. Click the selected card again to disable it.",
        cards = PowerOrbCard.entries.map { card ->
          VisualCard(
            label = card.label,
            imagePath = card.imagePath,
            selected = setting.selectedPowerOrb() == card,
            detected = hotbarHasAny(card.hotbarKeywords),
            badge = if (setting.selectedPowerOrb() == card) "Enabled" else "Orb",
            interactive = true,
            onClick = { setting.togglePowerOrb(card) }
          )
        },
        startY = cursorY,
        columns = 4,
        cardWidth = 132F,
        cardHeight = 112F,
        contentX = contentX,
        hitboxes = this
      )

      renderUtilitySection(contentX, cursorY, this)
    }
  }

  override fun mouseClicked(button: Int): Boolean {
    if (button != 0) return false
    val hit = cardHitboxes.firstOrNull { hitbox ->
      isHoveringOver(hitbox.x, hitbox.y, hitbox.width, hitbox.height)
    } ?: return false
    hit.onClick()
    return true
  }

  private fun renderUtilitySection(contentX: Float, startY: Float, hitboxes: MutableList<CardHitbox>) {
    NVGRenderer.text("Utility Items", contentX, startY, 15F, ThemeManager.currentTheme.text)
    NVGRenderer.text(
      "Clickable cards toggle the existing automation. Wither Cloak is shown as a tracked hotbar item only.",
      contentX,
      startY + 18F,
      12F,
      ThemeManager.currentTheme.textSecondary
    )

    UtilityCard.entries.forEachIndexed { index, card ->
      val col = index % 4
      val row = index / 4
      val cardX = contentX + col * (132F + CARD_GAP)
      val cardY = startY + 46F + row * (112F + CARD_GAP)
      val selected = setting.utilityEnabled(card)
      val detected = hotbarHasAny(card.hotbarKeywords)
      drawCard(
        x = cardX,
        y = cardY,
        w = 132F,
        h = 112F,
        label = card.label,
        imagePath = card.imagePath,
        selected = selected,
        detected = detected,
        badge = when {
          card.interactive && selected -> "Enabled"
          card.interactive -> "Optional"
          detected -> "Tracked"
          else -> "Visual only"
        },
        interactive = card.interactive
      )
      if (card.interactive) {
        hitboxes += CardHitbox(cardX, cardY, 132F, 112F) { setting.toggleUtility(card) }
      }
    }
  }

  private fun renderInfoBlock(
    contentX: Float,
    contentWidth: Float,
    startY: Float,
    title: String,
    text: String,
  ): Float {
    val blockHeight = 88F
    NVGRenderer.rect(contentX, startY, contentWidth, blockHeight, ThemeManager.currentTheme.panel, 8F)
    NVGRenderer.hollowRect(contentX, startY, contentWidth, blockHeight, 1F, ThemeManager.currentTheme.controlBorder, 8F)
    NVGRenderer.text(title, contentX + 14F, startY + 14F, 14F, ThemeManager.currentTheme.text)
    NVGRenderer.drawWrappedString(
      text,
      contentX + 14F,
      startY + 34F,
      contentWidth - 28F,
      12F,
      ThemeManager.currentTheme.textSecondary
    )
    return startY + blockHeight + 18F
  }

  private fun renderChoiceSection(
    title: String,
    subtitle: String,
    cards: List<VisualCard>,
    startY: Float,
    columns: Int,
    cardWidth: Float,
    cardHeight: Float,
    contentX: Float,
    hitboxes: MutableList<CardHitbox>,
  ): Float {
    NVGRenderer.text(title, contentX, startY, 15F, ThemeManager.currentTheme.text)
    NVGRenderer.text(subtitle, contentX, startY + 18F, 12F, ThemeManager.currentTheme.textSecondary)

    val cardsTop = startY + 42F
    val rows = ((cards.size + columns - 1) / columns)
    cards.forEachIndexed { index, card ->
      val col = index % columns
      val row = index / columns
      val cardX = contentX + col * (cardWidth + CARD_GAP)
      val cardY = cardsTop + row * (cardHeight + CARD_GAP)
      drawCard(
        x = cardX,
        y = cardY,
        w = cardWidth,
        h = cardHeight,
        label = card.label,
        imagePath = card.imagePath,
        selected = card.selected,
        detected = card.detected,
        badge = card.badge,
        interactive = card.interactive
      )
      if (card.interactive) {
        hitboxes += CardHitbox(cardX, cardY, cardWidth, cardHeight) { card.onClick() }
      }
    }

    return cardsTop + rows * cardHeight + (rows - 1).coerceAtLeast(0) * CARD_GAP + 18F
  }

  private fun drawCard(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    label: String,
    imagePath: String,
    selected: Boolean,
    detected: Boolean,
    badge: String,
    interactive: Boolean,
  ) {
    val hovering = isHoveringOver(x, y, w, h)
    val background = when {
      selected -> ThemeManager.currentTheme.selectedOverlay
      hovering && interactive -> ThemeManager.currentTheme.overlay
      else -> ThemeManager.currentTheme.panel
    }
    val border = when {
      selected -> ThemeManager.currentTheme.accent
      hovering && interactive -> ThemeManager.currentTheme.textPrimary
      else -> ThemeManager.currentTheme.controlBorder
    }
    val badgeColor = when {
      selected -> ThemeManager.currentTheme.accent
      detected -> ThemeManager.currentTheme.successIcon
      !interactive -> ThemeManager.currentTheme.textDisabled
      else -> ThemeManager.currentTheme.textSecondary
    }

    NVGRenderer.rect(x, y, w, h, background, 9F)
    NVGRenderer.hollowRect(x, y, w, h, 1.5F, border, 9F)

    val image = resolveImage(imagePath)
    if (image != null) {
      NVGRenderer.image(image, x + 30F, y + 12F, w - 60F, 44F, 6F)
    } else {
      NVGRenderer.rect(x + 30F, y + 12F, w - 60F, 44F, ThemeManager.currentTheme.controlBg, 6F)
      NVGRenderer.hollowRect(x + 30F, y + 12F, w - 60F, 44F, 1F, ThemeManager.currentTheme.controlBorder, 6F)
      NVGRenderer.text(label.take(2).uppercase(), x + w / 2F - 10F, y + 25F, 16F, ThemeManager.currentTheme.textSecondary)
    }

    NVGRenderer.drawWrappedString(
      label,
      x + 10F,
      y + 64F,
      w - 20F,
      12F,
      ThemeManager.currentTheme.text
    )
    NVGRenderer.text(badge, x + 10F, y + h - 18F, 11F, badgeColor)
    if (detected) {
      NVGRenderer.text("In hotbar", x + w - 55F, y + h - 18F, 11F, ThemeManager.currentTheme.successIcon)
    }
  }

  private fun hotbarHasAny(keywords: Array<String>): Boolean {
    val player = mc.player ?: return false
    val normalizedKeywords = keywords.map { keyword -> SlayerLoadoutSetting.normalize(keyword) }
    for (slot in 0..8) {
      val stack = player.inventory.getItem(slot)
      if (stack.isEmpty) continue
      val name = SlayerLoadoutSetting.normalize(stack.hoverName.string)
      if (normalizedKeywords.any { keyword -> name.contains(keyword) }) {
        return true
      }
    }
    return false
  }

  private fun resolveImage(path: String): Image? {
    return imageCache.getOrPut(path) {
      runCatching { NVGRenderer.createImage(path) }.getOrNull()
    }
  }

  private data class VisualCard(
    val label: String,
    val imagePath: String,
    val selected: Boolean,
    val detected: Boolean,
    val badge: String,
    val interactive: Boolean,
    val onClick: () -> Unit,
  )

  companion object {
    private const val CARD_GAP = 12F
    private const val SPIDER_TYPE_INDEX = 2
  }
}
