package org.cobalt.internal.dungeons

import java.awt.Color
import java.util.ArrayDeque
import java.util.Locale
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.zombie.Zombie
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.world.scores.DisplaySlot
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.TickEvent
import org.cobalt.api.event.impl.render.WorldRenderEvent
import org.cobalt.api.module.Module
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.module.setting.impl.ColorSetting
import org.cobalt.api.module.setting.impl.SliderSetting
import org.cobalt.api.util.getHeadTextureId
import org.cobalt.api.util.render.Render3D
import org.cobalt.internal.dungeons.map.DoorKind
import org.cobalt.internal.dungeons.map.DungeonDoor
import org.cobalt.internal.dungeons.map.DungeonRoom
import org.cobalt.internal.dungeons.map.DungeonScanState
import org.cobalt.internal.dungeons.map.RoomKind

object BloodCampHelperModule : Module("Blood Camp Helper") {

  private const val WATCHER_SCAN_RADIUS = 2.0
  private const val FIRST_WAVE_DISTANCE = 15.6 + 0.5
  private const val LATE_WAVE_DISTANCE = 11.4 + 0.5
  private const val DELTA_SAMPLES = 5
  private const val MOTION_EPSILON_SQ = 1.0E-4
  private const val MARKER_Y_OFFSET = 2.0
  private const val MARKER_HALF_WIDTH = 0.5
  private const val MARKER_HEIGHT = 2.0

  private val enabled = CheckboxSetting(
    "Enabled",
    "Predict where Blood Room mobs spawned by The Watcher will land.",
    true
  )

  private val currentMarker = CheckboxSetting(
    "Current Marker",
    "Draw a marker on the tracked blood mob before it reaches the prediction.",
    true
  )

  private val traceLine = CheckboxSetting(
    "Trace Line",
    "Draw a line from the tracked blood mob to its predicted landing spot.",
    true
  )

  private val firstWaveOnly = CheckboxSetting(
    "First Wave Only",
    "Only render the first four blood mob predictions.",
    false
  )

  private val nextDoorEsp = CheckboxSetting(
    "Next Door ESP",
    "Render the next dungeon door on the shortest path toward Blood Camp.",
    true
  )

  private val currentColor = ColorSetting(
    "Current Color",
    "Color used for the live blood mob marker and trace.",
    0xFFFFDD66.toInt()
  )

  private val predictedColor = ColorSetting(
    "Predicted Color",
    "Color used for the predicted blood mob landing box.",
    0xFFFF5266.toInt()
  )

  private val nextDoorColor = ColorSetting(
    "Door Color",
    "Color used for the next door ESP toward Blood Camp.",
    0xFF6BDDFF.toInt()
  )

  private val fillOpacity = SliderSetting(
    "Fill Opacity",
    "Opacity of the rendered blood helper boxes.",
    0.18,
    0.0,
    1.0
  )

  private val lineWidth = SliderSetting(
    "Line Width",
    "Thickness of the blood helper ESP lines.",
    2.6,
    0.5,
    8.0
  )

  private val mc = Minecraft.getInstance()
  private val trackedMobs = mutableMapOf<Int, TrackedBloodMob>()
  private var predictedMobCount = 0
  private var nextBloodDoor: DungeonDoor? = null
  private var trackedLevel: ClientLevel? = null

  private val watcherTextureIds = setOf(
    "2739d7f4e66a7db2ea6cd414e4c4ba41df7a92455c9fc42caab014665c367ad5",
    "bf6e1e7ed36586c2d98057002bc1adc981e2889f7bd7b5b3852bc55cc7802204",
    "e5c1dc47a04ce57001a8b726f018cdef40b7ea9d7bd6d835ca495a0ef169f893",
    "5662b6fb4b8b586dc4cdf803b0444d9b41d245cdf668dab38fa6c064afe8e461",
    "4cec40008e1c31c1984f4d650abb3410f2037119fd624afc953563b73515a077",
    "9fd61e8055f6ee97ab5b6196a8d7ec98078ac37e00376157b6b520eaaa2f93af",
    "b37dd18b5983a767e556dc64424af4b9abdb75d4c9e8b097818afbc431bf0e09",
    "f5f0d78fe38d1d7f75f08cdcf2a1855d6da0337e114a3c63e3bf3c618bc732b0",
    "51967db5e3199916252021903cf4e9952ef7cec220faaca1ba79bafe5938bd80",
  )

