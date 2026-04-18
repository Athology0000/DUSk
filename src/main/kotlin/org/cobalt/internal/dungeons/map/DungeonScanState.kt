package org.cobalt.internal.dungeons.map

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.StandardCharsets
import java.util.LinkedHashSet
import java.util.Locale
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.MapItem
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.SlabBlock
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes
import net.minecraft.world.level.saveddata.maps.MapId
import net.minecraft.world.scores.DisplaySlot

object DungeonScanState {

  private val mc = Minecraft.getInstance()
  private val floorRegex =
    Regex(
      "The Catacombs \\((Entrance|E|F[1-7]|M[1-7]|Floor [IVX]+|Master Mode Floor [IVX]+)\\)",
      RegexOption.IGNORE_CASE
    )

  private var lastProcessedTick = Long.MIN_VALUE
  private var referenceDataLoaded = false
  private var softResetCounter = 0
  private var roomDefinitionsByCore: Map<Int, RoomDefinition> = emptyMap()
  private var legacyBlockIds: Map<String, Int> = emptyMap()
  private var routesByKey: Map<String, LoadedRoute> = emptyMap()
  private var referenceStatus = "Waiting for bundled data"
  private var cachedMapId: MapId? = null

  private var roomSizeOnMap = -1
  private var roomGapOnMap = -1
  private var mapOffsetX = -1
  private var mapOffsetZ = -1

  private val availableComponents = mutableListOf<GridComponent>()
  private val rooms = arrayOfNulls<DungeonRoom>(ROOM_COUNT)
  private val doors = arrayOfNulls<DungeonDoor>(DOOR_COUNT)
  private val mapMarkers = mutableListOf<MapPlayerMarker>()

  var floor: DungeonFloor = DungeonFloor.NONE
    private set

  var currentRoom: DungeonRoom? = null
    private set

  var isInDungeon: Boolean = false
    private set

  init {
    reset()
  }

  fun getRooms(): Array<DungeonRoom?> = rooms

  fun getDoors(): Array<DungeonDoor?> = doors

  fun getMapMarkers(): List<MapPlayerMarker> = mapMarkers

  fun getReferenceStatus(): String = referenceStatus

  fun tick() {
    val level = mc.level
    val player = mc.player
    if (level == null || player == null) {
      reset()
      return
    }

    val gameTime = level.gameTime
    if (gameTime == lastProcessedTick) return
    lastProcessedTick = gameTime

    loadReferenceDataIfNeeded()

    val detectedFloor = detectFloor(level)
    isInDungeon = detectedFloor != DungeonFloor.NONE
    if (!isInDungeon) {
      resetTransientState()
      return
    }

    if (detectedFloor != floor) {
      setFloor(detectedFloor)
    }

    scanAvailableComponents(level)
    checkRoomRotations(level)
    checkDoorStates(level)

    softResetCounter++
    if (softResetCounter >= 20) {
      softResetCounter = 0
      softReset()
    }

    updateCurrentRoom(player.x, player.z)
    updateFromDungeonMap(level)
  }

  fun resolveRoute(room: DungeonRoom): LoadedRoute? {
    val roomName = room.name ?: return null
    val candidates = LinkedHashSet<String>().apply {
      if (room.totalSecrets > 0) add("$roomName-${room.totalSecrets}")
      add(roomName)
    }

    for (candidate in candidates) {
      val route = routesByKey[normalizeRouteKey(candidate)]
      if (route != null) return route
    }

    val normalizedName = normalizeRouteKey(roomName)
    val partialMatches = routesByKey.filterKeys { key -> key.startsWith(normalizedName) }
    if (partialMatches.size == 1) {
      return partialMatches.values.first()
    }

    return null
  }

  fun relativeToActual(room: DungeonRoom, relative: BlockPos): BlockPos? {
    val direction = room.direction ?: return null
    val corner = room.corner ?: return null
    return when (direction) {
      RoomDirection.NW -> BlockPos(relative.x + corner.x, relative.y, relative.z + corner.z)
      RoomDirection.NE -> BlockPos(-relative.z + corner.x, relative.y, relative.x + corner.z)
      RoomDirection.SW -> BlockPos(relative.z + corner.x, relative.y, -relative.x + corner.z)
      RoomDirection.SE -> BlockPos(-relative.x + corner.x, relative.y, -relative.z + corner.z)
    }
  }

