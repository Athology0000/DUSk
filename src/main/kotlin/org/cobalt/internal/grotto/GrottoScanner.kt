package org.cobalt.internal.grotto

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.awt.Color
import java.io.File
import java.util.concurrent.BlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.TickEvent
import org.cobalt.api.event.impl.render.WorldRenderEvent
import org.cobalt.api.util.render.Render3D
import org.cobalt.internal.mining.FairyModule

object GrottoScanner {

  private val mc = Minecraft.getInstance()

  private const val MAX_CHUNK_RADIUS = 8
  private const val MIN_SCAN_Y = 30
  private const val MAX_SCAN_Y = 185
  private const val MAX_ENQUEUE_PER_TICK = 64
  private const val MAX_RESULTS_APPLY_PER_TICK = 8
  private const val MAX_BACKLOG = 512
  private const val EMPTY_RETRY_MS_FAST = 600L
  private const val EMPTY_RETRY_MS_SLOW = 1500L
  private const val EMPTY_TRIES_BEFORE_FINALIZE = 10
  private const val ERROR_RETRY_MS = 350L
  private const val SCAN_EVERY_TICKS = 1L
  private const val RENDER_MAX_DIST_BLOCKS = 140
  private const val TRACER_CELL_SIZE = 10
  private const val TRACER_MAX = 120
  private const val PRUNE_INTERVAL_TICKS = 20L

  private val magentaBlocks = ArrayList<BlockPos>()
  private val magentaKeys = HashSet<Long>()
  private val builtInBlacklist = HashSet<Long>()
  private val userBlacklist = HashMap<Long, BlockPos>()

  private val gson = GsonBuilder().setPrettyPrinting().create()
  private var blacklistFile: File? = null
  private var blacklistLoaded = false

  private val cooldownUntilMs = ConcurrentHashMap<String, Long>()
  private val emptyTries = ConcurrentHashMap<String, Int>()
  private val queuedChunks = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
  private val chunkQueue = ArrayList<ChunkEntry>()
  private val workQueue: BlockingQueue<ChunkWork> = LinkedBlockingQueue()
  private val resultQueue: ConcurrentLinkedQueue<ScanResult> = ConcurrentLinkedQueue()

  private var tickCounter = 0L
  private var queueCenterChunkX = 0
  private var queueCenterChunkZ = 0
  private var queueValid = false
  private var lastWorld: ClientLevel? = null
  private var lastDimension: net.minecraft.resources.ResourceKey<Level>? = null
  private var generation = 0
  private var workerRunning = false
  private var executor: java.util.concurrent.ExecutorService? = null

  private var enabled = false

  init {
    addBuiltIn(522, 116, 561)
    addBuiltIn(523, 116, 560)
    addBuiltIn(523, 115, 559)
    addBuiltIn(522, 115, 557)
    addBuiltIn(521, 116, 559)
    addBuiltIn(521, 116, 558)
    addBuiltIn(520, 115, 559)
    addBuiltIn(520, 117, 559)
    addBuiltIn(521, 119, 560)
    addBuiltIn(520, 118, 560)
    addBuiltIn(519, 117, 560)
    addBuiltIn(506, 117, 559)
    addBuiltIn(505, 119, 560)
    addBuiltIn(504, 118, 560)
    addBuiltIn(504, 117, 559)
    addBuiltIn(506, 115, 558)
    addBuiltIn(503, 116, 559)
    addBuiltIn(504, 115, 558)
  }

  fun toggle() {
    setEnabled(!enabled, announce = true)
  }

  private fun syncSetting() {
    val desired = FairyModule.scannerEnabled.value
    if (desired != enabled) {
      setEnabled(desired, announce = false)
    }
  }

  private fun setEnabled(value: Boolean, announce: Boolean) {
    if (enabled == value) return
    enabled = value
    FairyModule.scannerEnabled.value = value

    if (announce) {
      val state = if (enabled) "Enabled" else "Disabled"
      GrottoChat.grotto(state)
    }

    if (enabled) {
      initBlacklistFile()
      startWorkerIfNeeded()
    } else {
      stopWorker()
      clearAll()
    }
  }

  @JvmStatic
  fun getMagentaBlocks(): List<BlockPos> = magentaBlocks

  @JvmStatic
  fun initBlacklistFile() {
    if (blacklistFile == null) {
      blacklistFile = File(mc.gameDirectory, "config/cobalt/grotto_blacklist.json")
    }
    if (!blacklistLoaded) {
      loadUserBlacklist()
      blacklistLoaded = true
    }
  }

