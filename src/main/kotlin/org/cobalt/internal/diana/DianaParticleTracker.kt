package org.cobalt.internal.diana

import net.minecraft.client.Minecraft
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.world.level.Level
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.PacketEvent
import kotlin.math.floor

/**
 * Thread-safe detector of Diana Griffin burrow locations via CRIT particle packets.
 *
 * SkyHanni reference: GriffinBurrowParticleFinder.kt - MOB burrow particles:
 *   type=CRIT, spawned at the burrow block's top surface.
 *
 * Each CRIT packet more than 3 blocks away is treated as a burrow indicator.
 * X/Z are taken directly from the packet (block-floor precision);
 * Y is snapped to the surface heightmap in getBurrowPos() for reliability.
 */
object DianaParticleTracker {

    private val mc = Minecraft.getInstance()

    data class BurrowRecord(val bx: Int, val bz: Int, var lastSeenMs: Long)

    // Keyed by packed (bx, bz); one record per unique XZ block column
    private val seenBurrows = HashMap<Long, BurrowRecord>()

    @Volatile private var activationPos: Vec3? = null
    @Volatile private var lastParticleMs: Long = 0L
    @Volatile private var packetCount: Int = 0

    init { EventBus.register(this) }

    @SubscribeEvent
    fun onPacket(event: PacketEvent.Incoming) {
        val pkt = event.packet as? ClientboundLevelParticlesPacket ?: return
        if (pkt.particle.type != ParticleTypes.CRIT) return

        val player = mc.player ?: return
        val dx = pkt.x - player.x
        val dy = pkt.y - player.y
        val dz = pkt.z - player.z
        // Ignore particles within 3 blocks - those are combat hits on nearby mobs
        if (dx * dx + dy * dy + dz * dz < 9.0) return

        if (activationPos == null) activationPos = player.position()

        val bx = floor(pkt.x).toInt()
        val bz = floor(pkt.z).toInt()
        val key = (bx.toLong() shl 32) or (bz.toLong() and 0xFFFFFFFFL)

        val now = System.currentTimeMillis()
        synchronized(seenBurrows) {
            val rec = seenBurrows[key]
            if (rec != null) rec.lastSeenMs = now
            else seenBurrows[key] = BurrowRecord(bx, bz, now)
        }
        lastParticleMs = now
        packetCount++
    }

    fun reset() {
        synchronized(seenBurrows) { seenBurrows.clear() }
        activationPos = null
        lastParticleMs = 0L
        packetCount = 0
    }

    /** Remove a single burrow entry so the helper stops rendering it after it's been dug. */
    fun removeBurrow(bx: Int, bz: Int) {
        val key = (bx.toLong() shl 32) or (bz.toLong() and 0xFFFFFFFFL)
        synchronized(seenBurrows) { seenBurrows.remove(key) }
    }

    /** Number of matching CRIT packets received - used by the macro threshold check. */
    fun count(): Int = packetCount

    fun getLastParticleMs(): Long = lastParticleMs

    fun getActivationPos(): Vec3? = activationPos

    /**
     * All known burrow positions whose last-seen timestamp is within [expireMs] ms,
     * sorted most-recently-seen first. Y is snapped to surface via heightmap.
     */
    fun getBurrowPositions(level: Level, expireMs: Long = Long.MAX_VALUE): List<Vec3> {
        val now = System.currentTimeMillis()
        val snapshot: List<BurrowRecord>
        synchronized(seenBurrows) {
            snapshot = seenBurrows.values
                .filter { now - it.lastSeenMs <= expireMs }
                .sortedByDescending { it.lastSeenMs }
                .toList()
        }
        return snapshot.map { rec ->
            // getHeight(MOTION_BLOCKING) returns the Y of the air block above the topmost solid
            // block, so subtracting 1 gives the surface block (the burrow grass block)
            val surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, rec.bx, rec.bz) - 1
            Vec3(rec.bx + 0.5, surfaceY.toDouble(), rec.bz + 0.5)
        }
    }

    /** Most recently confirmed burrow position, or null. */
    fun getBurrowPos(level: Level): Vec3? = getBurrowPositions(level).firstOrNull()
}
