package org.phantom.api.pathfinder.cache

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.LevelChunk
import org.phantom.api.event.annotation.SubscribeEvent
import org.phantom.api.event.impl.client.BlockChangeEvent
import org.phantom.api.event.impl.client.TickEvent
import org.phantom.api.pathfinder.jni.NativePathfinderBridge
import org.phantom.api.pathfinder.jni.NativeStateEncoder
import org.phantom.api.util.ChatUtils
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object CachedWorld {

    const val CHUNKS_PER_TICK = 8
    const val MAXIMUM_CACHED_CHUNKS = 4096

    val executor: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "Phantom-Swift-${System.currentTimeMillis()}").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }

    @Volatile private var chunks = ConcurrentHashMap<Long, CachedChunk>(512)
    private val chunkInsertionOrder = ArrayDeque<Long>(512)
    private val pendingChunks = ConcurrentLinkedQueue<LevelChunk>()
    private val pendingNativeUpdates = ConcurrentHashMap<Long, Int>(256)

    private const val RUNTIME_WORLD_KEY = "runtime_memory"
    @Volatile private var worldKey: String = RUNTIME_WORLD_KEY
    @Volatile private var worldDisplayName: String = "Runtime"
    @Volatile private var nativeWorldToken: String = ""
    @Volatile private var pendingNativeResync = true
    @Volatile private var dirty = false
    private var lastAutoSaveTick = 0L

    private var cacheKey: Long = Long.MIN_VALUE
    private var cacheChunk: CachedChunk? = null

    @Volatile private var unlimitedChunkCache = false

    data class CachedChunkPos(val x: Int, val z: Int)

    private fun chunkKey(x: Int, z: Int): Long =
        (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)

    private fun blockKey(x: Int, y: Int, z: Int): Long {
        val packedX = (x.toLong() + 33_554_432L) and 0x3FFFFFFL
        val packedY = (y.toLong() + 2_048L) and 0xFFFL
        val packedZ = (z.toLong() + 33_554_432L) and 0x3FFFFFFL
        return (packedX shl 38) or (packedY shl 26) or packedZ
    }

    private fun unpackBlockX(key: Long): Int = ((key ushr 38) and 0x3FFFFFFL).toInt() - 33_554_432
    private fun unpackBlockY(key: Long): Int = ((key ushr 26) and 0xFFFL).toInt() - 2_048
    private fun unpackBlockZ(key: Long): Int = (key and 0x3FFFFFFL).toInt() - 33_554_432

    @JvmStatic
    fun getBlockFlags(x: Int, y: Int, z: Int): Short? {
        val chunkX = x shr 4
        val chunkZ = z shr 4
        val key = chunkKey(chunkX, chunkZ)

        val cached = cacheChunk
        if (cacheKey == key && cached != null && cached.ready) {
            return cached.getFlags(x and 15, y, z and 15)
        }

        val chunk = chunks[key] ?: return null
        if (!chunk.ready) return null

        cacheKey = key
        cacheChunk = chunk
        return chunk.getFlags(x and 15, y, z and 15)
    }

    @JvmStatic
    fun getChunk(x: Int, z: Int): CachedChunk? {
        val key = chunkKey(x, z)
        val chunk = chunks[key]
        return if (chunk?.ready == true) chunk else null
    }

    fun cacheLoadedChunksAround(level: Level, center: BlockPos, radiusChunks: Int): Int {
        val dimType = level.dimensionType()
        val minY = dimType.minY()
        val maxY = minY + dimType.height()
        ensureNativeWorld(level.dimension().toString(), minY, maxY)

        val centerChunkX = center.x shr 4
        val centerChunkZ = center.z shr 4
        var cachedCount = 0
        for (chunkX in centerChunkX - radiusChunks..centerChunkX + radiusChunks) {
            for (chunkZ in centerChunkZ - radiusChunks..centerChunkZ + radiusChunks) {
                val chunk = level.getChunk(chunkX, chunkZ) as? LevelChunk ?: continue
                if (cacheChunk(chunk, minY, maxY)) cachedCount++
            }
        }
        return cachedCount
    }

    fun register() {
        ClientChunkEvents.CHUNK_LOAD.register { _, chunk ->
            pendingChunks.add(chunk)
        }
        ClientPlayConnectionEvents.JOIN.register { _, _, client ->
            val key = deriveServerKey(client)
            setWorldKey(key, key)
            if (key != null) load(key)
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            val key = worldKey
            if (key != RUNTIME_WORLD_KEY) saveAndClear(key) else clear()
            setWorldKey(null)
        }
    }

    private fun deriveServerKey(client: Minecraft): String? {
        val ip = client.currentServer?.ip ?: return null
        val host = ip.substringBefore(':').trim().trimEnd('.').lowercase()
        val sanitized = host.replace(Regex("[^a-zA-Z0-9._\\-]"), "_")
        return sanitized.takeUnless { it.isBlank() || it.all { c -> c == '_' || c == '.' || c == '-' } }
    }

    @SubscribeEvent
    fun onBlockChange(event: BlockChangeEvent) {
        val pos = event.pos
        val key = chunkKey(pos.x shr 4, pos.z shr 4)
        val chunk = chunks[key]
        if (chunk != null && chunk.ready) {
            val flags = NativeStateEncoder.flagsShortForState(event.newBlock)
            chunk.setFlags(pos.x and 15, pos.y, pos.z and 15, flags)
            queueNativeUpdate(pos.x, pos.y, pos.z, flags.toInt() and 0xFFFF)
            if (cacheKey == key) cacheChunk = chunk
            dirty = true
        }
    }

    @SubscribeEvent
    fun onTick(@Suppress("UNUSED_PARAMETER") event: TickEvent.End) {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return

        val dimType = level.dimensionType()
        val minY = dimType.minY()
        val maxY = minY + dimType.height()

        ensureNativeWorld(level.dimension().toString(), minY, maxY)

        repeat(CHUNKS_PER_TICK) {
            val chunk = pendingChunks.poll() ?: return@repeat
            cacheChunk(chunk, minY, maxY)
        }

        if (!unlimitedChunkCache && chunks.size > MAXIMUM_CACHED_CHUNKS) {
            var toRemove = chunks.size - MAXIMUM_CACHED_CHUNKS
            while (toRemove-- > 0 && chunkInsertionOrder.isNotEmpty()) {
                val oldest = chunkInsertionOrder.removeFirst()
                chunks.remove(oldest)
                if (cacheKey == oldest) { cacheKey = Long.MIN_VALUE; cacheChunk = null }
            }
        }

        if (pendingNativeResync) {
            syncAllCachedChunksToNative()
            pendingNativeResync = false
        }

        flushPendingNativeUpdates()
        autoSave(level.gameTime)
    }

    private fun resetState() {
        chunks = ConcurrentHashMap(512)
        chunkInsertionOrder.clear()
        pendingChunks.clear()
        pendingNativeUpdates.clear()
        cacheKey = Long.MIN_VALUE
        cacheChunk = null
        dirty = false
        pendingNativeResync = true
        nativeWorldToken = ""
        NativePathfinderBridge.clearWorld()
    }

    fun saveAndClear(lobbyName: String) {
        val mapToSave = chunks
        resetState()
        if (mapToSave.isNotEmpty()) {
            executor.submit {
                try {
                    WorldSerializer.save(lobbyName, mapToSave)
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
    }

    fun load(lobbyName: String, notify: Boolean = true) {
        resetState()
        val sessionMap = chunks
        executor.submit {
            try {
                val loaded = WorldSerializer.load(lobbyName)
                if (loaded != null && chunks === sessionMap) {
                    for ((key, chunk) in loaded) sessionMap.putIfAbsent(key, chunk)
                    dirty = false
                    // Push disk-loaded chunks to the native pathfinder directly.
                    // The first onTick after resetState clears pendingNativeResync
                    // before the async disk read finishes, so disk chunks would
                    // otherwise never reach the native side — findPath then sees
                    // a world of solid walls and returns null on every long path.
                    syncAllCachedChunksToNative()
                    if (notify) {
                        sendCacheMessage("World cache: ${getWorldDisplayName()} loaded ${loaded.values.count { it.ready }} chunks.")
                    }
                } else if (loaded == null && chunks === sessionMap) {
                    if (notify) {
                        sendCacheMessage("World cache: ${getWorldDisplayName()} started fresh.")
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun clear() {
        resetState()
    }

    val readyChunkCount: Int get() = chunks.values.count { it.ready }

    fun readyChunkPositions(): List<CachedChunkPos> =
        chunks.entries
            .filter { it.value.ready }
            .map { (key, _) -> CachedChunkPos((key shr 32).toInt(), key.toInt()) }

    fun getCacheStats(): String {
        val ready = chunks.values.count { it.ready }
        return "Cached: $ready, Pending: ${pendingChunks.size}"
    }

    fun getWorldKey(): String = worldKey

    fun getWorldDisplayName(): String = worldDisplayName

    fun setUnlimitedChunkCache(enabled: Boolean) {
        unlimitedChunkCache = enabled
    }

    fun setWorldKey(newWorldKey: String?, displayName: String? = null) {
        val normalized = newWorldKey?.ifBlank { RUNTIME_WORLD_KEY } ?: RUNTIME_WORLD_KEY
        val normalizedDisplayName = displayName?.takeIf { it.isNotBlank() }
            ?: newWorldKey?.takeIf { it.isNotBlank() }
            ?: "Runtime"
        if (worldKey == normalized) {
            worldDisplayName = normalizedDisplayName
            return
        }
        val previous = worldKey
        if (previous != RUNTIME_WORLD_KEY && dirty) {
            save(previous)
        }
        worldKey = normalized
        worldDisplayName = normalizedDisplayName
        nativeWorldToken = ""
        pendingNativeResync = true
        NativePathfinderBridge.clearWorld()
    }

    fun save(lobbyName: String = worldKey) {
        if (lobbyName == RUNTIME_WORLD_KEY) return
        val snapshot = ConcurrentHashMap(chunks)
        val count = snapshot.values.count { it.ready }
        if (count == 0) return
        dirty = false
        executor.submit {
            try {
                WorldSerializer.save(lobbyName, snapshot)
            } catch (e: Exception) {
                dirty = true
                e.printStackTrace()
            }
        }
    }

    private fun autoSave(gameTime: Long) {
        if (!dirty || worldKey == RUNTIME_WORLD_KEY) return
        if (gameTime - lastAutoSaveTick < AUTO_SAVE_INTERVAL_TICKS) return
        lastAutoSaveTick = gameTime
        save(worldKey)
    }

    private fun ensureNativeWorld(dimKey: String, minY: Int, maxY: Int) {
        val token = "$worldKey|$dimKey|$minY|$maxY"
        if (token == nativeWorldToken) return
        NativePathfinderBridge.setWorld(worldKey, minY, maxY)
        if (NativePathfinderBridge.getLastError() == null) {
            nativeWorldToken = token
            pendingNativeResync = true
        }
    }

    private fun syncAllCachedChunksToNative() {
        for ((key, chunk) in chunks) {
            if (!chunk.ready) continue
            val chunkX = (key shr 32).toInt()
            val chunkZ = key.toInt()
            syncChunkToNative(chunkX, chunkZ, chunk)
        }
    }

    private fun cacheChunk(chunk: LevelChunk, minY: Int, maxY: Int): Boolean {
        val chunkX = chunk.pos.x
        val chunkZ = chunk.pos.z
        val cached = CachedChunk(minY, maxY)
        val sections = chunk.sections

        for (sectionIndex in sections.indices) {
            val section = sections[sectionIndex]
            if (section.hasOnlyAir()) continue

            val sectionData = ShortArray(4096) { CachedChunk.AIR_FLAGS }
            for (ly in 0..15) {
                val yOffset = ly shl 8
                for (lz in 0..15) {
                    val zOffset = lz shl 4
                    for (lx in 0..15) {
                        sectionData[yOffset or zOffset or lx] =
                            NativeStateEncoder.flagsShortForState(section.getBlockState(lx, ly, lz))
                    }
                }
            }
            cached.setSection(sectionIndex, sectionData)
        }

        cached.ready = true
        val key = chunkKey(chunkX, chunkZ)
        if (!chunks.containsKey(key)) chunkInsertionOrder.addLast(key)
        chunks[key] = cached
        if (cacheKey == key) cacheChunk = cached
        dirty = true
        syncChunkToNative(chunkX, chunkZ, cached)
        return true
    }

    private fun syncChunkToNative(chunkX: Int, chunkZ: Int, chunk: CachedChunk) {
        if (!chunk.ready) return
        val sectionCount = (chunk.maxY - chunk.minY + 15) shr 4
        var sectionMask = 0L
        var totalValues = 0
        for (i in 0 until sectionCount) {
            if (chunk.hasSection(i)) {
                sectionMask = sectionMask or (1L shl i)
                totalValues += 4096
            }
        }

        if (totalValues == 0) {
            NativePathfinderBridge.upsertChunk(chunkX, chunkZ, chunk.minY, chunk.maxY, 0L, ShortArray(0))
            return
        }

        val sectionFlags = ShortArray(totalValues)
        var offset = 0
        for (i in 0 until sectionCount) {
            if ((sectionMask and (1L shl i)) == 0L) continue
            chunk.copySectionFlags(i, sectionFlags, offset)
            offset += 4096
        }
        NativePathfinderBridge.upsertChunk(chunkX, chunkZ, chunk.minY, chunk.maxY, sectionMask, sectionFlags)
    }

    private fun flushPendingNativeUpdates() {
        if (pendingNativeUpdates.isEmpty()) return

        var updates = IntArray(maxOf(16, pendingNativeUpdates.size * 4))
        var offset = 0
        pendingNativeUpdates.forEach { key, flags ->
            if (!pendingNativeUpdates.remove(key, flags)) return@forEach
            if (offset + 4 > updates.size) {
                updates = updates.copyOf(maxOf(updates.size shl 1, offset + 4))
            }
            updates[offset] = unpackBlockX(key)
            updates[offset + 1] = unpackBlockY(key)
            updates[offset + 2] = unpackBlockZ(key)
            updates[offset + 3] = flags
            offset += 4
        }

        if (offset == 0) return
        NativePathfinderBridge.applyBlockUpdates(if (offset == updates.size) updates else updates.copyOf(offset))
    }

    private fun queueNativeUpdate(x: Int, y: Int, z: Int, flags: Int) {
        pendingNativeUpdates[blockKey(x, y, z)] = flags
    }

    private fun sendCacheMessage(message: String) {
        Minecraft.getInstance().execute {
            ChatUtils.sendMessage(message)
        }
    }

    private const val AUTO_SAVE_INTERVAL_TICKS = 1200L
}