  fun isBlacklisted(pos: BlockPos?): Boolean {
    if (pos == null) return false
    val key = pack(pos.x, pos.y, pos.z)
    return builtInBlacklist.contains(key) || userBlacklist.containsKey(key)
  }

  fun addPermanentBlacklist(pos: BlockPos?): Boolean {
    if (pos == null) return false
    initBlacklistFile()
    val key = pack(pos.x, pos.y, pos.z)
    if (builtInBlacklist.contains(key)) return false
    if (userBlacklist.containsKey(key)) return false
    userBlacklist[key] = pos
    saveUserBlacklist()
    return true
  }

  fun removePermanentBlacklist(pos: BlockPos?): Boolean {
    if (pos == null) return false
    initBlacklistFile()
    val key = pack(pos.x, pos.y, pos.z)
    val removed = userBlacklist.remove(key)
    if (removed != null) {
      saveUserBlacklist()
      return true
    }
    return false
  }

  fun clearPermanentBlacklist() {
    initBlacklistFile()
    userBlacklist.clear()
    saveUserBlacklist()
  }

  private fun loadUserBlacklist() {
    userBlacklist.clear()
    val file = blacklistFile ?: return
    if (!file.exists()) return

    val text = runCatching { file.readText() }.getOrNull()?.trim().orEmpty()
    if (text.isEmpty()) return

    val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return
    val blocks = obj.getAsJsonArray("blocks") ?: return

    blocks.forEach { element ->
      if (!element.isJsonObject) return@forEach
      val entry = element.asJsonObject
      if (!entry.has("x") || !entry.has("y") || !entry.has("z")) return@forEach

      val x = entry.get("x").asInt
      val y = entry.get("y").asInt
      val z = entry.get("z").asInt
      val key = pack(x, y, z)
      userBlacklist[key] = BlockPos(x, y, z)
    }
  }

  private fun saveUserBlacklist() {
    val file = blacklistFile ?: return
    file.parentFile?.mkdirs()

    val root = JsonObject()
    root.addProperty("version", 1)

    val blocks = JsonArray()
    userBlacklist.values.forEach { pos ->
      val obj = JsonObject()
      obj.addProperty("x", pos.x)
      obj.addProperty("y", pos.y)
      obj.addProperty("z", pos.z)
      blocks.add(obj)
    }

    root.add("blocks", blocks)
    file.writeText(gson.toJson(root))
  }

  @SubscribeEvent
  fun onTick(@Suppress("UNUSED_PARAMETER") event: TickEvent.End) {
    syncSetting()
    if (!enabled) return

    val level = mc.level
    val player = mc.player
    if (level == null || player == null) return

    if (!CrystalHollowsDetector.isInCrystalHollows()) return

    initBlacklistFile()
    startWorkerIfNeeded()

    if (level !== lastWorld) {
      lastWorld = level
      clearAll()
    }

    val dimension = level.dimension()
    if (lastDimension != dimension) {
      lastDimension = dimension
      clearAll()
    }

    tickCounter++
    if (tickCounter % SCAN_EVERY_TICKS != 0L) return

    applyResults()
    if (tickCounter % PRUNE_INTERVAL_TICKS == 0L) {
      pruneMinedBlocks(level)
    }

    val chunkPos = player.chunkPosition()
    val cx = chunkPos.x
    val cz = chunkPos.z

    if (!queueValid || cx != queueCenterChunkX || cz != queueCenterChunkZ) {
      rebuildChunkQueue(cx, cz)
    }

    if (chunkQueue.isEmpty()) return
    if (workQueue.size > MAX_BACKLOG) return

    val now = System.currentTimeMillis()
    var enqueued = 0

    while (chunkQueue.isNotEmpty() && enqueued < MAX_ENQUEUE_PER_TICK) {
      val entry = chunkQueue.removeAt(0)
      val key = "${entry.cx},${entry.cz}"

      if (queuedChunks.contains(key)) continue

      val cooldown = cooldownUntilMs[key]
      if (cooldown != null) {
        if (cooldown == Long.MAX_VALUE || now < cooldown) continue
      }

      val chunk = getLoadedChunk(level, entry.cx, entry.cz) ?: continue

      queuedChunks.add(key)
      workQueue.offer(ChunkWork(chunk, entry.cx, entry.cz, generation))
      enqueued++
    }
  }

