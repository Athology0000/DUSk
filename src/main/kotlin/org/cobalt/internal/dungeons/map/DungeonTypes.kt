package org.cobalt.internal.dungeons.map

import kotlin.math.floor
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks

internal const val CORNER_START_X: Int = -200
internal const val CORNER_START_Z: Int = -200
internal const val CORNER_END_X: Int = -10
internal const val CORNER_END_Z: Int = -10
internal const val ROOM_SIZE: Int = 31
internal const val ROOM_AND_DOOR_SIZE: Int = 32
internal const val HALF_ROOM_SIZE: Int = ROOM_SIZE / 2
internal const val HALF_COMPONENT_SIZE: Int = ROOM_AND_DOOR_SIZE / 2
internal const val GRID_SIZE: Int = 11
internal const val ROOM_COUNT: Int = 36
internal const val DOOR_COUNT: Int = 60
internal const val MAP_SIZE: Int = 128
internal const val MAP_ROOM_GAP: Int = 4

data class GridComponent(
  val x: Int,
  val z: Int,
) {

  fun isValid(): Boolean = x in 0..10 && z in 0..10

  fun isRoom(): Boolean = (x and 1) == 0 && (z and 1) == 0

  fun isDoor(): Boolean = ((x and 1) xor (z and 1)) == 1

  fun roomIndex(): Int = (z / 2) * 6 + (x / 2)

  fun doorIndex(): Int {
    val index = ((x - 1) shr 1) + 6 * z
    return index - index / 12
  }

  fun toWorldCenter(): BlockPos =
    BlockPos(
      CORNER_START_X + HALF_ROOM_SIZE + HALF_COMPONENT_SIZE * x,
      0,
      CORNER_START_Z + HALF_ROOM_SIZE + HALF_COMPONENT_SIZE * z,
    )

  fun neighboringRooms(): List<GridComponent> {
    if (!isDoor()) return emptyList()
    return if ((x and 1) == 1) {
      listOf(GridComponent(x - 1, z), GridComponent(x + 1, z)).filter { it.isValid() }
    } else {
      listOf(GridComponent(x, z - 1), GridComponent(x, z + 1)).filter { it.isValid() }
    }
  }

  fun neighboringDoors(): List<GridComponent> {
    if (!isRoom()) return emptyList()
    return listOf(
      GridComponent(x, z - 1),
      GridComponent(x, z + 1),
      GridComponent(x - 1, z),
      GridComponent(x + 1, z),
    ).filter { it.isValid() }
  }

  fun roomNeighbors(): List<RoomNeighbor> {
    if (!isRoom()) return emptyList()
    return listOf(
      RoomNeighbor(GridComponent(x, z - 2), GridComponent(x, z - 1)),
      RoomNeighbor(GridComponent(x, z + 2), GridComponent(x, z + 1)),
      RoomNeighbor(GridComponent(x - 2, z), GridComponent(x - 1, z)),
      RoomNeighbor(GridComponent(x + 2, z), GridComponent(x + 1, z)),
    ).filter { it.room.isValid() }
  }

  companion object {
    fun fromWorld(worldX: Double, worldZ: Double): GridComponent =
      GridComponent(
        floor((worldX - CORNER_START_X) / HALF_COMPONENT_SIZE.toDouble()).toInt(),
        floor((worldZ - CORNER_START_Z) / HALF_COMPONENT_SIZE.toDouble()).toInt(),
      )
  }
}

data class RoomNeighbor(
  val room: GridComponent,
  val door: GridComponent,
)

data class RoomDefinition(
  val name: String,
  val type: RoomKind,
  val secrets: Int,
)

data class LoadedRoute(
  val rawKey: String,
  val steps: List<RouteStep>,
)

