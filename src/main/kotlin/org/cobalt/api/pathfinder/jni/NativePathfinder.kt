package org.cobalt.api.pathfinder.jni

import net.minecraft.client.Minecraft
import org.cobalt.api.rotation.RotationExecutor
import org.cobalt.api.util.player.MovementManager
import org.cobalt.pathfinder.NativePathfinderBridge

/**
 * Singleton wrapper around the native pathfinder engine.
 *
 * Usage:
 *   NativePathfinder.init()                      // once at mod startup
 *   NativePathfinder.setTarget(x, y, z)
 *   // each tick:
 *   NativePathfinder.tick()?.applyToPlayer()
 *   NativePathfinder.destroy()                   // on mod unload
 */
object NativePathfinder {

    private var handle: Long = 0L

    val isInitialized: Boolean get() = handle != 0L

    /** Cached path nodes as Vec3 list; updated on EXECUTING transition. Call only from game tick thread. */
    var cachedPathNodes: List<net.minecraft.world.phys.Vec3> = emptyList()
        private set

    /**
     * Forward-only cursor into [cachedPathNodes].
     * PathCommand.nearestNodeIndex uses this as a lower bound so the "nearest node"
     * search can never snap backward to already-passed nodes. Resets to 0 whenever
     * the active path is refreshed (new EXECUTING transition).
     */
    var pathNodeCursor: Int = 0
        internal set

    private var lastTickStatus: PathStatus = PathStatus.IDLE
    private var prevJump: Boolean = false

    fun init() {
        if (handle != 0L) return
        handle = NativePathfinderBridge.createEngine()
    }

    fun destroy() {
        if (handle == 0L) return
        NativePathfinderBridge.destroyEngine(handle)
        handle = 0L
        releaseGuidedControl()
    }

    fun setTarget(x: Double, y: Double, z: Double) {
        if (handle == 0L) return
        NativePathfinderBridge.setTarget(handle, x, y, z)
    }

    fun setTargetWithRadius(x: Double, y: Double, z: Double, radius: Double) {
        if (handle == 0L) return
        NativePathfinderBridge.setTargetWithRadius(handle, x, y, z, radius)
    }

    /**
     * @param waypoints list of Vec3-like triples as flat [x0,y0,z0, x1,y1,z1, ...]
     * @param loop whether to loop the route
     * @param profile MovementProfile ordinal
     */
    fun setRoute(waypoints: DoubleArray, loop: Boolean, profile: MovementProfile) {
        if (handle == 0L) return
        NativePathfinderBridge.setRoute(handle, waypoints, loop, profile.ordinal)
    }

    fun stop() {
        if (handle == 0L) return
        NativePathfinderBridge.stop(handle)
        prevJump = false
        releaseGuidedControl()
    }

    /** Call when the player changes dimension or disconnects so the buffer cache is flushed. */
    fun onLevelChange() {
        WorldBufferSerializer.invalidate()
        prevJump = false
        releaseGuidedControl()
    }

    val status: PathStatus
        get() {
            if (handle == 0L) return PathStatus.IDLE
            val ordinal = NativePathfinderBridge.getStatus(handle)
            return PathStatus.entries.getOrElse(ordinal) { PathStatus.FAILED }
        }

    /**
     * Must be called every tick (TickEvent.Start) on the main thread.
     * Returns null if the engine is not initialized or the world is unavailable.
     */
    fun tick(): PathCommand? {
        if (handle == 0L) {
            releaseGuidedControl()
            return null
        }
        val mc = Minecraft.getInstance()
        val player = mc.player ?: run {
            releaseGuidedControl()
            return null
        }

        val world = WorldBufferSerializer.serialize(mc) ?: run {
            releaseGuidedControl()
            return null
        }

        val r = NativePathfinderBridge.update(
            handle,
            world.buf,
            world.bx, world.by, world.bz,
            player.x, player.y, player.z,
            player.yRot, player.xRot,
            player.onGround()
        )

        // int[10]: [0]=forward [1]=back [2]=jump [3]=sneak [4]=sprint
        //          [5]=targetYaw (float bits) [6]=targetPitch (float bits)
        //          [7]=PathStatus ordinal [8]=ActionType ordinal
        //          [9]=distanceToTarget (float bits)
        val statusOrdinal = r[7]
        val actionOrdinal = r[8]
        val parsedStatus = PathStatus.entries.getOrElse(statusOrdinal) { PathStatus.FAILED }

        // Refresh node cache on EXECUTING transition; clear when path ends.
        // REPLANNING: keep old cached nodes (engine continues on old path until new plan arrives).
        // PLANNING: neither refresh nor clear (no path exists yet).
        when {
            parsedStatus == PathStatus.EXECUTING && lastTickStatus != PathStatus.EXECUTING -> refreshPathNodes()
            parsedStatus == PathStatus.IDLE || parsedStatus == PathStatus.ARRIVED || parsedStatus == PathStatus.FAILED -> {
                if (cachedPathNodes.isNotEmpty()) cachedPathNodes = emptyList()
                pathNodeCursor = 0
            }
        }
        lastTickStatus = parsedStatus

        // Don't lock movement/rotation when the engine isn't actively navigating
        if (parsedStatus == PathStatus.IDLE ||
            parsedStatus == PathStatus.PLANNING ||
            parsedStatus == PathStatus.ARRIVED ||
            parsedStatus == PathStatus.FAILED) {
            prevJump = false
            releaseGuidedControl()
            return null
        }

        // Edge-trigger jump: fire for exactly one tick on the rising edge.
        // Prevents bunny-hopping when the DLL holds jump=true for multiple ticks.
        // Re-arm on landing so a pulse wasted while airborne (e.g. mid-fall) retries.
        val jumpRaw = r[2] != 0
        if (jumpRaw && player.onGround()) prevJump = false
        val jumpPulse = jumpRaw && !prevJump
        prevJump = jumpRaw

        return PathCommand(
            forward  = r[0] != 0,
            back     = r[1] != 0,
            jump     = jumpPulse,
            sneak    = r[3] != 0,
            sprint   = r[4] != 0,
            targetYaw   = java.lang.Float.intBitsToFloat(r[5]),
            targetPitch = java.lang.Float.intBitsToFloat(r[6]),
            status      = parsedStatus,
            activeAction = ActionType.entries.getOrElse(actionOrdinal) { ActionType.WALK },
            distanceToTarget = java.lang.Float.intBitsToFloat(r[9])
        )
    }

    private fun refreshPathNodes() {
        if (handle == 0L) { cachedPathNodes = emptyList(); return }
        val raw = NativePathfinderBridge.getPathNodes(handle)
        val result = ArrayList<net.minecraft.world.phys.Vec3>(raw.size / 3)
        var i = 0
        while (i + 2 < raw.size) {
            result.add(net.minecraft.world.phys.Vec3(raw[i].toDouble(), raw[i + 1].toDouble(), raw[i + 2].toDouble()))
            i += 3
        }
        cachedPathNodes = result
        pathNodeCursor = 0
    }

    private fun releaseGuidedControl() {
        MovementManager.setLookLock(false)
        RotationExecutor.stopIfUsing(PathfinderRotationStrategy)
    }
}
