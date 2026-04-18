package org.cobalt.internal.visual

import net.minecraft.client.Minecraft
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.core.component.DataComponents
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket
import net.minecraft.world.item.ItemStack
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.PacketEvent
import org.cobalt.api.event.impl.client.TickEvent
import org.cobalt.api.event.impl.render.GuiRenderEvent
import org.cobalt.api.hud.HudAnchor
import org.cobalt.api.hud.HudModuleManager
import org.cobalt.api.hud.hudElement
import org.cobalt.api.module.Module
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.ui.theme.ThemeManager
import org.cobalt.api.util.ui.NVGRenderer
import org.cobalt.api.util.ui.helper.Gradient
import org.cobalt.api.util.ui.helper.Image

object PetDisplayModule : Module("Pet Display") {

    private val mc = Minecraft.getInstance()

    // -- Settings --------------------------------------------------------------

    private val enabledSetting      = CheckboxSetting("Enabled",        "Show the pet info HUD.",         true)
    private val glowSetting         = CheckboxSetting("Glow",           "Animated glow border.",          true)
    private val showHeldItemSetting = CheckboxSetting("Show Held Item", "Show the pet's held item line.", true)

    // -- Layout constants -------------------------------------------------------

    private const val W      = 176f
    private const val ICON   = 34f
    private const val CORNER = 9f
    private const val PAD    = 8f
    private const val TEXT_X = PAD + ICON + 10f
    private const val BAR_H = 8f
    private const val ARMOR_SLOT = 24f
    private const val ARMOR_ICON = 16f
    private const val ARMOR_GAP = 4f

    // -- Pet item + head image --------------------------------------------------

    @Volatile private var cachedPetItem: ItemStack? = null
    @Volatile private var cachedPetName: String?    = null

    @Volatile private var petHeadImage:       Image?   = null
    @Volatile private var petHeadPendingDel: Image?   = null
    @Volatile private var petHeadPendingPath: String? = null  // set by bg thread, consumed on render thread
    @Volatile private var petHeadItemKey:    String   = ""
    @Volatile private var petHeadLoading:    Boolean  = false

    // Ghost icon
    private var ghostImage: Image? = null

    // -- XP bar animation ------------------------------------------------------

    private var displayRatio: Float  = 0f
    private var lastRenderMs: Long   = 0L

    // -- Hypixel items API cache (petName.lowercase -> skull URL) ---------------

    private val petSkullUrls   = HashMap<String, String>()
    @Volatile private var apiCached   = false
    @Volatile private var apiFetching = false

    // -- Formatting helpers -----------------------------------------------------

    private val formattingCodeRegex = Regex("""\u00A7[0-9A-FK-ORa-fk-or]""")
    private fun stripFormatting(s: String) = formattingCodeRegex.replace(s, "")
    private fun matchesPet(stack: ItemStack, petName: String): Boolean {
        if (stack.isEmpty) return false
        return stripFormatting(stack.hoverName.string).contains(petName, ignoreCase = true)
    }

