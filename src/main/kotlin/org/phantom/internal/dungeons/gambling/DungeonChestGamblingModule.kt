package org.phantom.internal.dungeons.gambling

import java.util.Locale
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.pow
import kotlin.math.sqrt
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.util.Mth
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.CustomData
import org.phantom.api.module.Module
import org.phantom.api.module.setting.impl.CheckboxSetting
import org.phantom.api.module.setting.impl.SliderSetting
import org.phantom.api.module.setting.inGroup
import org.phantom.api.util.getSkyblockApiId
import org.phantom.api.util.getSkyblockId
import org.phantom.api.event.EventBus
import org.phantom.api.event.annotation.SubscribeEvent
import org.phantom.api.event.impl.client.ContainerMouseEvent
import org.phantom.api.event.impl.client.ContainerSlotClickEvent
import org.phantom.api.event.impl.client.ScreenKeyEvent
import org.phantom.api.event.impl.client.ScreenOpenEvent
import org.phantom.api.event.impl.render.ScreenDrawEvent
import org.phantom.internal.qol.SkyblockPriceService

object DungeonChestGamblingModule : Module("Dungeon Chest Gambling") {

  private val enabled = CheckboxSetting(
    "Enabled",
    "Play a CSGO-style roll animation over Obsidian and Bedrock dungeon reward chests.",
    false,
  ).inGroup("General")

  private val croesus = CheckboxSetting(
    "Croesus",
    "Also play the animation in Croesus chest menus.",
    true,
  ).inGroup("General")

  private val rollTime = SliderSetting(
    "Roll Time",
    "Seconds the case roll takes before revealing the reward.",
    5.0,
    1.0,
    12.0,
    step = 0.25,
  ).inGroup("General")

  private val items = mutableListOf<ItemStack>()
  private var randomOffset = 0
  private var startMs = 0L
  private var lastSound = 0
  private var activeMenuId: Int? = null

  init {
    addSetting(enabled, croesus, rollTime)
    EventBus.register(this)
  }

  @SubscribeEvent
  fun onScreenOpen(event: ScreenOpenEvent) {
    onScreenChanged(event.screen)
  }

  @SubscribeEvent
  fun onScreenDraw(event: ScreenDrawEvent) {
    if (renderScreen(event.screen, event.graphics)) {
      event.setCancelled(true)
    }
  }

  @SubscribeEvent
  fun onScreenKey(event: ScreenKeyEvent) {
    if (!event.released && onKeyPressed(event.key.key())) {
      event.setCancelled(true)
    }
  }

  @SubscribeEvent
  fun onContainerMouse(event: ContainerMouseEvent) {
    if (isRendering()) {
      event.setCancelled(true)
    }
  }

  @SubscribeEvent
  fun onContainerSlotClick(event: ContainerSlotClickEvent) {
    if (isRendering()) {
      event.setCancelled(true)
    }
  }

  @JvmStatic
  fun renderScreen(screen: Screen, graphics: GuiGraphics): Boolean {
    if (!enabled.value) {
      cancel()
      return false
    }

    val containerScreen = screen as? AbstractContainerScreen<*> ?: run {
      cancel()
      return false
    }

    if (containerScreen.menu.containerId != activeMenuId) {
      tryStart(containerScreen)
    }

    if (containerScreen.menu.containerId != activeMenuId) return false
    return render(graphics)
  }

  @JvmStatic
  fun isRendering(): Boolean =
    enabled.value && activeMenuId != null && items.isNotEmpty() && startMs > 0L

  @JvmStatic
  fun onScreenChanged(screen: Screen?) {
    if (screen !is AbstractContainerScreen<*>) {
      cancel()
    }
  }

  @JvmStatic
  fun onKeyPressed(key: Int): Boolean {
    if (!isRendering()) return false
    if (key == 256) cancel()
    return true
  }

  @JvmStatic
  fun cancel() {
    startMs = 0L
    lastSound = 0
    activeMenuId = null
    items.clear()
  }

  private fun tryStart(screen: AbstractContainerScreen<*>) {
    cancel()

    val title = screen.title.string.stripFormatting().trim()
    val chestType = chestTypeFromTitle(title) ?: run {
      debug { "Skipping menu '$title': not a supported dungeon reward chest." }
      return
    }
    if (!croesus.value && chestType == CROESUS_TYPE) {
      debug { "Skipping Croesus menu '$title' because Croesus is disabled." }
      return
    }

    val candidates = rewardItems(screen)
    if (candidates.isEmpty()) {
      debug { "No reward candidates found in '$title'." }
      return
    }

    val winner = candidates.maxByOrNull(::estimatedValue) ?: candidates.first()
    debug { "Starting roll for '$title' with ${candidates.size} candidates; winner preview ${winner.hoverName.string.stripFormatting()}." }
    init(screen.menu.containerId, candidates, winner)
  }

