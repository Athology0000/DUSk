package org.cobalt.internal.dungeons

import kotlin.math.abs
import kotlin.math.min
import net.minecraft.client.Minecraft
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.TickEvent
import org.cobalt.api.hud.HudAnchor
import org.cobalt.api.hud.hudElement
import org.cobalt.api.module.Module
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.module.setting.impl.ColorSetting
import org.cobalt.api.module.setting.impl.SliderSetting
import org.cobalt.api.util.ui.NVGRenderer
import org.cobalt.api.util.ui.helper.Gradient
import org.cobalt.api.util.ui.helper.Image
import org.cobalt.internal.dungeons.map.CORNER_START_X
import org.cobalt.internal.dungeons.map.CORNER_START_Z
import org.cobalt.internal.dungeons.map.DungeonRoom
import org.cobalt.internal.dungeons.map.DungeonScanState
import org.cobalt.internal.dungeons.map.HALF_COMPONENT_SIZE
import org.cobalt.internal.dungeons.map.RoomCheckmark
import org.cobalt.internal.dungeons.map.RoomKind

object DungeonMapModule : Module("Dungeon Map") {

  private const val PAD = 10f
  private const val HEADER_H = 26f
  private const val FOOTER_H = 16f
  private const val ROOM_FILL = 0.8f
  private const val DOOR_FILL = 0.26f

  private val mc = Minecraft.getInstance()

  // Lazy so NVGRenderer (and NanoVG nvgCreate) is only called on first render,
  // when the OpenGL context is guaranteed to be ready.
  private val mapImages by lazy {
    listOf(
      "arrow.png",
      "blaze_powder.png",
      "book_normal.png",
      "boss_diamond.png",
      "boss_la.png",
      "boss_midas.png",
      "boss_sa.png",
      "boss_strong.png",
      "boss_superior.png",
      "boss_wise.png",
      "boss_young.png",
      "bucket_water.png",
      "chest.png",
      "creeper.png",
      "endframe_side.png",
      "failedRoom.png",
      "greenCheck.png",
      "ice.png",
      "markerOther.png",
      "markerSelf.png",
      "planks_oak.png",
      "questionMark.png",
      "shears.png",
      "spawner.png",
      "whiteCheck.png",
    ).associateWith(::loadMapImage)
  }

  private val checkmarkImages by lazy {
    mapOf(
      RoomCheckmark.WHITE to mapImages["whiteCheck.png"],
      RoomCheckmark.GREEN to mapImages["greenCheck.png"],
      RoomCheckmark.FAILED to mapImages["failedRoom.png"],
      RoomCheckmark.UNEXPLORED to mapImages["questionMark.png"],
    )
  }

  private val roomIcons by lazy {
    mapOf(
      "arrowtrap" to mapImages["arrow.png"],
      "blaze" to mapImages["blaze_powder.png"],
      "bombdefuse" to mapImages["shears.png"],
      "boulder" to mapImages["spawner.png"],
      "creeper" to mapImages["creeper.png"],
      "creeperbeams" to mapImages["creeper.png"],
      "icefill" to mapImages["ice.png"],
      "icepath" to mapImages["ice.png"],
      "quiz" to mapImages["book_normal.png"],
      "teleportmaze" to mapImages["endframe_side.png"],
      "threeweirdos" to mapImages["chest.png"],
      "tictactoe" to mapImages["planks_oak.png"],
      "waterboard" to mapImages["bucket_water.png"],
    )
  }

  private val enabled = CheckboxSetting(
    "Enabled",
    "Render the dungeon map HUD.",
    true
  )

  private val showWitherKeyRoom = CheckboxSetting(
    "Show Wither Key Room",
    "Highlight the room containing a dropped Wither Key.",
    true
  )

  private val funnyMode = CheckboxSetting(
    "Funny Mode",
    "Show scanned rooms even before they are explored on the vanilla map.",
    true
  )

  private val showCheckmarks = CheckboxSetting(
    "Show Checkmarks",
    "Show room completion dots.",
    true
  )

  private val showRoomNames = CheckboxSetting(
    "Show Room Names",
    "Show known room names on the map.",
    false
  )

  private val showRoomIcons = CheckboxSetting(
    "Show Room Icons",
    "Render bundled room and puzzle icons on the map when a match exists.",
    true
  )

  private val showPartyMarkers = CheckboxSetting(
    "Show Party Markers",
    "Show player markers from the dungeon map.",
    true
  )

  private val mapSize = SliderSetting(
    "Map Size",
    "Size of the square map area.",
    168.0,
    120.0,
    280.0,
    1.0
  )