data class RouteStep(
  val pathLocations: List<BlockPos>,
  val etherwarps: List<BlockPos>,
  val mines: List<BlockPos>,
  val interacts: List<BlockPos>,
  val tnts: List<BlockPos>,
  val secretPos: BlockPos,
  val secretType: String,
  /** Absolute world positions for ender pearl throw start points (not room-relative). */
  val enderPearls: List<BlockPos>,
  /** Corresponding [pitch, yaw] pairs for each ender pearl throw. */
  val enderPearlAngles: List<FloatArray>,
)

data class MapPlayerMarker(
  val componentX: Double,
  val componentZ: Double,
  val rotationRadians: Double,
)

enum class DungeonFloor(
  val shortName: String,
  val roomsWide: Int,
  val roomsTall: Int,
) {
  NONE("", 0, 0),
  ENTRANCE("E", 4, 4),
  F1("F1", 4, 5),
  F2("F2", 5, 5),
  F3("F3", 5, 5),
  F4("F4", 6, 5),
  F5("F5", 6, 6),
  F6("F6", 6, 6),
  F7("F7", 6, 6),
  M1("M1", 4, 5),
  M2("M2", 5, 5),
  M3("M3", 5, 5),
  M4("M4", 6, 5),
  M5("M5", 6, 6),
  M6("M6", 6, 6),
  M7("M7", 6, 6),
  ;

  companion object {
    fun fromName(name: String?): DungeonFloor {
      if (name == null) return NONE
      val normalized = name.trim().uppercase()
      entries.firstOrNull { it.shortName.equals(normalized, ignoreCase = true) }?.let { return it }

      return when {
        normalized == "ENTRANCE" -> ENTRANCE
        normalized.startsWith("MASTER MODE FLOOR ") -> fromRoman(normalized.removePrefix("MASTER MODE FLOOR ").trim(), masterMode = true)
        normalized.startsWith("FLOOR ") -> fromRoman(normalized.removePrefix("FLOOR ").trim(), masterMode = false)
        else -> NONE
      }
    }

    private fun fromRoman(roman: String, masterMode: Boolean): DungeonFloor {
      val number =
        when (roman) {
          "I" -> 1
          "II" -> 2
          "III" -> 3
          "IV" -> 4
          "V" -> 5
          "VI" -> 6
          "VII" -> 7
          else -> return NONE
        }

      val id = if (masterMode) "M$number" else "F$number"
      return entries.firstOrNull { it.shortName == id } ?: NONE
    }
  }
}

enum class RoomKind {
  BLOOD,
  ENTRANCE,
  PUZZLE,
  RARE,
  YELLOW,
  TRAP,
  UNKNOWN,
  FAIRY,
  NORMAL,
  ;

  companion object {
    fun fromName(name: String?): RoomKind =
      entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: UNKNOWN

    fun fromMapColor(color: Byte): RoomKind =
      when (color) {
        MapColorHint.ROOM_ENTRANCE.color -> ENTRANCE
        MapColorHint.ROOM_BLOOD.color -> BLOOD
        MapColorHint.ROOM_UNOPENED.color -> NORMAL
        MapColorHint.ROOM_BOSS.color -> YELLOW
        MapColorHint.ROOM_FAIRY.color -> FAIRY
        MapColorHint.ROOM_NORMAL.color -> NORMAL
        MapColorHint.ROOM_PUZZLE.color -> PUZZLE
        MapColorHint.ROOM_TRAP.color -> TRAP
        else -> UNKNOWN
      }
  }
}

enum class RoomCheckmark {
  NONE,
  WHITE,
  GREEN,
  FAILED,
  UNEXPLORED,
}

enum class RoomShape {
  UNKNOWN,
  ONE_BY_ONE,
  ONE_BY_TWO,
  ONE_BY_THREE,
  ONE_BY_FOUR,
  TWO_BY_TWO,
  L_SHAPE,
}

enum class DoorKind {
  NORMAL,
  WITHER,
  BLOOD,
  ENTRANCE,
}

enum class RoomDirection {
  NW,
  NE,
  SE,
  SW,
}