  private fun reset() {
    floor = DungeonFloor.NONE
    isInDungeon = false
    currentRoom = null
    lastProcessedTick = Long.MIN_VALUE
    resetTransientState()
  }

  private fun resetTransientState() {
    currentRoom = null
    roomSizeOnMap = -1
    roomGapOnMap = -1
    mapOffsetX = -1
    mapOffsetZ = -1
    softResetCounter = 0
    mapMarkers.clear()
    availableComponents.clear()
    availableComponents.addAll(findAvailableComponents())
    for (index in rooms.indices) rooms[index] = null
    for (index in doors.indices) doors[index] = null
  }

  private fun setFloor(newFloor: DungeonFloor) {
    floor = newFloor
    resetTransientState()
  }

  private fun loadReferenceDataIfNeeded() {
    if (referenceDataLoaded) return

    roomDefinitionsByCore = loadRoomDefinitions("/assets/dungeons/data/rooms.json")
    legacyBlockIds = loadLegacyBlocks("/assets/dungeons/data/legacy_blocks.json")
    routesByKey = loadRoutes("/assets/dungeons/data/routes.json")
    referenceDataLoaded = true

    referenceStatus = buildString {
      append("Bundled data")
      append(" | rooms ")
      append(if (roomDefinitionsByCore.isEmpty()) "missing" else roomDefinitionsByCore.size)
      append(" | legacy ")
      append(if (legacyBlockIds.isEmpty()) "missing" else legacyBlockIds.size)
      append(" | routes ")
      append(if (routesByKey.isEmpty()) "missing" else routesByKey.size)
    }

    val oldFloor = floor
    val oldInDungeon = isInDungeon
    resetTransientState()
    floor = oldFloor
    isInDungeon = oldInDungeon
  }

  private fun loadRoomDefinitions(resourcePath: String): Map<Int, RoomDefinition> {
    return readBundledResource(resourcePath, emptyMap()) { reader ->
        val root = JsonParser.parseReader(reader).asJsonArray
        buildMap {
          for (element in root) {
            if (!element.isJsonObject) continue
            val obj = element.asJsonObject
            val definition = RoomDefinition(
              name = obj.get("name")?.asString.orEmpty(),
              type = RoomKind.fromName(obj.get("type")?.asString),
              secrets = obj.get("secrets")?.asInt ?: obj.get("crypts")?.asInt ?: 0,
            )
            val cores = obj.getAsJsonArray("cores") ?: continue
            for (coreElement in cores) {
              put(coreElement.asInt, definition)
            }
          }
        }
    }
  }

  private fun loadLegacyBlocks(resourcePath: String): Map<String, Int> {
    return readBundledResource(resourcePath, emptyMap()) { reader ->
        val root = JsonParser.parseReader(reader).asJsonObject
        buildMap {
          for ((key, value) in root.entrySet()) {
            put(key, value.asInt)
          }
        }
    }
  }

  private fun loadRoutes(resourcePath: String): Map<String, LoadedRoute> {
    return readBundledResource(resourcePath, emptyMap()) { reader ->
        val root = JsonParser.parseReader(reader).asJsonObject
        buildMap {
          for ((rawKey, value) in root.entrySet()) {
            if (!value.isJsonArray) continue
            val steps = mutableListOf<RouteStep>()
            for (stepElement in value.asJsonArray) {
              if (!stepElement.isJsonObject) continue
              val step = parseRouteStep(stepElement.asJsonObject) ?: continue
              steps.add(step)
            }
            if (steps.isNotEmpty()) {
              put(normalizeRouteKey(rawKey), LoadedRoute(rawKey, steps))
            }
          }
      }
    }
  }

  private fun <T> readBundledResource(resourcePath: String, fallback: T, loader: (Reader) -> T): T {
    return runCatching {
      DungeonScanState::class.java.getResourceAsStream(resourcePath)?.use { stream ->
        InputStreamReader(stream, StandardCharsets.UTF_8).use(loader)
      }
    }.getOrNull() ?: fallback
  }