  private val accentStart = ColorSetting(
    "Accent Start",
    "Header and border gradient start.",
    0xFF4DE2C5.toInt()
  )

  private val accentEnd = ColorSetting(
    "Accent End",
    "Header and border gradient end.",
    0xFFFF8C5A.toInt()
  )

  private val roomNameColor = ColorSetting(
    "Name Color",
    "Color used for room labels.",
    0xFFECECEC.toInt()
  )

  private var witherKeyRoomIndices = emptySet<Int>()

  private val hud = hudElement("dungeon-map", "Dungeon Map", "Illegal-style dungeon map HUD") {
    anchor = HudAnchor.TOP_RIGHT
    offsetX = 18f
    offsetY = 34f
    minScale = 0.65f
    maxScale = 2.2f

    width { mapSize.value.toFloat() + PAD * 2f }
    height { mapSize.value.toFloat() + HEADER_H + FOOTER_H + PAD }

    render { _, _, _ ->
      if (!enabled.value) return@render
      renderHud()
    }
  }

  init {
    addSetting(
      enabled,
      showWitherKeyRoom,
      funnyMode,
      showCheckmarks,
      showRoomNames,
      showRoomIcons,
      showPartyMarkers,
      mapSize,
      accentStart,
      accentEnd,
      roomNameColor,
    )
    EventBus.register(this)
  }

  @SubscribeEvent
  fun onTick(@Suppress("UNUSED_PARAMETER") event: TickEvent.End) {
    if (!enabled.value) return
    DungeonScanState.tick()
    witherKeyRoomIndices =
      if (showWitherKeyRoom.value && DungeonScanState.isInDungeon) {
        mc.level?.let(::findWitherKeyRoomIndices).orEmpty()
      } else {
        emptySet()
      }
  }

  private fun renderHud() {
    val width = mapSize.value.toFloat() + PAD * 2f
    val height = mapSize.value.toFloat() + HEADER_H + FOOTER_H + PAD
    val mapAreaSize = mapSize.value.toFloat()
    val mapX = PAD
    val mapY = HEADER_H

    NVGRenderer.gradientRect(0f, 0f, width, height, 0xF015171A.toInt(), 0xF01A1D21.toInt(), Gradient.TopToBottom, 10f)
    NVGRenderer.hollowGradientRect(0.5f, 0.5f, width - 1f, height - 1f, 1.8f, accentStart.value, accentEnd.value, Gradient.LeftToRight, 10f)
    NVGRenderer.gradientRect(0f, 0f, width, HEADER_H, accentStart.value, accentEnd.value, Gradient.LeftToRight, 10f)

    val headerLabel =
      if (DungeonScanState.isInDungeon && DungeonScanState.floor != org.cobalt.internal.dungeons.map.DungeonFloor.NONE) {
        buildString {
          append(DungeonScanState.floor.shortName)
          DungeonScanState.currentRoom?.name?.takeIf { it.isNotBlank() }?.let {
            append("  ")
            append(it)
          }
        }
      } else {
        "Dungeon Map"
      }
    NVGRenderer.text(headerLabel, PAD, 6f, 15f, 0xFFF4F7FB.toInt())

    NVGRenderer.rect(mapX, mapY, mapAreaSize, mapAreaSize, 0xCC090B0F.toInt(), 8f)

    if (!DungeonScanState.isInDungeon || DungeonScanState.floor == org.cobalt.internal.dungeons.map.DungeonFloor.NONE) {
      val text = "Not in Dungeon"
      val textWidth = NVGRenderer.textWidth(text, 14f)
      NVGRenderer.text(text, mapX + mapAreaSize / 2f - textWidth / 2f, mapY + mapAreaSize / 2f - 8f, 14f, 0xFFE2E2E2.toInt())
      renderFooter(width, height)
      return
    }

    val floor = DungeonScanState.floor
    val roomScale = min(mapAreaSize / floor.roomsWide, mapAreaSize / floor.roomsTall)
    drawDoors(mapX, mapY, roomScale)
    drawRooms(mapX, mapY, roomScale)
    if (showPartyMarkers.value) {
      drawMapMarkers(mapX, mapY, roomScale)
    }
    drawSelfMarker(mapX, mapY, roomScale)
    renderFooter(width, height)
  }