  @SubscribeEvent
  fun onRender(event: WorldRenderEvent.Last) {
    if (!enabled) return
    if (!CrystalHollowsDetector.isInCrystalHollows()) return

    val player = mc.player ?: return
    val level = mc.level ?: return

    if (magentaBlocks.isEmpty()) return

    val context = event.context
    val camPos = player.eyePosition
    val maxDistSq = (RENDER_MAX_DIST_BLOCKS * RENDER_MAX_DIST_BLOCKS).toDouble()

    if (FairyModule.scannerRenderBoxes.value) {
      magentaBlocks.forEach { pos ->
        val dx = (pos.x + 0.5) - camPos.x
        val dy = (pos.y + 0.5) - camPos.y
        val dz = (pos.z + 0.5) - camPos.z
        if (dx * dx + dy * dy + dz * dz > maxDistSq) return@forEach

        val box = AABB(
          pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble(),
          pos.x + 1.0, pos.y + 1.0, pos.z + 1.0
        )
        Render3D.drawBox(context, box, Color(255, 0, 255, 115), esp = true)
      }
    }

    if (FairyModule.scannerRenderTracers.value) {
      val selected = HashMap<Long, BlockPos>()
      val selectedDist = HashMap<Long, Double>()

      for (pos in magentaBlocks) {
        val dx = (pos.x + 0.5) - camPos.x
        val dy = (pos.y + 0.5) - camPos.y
        val dz = (pos.z + 0.5) - camPos.z
        val distSq = dx * dx + dy * dy + dz * dz
        if (distSq > maxDistSq) continue

        val cellX = floorDiv(pos.x, TRACER_CELL_SIZE)
        val cellZ = floorDiv(pos.z, TRACER_CELL_SIZE)
        val cellKey = (cellX.toLong() shl 32) xor (cellZ.toLong() and 0xffffffffL)

        val existingDist = selectedDist[cellKey]
        if (existingDist == null || distSq < existingDist) {
          selectedDist[cellKey] = distSq
          selected[cellKey] = pos
        }

        if (selected.size >= TRACER_MAX) break
      }

      val start = player.eyePosition
      selected.values.forEach { pos ->
        val end = Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
        Render3D.drawLine(context, start, end, Color(255, 0, 255, 200), esp = true, thickness = 0.75f)
      }
    }
  }

  private fun applyResults() {
    var applied = 0
    val now = System.currentTimeMillis()

    while (applied < MAX_RESULTS_APPLY_PER_TICK) {
      val result = resultQueue.poll() ?: break
      if (result.gen != generation) continue

      val key = "${result.chunkX},${result.chunkZ}"

      if (result.hadError) {
        cooldownUntilMs[key] = now + ERROR_RETRY_MS
        applied++
        continue
      }

      val found = result.found ?: emptyList()
      if (found.isEmpty()) {
        val tries = inc(emptyTries, key)
        val delay = if (tries <= 4) EMPTY_RETRY_MS_FAST else EMPTY_RETRY_MS_SLOW
        cooldownUntilMs[key] = now + delay
        if (tries >= EMPTY_TRIES_BEFORE_FINALIZE) {
          cooldownUntilMs[key] = Long.MAX_VALUE
        }
        applied++
        continue
      }

      emptyTries.remove(key)
      cooldownUntilMs[key] = Long.MAX_VALUE

      found.forEach { pos ->
        val packed = pack(pos.x, pos.y, pos.z)
        if (builtInBlacklist.contains(packed)) return@forEach
        if (userBlacklist.containsKey(packed)) return@forEach
        if (magentaKeys.add(packed)) {
          magentaBlocks.add(pos)
        }
      }

      applied++
    }
  }

  private fun pruneMinedBlocks(level: ClientLevel) {
    if (magentaBlocks.isEmpty()) return
    val iterator = magentaBlocks.iterator()
    while (iterator.hasNext()) {
      val pos = iterator.next()
      if (!isMagentaState(level.getBlockState(pos))) {
        iterator.remove()
        magentaKeys.remove(pack(pos.x, pos.y, pos.z))
      }
    }
  }

  private fun rebuildChunkQueue(centerX: Int, centerZ: Int) {
    chunkQueue.clear()

    var radius = MAX_CHUNK_RADIUS
    val options = mc.options
    if (options != null) {
      radius = kotlin.math.min(radius, options.renderDistance().get())
    }

    for (x in centerX - radius..centerX + radius) {
      for (z in centerZ - radius..centerZ + radius) {
        val dx = x - centerX
        val dz = z - centerZ
        val dist = dx * dx + dz * dz
        chunkQueue.add(ChunkEntry(x, z, dist))
      }
    }

    chunkQueue.sortWith(compareBy { it.dist2 })
    queueCenterChunkX = centerX
    queueCenterChunkZ = centerZ
    queueValid = true
  }