  private fun parseRouteStep(obj: JsonObject): RouteStep? {
    val secret = obj.getAsJsonObject("secret") ?: return null
    val secretLocation = parseBlockPos(secret.get("location")) ?: return null
    val secretType = secret.get("type")?.asString.orEmpty()

    return RouteStep(
      pathLocations = parseBlockPosList(obj.get("locations")),
      etherwarps = parseBlockPosList(obj.get("etherwarps")),
      mines = parseBlockPosList(obj.get("mines")),
      interacts = parseBlockPosList(obj.get("interacts")),
      tnts = parseBlockPosList(obj.get("tnts")),
      secretPos = secretLocation,
      secretType = secretType,
      enderPearls = parseBlockPosList(obj.get("enderpearls")),
      enderPearlAngles = parseAngleList(obj.get("enderpearlangles")),
    )
  }

  private fun parseAngleList(element: com.google.gson.JsonElement?): List<FloatArray> {
    if (element == null || !element.isJsonArray) return emptyList()
    val list = ArrayList<FloatArray>()
    for (entry in element.asJsonArray) {
      if (!entry.isJsonArray) continue
      val arr = entry.asJsonArray
      if (arr.size() >= 2) list.add(floatArrayOf(arr[0].asFloat, arr[1].asFloat))
    }
    return list
  }

  private fun parseBlockPosList(element: com.google.gson.JsonElement?): List<BlockPos> {
    if (element == null || !element.isJsonArray) return emptyList()

    val list = ArrayList<BlockPos>()
    for (entry in element.asJsonArray) {
      val pos = parseBlockPos(entry) ?: continue
      list.add(pos)
    }
    return list
  }

  private fun parseBlockPos(element: com.google.gson.JsonElement?): BlockPos? {
    if (element == null || !element.isJsonArray) return null
    val arr = element.asJsonArray
    if (arr.size() < 3) return null
    return BlockPos(arr[0].asInt, arr[1].asInt, arr[2].asInt)
  }

  private fun normalizeRouteKey(value: String): String =
    value.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

  private fun detectFloor(level: net.minecraft.client.multiplayer.ClientLevel): DungeonFloor {
    val scoreboard = level.scoreboard
    val objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return DungeonFloor.NONE

    val lines = buildList {
      add(cleanScoreboardLine(objective.displayName.string))
      try {
        for (score in scoreboard.listPlayerScores(objective)) {
          val owner = score.owner()
          val team = scoreboard.getPlayersTeam(owner)
          if (team != null) {
            add(cleanScoreboardLine(team.playerPrefix.string + team.playerSuffix.string))
            add(cleanScoreboardLine(team.playerPrefix.string + owner + team.playerSuffix.string))
          } else {
            add(cleanScoreboardLine(owner))
          }
        }
      } catch (_: Exception) {
      }
    }

    val joined = lines.joinToString(" ")
    if (joined.contains("Hub", ignoreCase = true)) return DungeonFloor.NONE

    for (line in lines) {
      val match = floorRegex.find(line) ?: continue
      return DungeonFloor.fromName(match.groupValues[1])
    }

    return DungeonFloor.NONE
  }

  private fun cleanScoreboardLine(value: String): String =
    ChatFormatting.stripFormatting(value)
      ?.replace(Regex("[^\\x20-\\x7E]"), " ")
      ?.replace(Regex("\\s+"), " ")
      ?.trim()
      .orEmpty()