  private fun drawRooms(mapX: Float, mapY: Float, scale: Float) {
    val seen = HashSet<DungeonRoom>()
    for (room in DungeonScanState.getRooms()) {
      room ?: continue
      if (!seen.add(room)) continue
      if (!funnyMode.value && !room.explored) continue

      val fill = roomFillColor(room)
      val roomInset = (1f - ROOM_FILL) * 0.5f
      for (component in room.components) {
        val cellX = component.x / 2f + roomInset
        val cellZ = component.z / 2f + roomInset
        NVGRenderer.rect(
          mapX + cellX * scale,
          mapY + cellZ * scale,
          ROOM_FILL * scale,
          ROOM_FILL * scale,
          fill,
          4f
        )
      }

      for (first in room.components) {
        for (second in room.components) {
          if (first == second) continue
          if (abs(first.x - second.x) + abs(first.z - second.z) != 2) continue
          if (first.x > second.x || first.z > second.z) continue

          if (first.x == second.x) {
            val x = first.x / 2f + roomInset
            val z = first.z / 2f + roomInset + ROOM_FILL
            NVGRenderer.rect(mapX + x * scale, mapY + z * scale, ROOM_FILL * scale, (1f - ROOM_FILL) * scale, fill, 2f)
          } else {
            val x = first.x / 2f + roomInset + ROOM_FILL
            val z = first.z / 2f + roomInset
            NVGRenderer.rect(mapX + x * scale, mapY + z * scale, (1f - ROOM_FILL) * scale, ROOM_FILL * scale, fill, 2f)
          }
        }
      }

      if (roomHasWitherKey(room)) {
        drawWitherKeyRoom(room, mapX, mapY, scale)
      }

      if (showCheckmarks.value) {
        drawCheckmark(room, mapX, mapY, scale)
      }
      if (showRoomIcons.value) {
        drawRoomIcon(room, mapX, mapY, scale)
      }
      if (showRoomNames.value) {
        drawRoomName(room, mapX, mapY, scale)
      }
    }
  }

  private fun drawDoors(mapX: Float, mapY: Float, scale: Float) {
    val seen = HashSet<Int>()
    for (door in DungeonScanState.getDoors()) {
      door ?: continue
      val index = door.component.doorIndex()
      if (!seen.add(index)) continue

      val fill = doorFillColor(door.type)
      val roomInset = (1f - ROOM_FILL) * 0.5f
      val halfDoorInset = (1f - DOOR_FILL) * 0.5f
      val baseX = door.component.x / 2f
      val baseZ = door.component.z / 2f

      if ((door.component.x and 1) == 1) {
        NVGRenderer.rect(
          mapX + (baseX + 0.5f - DOOR_FILL * 0.5f) * scale,
          mapY + (baseZ + roomInset) * scale,
          DOOR_FILL * scale,
          ROOM_FILL * scale,
          fill,
          3f
        )
      } else {
        NVGRenderer.rect(
          mapX + (baseX + roomInset) * scale,
          mapY + (baseZ + 0.5f - DOOR_FILL * 0.5f) * scale,
          ROOM_FILL * scale,
          DOOR_FILL * scale,
          fill,
          3f
        )
      }
    }
  }

  private fun drawCheckmark(room: DungeonRoom, mapX: Float, mapY: Float, scale: Float) {
    val origin = room.components.firstOrNull() ?: return
    val centerX = mapX + (origin.x / 2f + 0.5f) * scale
    val centerY = mapY + (origin.z / 2f + 0.5f) * scale
    val image = checkmarkImages[room.checkmark]
    if (room.checkmark == RoomCheckmark.NONE) return
    if (image != null) {
      drawCenteredImage(image, centerX, centerY, (scale * 0.22f).coerceIn(10f, 18f))
      return
    }

    val color =
      when (room.checkmark) {
        RoomCheckmark.WHITE -> 0xFFF5F5F5.toInt()
        RoomCheckmark.GREEN -> 0xFF64FF79.toInt()
        RoomCheckmark.FAILED -> 0xFFFF5D5D.toInt()
        RoomCheckmark.UNEXPLORED -> 0xFF989898.toInt()
        RoomCheckmark.NONE -> return
      }
    NVGRenderer.circle(centerX, centerY, 3.6f, color)
  }

  private fun drawRoomIcon(room: DungeonRoom, mapX: Float, mapY: Float, scale: Float) {
    val image = roomIconFor(room) ?: return
    val origin = room.components.firstOrNull() ?: return
    val centerX = mapX + (origin.x / 2f + 0.5f) * scale
    val centerY = mapY + (origin.z / 2f + 0.5f) * scale
    val iconSize = (scale * 0.26f).coerceIn(12f, 18f)
    val yOffset = if (showCheckmarks.value && room.checkmark != RoomCheckmark.NONE) iconSize * 0.75f else 0f
    drawCenteredImage(image, centerX, centerY - yOffset, iconSize)
  }