  private fun init(menuId: Int, candidates: List<ItemStack>, winner: ItemStack) {
    activeMenuId = menuId
    startMs = System.currentTimeMillis()
    lastSound = 0
    randomOffset = ((4 * ITEM_SCALE) + ThreadLocalRandom.current().nextInt(4 * ITEM_SCALE)) *
      if (ThreadLocalRandom.current().nextBoolean()) 1 else -1
    items.clear()

    repeat(ITEM_SIZE) {
      items.add(candidates[ThreadLocalRandom.current().nextInt(candidates.size)].copy())
    }
    items[WINNER_INDEX] = winner.copy()
  }

  private fun render(graphics: GuiGraphics): Boolean {
    if (items.isEmpty() || startMs <= 0L) return false

    graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), 0x80000000.toInt())

    val rawProgress = ((System.currentTimeMillis() - startMs).toFloat() / (rollTime.value.toFloat() * 1000f))
    val progress = (rawProgress + 0.25f).coerceIn(0f, 1f)
    val endOffset = (WINNER_INDEX * FULL_CARD_WIDTH) * ease(progress)

    if (progress >= 0.96f && rawProgress >= 1f) {
      cancel()
      return false
    }

    val soundIndex = endOffset.toInt() / FULL_CARD_WIDTH
    if (soundIndex > lastSound) {
      Minecraft.getInstance().player?.playSound(SoundEvents.ITEM_PICKUP, 1f, 2f)
      lastSound = soundIndex
    }

    graphics.pose().pushMatrix()
    graphics.pose().translate(
      graphics.guiWidth() / 2f - endOffset - ((8 * ITEM_SCALE) + randomOffset),
      (graphics.guiHeight() - FULL_CARD_HEIGHT) / 2f,
    )

    items.forEachIndexed { index, item ->
      graphics.pose().pushMatrix()
      graphics.pose().translate((index * FULL_CARD_WIDTH).toFloat(), 0f)
      graphics.pose().scale(ITEM_SCALE.toFloat(), ITEM_SCALE.toFloat())
      renderCard(graphics, item, rarityColor(item))
      graphics.pose().popMatrix()
    }
    graphics.pose().popMatrix()

    val markerX = graphics.guiWidth() / 2 - 1
    val markerY = (graphics.guiHeight() + FULL_CARD_HEIGHT) / 2
    graphics.fill(markerX, markerY, markerX + 2, markerY + (DUNGEON_CARD_HEIGHT * ITEM_SCALE * 0.25).toInt(), 0xFFFF5555.toInt())

    if (progress >= 0.96f) {
      val winner = items[WINNER_INDEX].hoverName
      val font = Minecraft.getInstance().font
      val length = font.width(winner)
      val scale = Mth.lerp((progress - 0.96f) / 0.04f, 1f, 3f)

      graphics.pose().pushMatrix()
      graphics.pose().translate(graphics.guiWidth() / 2f, graphics.guiHeight() * 0.2f)
      graphics.pose().scale(scale, scale)
      graphics.pose().translate(-length / 2f, 0f)
      graphics.drawString(font, winner, 0, 0, 0xFFFFFFFF.toInt(), true)
      graphics.pose().popMatrix()
    }

    return true
  }

  private fun renderCard(graphics: GuiGraphics, item: ItemStack, color: Int) {
    graphics.fillGradient(0, 0, DUNGEON_CARD_WIDTH, DUNGEON_CARD_HEIGHT - 1, BACKGROUND_COLOR, alpha(color, 0x60))
    graphics.fill(0, DUNGEON_CARD_HEIGHT - 1, DUNGEON_CARD_WIDTH, DUNGEON_CARD_HEIGHT, color)
    graphics.renderItem(item, DUNGEON_CARD_WIDTH / 2 - 8, DUNGEON_CARD_HEIGHT / 2 - 8)
  }

  private fun rewardItems(screen: AbstractContainerScreen<*>): List<ItemStack> {
    SkyblockPriceService.refreshIfNeeded(120L)
    val slots = screen.menu.slots.filterNot { it.container is Inventory }
    val skyblockItems = slots.map { it.item }
      .filter { stack -> !stack.isEmpty && stack.getSkyblockId().isNotBlank() }
      .filterNot(::isNavigationItem)
      .distinctBy { it.getSkyblockApiId().ifBlank { it.hoverName.string.stripFormatting() } }

    if (skyblockItems.isNotEmpty()) return skyblockItems

    val loreItems = rewardItemsFromOpenChestLore(slots.map { it.item })
    if (loreItems.isNotEmpty()) return loreItems

    return slots.map { it.item }
      .filter { stack -> !stack.isEmpty }
      .filterNot(::isNavigationItem)
      .distinctBy { it.hoverName.string.stripFormatting() }
  }

  private fun estimatedValue(stack: ItemStack): Double {
    val internalId = stack.getSkyblockId()
    val apiId = stack.getSkyblockApiId().ifBlank { internalId }
    if (apiId.isBlank()) return 0.0
    val bazaar = SkyblockPriceService.getBazaarQuote(apiId)
    return listOfNotNull(
      bazaar?.buyPrice,
      bazaar?.sellPrice,
      SkyblockPriceService.getLowestBin(apiId),
      SkyblockPriceService.getNpcSellPrice(internalId),
    ).maxOrNull() ?: 0.0
  }

  private fun chestTypeFromTitle(title: String): String? {
    val directType = DUNGEON_CHEST_TITLE.matchEntire(title)?.groupValues?.getOrNull(1)
    if (directType.equals("Obsidian", ignoreCase = true)) return "Obsidian"
    if (directType.equals("Bedrock", ignoreCase = true)) return "Bedrock"
    if (isCroesusTitle(title)) return CROESUS_TYPE
    return null
  }

  private fun isCroesusTitle(title: String): Boolean =
    CROESUS_TITLE.matches(title)

  private fun rewardItemsFromOpenChestLore(stacks: List<ItemStack>): List<ItemStack> {
    val chestStack = stacks.firstOrNull { stack ->
      !stack.isEmpty && stack.hoverName.string.stripFormatting().trim().equals("Open Reward Chest", ignoreCase = true)
    } ?: stacks.firstOrNull { stack ->
      !stack.isEmpty && stack.loreLines().any { it.equals("Contents", ignoreCase = true) }
    } ?: return emptyList()

    val lore = chestStack.loreLines()
    val contentsIndex = lore.indexOfFirst { it.equals("Contents", ignoreCase = true) }
    if (contentsIndex < 0) return emptyList()

    return lore.drop(contentsIndex + 1)
      .takeWhile { line -> !line.equals("Cost", ignoreCase = true) }
      .mapNotNull(::rewardStackFromLoreLine)
      .distinctBy { it.getSkyblockApiId().ifBlank { it.hoverName.string.stripFormatting() } }
  }

  private fun rewardStackFromLoreLine(line: String): ItemStack? {
    val rewardName = line
      .removePrefix("-")
      .trim()
      .substringBefore(" x")
      .substringBefore(" [")
      .trim()

    if (rewardName.isBlank()) return null
    if (rewardName.equals("FREE", ignoreCase = true)) return null
    if (rewardName.contains("Coins", ignoreCase = true)) return null
    if (rewardName.equals("Dungeon Chest Key", ignoreCase = true)) return null

    val apiId = apiIdFromRewardName(rewardName)
    return ItemStack(iconForRewardName(rewardName)).also { stack ->
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(rewardName))
      stack.set(DataComponents.CUSTOM_DATA, CustomData.of(CompoundTag().apply { putString("id", apiId) }))
    }
  }

  private fun apiIdFromRewardName(name: String): String {
    ENCHANTED_BOOK_REWARD.matchEntire(name)?.let { match ->
      return "ENCHANTMENT_${normalizeApiId(match.groupValues[1])}_${romanToInt(match.groupValues[2])}"
    }

    ESSENCE_REWARD.matchEntire(name)?.let { match ->
      return "${normalizeApiId(match.groupValues[2])}_ESSENCE"
    }

    return normalizeApiId(name).ifBlank { name.uppercase(Locale.US) }
  }

  private fun normalizeApiId(value: String): String =
    value
      .stripFormatting()
      .uppercase(Locale.US)
      .replace("'", "")
      .replace(Regex("[^A-Z0-9]+"), "_")
      .trim('_')

  private fun iconForRewardName(name: String) = when {
    name.startsWith("Enchanted Book", ignoreCase = true) -> Items.ENCHANTED_BOOK
    name.contains("Essence", ignoreCase = true) -> Items.GLOWSTONE_DUST
    name.contains("Star", ignoreCase = true) -> Items.NETHER_STAR
    name.contains("Recombobulator", ignoreCase = true) -> Items.ANVIL
    name.contains("Catalyst", ignoreCase = true) -> Items.END_CRYSTAL
    else -> Items.PAPER
  }

  private fun isNavigationItem(stack: ItemStack): Boolean {
    val item = stack.item
    if (item == Items.BLACK_STAINED_GLASS_PANE || item == Items.GRAY_STAINED_GLASS_PANE) return true
    if (item == Items.BARRIER || item == Items.ARROW) return true
    val name = stack.hoverName.string.stripFormatting()
    return name.equals("Close", ignoreCase = true) ||
      name.equals("Go Back", ignoreCase = true) ||
      name.equals("Next Page", ignoreCase = true) ||
      name.equals("Previous Page", ignoreCase = true) ||
      name.equals("Open Reward Chest", ignoreCase = true) ||
      name.equals("Reroll Chest", ignoreCase = true)
  }

  private fun rarityColor(stack: ItemStack): Int {
    val text = stack.hoverName.string
    val lore = stack.get(DataComponents.LORE)?.lines()?.joinToString(" ") { it.string }.orEmpty()
    val source = "$text $lore"
    return when {
      source.contains("DIVINE", ignoreCase = true) -> 0xFF55FFFF.toInt()
      source.contains("MYTHIC", ignoreCase = true) -> 0xFFFF55FF.toInt()
      source.contains("LEGENDARY", ignoreCase = true) -> 0xFFFFAA00.toInt()
      source.contains("EPIC", ignoreCase = true) -> 0xFFAA00AA.toInt()
      source.contains("RARE", ignoreCase = true) -> 0xFF5555FF.toInt()
      source.contains("UNCOMMON", ignoreCase = true) -> 0xFF55FF55.toInt()
      source.contains("COMMON", ignoreCase = true) -> 0xFFFFFFFF.toInt()
      else -> stack.hoverName.style.color?.value ?: ChatFormatting.WHITE.color ?: 0xFFFFFFFF.toInt()
    }
  }

  private fun ease(t: Float): Float =
    if (t < 0.5) {
      (1 - sqrt(1 - (2 * t).pow(2.0f))) / 2f
    } else {
      (sqrt(1 - (-2 * t + 2).pow(2.0f)) + 1) / 2f
    }

  private fun alpha(color: Int, alpha: Int): Int =
    (alpha.coerceIn(0, 255) shl 24) or (color and 0x00FFFFFF)

  private fun String.stripFormatting(): String =
    replace(FORMATTING_CODE, "")

  private fun ItemStack.loreLines(): List<String> =
    get(DataComponents.LORE)?.lines()?.map { it.string.stripFormatting().trim() }.orEmpty()

  private fun romanToInt(value: String): Int {
    var total = 0
    var previous = 0
    value.uppercase(Locale.US).reversed().forEach { char ->
      val current = when (char) {
        'I' -> 1
        'V' -> 5
        'X' -> 10
        else -> 0
      }
      if (current < previous) total -= current else total += current
      previous = current
    }
    return total.coerceAtLeast(1)
  }

  private const val ITEM_SCALE = 4
  private const val ITEM_SIZE = 50
  private const val WINNER_INDEX = ITEM_SIZE - 10
  private const val ITEM_GAP = 5

  private const val DUNGEON_CARD_WIDTH = 24
  private const val DUNGEON_CARD_HEIGHT = 18
  private const val BACKGROUND_COLOR = -2144321488

  private const val FULL_CARD_WIDTH = (DUNGEON_CARD_WIDTH * ITEM_SCALE) + ITEM_GAP
  private const val FULL_CARD_HEIGHT = DUNGEON_CARD_HEIGHT * ITEM_SCALE
  private const val CROESUS_TYPE = "Croesus"

  private val DUNGEON_CHEST_TITLE = Regex("(?i)^(Wood|Gold|Diamond|Emerald|Obsidian|Bedrock)(?:\\s+Chest)?$")
  private val CROESUS_TITLE = Regex("(?i)^(?:Master\\s+)?(?:Catacombs|Kuudra)\\s+-\\s+.+$")
  private val ENCHANTED_BOOK_REWARD = Regex("(?i)^Enchanted Book \\((.+)\\s+([IVX]+)\\)$")
  private val ESSENCE_REWARD = Regex("(?i)^([\\d,]+)\\s+(.+)\\s+Essence$")
  private val FORMATTING_CODE = Regex("(?i)§[0-9A-FK-OR]")
}