  private fun scanAvailableComponents(level: net.minecraft.client.multiplayer.ClientLevel) {
    if (availableComponents.isEmpty()) return

    val toRemove = ArrayList<GridComponent>()
    for (component in availableComponents) {
      val worldCenter = component.toWorldCenter()
      if (!isChunkLoaded(level, worldCenter.x, worldCenter.z)) continue

      toRemove.add(component)

      val roofHeight = getHighestY(level, worldCenter.x, worldCenter.z)
      if (component.isDoor()) {
        if (roofHeight != 0 && roofHeight < 85) {
          val door = DungeonDoor(component)
          if (component.z % 2 == 1) {
            door.rotation = 0
          }
          addDoor(door)
        }
        continue
      }

      if (roofHeight <= 0) continue

      var room = DungeonRoom(mutableListOf(component), roofHeight)
      scanRoomDefinition(level, room)
      if (room.type == RoomKind.ENTRANCE) {
        room.explored = true
        room.checkmark = RoomCheckmark.NONE
      }
      addRoom(component, room)

      for (neighbor in component.roomNeighbors()) {
        val doorCenter = neighbor.door.toWorldCenter()
        val blockAtHeight = getBlock(level, doorCenter.x, roofHeight, doorCenter.z)
        val blockAbove = getBlock(level, doorCenter.x, roofHeight + 1, doorCenter.z)

        val heightEmpty = blockAtHeight == Blocks.AIR
        val aboveEmpty = blockAbove == null || blockAbove == Blocks.AIR

        if (room.type == RoomKind.ENTRANCE && !heightEmpty) {
          val blockAt76 = getBlock(level, doorCenter.x, 76, doorCenter.z)
          if (blockAt76 != null && blockAt76 != Blocks.AIR) {
            val door = DungeonDoor(neighbor.door)
            door.type = DoorKind.ENTRANCE
            addDoor(door)
          }
          continue
        }

        if (heightEmpty || !aboveEmpty) continue

        val neighborIndex = neighbor.room.roomIndex()
        if (neighborIndex !in rooms.indices) continue
        val neighborRoom = rooms[neighborIndex]

        if (neighborRoom == null) {
          room.addComponent(neighbor.room, shouldUpdate = false)
          addRoom(neighbor.room, room)
          continue
        }

        if (neighborRoom.type == RoomKind.ENTRANCE || neighborRoom === room) continue
        mergeRooms(neighborRoom, room, level)
        room = neighborRoom
      }
    }

    availableComponents.removeAll(toRemove.toSet())
  }

  private fun addRoom(component: GridComponent, room: DungeonRoom, force: Boolean = false) {
    val index = component.roomIndex()
    if (index !in rooms.indices) return

    if (!force && rooms[index] != null) {
      val existing = rooms[index] ?: return
      if (room.name == null) {
        mergeRooms(existing, room, mc.level)
      } else {
        mergeRooms(room, existing, mc.level)
      }
      return
    }

    rooms[index] = room

    for (doorComponent in component.neighboringDoors()) {
      val doorIndex = doorComponent.doorIndex()
      if (doorIndex !in doors.indices) continue
      val door = doors[doorIndex] ?: continue
      door.rooms.add(room)
      room.doors.add(door)
    }
  }

  private fun addDoor(door: DungeonDoor) {
    val index = door.component.doorIndex()
    if (index !in doors.indices) return

    doors[index] = door
    for (roomComponent in door.component.neighboringRooms()) {
      val roomIndex = roomComponent.roomIndex()
      if (roomIndex !in rooms.indices) continue
      val room = rooms[roomIndex] ?: continue
      room.doors.add(door)
      door.rooms.add(room)
    }
  }

  private fun mergeRooms(primary: DungeonRoom, secondary: DungeonRoom, level: net.minecraft.client.multiplayer.ClientLevel?) {
    if (primary === secondary) return

    for (component in secondary.components) {
      primary.addComponent(component, shouldUpdate = false)
      addRoom(component, primary, force = true)
    }

    primary.updateShape()
    if (level != null) {
      scanRoomDefinition(level, primary)
      primary.corner = null
      primary.rotation = -1
    }
    if (secondary.explored) {
      primary.explored = true
    }
    if (secondary.checkmark.ordinal > primary.checkmark.ordinal) {
      primary.checkmark = secondary.checkmark
    }

    for (door in secondary.doors) {
      door.rooms.remove(secondary)
      door.rooms.add(primary)
      primary.doors.add(door)
    }
  }

  private fun checkDoorStates(level: net.minecraft.client.multiplayer.ClientLevel) {
    val seen = HashSet<DungeonDoor>()
    for (door in doors) {
      if (door == null || !seen.add(door) || door.opened) continue
      val center = door.component.toWorldCenter()
      if (!isChunkLoaded(level, center.x, center.z)) continue
      val blockAt69 = level.getBlockState(BlockPos(center.x, 69, center.z)).block
      door.updateFromBlock(blockAt69)
    }
  }