  private val bloodMobTextureIds = setOf(
    "fb156cee370706408bb067261f59386f281eaf0bc24d168d9d01b13012946d04",
    "ac91f9afd84f2365cee8a53b61b9442b28e4f0e25bc6b6b1badbcdafc3e30c49",
    "7de7bbbdf22bfe17980d4e20687e386f11d59ee1db6f8b4762391b79a5ac532d",
    "3260325171a7ba8460830c0eea515c757a665e5b16a14207ba1a3182752bee87",
    "8421ba5b8e3573ef97beb5b40e15d15b20f30631c4c5330c3deda3047df0e92",
    "ad22772f769045fdc5be819ad68b01a97ac04c60886d2ca7afee39b282f7a383",
    "fb3973a752b24a2f3abb003427f6dbe6ca3a61db0a1bcf351c6eab27ec27e50",
    "ad67f97d7f821729beb34a82c3f13592b40439fe5248e72576fde7aa180bf77",
    "62d8fd3aa5617b1dac0aae9c81f6dd70ad93a59942f460d27e4d55a5cb8918e8",
    "c1007c5b7114abec734206d4fc613da4f3a0e99f71ff949cedadc99079135a0b",
    "69198f410a10f99314aa0fbe9a3db10697bbc1c011f019507d96673c64217f5a",
    "49f7cec00afe9f7c624ae8df5c033cb419f6ea41017021b9befd91970b833a5c",
    "3b48ec9c3e23a09e8aa2e1efbff9afb25e7315f9390984d01671dd0ae3c469ab",
    "12716ecbf5b8da00b05f316ec6af61e8bd02805b21eb8e440151468dc656549c",
    "ff184c19e725623d32828a0a4e741e86f135ac63dbc828ff3c8468338f3683b",
    "a89f6303af85877610912dc04b8b1e89724752f0a7eea05ab6547e228179c06f",
    "aa23c8cde2943c84249de8351bc3540be5f8afaaba8b2cb032fc5acad78a269b",
    "9171f35b8f508142bd8c65417d0f324153ab9147739ee4d10dea733cc80eaa20",
    "b5ba76e02cab72fa7d8ac54ceec849976ab0b00a01068d68c266766bf70c3997",
    "7d12b2ade413a6cd7cca3c95e961ba9f0ae7165fa41fc7b5d5f094a01240c609",
    "67237eddaebdbbdaacfa912885560ccdc65da93b4c3d513532868ec23bb5b448",
    "f4624a9a8c69ca204504abb043d47456cd9b09749a36357462303f276a229d4",
    "5cccd53f5191c29a9dc8f0170fbdc4e59e66476aae33de27b468f1de1b7cf3b2",
    "5a79860aca799407c0faa10b1bbcf42998fad4ebcf31d7a214180826b4ac94e1",
    "c919e5b8d56f062a21d224de14af771e2f55d09b59e7b099d09daa57540b79cf",
    "4774871190c878c9a2c4496c1e10257c6c4ea13807d72c15d7ac6ab3a7a9a8dc",
    "56fc854bb84cf4b7697297973e02b79bc10698460b51a639c60e5e417734e11",
  )

  init {
    addSetting(
      enabled,
      currentMarker,
      traceLine,
      firstWaveOnly,
      nextDoorEsp,
      currentColor,
      predictedColor,
      nextDoorColor,
      fillOpacity,
      lineWidth,
    )
    EventBus.register(this)
  }