  private fun drawRoomName(room: DungeonRoom, mapX: Float, mapY: Float, scale: Float) {
    val name = room.name ?: return
    val origin = room.components.firstOrNull() ?: return
    val text = name.take(12)
    val width = NVGRenderer.textWidth(text, 10f)
    val centerX = mapX + (origin.x / 2f + 0.5f) * scale
    val centerY = mapY + (origin.z / 2f + 0.5f) * scale + if (showRoomIcons.value && roomIconFor(room) != null) 4f else 0f
    NVGRenderer.text(text, centerX - width / 2f, centerY - 6f, 10f, roomNameColor.value)
  }

  private fun drawMapMarkers(mapX: Float, mapY: Float, scale: Float) {
    val otherMarker = mapImages["markerOther.png"]
    for (marker in DungeonScanState.getMapMarkers()) {
      val x = mapX + (marker.componentX.toFloat() / 2f) * scale
      val y = mapY + (marker.componentZ.toFloat() / 2f) * scale
      if (otherMarker != null) {
        drawCenteredImage(otherMarker, x, y, (scale * 0.22f).coerceIn(9f, 15f))
      } else {
        NVGRenderer.circle(x, y, 2.4f, 0xFFEDEDED.toInt())
      }
    }
  }

  private fun drawSelfMarker(mapX: Float, mapY: Float, scale: Float) {
    val player = mc.player ?: return
    val roomX = (((player.x - CORNER_START_X) / HALF_COMPONENT_SIZE.toDouble()) / 2.0).toFloat()
    val roomZ = (((player.z - CORNER_START_Z) / HALF_COMPONENT_SIZE.toDouble()) / 2.0).toFloat()
    val x = mapX + roomX * scale
    val y = mapY + roomZ * scale
    val selfMarker = mapImages["markerSelf.png"]
    if (selfMarker != null) {
      drawCenteredImage(
        selfMarker,
        x,
        y,
        (scale * 0.28f).coerceIn(12f, 18f),
        Math.toRadians((player.yRot + 90f).toDouble()).toFloat()
      )
    } else {
      NVGRenderer.circle(x, y, 3.8f, 0xFF1A1D21.toInt())
      NVGRenderer.circle(x, y, 2.8f, accentStart.value)
      val yawRadians = Math.toRadians((player.yRot + 90f).toDouble())
      val tipX = x + kotlin.math.cos(yawRadians).toFloat() * 7f
      val tipY = y + kotlin.math.sin(yawRadians).toFloat() * 7f
      NVGRenderer.line(x, y, tipX, tipY, 2f, accentEnd.value)
    }
  }

  private fun renderFooter(width: Float, height: Float) {
    val status = DungeonScanState.getReferenceStatus()
    val clipped = if (status.length > 54) status.take(54) + "..." else status
    NVGRenderer.text(clipped, PAD, height - FOOTER_H + 1f, 10f, 0xFFC6C7CB.toInt())
  }

  private fun roomFillColor(room: DungeonRoom): Int {
    val base =
      when (room.type) {
        RoomKind.ENTRANCE -> 0xFF25703D.toInt()
        RoomKind.BLOOD -> 0xFF992B2B.toInt()
        RoomKind.PUZZLE -> 0xFF7E45D8.toInt()
        RoomKind.TRAP -> 0xFFAC6D2D.toInt()
        RoomKind.FAIRY -> 0xFFD96EA7.toInt()
        RoomKind.YELLOW -> 0xFFC9A62E.toInt()
        RoomKind.RARE -> 0xFFE0C54E.toInt()
        RoomKind.NORMAL -> 0xFF6E4A2B.toInt()
        RoomKind.UNKNOWN -> 0xFF43464E.toInt()
      }
    return if (room.explored || funnyMode.value) base else darken(base, 0.45f)
  }

  private fun doorFillColor(type: org.cobalt.internal.dungeons.map.DoorKind): Int =
    when (type) {
      org.cobalt.internal.dungeons.map.DoorKind.ENTRANCE -> 0xFF2C7C47.toInt()
      org.cobalt.internal.dungeons.map.DoorKind.BLOOD -> 0xFFAA3737.toInt()
      org.cobalt.internal.dungeons.map.DoorKind.WITHER -> 0xFF22252A.toInt()
      org.cobalt.internal.dungeons.map.DoorKind.NORMAL -> 0xFF514337.toInt()
    }