  private fun checkRoomRotations(level: net.minecraft.client.multiplayer.ClientLevel) {
    val seen = HashSet<DungeonRoom>()
    for (room in rooms) {
      if (room == null || !seen.add(room) || room.rotation != -1) continue
      findRoomRotation(level, room)
    }
  }

  private fun softReset() {
    availableComponents.clear()

    for (z in 0..10 step 2) {
      for (x in 0..10 step 2) {
        val component = GridComponent(x, z)
        val index = component.roomIndex()
        if (index in rooms.indices && rooms[index] == null) {
          availableComponents.add(component)
        }
      }
    }

    for (z in 0..10) {
      for (x in 0..10) {
        val component = GridComponent(x, z)
        if (!component.isDoor()) continue
        val index = component.doorIndex()
        if (index in doors.indices && doors[index] == null) {
          availableComponents.add(component)
        }
      }
    }
  }

  private fun updateCurrentRoom(playerX: Double, playerZ: Double) {
    val component = GridComponent.fromWorld(playerX, playerZ)
    val roomIndex = component.roomIndex()
    if (roomIndex !in rooms.indices) {
      currentRoom = null
      return
    }

    currentRoom = rooms[roomIndex]
    currentRoom?.explored = true
  }

  private fun updateFromDungeonMap(level: net.minecraft.client.multiplayer.ClientLevel) {
    val player = mc.player ?: return
    updateCachedMapId(player)
    val mapId = currentDungeonMapId(player) ?: return
    val mapState = MapItem.getSavedData(mapId, level) ?: return
    val colors = mapState.colors

    if (roomSizeOnMap == -1 && !scanMapDimensions(colors)) {
      return
    }

    updateMarkersFromMap(mapState)
    updateRoomsFromMap(colors)
  }

  private fun updateCachedMapId(player: net.minecraft.client.player.LocalPlayer) {
    for (slot in 0..8) {
      val stack = player.inventory.getItem(slot)
      if (!stack.`is`(Items.FILLED_MAP)) continue
      val mapId = stack.get(DataComponents.MAP_ID) ?: continue
      cachedMapId = mapId
      return
    }
  }

  private fun currentDungeonMapId(player: net.minecraft.client.player.LocalPlayer): MapId? {
    val slotEight = player.inventory.getItem(8)
    if (slotEight.`is`(Items.FILLED_MAP)) {
      slotEight.get(DataComponents.MAP_ID)?.let {
        cachedMapId = it
        return it
      }
    }
    return cachedMapId
  }

  private fun scanMapDimensions(colors: ByteArray): Boolean {
    if (floor == DungeonFloor.NONE) return false

    var entranceIndex = 0
    var searchIndex = 0
    while (entranceIndex < colors.size && colors[entranceIndex] != MapColorHint.ROOM_ENTRANCE.color) {
      searchIndex++
      entranceIndex = ((searchIndex and 7) shl 4) + ((searchIndex shr 3) shl 11)
    }

    if (entranceIndex >= colors.size) return false

    var left = entranceIndex
    var right = entranceIndex
    while (left > 0 && colors[left - 1] == MapColorHint.ROOM_ENTRANCE.color) left--
    while (right < colors.lastIndex && colors[right + 1] == MapColorHint.ROOM_ENTRANCE.color) right++

    var top = entranceIndex
    var bottom = entranceIndex
    while (top >= MAP_SIZE && colors[top - MAP_SIZE] == MapColorHint.ROOM_ENTRANCE.color) top -= MAP_SIZE
    while (bottom < colors.size - MAP_SIZE && colors[bottom + MAP_SIZE] == MapColorHint.ROOM_ENTRANCE.color) bottom += MAP_SIZE

    left = left and 127
    right = right and 127
    top = top shr 7
    bottom = bottom shr 7

    roomSizeOnMap = right - left + 1
    roomGapOnMap = roomSizeOnMap + MAP_ROOM_GAP
    mapOffsetX = left % roomGapOnMap
    mapOffsetZ = top % roomGapOnMap

    val mapWidth = roomGapOnMap * (floor.roomsWide - 1) + roomSizeOnMap
    val mapHeight = roomGapOnMap * (floor.roomsTall - 1) + roomSizeOnMap
    if (MAP_SIZE - mapWidth >= roomGapOnMap * 2) mapOffsetX += roomGapOnMap
    if (MAP_SIZE - mapHeight >= roomGapOnMap * 2) mapOffsetZ += roomGapOnMap

    return true
  }