  @SubscribeEvent
  fun onTick(@Suppress("UNUSED_PARAMETER") event: TickEvent.Start) {
    val level = mc.level ?: run {
      resetTracking()
      trackedLevel = null
      return
    }

    if (trackedLevel !== level) {
      resetTracking()
      trackedLevel = level
    }

    if (!enabled.value || !isInDungeon(level)) {
      resetTracking()
      return
    }

    updateNextBloodDoor()
    tickTracking(level)
  }

  @SubscribeEvent
  fun onRender(event: WorldRenderEvent.Last) {
    val level = mc.level ?: return
    if (!enabled.value || !isInDungeon(level)) return

    if (nextDoorEsp.value) {
      renderNextDoorEsp(event, nextBloodDoor)
    }
    if (trackedMobs.isEmpty()) return

    val currentStroke = colorFromSetting(currentColor.value)
    val predictedStroke = colorFromSetting(predictedColor.value)
    val currentFill = withAlpha(currentStroke, (fillOpacity.value * 170.0).toInt())
    val predictedFill = withAlpha(predictedStroke, (fillOpacity.value * 255.0).toInt())
    val outlineWidth = lineWidth.value.toFloat()
    val traceWidth = (outlineWidth - 0.7f).coerceAtLeast(1.0f)

    for (tracked in trackedMobs.values) {
      val predicted = tracked.predictedPos ?: continue
      if (firstWaveOnly.value && !tracked.firstWave) continue

      if (currentMarker.value) {
        val currentBox = tracked.entity.boundingBox.move(0.0, MARKER_Y_OFFSET, 0.0).inflate(0.08, 0.0, 0.08)
        Render3D.drawStyledBox(event.context, currentBox, currentStroke, currentFill, esp = true, lineWidth = outlineWidth)
      }

      if (traceLine.value) {
        val start = tracked.entity.position().add(0.0, MARKER_Y_OFFSET + 0.2, 0.0)
        val end = predicted.add(0.0, MARKER_Y_OFFSET + 0.2, 0.0)
        Render3D.drawLine(event.context, start, end, currentStroke, esp = true, thickness = traceWidth)
      }

      Render3D.drawStyledBox(
        event.context,
        predictionBoxAt(predicted),
        if (tracked.firstWave) predictedStroke.brighter() else predictedStroke,
        predictedFill,
        esp = true,
        lineWidth = outlineWidth
      )
    }
  }

  private fun tickTracking(level: ClientLevel) {
    val watcherBoxes = mutableListOf<AABB>()
    val candidateStands = mutableListOf<ArmorStand>()

    for (entity in level.entitiesForRendering()) {
      when (entity) {
        is Zombie -> if (isWatcher(entity)) watcherBoxes += entity.boundingBox.inflate(WATCHER_SCAN_RADIUS)
        is ArmorStand -> {
          if (!entity.isAlive || trackedMobs.containsKey(entity.id)) continue
          if (!entity.getItemBySlot(EquipmentSlot.HEAD).isEmpty) {
            candidateStands += entity
          }
        }
      }
    }

    if (watcherBoxes.isNotEmpty()) {
      for (stand in candidateStands) {
        if (!watcherBoxes.any { it.intersects(stand.boundingBox) }) continue
        if (!isBloodMobStand(stand)) continue
        trackedMobs[stand.id] = TrackedBloodMob(stand)
      }
    }

    val iterator = trackedMobs.entries.iterator()
    while (iterator.hasNext()) {
      val tracked = iterator.next().value
      if (!tracked.entity.isAlive) {
        iterator.remove()
        continue
      }

      val direction = tracked.updateAndGetDirection() ?: continue
      tracked.firstWave = predictedMobCount < 4
      tracked.predictedPos = tracked.startPos.add(direction.scale(if (tracked.firstWave) FIRST_WAVE_DISTANCE else LATE_WAVE_DISTANCE))
      predictedMobCount++
    }

    if (watcherBoxes.isEmpty() && trackedMobs.isEmpty()) {
      predictedMobCount = 0
    }
  }