    private fun compactNumber(value: Long): String = when {
        value >= 1_000_000L -> String.format("%.1fm", value / 1_000_000f).removeSuffix(".0m") + "m"
        value >= 1_000L     -> String.format("%.1fk", value / 1_000f).removeSuffix(".0k") + "k"
        else               -> value.toString()
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

    // -- Ghost loading ----------------------------------------------------------

    private fun ensureGhostLoaded() {
        if (ghostImage != null) return
        ghostImage = runCatching {
            NVGRenderer.createImage("/assets/cobalt/textures/ui/ghost.svg")
        }.getOrNull()
    }

    // -- Skull URL extraction from ItemStack NBT --------------------------------

    private fun extractSkullUrl(stack: ItemStack): String? {
        val profile = stack.get(DataComponents.PROFILE) ?: return null
        val textures = profile.partialProfile().properties()["textures"]
        val b64 = textures.firstOrNull()?.value() ?: return null
        return runCatching {
            val json = String(java.util.Base64.getDecoder().decode(b64))
            val i1 = json.indexOf("\"url\"").takeIf { it >= 0 } ?: return null
            val q1 = json.indexOf('"', json.indexOf(':', i1) + 1) + 1
            json.substring(q1, json.indexOf('"', q1))
        }.getOrNull()
    }

    // -- Hypixel items API: populate petSkullUrls once per session -------------

    private fun fetchItemsApi() {
        if (apiCached || apiFetching) return
        apiFetching = true
        Thread({
            try {
                val conn = java.net.URL("https://api.hypixel.net/v2/resources/skyblock/items")
                    .openConnection() as java.net.HttpURLConnection
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                conn.connectTimeout = 6_000
                conn.readTimeout    = 15_000
                val root  = com.google.gson.JsonParser.parseReader(conn.inputStream.bufferedReader()).asJsonObject
                val items = root.getAsJsonArray("items") ?: return@Thread
                for (el in items) {
                    val obj  = el.asJsonObject
                    val name = obj.get("name")?.asString ?: continue
                    val skin = obj.get("skin")?.asString ?: continue
                    runCatching {
                        val decoded = String(java.util.Base64.getDecoder().decode(skin))
                        val texObj  = com.google.gson.JsonParser.parseString(decoded).asJsonObject
                        val url     = texObj.getAsJsonObject("textures")
                            ?.getAsJsonObject("SKIN")?.get("url")?.asString ?: return@runCatching
                        synchronized(petSkullUrls) { petSkullUrls[name.lowercase()] = url }
                    }
                }
                apiCached = true
            } catch (_: Exception) {
            } finally {
                apiFetching = false
            }
        }, "pet-items-api").also { it.isDaemon = true }.start()
    }

    // -- Head image download + crop ---------------------------------------------

    private fun tryStartHeadLoad(petName: String, skullUrl: String) {
        if (petHeadLoading) return
        val key = "$petName|$skullUrl"
        if (petHeadItemKey == key) return
        petHeadLoading = true
        Thread({
            try {
                val conn = java.net.URL(skullUrl).openConnection() as java.net.HttpURLConnection
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                conn.connectTimeout = 5_000
                conn.readTimeout    = 10_000
                val skin = javax.imageio.ImageIO.read(conn.inputStream) ?: return@Thread

                val size = 128
                val out  = java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                val g    = out.createGraphics()
                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
                g.drawImage(skin, 0, 0, size, size, 8, 8, 16, 16, null)   // base face
                g.drawImage(skin, 0, 0, size, size, 40, 8, 48, 16, null)  // overlay layer
                g.dispose()

                val tmp = java.io.File(System.getProperty("java.io.tmpdir"), "cobalt_pet_head_${petName.replace(" ", "_")}.png")
                javax.imageio.ImageIO.write(out, "PNG", tmp)

                // Don't call NVGRenderer here - must happen on render thread.
                // Signal the render block to load the image next frame.
                petHeadPendingPath = tmp.absolutePath
                petHeadItemKey     = key
            } catch (_: Exception) {
            } finally {
                petHeadLoading = false
            }
        }, "pet-head-fetch").also { it.isDaemon = true }.start()
    }

    // -- Cache update (runs every tick) ----------------------------------------

    private fun updateCachedPetItem() {
        val pet = PetTabListParser.current
        if (pet == null) {
            cachedPetItem  = null
            cachedPetName  = null
            return
        }

        if (cachedPetName != null && !cachedPetName.equals(pet.name, ignoreCase = true)) {
            cachedPetItem  = null
            petHeadItemKey = ""
            displayRatio   = 0f
        }
        cachedPetName = pet.name

        // If we already have the head image for this pet, nothing more to do
        if (petHeadItemKey.startsWith(pet.name + "|")) return

        // Path A: skull item cached from a container packet - highest fidelity
        val item = cachedPetItem
        if (item != null && matchesPet(item, pet.name)) {
            val url = extractSkullUrl(item)
            if (url != null) { tryStartHeadLoad(pet.name, url); return }
        }

        // Path B: Hypixel items API lookup by pet name (no pets menu required)
        val apiUrl = synchronized(petSkullUrls) { petSkullUrls[pet.name.lowercase()] }
        if (apiUrl != null) {
            tryStartHeadLoad(pet.name, apiUrl)
        } else {
            // Kick off API fetch if not yet started; once it completes a later tick will pick it up
            fetchItemsApi()

            // Also scan live inventory in case the pet item is there right now
            val player = mc.player ?: return
            val found  = (0 until player.inventory.containerSize).asSequence()
                .map { player.inventory.getItem(it) }
                .plus(player.containerMenu.slots.asSequence().map { it.item })
                .firstOrNull { matchesPet(it, pet.name) }
            if (found != null) {
                cachedPetItem = found.copy()
                val url = extractSkullUrl(found)
                if (url != null) tryStartHeadLoad(pet.name, url)
            }
        }
    }

    // -- Packet listener --------------------------------------------------------

    @SubscribeEvent
    fun onPacket(event: PacketEvent.Incoming) {
        val petName = cachedPetName ?: return
        when (val pkt = event.packet) {
            is ClientboundContainerSetContentPacket -> {
                val match = pkt.items().firstOrNull { matchesPet(it, petName) } ?: return
                val copy  = match.copy()
                cachedPetItem = copy
                val url = extractSkullUrl(copy) ?: return
                tryStartHeadLoad(petName, url)
            }
            is ClientboundContainerSetSlotPacket -> {
                if (!matchesPet(pkt.item, petName)) return
                val copy = pkt.item.copy()
                cachedPetItem = copy
                val url = extractSkullUrl(copy) ?: return
                tryStartHeadLoad(petName, url)
            }
        }
    }

    // -- HUD -------------------------------------------------------------------

    private fun hudHeight(): Float {
        val hasItem = showHeldItemSetting.value &&
            PetTabListParser.current?.heldItem?.isNotEmpty() == true
        return if (hasItem) 110f else 96f
    }

    val petHud = hudElement("pet-display", "Pet Display", "Animated pet info HUD") {
        anchor  = HudAnchor.BOTTOM_RIGHT
        offsetX = 10f
        offsetY = 10f

        width  { W }
        height { hudHeight() }

        render { x, y, _ ->
            if (!enabledSetting.value) return@render
            ensureGhostLoaded()

            // Load pending head image on render thread (NVG requires render thread)
            petHeadPendingPath?.let { path ->
                petHeadPendingPath = null
                val newImg = runCatching { NVGRenderer.createImage(path) }.getOrNull()
                petHeadPendingDel = petHeadImage
                petHeadImage      = newImg
            }
            // Flush old head image deletion
            petHeadPendingDel?.let { runCatching { NVGRenderer.deleteImage(it) }; petHeadPendingDel = null }

            val now   = System.currentTimeMillis()
            val h     = hudHeight()
            val c1    = ThemeManager.currentTheme.accent
            val c2    = ThemeManager.currentTheme.accentSecondary
            val data  = PetTabListParser.current
            val titleX = x + TEXT_X
            val titleMaxW = W - TEXT_X - PAD
            val hasHeldItem = showHeldItemSetting.value && data?.heldItem?.isNotEmpty() == true
            val detailY = if (hasHeldItem) y + 45f else y + 32f
            val armorY = y + h - PAD - BAR_H - ARMOR_SLOT - 8f
            val armorStartX = titleX
            val progressRatio =
                if (data == null) 0f
                else if (data.isMaxLevel) 1f
                else if (data.xpRequired > 0) {
                    (data.xpCurrent.toFloat() / data.xpRequired.toFloat()).coerceIn(0f, 1f)
                } else 0f

            // -- Shadow / glow -------------------------------------------------
            if (glowSetting.value) {
                NVGRenderer.rect(x + 6f, y + 7f, W, h, 0x28000000, CORNER + 2f)
                NVGRenderer.rect(x + 3f, y + 3f, W, h, 0x1A000000, CORNER + 1f)
            }

            // -- Background ----------------------------------------------------
            NVGRenderer.rect(x, y, W, h, 0xE61A2234.toInt(), CORNER)
            NVGRenderer.gradientRect(x, y, W, h * 0.55f, 0x10FFFFFF, 0x00000000, Gradient.TopToBottom, CORNER)
            NVGRenderer.hollowRect(x, y, W, h, 1f, 0x1FFFFFFF, CORNER)

            // -- Icon box ------------------------------------------------------
            val iconX = x + PAD
            val iconY = y + PAD
            NVGRenderer.rect(iconX, iconY, ICON, ICON, 0xFF2B354C.toInt(), 6f)
            NVGRenderer.hollowRect(iconX, iconY, ICON, ICON, 1f, 0x20FFFFFF, 6f)

            if (data == null) {
                val ghost = ghostImage
                if (ghost != null) {
                    NVGRenderer.image(ghost, iconX + 5f, iconY + 5f, ICON - 10f, ICON - 10f)
                } else {
                    val sym = "\u2726"
                    NVGRenderer.text(sym, iconX + ICON / 2f - NVGRenderer.textWidth(sym, 20f) / 2f,
                        iconY + ICON / 2f - 10f, 20f, 0x33FFFFFF)
                }
                NVGRenderer.textShadow("No pet active", titleX, y + 18f, 11f, 0xFFFFFFFF.toInt())
                NVGRenderer.text("Open the pet menu to refresh the card.", titleX, y + 33f, 9f, 0xAFC5D5FF.toInt())
                renderArmorRow(armorStartX, armorY)
                renderProgressBar(titleX, y + h - PAD - BAR_H, W - TEXT_X - PAD, 0f, c1, c2, "0%")
                return@render
            }

            // Pet icon - NVG image (face texture) or star while loading
            val headImg = petHeadImage
            if (headImg != null) {
                NVGRenderer.image(headImg, iconX + 3f, iconY + 3f, ICON - 6f, ICON - 6f, 4f)
            } else {
                val sym = "\u2726"
                NVGRenderer.text(sym, iconX + ICON / 2f - NVGRenderer.textWidth(sym, 20f) / 2f,
                    iconY + ICON / 2f - 10f, 20f, c1)
            }

            val textColor = 0xFFFFFFFF.toInt()
            val dimColor  = 0xAFC5D5FF.toInt()

            // -- Pet name + level ----------------------------------------------
            val title = ellipsize(data.name, titleMaxW, 12f)
            NVGRenderer.textShadow(title, titleX, y + 18f, 12f, textColor)

            // -- Held item -----------------------------------------------------
            if (showHeldItemSetting.value && data.heldItem.isNotEmpty()) {
                val heldText = ellipsize(data.heldItem, titleMaxW, 9f)
                NVGRenderer.text(heldText, titleX, y + 32f, 9f, dimColor)
            }

            // -- Summary -------------------------------------------------------
            val dt = if (lastRenderMs == 0L) 0f else ((now - lastRenderMs).coerceIn(0L, 100L) / 1000f)
            val lerpSpeed = 2.5f
            displayRatio = if (progressRatio > displayRatio) {
                (displayRatio + lerpSpeed * dt).coerceAtMost(progressRatio)
            } else {
                progressRatio
            }

            val summary = buildString {
                append("Lvl ")
                append(data.level)
                append(" • ")
                append(if (data.isMaxLevel) "MAX" else "${(progressRatio * 100).toInt()}%")
                if (!data.isMaxLevel && data.xpRequired > 0 && !data.isPercentageFormat) {
                    append(" • ")
                    append(compactNumber(data.xpCurrent))
                    append("/")
                    append(compactNumber(data.xpRequired))
                }
            }
            NVGRenderer.text(ellipsize(summary, titleMaxW, 9f), titleX, detailY, 9f, dimColor)

            // -- Equipped armor -----------------------------------------------
            renderArmorRow(armorStartX, armorY)

            // -- XP bar --------------------------------------------------------
            val barX = titleX
            val barY = y + h - PAD - BAR_H
            val barW = W - TEXT_X - PAD
            val barText = if (data.isMaxLevel) "MAX" else "${(progressRatio * 100).toInt()}%"
            renderProgressBar(barX, barY, barW, displayRatio, c1, c2, barText)
            lastRenderMs = now
        }

    }

    // -- Init ------------------------------------------------------------------

    init {
        addSetting(enabledSetting, glowSetting, showHeldItemSetting)
        EventBus.register(this)
    }

    // -- Gui render (items must use live GuiGraphics, not the stale postRender one) --

    @SubscribeEvent
    fun onGuiRender(event: GuiRenderEvent) {
        if (!enabledSetting.value || !petHud.enabled) return
        if (mc.screen != null && !HudModuleManager.isEditorOpen) return
        val guiScale = mc.window.guiScale.toFloat()
        if (guiScale <= 0f) return

        val window = mc.window
        val (screenX, screenY) = petHud.getScreenPosition(
            window.screenWidth.toFloat(), window.screenHeight.toFloat()
        )
        val scale = petHud.scale
        val originX = screenX / guiScale
        val originY = screenY / guiScale
        val renderScale = scale / guiScale

        val armorY = hudHeight() - PAD - BAR_H - ARMOR_SLOT - 8f
        currentArmorStacks().forEachIndexed { index, stack ->
            if (stack.isEmpty) return@forEachIndexed
            renderHudItem(
                graphics = event.graphics,
                stack = stack,
                originX = originX,
                originY = originY,
                renderScale = renderScale,
                localX = TEXT_X + 1f + index * (ARMOR_SLOT + ARMOR_GAP) + (ARMOR_SLOT - ARMOR_ICON) / 2f,
                localY = armorY + (ARMOR_SLOT - ARMOR_ICON) / 2f
            )
        }
    }

    // -- Tick ------------------------------------------------------------------

    @SubscribeEvent
    fun onTick(@Suppress("UNUSED_PARAMETER") event: TickEvent.End) {
        if (!enabledSetting.value || !petHud.enabled) return
        PetTabListParser.update()
        updateCachedPetItem()
    }

    private fun currentArmorStacks(): List<ItemStack> {
        val player = mc.player ?: return listOf(ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY)
        return listOf(
            player.getItemBySlot(EquipmentSlot.HEAD),
            player.getItemBySlot(EquipmentSlot.CHEST),
            player.getItemBySlot(EquipmentSlot.LEGS),
            player.getItemBySlot(EquipmentSlot.FEET)
        )
    }

    private fun renderArmorRow(x: Float, y: Float) {
        for (index in 0 until 4) {
            val slotX = x + index * (ARMOR_SLOT + ARMOR_GAP)
            NVGRenderer.rect(slotX, y, ARMOR_SLOT, ARMOR_SLOT, 0x59212B40, 4f)
            NVGRenderer.hollowRect(slotX, y, ARMOR_SLOT, ARMOR_SLOT, 1f, 0x18FFFFFF, 4f)
        }
    }

    private fun renderHudItem(
        graphics: net.minecraft.client.gui.GuiGraphics,
        stack: ItemStack,
        originX: Float,
        originY: Float,
        renderScale: Float,
        localX: Float,
        localY: Float
    ) {
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(originX + localX * renderScale, originY + localY * renderScale)
        pose.scale(renderScale, renderScale)
        graphics.renderItem(stack, 0, 0)
        pose.popMatrix()
    }

    private fun renderProgressBar(x: Float, y: Float, w: Float, ratio: Float, c1: Int, c2: Int, label: String) {
        val barR = BAR_H / 2f
        NVGRenderer.rect(x, y, w, BAR_H, 0x70131A29, barR)
        val fillW = (w * ratio).coerceIn(0f, w)
        if (fillW > 0f) {
            NVGRenderer.pushScissor(x, y, fillW, BAR_H)
            NVGRenderer.gradientRect(x, y, w, BAR_H, c1, c2, Gradient.LeftToRight, barR)
            NVGRenderer.popScissor()
        }
        NVGRenderer.text(label, x + w / 2f - NVGRenderer.textWidth(label, 8f) / 2f, y - 2f, 8f, 0xD9FFFFFF.toInt())
    }
}