  private fun updateMarkersFromMap(mapState: net.minecraft.world.level.saveddata.maps.MapItemSavedData) {
    if (floor == DungeonFloor.NONE || roomGapOnMap <= 0) return

    mapMarkers.clear()
    val decorations = mapState.decorations
    for (decoration in decorations) {
      if (decoration.type().value() == MapDecorationTypes.FRAME.value()) continue

      val componentX = rescale(
        (decoration.x() + 128.0) * 0.5,
        mapOffsetX.toDouble(),
        (mapOffsetX + roomGapOnMap * floor.roomsWide).toDouble(),
        0.0,
        floor.roomsWide * 2.0,
      )
      val componentZ = rescale(
        (decoration.y() + 128.0) * 0.5,
        mapOffsetZ.toDouble(),
        (mapOffsetZ + roomGapOnMap * floor.roomsTall).toDouble(),
        0.0,
        floor.roomsTall * 2.0,
      )
      val rotation = -(decoration.rot() / 16.0 * 360.0 + 90.0) / 180.0 * Math.PI
      mapMarkers.add(MapPlayerMarker(componentX, componentZ, rotation))
    }
  }

  private fun updateRoomsFromMap(colors: ByteArray) {
    if (colors.size < MAP_SIZE * MAP_SIZE) return

    val seen = HashSet<DungeonRoom>()
    for (roomIndex in 0 until ROOM_COUNT) {
      var room = rooms[roomIndex]
      if (room != null && !seen.add(room)) continue

      val roomX = roomIndex % 6
      val roomZ = roomIndex / 6
      val mapRoomX = mapOffsetX + roomX * roomGapOnMap
      val mapRoomZ = mapOffsetZ + roomZ * roomGapOnMap
      val mapCenterX = mapRoomX + roomSizeOnMap / 2 - 1
      val mapCenterZ = mapRoomZ + roomSizeOnMap / 2 + 1
      val cornerIndex = mapRoomX + mapRoomZ * MAP_SIZE
      val centerIndex = mapCenterX + mapCenterZ * MAP_SIZE

      if (cornerIndex !in colors.indices || centerIndex !in colors.indices) continue

      val roomColor = colors[cornerIndex]
      val centerColor = colors[centerIndex]
      if (roomColor == MapColorHint.EMPTY.color) continue

      if (room == null) {
        val component = GridComponent(roomX * 2, roomZ * 2)
        room = DungeonRoom(mutableListOf(component), 0)
        addRoom(component, room)
      } else {
        seen.add(room)
      }

      if (room.type == RoomKind.UNKNOWN) {
        room.type = RoomKind.fromMapColor(roomColor)
      }
      if (!room.explored && roomColor != MapColorHint.ROOM_UNOPENED.color) {
        room.explored = true
      }

      room.checkmark =
        if (roomColor == centerColor) {
          RoomCheckmark.NONE
        } else {
          when (centerColor) {
            MapColorHint.CHECK_WHITE.color -> RoomCheckmark.WHITE
            MapColorHint.CHECK_GREEN.color -> RoomCheckmark.GREEN
            MapColorHint.CHECK_FAIL.color -> RoomCheckmark.FAILED
            MapColorHint.CHECK_UNKNOWN.color -> RoomCheckmark.UNEXPLORED
            else -> RoomCheckmark.NONE
          }
        }
    }
  }

  private fun rescale(value: Double, inMin: Double, inMax: Double, outMin: Double, outMax: Double): Double {
    if (inMax - inMin == 0.0) return outMin
    return outMin + (value - inMin) * (outMax - outMin) / (inMax - inMin)
  }