  private fun updateNextBloodDoor() {
    if (!nextDoorEsp.value) {
      nextBloodDoor = null
      return
    }

    DungeonScanState.tick()
    nextBloodDoor = findNextDoorTowardBlood()
  }

  private fun findNextDoorTowardBlood(): DungeonDoor? {
    if (!DungeonScanState.isInDungeon) return null

    val startRoom = DungeonScanState.currentRoom ?: return null
    if (startRoom.type == RoomKind.BLOOD) return null

    startRoom.doors.firstOrNull { it.type == DoorKind.BLOOD }?.let { return it }

    val visited = linkedSetOf(startRoom)
    val queue = ArrayDeque<DungeonRoom>().apply { add(startRoom) }
    val previousRoom = mutableMapOf<DungeonRoom, DungeonRoom>()
    val previousDoor = mutableMapOf<DungeonRoom, DungeonDoor>()

    while (queue.isNotEmpty()) {
      val room = queue.removeFirst()
      for (door in room.doors) {
        if (door.type == DoorKind.BLOOD) {
          return if (room === startRoom) {
            door
          } else {
            firstDoorInPath(startRoom, room, previousRoom, previousDoor)
          }
        }

        for (neighbor in door.rooms) {
          if (neighbor === room || !visited.add(neighbor)) continue

          previousRoom[neighbor] = room
          previousDoor[neighbor] = door

          if (neighbor.type == RoomKind.BLOOD) {
            return firstDoorInPath(startRoom, neighbor, previousRoom, previousDoor)
          }

          queue.addLast(neighbor)
        }
      }
    }

    return null
  }

  private fun firstDoorInPath(
    startRoom: DungeonRoom,
    targetRoom: DungeonRoom,
    previousRoom: Map<DungeonRoom, DungeonRoom>,
    previousDoor: Map<DungeonRoom, DungeonDoor>,
  ): DungeonDoor? {
    var current = targetRoom
    while (true) {
      val parent = previousRoom[current] ?: return null
      val door = previousDoor[current] ?: return null
      if (parent === startRoom) {
        return door
      }
      current = parent
    }
  }

  private fun renderNextDoorEsp(event: WorldRenderEvent.Last, door: DungeonDoor?) {
    val targetDoor = door ?: return
    val stroke = colorFromSetting(nextDoorColor.value)
    val glow = withAlpha(stroke, (fillOpacity.value * 105.0).toInt())
    val fill = withAlpha(stroke, (fillOpacity.value * 185.0).toInt())
    val box = nextDoorBox(targetDoor)

    Render3D.drawStyledBox(
      event.context,
      box.inflate(0.18, 0.10, 0.18),
      stroke.brighter(),
      glow,
      esp = true,
      lineWidth = (lineWidth.value + 1.3).toFloat()
    )
    Render3D.drawStyledBox(
      event.context,
      box,
      stroke,
      fill,
      esp = true,
      lineWidth = lineWidth.value.toFloat()
    )
  }

  private fun nextDoorBox(door: DungeonDoor): AABB {
    val center = door.component.toWorldCenter()
    val minY = 69.0
    val maxY = 73.0

    return if ((door.component.x and 1) == 1) {
      // Door runs along X axis — 1 block thick in X, 3 blocks wide in Z
      AABB(
        center.x + 0.5 - 0.5,
        minY,
        center.z + 0.5 - 1.5,
        center.x + 0.5 + 0.5,
        maxY,
        center.z + 0.5 + 1.5,
      )
    } else {
      // Door runs along Z axis — 3 blocks wide in X, 1 block thick in Z
      AABB(
        center.x + 0.5 - 1.5,
        minY,
        center.z + 0.5 - 0.5,
        center.x + 0.5 + 1.5,
        maxY,
        center.z + 0.5 + 0.5,
      )
    }
  }