enum class MapColorHint(val color: Byte) {
  EMPTY(0),
  CHECK_WHITE(34),
  CHECK_GREEN(30),
  CHECK_FAIL(18),
  CHECK_UNKNOWN(119),
  ROOM_ENTRANCE(30),
  ROOM_NORMAL(63),
  ROOM_UNOPENED(85),
  ROOM_TRAP(62),
  ROOM_BOSS(74),
  ROOM_PUZZLE(66),
  ROOM_FAIRY(82),
  ROOM_BLOOD(18),
  ;
}

class DungeonDoor(
  val component: GridComponent,
) {

  val rooms = linkedSetOf<DungeonRoom>()
  var rotation: Int = -1
  var opened: Boolean = false
  var type: DoorKind = DoorKind.NORMAL

  fun updateFromBlock(blockAt69: Block) {
    opened = blockAt69 == Blocks.AIR || blockAt69 == Blocks.BARRIER
    type =
      when {
        isInfested(blockAt69) -> DoorKind.ENTRANCE
        blockAt69 == Blocks.COAL_BLOCK -> DoorKind.WITHER
        blockAt69 == Blocks.RED_TERRACOTTA -> DoorKind.BLOOD
        else -> DoorKind.NORMAL
      }
  }

  private fun isInfested(block: Block): Boolean =
    block == Blocks.INFESTED_COBBLESTONE ||
      block == Blocks.INFESTED_CHISELED_STONE_BRICKS ||
      block == Blocks.INFESTED_CRACKED_STONE_BRICKS ||
      block == Blocks.INFESTED_DEEPSLATE ||
      block == Blocks.INFESTED_MOSSY_STONE_BRICKS ||
      block == Blocks.INFESTED_STONE ||
      block == Blocks.INFESTED_STONE_BRICKS
}

class DungeonRoom(
  initialComponents: MutableList<GridComponent>,
  var height: Int,
) {

  val components: MutableList<GridComponent> = initialComponents
  val doors = linkedSetOf<DungeonDoor>()

  var explored: Boolean = false
  var name: String? = null
  var type: RoomKind = RoomKind.UNKNOWN
  var checkmark: RoomCheckmark = RoomCheckmark.UNEXPLORED
  var shape: RoomShape = RoomShape.ONE_BY_ONE
  var totalSecrets: Int = 0
  var corner: BlockPos? = null
  var rotation: Int = -1

  val direction: RoomDirection?
    get() =
      when (rotation) {
        0 -> RoomDirection.NW
        90 -> RoomDirection.NE
        180 -> RoomDirection.SE
        270 -> RoomDirection.SW
        else -> null
      }

  fun updateShape() {
    val size = components.size
    if (size <= 0 || size > 4) {
      shape = RoomShape.UNKNOWN
      return
    }

    val distinctX = components.mapTo(linkedSetOf()) { it.x }
    val distinctZ = components.mapTo(linkedSetOf()) { it.z }

    shape =
      when {
        size == 1 -> RoomShape.ONE_BY_ONE
        size == 2 -> RoomShape.ONE_BY_TWO
        size == 4 && (distinctX.size == 1 || distinctZ.size == 1) -> RoomShape.ONE_BY_FOUR
        size == 4 -> RoomShape.TWO_BY_TWO
        size == 3 && (distinctX.size == size || distinctZ.size == size) -> RoomShape.ONE_BY_THREE
        size == 3 -> RoomShape.L_SHAPE
        else -> RoomShape.UNKNOWN
      }
  }

  fun addComponent(component: GridComponent, shouldUpdate: Boolean = true) {
    if (components.contains(component)) return
    components.add(component)
    if (shouldUpdate) {
      updateShape()
    }
  }

  fun applyDefinition(definition: RoomDefinition) {
    name = definition.name
    if (definition.type != RoomKind.UNKNOWN) {
      type = definition.type
    }
    totalSecrets = definition.secrets
  }
}