  private fun scanRoomDefinition(level: net.minecraft.client.multiplayer.ClientLevel, room: DungeonRoom) {
    for (component in room.components) {
      val center = component.toWorldCenter()
      if (!isChunkLoaded(level, center.x, center.z)) continue
      if (room.height == 0) {
        room.height = getHighestY(level, center.x, center.z)
      }
      val definition = roomDefinitionsByCore[hashCeiling(level, center.x, center.z)] ?: continue
      room.applyDefinition(definition)
    }
    room.updateShape()
  }

  private fun findRoomRotation(level: net.minecraft.client.multiplayer.ClientLevel, room: DungeonRoom) {
    if (room.height == 0) return

    if (room.type == RoomKind.FAIRY) {
      val center = room.components.firstOrNull()?.toWorldCenter() ?: return
      room.rotation = 0
      room.corner = BlockPos(center.x - HALF_ROOM_SIZE, 0, center.z - HALF_ROOM_SIZE)
      return
    }

    for (component in room.components) {
      val center = component.toWorldCenter()
      val candidates = arrayOf(
        BlockPos(center.x - HALF_ROOM_SIZE, 0, center.z - HALF_ROOM_SIZE),
        BlockPos(center.x + HALF_ROOM_SIZE, 0, center.z - HALF_ROOM_SIZE),
        BlockPos(center.x + HALF_ROOM_SIZE, 0, center.z + HALF_ROOM_SIZE),
        BlockPos(center.x - HALF_ROOM_SIZE, 0, center.z + HALF_ROOM_SIZE),
      )

      for ((index, candidate) in candidates.withIndex()) {
        if (!isChunkLoaded(level, candidate.x, candidate.z)) continue
        if (level.getBlockState(BlockPos(candidate.x, room.height, candidate.z)).`is`(Blocks.BLUE_TERRACOTTA)) {
          room.rotation = index * 90
          room.corner = candidate
          return
        }
      }
    }
  }

  private fun hashCeiling(level: net.minecraft.client.multiplayer.ClientLevel, x: Int, z: Int): Int {
    if (legacyBlockIds.isEmpty()) return 0

    val builder = StringBuilder()
    for (y in 140 downTo 12) {
      val state = level.getBlockState(BlockPos(x, y, z))
      val block = state.block
      val blockId = getLegacyBlockId(state, block) ?: continue

      if (block == Blocks.IRON_BARS || block == Blocks.CHEST) {
        builder.append('0')
        continue
      }

      builder.append(blockId)
    }

    return builder.toString().hashCode()
  }

  private fun getLegacyBlockId(
    state: net.minecraft.world.level.block.state.BlockState,
    block: Block,
  ): Int? {
    var registryName = BuiltInRegistries.BLOCK.getKey(block).toString()

    if (!state.fluidState.isEmpty) {
      if (state.fluidState.isSource) {
        if (block == Blocks.WATER) return 9
        if (block == Blocks.LAVA) return 11
      } else {
        if (block == Blocks.WATER) return 8
        if (block == Blocks.LAVA) return 10
      }
    }

    if (block is SlabBlock) {
      registryName += "[type=${state.getValue(SlabBlock.TYPE).getSerializedName()}]"
    }

    return legacyBlockIds[registryName]
  }

  private fun findAvailableComponents(): List<GridComponent> {
    val positions = ArrayList<GridComponent>()
    for (z in 0 until GRID_SIZE) {
      for (x in 0 until GRID_SIZE) {
        val component = GridComponent(x, z)
        if ((x % 2 != 0) && (z % 2 != 0)) continue
        positions.add(component)
      }
    }
    return positions
  }

  private fun isChunkLoaded(level: net.minecraft.client.multiplayer.ClientLevel, x: Int, z: Int): Boolean =
    level.hasChunk(x shr 4, z shr 4)

  private fun getHighestY(level: net.minecraft.client.multiplayer.ClientLevel, x: Int, z: Int): Int {
    for (y in 256 downTo 0) {
      val state = level.getBlockState(BlockPos(x, y, z))
      if (!state.isAir && !state.`is`(Blocks.GOLD_BLOCK)) {
        return y
      }
    }
    return 0
  }

  private fun getBlock(level: net.minecraft.client.multiplayer.ClientLevel, x: Int, y: Int, z: Int): Block? {
    return runCatching { level.getBlockState(BlockPos(x, y, z)).block }.getOrNull()
  }
}