  private fun isWatcher(zombie: Zombie): Boolean {
    val head = zombie.getItemBySlot(EquipmentSlot.HEAD)
    return head.getHeadTextureId() in watcherTextureIds
  }

  private fun isBloodMobStand(stand: ArmorStand): Boolean {
    val head = stand.getItemBySlot(EquipmentSlot.HEAD)
    return head.getHeadTextureId() in bloodMobTextureIds
  }

  private fun predictionBoxAt(position: Vec3): AABB {
    return AABB(
      position.x - MARKER_HALF_WIDTH,
      position.y + MARKER_Y_OFFSET,
      position.z - MARKER_HALF_WIDTH,
      position.x + MARKER_HALF_WIDTH,
      position.y + MARKER_Y_OFFSET + MARKER_HEIGHT,
      position.z + MARKER_HALF_WIDTH,
    )
  }

  private fun resetTracking() {
    trackedMobs.clear()
    predictedMobCount = 0
    nextBloodDoor = null
  }

  private fun colorFromSetting(value: Int): Color = Color(value, true)

  private fun withAlpha(color: Color, alpha: Int): Color {
    return Color(color.red, color.green, color.blue, alpha.coerceIn(0, 255))
  }

  private fun stripFormatting(text: String): String {
    return ChatFormatting.stripFormatting(text) ?: text
  }

  private fun isInDungeon(level: ClientLevel): Boolean {
    val scoreboard = level.scoreboard
    val objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return false

    val allText = StringBuilder()
    val display = objective.displayName.string
    if (display.isNotEmpty()) {
      allText.append(display).append(' ')
    }

    try {
      val scores = scoreboard.listPlayerScores(objective)
      for (score in scores) {
        val ownerName = score.owner()
        val team = scoreboard.getPlayersTeam(ownerName)
        val lineText =
          if (team != null) {
            team.playerPrefix.string + ownerName + team.playerSuffix.string
          } else {
            ownerName
          }
        allText.append(lineText).append(' ')
      }
    } catch (_: Exception) {
    }

    val fullText = stripFormatting(allText.toString())
    val lower = fullText.lowercase(Locale.ROOT)

    if (lower.contains("hub")) {
      return false
    }
    if (lower.contains("catacombs") || lower.contains("the catacombs")) {
      return true
    }
    if (lower.contains("(e)") || lower.contains("entrance")) {
      return true
    }
    for (i in 1..7) {
      if (lower.contains("(f$i)") || lower.contains("floor $i") || lower.contains("f$i")) {
        return true
      }
      if (lower.contains("(m$i)") || lower.contains("master $i") || lower.contains("m$i")) {
        return true
      }
    }
    return false
  }

  private class TrackedBloodMob(val entity: ArmorStand) {
    val startPos: Vec3 = entity.position()
    var lastPos: Vec3 = startPos
    var firstWave: Boolean = false
    var predictedPos: Vec3? = null
    private val deltas = ArrayDeque<Vec3>()

    fun updateAndGetDirection(): Vec3? {
      if (predictedPos != null) return null

      val currentPos = entity.position()
      val delta = currentPos.subtract(lastPos)
      lastPos = currentPos

      if (delta.lengthSqr() <= MOTION_EPSILON_SQ) return null

      if (deltas.size == DELTA_SAMPLES) {
        deltas.removeFirst()
      }
      deltas.addLast(delta)

      if (deltas.size < DELTA_SAMPLES) return null

      var totalDelta = Vec3.ZERO
      for (sample in deltas) {
        totalDelta = totalDelta.add(sample)
      }

      return if (totalDelta.lengthSqr() > MOTION_EPSILON_SQ) totalDelta.normalize() else null
    }
  }
}