  private fun darken(argb: Int, factor: Float): Int {
    val alpha = argb ushr 24 and 0xFF
    val red = ((argb ushr 16 and 0xFF) * factor).toInt().coerceIn(0, 255)
    val green = ((argb ushr 8 and 0xFF) * factor).toInt().coerceIn(0, 255)
    val blue = ((argb and 0xFF) * factor).toInt().coerceIn(0, 255)
    return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
  }

  private fun roomIconFor(room: DungeonRoom): Image? {
    val name = room.name ?: return null
    return roomIcons[normalizeRoomKey(name)]
  }

  private fun normalizeRoomKey(value: String): String =
    value.lowercase().filter { it.isLetterOrDigit() }

  private fun roomHasWitherKey(room: DungeonRoom): Boolean =
    witherKeyRoomIndices.isNotEmpty() && room.components.any { it.roomIndex() in witherKeyRoomIndices }

  private fun drawWitherKeyRoom(room: DungeonRoom, mapX: Float, mapY: Float, scale: Float) {
    val fill = withAlpha(DungeonsModule.witherKeyMapColor(), 52)
    val outline = withAlpha(DungeonsModule.witherKeyMapColor(), 225)
    val roomInset = (1f - ROOM_FILL) * 0.5f

    for (component in room.components) {
      val cellX = component.x / 2f + roomInset
      val cellZ = component.z / 2f + roomInset
      val x = mapX + cellX * scale
      val y = mapY + cellZ * scale
      val w = ROOM_FILL * scale
      val h = ROOM_FILL * scale
      NVGRenderer.rect(x, y, w, h, fill, 4f)
      NVGRenderer.hollowRect(x, y, w, h, 1.35f, outline, 4f)
    }

    for (first in room.components) {
      for (second in room.components) {
        if (first == second) continue
        if (abs(first.x - second.x) + abs(first.z - second.z) != 2) continue
        if (first.x > second.x || first.z > second.z) continue

        if (first.x == second.x) {
          val x = mapX + (first.x / 2f + roomInset) * scale
          val y = mapY + (first.z / 2f + roomInset + ROOM_FILL) * scale
          val w = ROOM_FILL * scale
          val h = (1f - ROOM_FILL) * scale
          NVGRenderer.rect(x, y, w, h, fill, 2f)
          NVGRenderer.hollowRect(x, y, w, h, 1.1f, outline, 2f)
        } else {
          val x = mapX + (first.x / 2f + roomInset + ROOM_FILL) * scale
          val y = mapY + (first.z / 2f + roomInset) * scale
          val w = (1f - ROOM_FILL) * scale
          val h = ROOM_FILL * scale
          NVGRenderer.rect(x, y, w, h, fill, 2f)
          NVGRenderer.hollowRect(x, y, w, h, 1.1f, outline, 2f)
        }
      }
    }

    val minX = room.components.minOf { it.x }
    val maxX = room.components.maxOf { it.x }
    val minZ = room.components.minOf { it.z }
    val maxZ = room.components.maxOf { it.z }
    val centerX = mapX + (((minX + maxX) / 4f) + 0.5f) * scale
    val centerY = mapY + (((minZ + maxZ) / 4f) + 0.5f) * scale
    val label = if (scale >= 28f) "WK" else "K"
    val fontSize = if (label.length == 2) 8.5f else 9.5f
    val textWidth = NVGRenderer.textWidth(label, fontSize)
    val badgeWidth = textWidth + 7f
    val badgeHeight = fontSize + 4f
    NVGRenderer.rect(
      centerX - badgeWidth * 0.5f,
      centerY - badgeHeight * 0.5f,
      badgeWidth,
      badgeHeight,
      outline,
      3f
    )
    NVGRenderer.text(
      label,
      centerX - textWidth * 0.5f,
      centerY - fontSize * 0.52f,
      fontSize,
      0xFF081015.toInt()
    )
  }

  private fun withAlpha(argb: Int, alpha: Int): Int =
    (alpha.coerceIn(0, 255) shl 24) or (argb and 0x00FFFFFF)

  private fun drawCenteredImage(image: Image, centerX: Float, centerY: Float, size: Float, rotationRadians: Float = 0f) {
    val half = size * 0.5f
    if (rotationRadians == 0f) {
      NVGRenderer.image(image, centerX - half, centerY - half, size, size)
      return
    }

    NVGRenderer.push()
    NVGRenderer.translate(centerX, centerY)
    NVGRenderer.rotate(rotationRadians)
    NVGRenderer.image(image, -half, -half, size, size)
    NVGRenderer.pop()
  }

  private fun loadMapImage(fileName: String): Image? =
    runCatching { NVGRenderer.createImage("/assets/dungeons/map/$fileName") }.getOrNull()
}