  private fun getLoadedChunk(level: ClientLevel, chunkX: Int, chunkZ: Int): LevelChunk? {
    return runCatching { level.chunkSource.getChunk(chunkX, chunkZ, false) }.getOrNull()
  }

  private fun scanChunkOffThread(chunk: LevelChunk, chunkX: Int, chunkZ: Int): List<BlockPos> {
    val found = ArrayList<BlockPos>()
    val sections = chunk.sections

    for (sectionIdx in sections.indices) {
      val section = sections[sectionIdx] ?: continue
      if (section.hasOnlyAir()) continue

      val baseY = sectionIdx shl 4
      if (baseY > MAX_SCAN_Y || baseY + 15 < MIN_SCAN_Y) continue

      for (localY in 0..15) {
        val worldY = baseY + localY
        if (worldY < MIN_SCAN_Y || worldY > MAX_SCAN_Y) continue

        for (localZ in 0..15) {
          for (localX in 0..15) {
            val state = section.getBlockState(localX, localY, localZ)
            if (!isMagentaState(state)) continue

            val worldX = (chunkX shl 4) + localX
            val worldZ = (chunkZ shl 4) + localZ
            found.add(BlockPos(worldX, worldY, worldZ))
          }
        }
      }
    }

    return found
  }

  private fun isMagentaState(state: BlockState?): Boolean {
    if (state == null) return false
    val block = state.block
    return block == Blocks.MAGENTA_STAINED_GLASS || block == Blocks.MAGENTA_STAINED_GLASS_PANE
  }

  private fun startWorkerIfNeeded() {
    if (workerRunning && executor != null) return
    workerRunning = true

    if (executor == null || executor!!.isShutdown) {
      executor = Executors.newSingleThreadExecutor { runnable ->
        val thread = Thread(runnable, "GrottoScanner")
        thread.isDaemon = true
        thread
      }
    }

    executor?.submit { workerLoop() }
  }

  private fun stopWorker() {
    generation++
    workerRunning = false

    runCatching { executor?.shutdownNow() }
    executor = null
    workQueue.clear()
    resultQueue.clear()
    queuedChunks.clear()
  }

  private fun workerLoop() {
    while (workerRunning && !Thread.currentThread().isInterrupted) {
      val work = try {
        workQueue.poll(250, TimeUnit.MILLISECONDS)
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        return
      }

      if (work == null) continue
      if (work.gen != generation) {
        queuedChunks.remove("${work.chunkX},${work.chunkZ}")
        continue
      }

      var hadError = false
      val found: List<BlockPos> = try {
        scanChunkOffThread(work.chunk, work.chunkX, work.chunkZ)
      } catch (_: Throwable) {
        hadError = true
        emptyList()
      }

      queuedChunks.remove("${work.chunkX},${work.chunkZ}")
      resultQueue.add(ScanResult(found, work.chunkX, work.chunkZ, hadError, work.gen))
    }
  }

  private fun clearAll() {
    magentaBlocks.clear()
    magentaKeys.clear()
    cooldownUntilMs.clear()
    emptyTries.clear()
    queuedChunks.clear()
    chunkQueue.clear()
    workQueue.clear()
    resultQueue.clear()
    queueValid = false
    tickCounter = 0L
    generation++
  }

  private fun addBuiltIn(x: Int, y: Int, z: Int) {
    builtInBlacklist.add(pack(x, y, z))
  }

  private fun pack(x: Int, y: Int, z: Int): Long {
    val lx = x.toLong() and 0x3ffffff
    val ly = y.toLong() and 0xfff
    val lz = z.toLong() and 0x3ffffff
    return (lx shl 38) or (ly shl 26) or lz
  }

  private fun floorDiv(a: Int, b: Int): Int {
    var r = a / b
    val mod = a % b
    if (mod != 0 && (mod < 0) != (b < 0)) {
      r -= 1
    }
    return r
  }

  private fun inc(map: ConcurrentHashMap<String, Int>, key: String): Int {
    val next = (map[key] ?: 0) + 1
    map[key] = next
    return next
  }

  private data class ChunkEntry(val cx: Int, val cz: Int, val dist2: Int)

  private data class ChunkWork(val chunk: LevelChunk, val chunkX: Int, val chunkZ: Int, val gen: Int)

  private data class ScanResult(
    val found: List<BlockPos>?,
    val chunkX: Int,
    val chunkZ: Int,
    val hadError: Boolean,
    val gen: Int,
  )

}
