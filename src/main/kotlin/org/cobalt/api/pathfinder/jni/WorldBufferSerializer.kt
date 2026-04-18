package org.cobalt.api.pathfinder.jni

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.tags.BlockTags
import net.minecraft.tags.FluidTags
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState

/**
 * Serializes a 96x40x96 block region centred on the player into a flat byte[].
 *
 * Buffer layout (matches Types.h constants):
 *   index(x,y,z) = (y-by)*BUF_STRIDE_Y + (z-bz)*BUF_STRIDE_Z + (x-bx)
 *   BUF_W=96, BUF_H=40, BUF_D=96
 *   BUF_STRIDE_Z=96, BUF_STRIDE_Y=96*96=9216
 *
 * Block type byte values (must match WorldAccessor.h):
 *   0 = AIR, 1 = SOLID, 2 = WATER, 3 = LAVA, 4 = LADDER, 5 = STEP
 *
 * Performance notes:
 *   - If the player's block position is unchanged the previous buffer is returned
 *     as-is (0 block reads - critical during the PLANNING phase when the player is frozen).
 *   - If the buffer origin shifts by at most +/-1 per axis the buffer is updated
 *     incrementally: only the newly exposed slice(s) are read (~4k blocks vs 369k).
 *   - Per-BlockState collision-shape results are cached so decorative blocks
 *     (flowers, carpets, torches...) are classified via a single HashMap lookup
 *     after the first encounter.
 */
object WorldBufferSerializer {

    private const val BUF_W = 96
    private const val BUF_H = 40
    private const val BUF_D = 96
    private const val BUF_STRIDE_Z = BUF_W
    private const val BUF_STRIDE_Y = BUF_W * BUF_D

    private const val BT_AIR: Byte    = 0
    private const val BT_SOLID: Byte  = 1
    private const val BT_WATER: Byte  = 2
    private const val BT_LAVA: Byte   = 3
    private const val BT_LADDER: Byte = 4
    private const val BT_STEP: Byte   = 5

    // -- Caches ----------------------------------------------------------------

    /** Buffer from the previous call; reused when origin is unchanged. */
    private var cachedBuf: ByteArray? = null
    private var cachedBx = Int.MIN_VALUE
    private var cachedBy = Int.MIN_VALUE
    private var cachedBz = Int.MIN_VALUE

    /**
     * Per-BlockState max collision height (0.0 = no shape, 1.0 = full block).
     * Most decorative blocks have static shapes, so caching by state is safe and fast.
     * Position-sensitive blocks (fences, walls) have non-empty shapes in all
     * configurations so they will always cache as 1.0 (correctly treated as SOLID).
     */
    private val heightByState = HashMap<BlockState, Double>(512)

    // -- Public API ------------------------------------------------------------

    fun serialize(mc: Minecraft): WorldBufferResult? {
        val level  = mc.level  ?: return null
        val player = mc.player ?: return null

        val bx = player.blockX - BUF_W / 2
        val by = player.blockY - 10
        val bz = player.blockZ - BUF_D / 2

        // Fast path: player didn't move to a new block - return cached buffer unchanged.
        val cached = cachedBuf
        if (cached != null && bx == cachedBx && by == cachedBy && bz == cachedBz) {
            return WorldBufferResult(cached, bx, by, bz)
        }

        val buf     = ByteArray(BUF_W * BUF_H * BUF_D)
        val mpos    = BlockPos.MutableBlockPos()
        val ddx     = bx - cachedBx
        val ddy     = by - cachedBy
        val ddz     = bz - cachedBz

        if (cached != null
            && ddx >= -1 && ddx <= 1
            && ddy >= -1 && ddy <= 1
            && ddz >= -1 && ddz <= 1
        ) {
            // Incremental path: apply each axis shift in sequence.
            // shiftX copies from cached -> buf; shiftY/Z operate on buf in-place.
            if (ddx != 0) shiftX(cached, buf, ddx, bx, by, bz, level, mpos)
            else System.arraycopy(cached, 0, buf, 0, buf.size)

            if (ddy != 0) shiftY(buf, ddy, bx, by, bz, level, mpos)
            if (ddz != 0) shiftZ(buf, ddz, bx, by, bz, level, mpos)

            // When the X column is filled by shiftX and then shiftY or shiftZ also
            // fires, the column data is displaced by the subsequent orthogonal shift
            // (e.g. stepping up a stair while moving forward shifts the new leading
            // column one Y slot off). Re-classify those cells now that all shifts
            // have settled, so the engine sees correct block data at the leading edge.
            if (ddx != 0 && (ddy != 0 || ddz != 0)) refillXSlice(buf, ddx, bx, by, bz, level, mpos)
            // Same issue: shiftY's new-row fill is displaced when shiftZ also fires.
            if (ddy != 0 && ddz != 0) refillYSlice(buf, ddy, bx, by, bz, level, mpos)
        } else {
            // Full rebuild (player teleported or first call).
            fullRebuild(buf, bx, by, bz, level, mpos)
        }

        cachedBuf = buf
        cachedBx  = bx
        cachedBy  = by
        cachedBz  = bz
        return WorldBufferResult(buf, bx, by, bz)
    }

    /** Drop caches (call when the player changes dimension or logs out). */
    fun invalidate() {
        cachedBuf = null
        heightByState.clear()
    }

    // -- Incremental shift helpers ---------------------------------------------

    /** Shift the buffer along the X axis by ddx (+/-1). Reads src, writes dst. */
    private fun shiftX(
        src: ByteArray, dst: ByteArray, ddx: Int,
        bx: Int, by: Int, bz: Int,
        level: Level, pos: BlockPos.MutableBlockPos,
    ) {
        if (ddx > 0) {
            // Origin moved right: shift data left, fill new right column(s).
            for (dy in 0 until BUF_H) {
                for (dz in 0 until BUF_D) {
                    val base = dy * BUF_STRIDE_Y + dz * BUF_STRIDE_Z
                    System.arraycopy(src, base + ddx, dst, base, BUF_W - ddx)
                    for (dx in BUF_W - ddx until BUF_W) {
                        pos.set(bx + dx, by + dy, bz + dz)
                        dst[base + dx] = classify(level.getBlockState(pos), level, pos)
                    }
                }
            }
        } else {
            val shift = -ddx
            // Origin moved left: shift data right, fill new left column(s).
            for (dy in 0 until BUF_H) {
                for (dz in 0 until BUF_D) {
                    val base = dy * BUF_STRIDE_Y + dz * BUF_STRIDE_Z
                    System.arraycopy(src, base, dst, base + shift, BUF_W - shift)
                    for (dx in 0 until shift) {
                        pos.set(bx + dx, by + dy, bz + dz)
                        dst[base + dx] = classify(level.getBlockState(pos), level, pos)
                    }
                }
            }
        }
    }

    /** Shift the buffer along the Y axis by ddy (+/-1). Modifies buf in-place. */
    private fun shiftY(
        buf: ByteArray, ddy: Int,
        bx: Int, by: Int, bz: Int,
        level: Level, pos: BlockPos.MutableBlockPos,
    ) {
        if (ddy > 0) {
            System.arraycopy(buf, ddy * BUF_STRIDE_Y, buf, 0, (BUF_H - ddy) * BUF_STRIDE_Y)
            for (dy in BUF_H - ddy until BUF_H) {
                for (dz in 0 until BUF_D) {
                    val base = dy * BUF_STRIDE_Y + dz * BUF_STRIDE_Z
                    for (dx in 0 until BUF_W) {
                        pos.set(bx + dx, by + dy, bz + dz)
                        buf[base + dx] = classify(level.getBlockState(pos), level, pos)
                    }
                }
            }
        } else {
            val shift = -ddy
            System.arraycopy(buf, 0, buf, shift * BUF_STRIDE_Y, (BUF_H - shift) * BUF_STRIDE_Y)
            for (dy in 0 until shift) {
                for (dz in 0 until BUF_D) {
                    val base = dy * BUF_STRIDE_Y + dz * BUF_STRIDE_Z
                    for (dx in 0 until BUF_W) {
                        pos.set(bx + dx, by + dy, bz + dz)
                        buf[base + dx] = classify(level.getBlockState(pos), level, pos)
                    }
                }
            }
        }
    }

    /** Shift the buffer along the Z axis by ddz (+/-1). Modifies buf in-place. */
    private fun shiftZ(
        buf: ByteArray, ddz: Int,
        bx: Int, by: Int, bz: Int,
        level: Level, pos: BlockPos.MutableBlockPos,
    ) {
        if (ddz > 0) {
            for (dy in 0 until BUF_H) {
                val baseY = dy * BUF_STRIDE_Y
                System.arraycopy(buf, baseY + ddz * BUF_STRIDE_Z, buf, baseY, (BUF_D - ddz) * BUF_STRIDE_Z)
                for (dz in BUF_D - ddz until BUF_D) {
                    val base = baseY + dz * BUF_STRIDE_Z
                    for (dx in 0 until BUF_W) {
                        pos.set(bx + dx, by + dy, bz + dz)
                        buf[base + dx] = classify(level.getBlockState(pos), level, pos)
                    }
                }
            }
        } else {
            val shift = -ddz
            for (dy in 0 until BUF_H) {
                val baseY = dy * BUF_STRIDE_Y
                System.arraycopy(buf, baseY, buf, baseY + shift * BUF_STRIDE_Z, (BUF_D - shift) * BUF_STRIDE_Z)
                for (dz in 0 until shift) {
                    val base = baseY + dz * BUF_STRIDE_Z
                    for (dx in 0 until BUF_W) {
                        pos.set(bx + dx, by + dy, bz + dz)
                        buf[base + dx] = classify(level.getBlockState(pos), level, pos)
                    }
                }
            }
        }
    }

    // -- Post-shift refill helpers ---------------------------------------------

    /** Re-classify the new X slice(s) using the settled (final) buffer origins. */
    private fun refillXSlice(
        buf: ByteArray, ddx: Int,
        bx: Int, by: Int, bz: Int,
        level: Level, pos: BlockPos.MutableBlockPos,
    ) {
        val xStart = if (ddx > 0) BUF_W - ddx else 0
        val xEnd   = if (ddx > 0) BUF_W       else -ddx
        for (dy in 0 until BUF_H) {
            val baseY = dy * BUF_STRIDE_Y
            for (dz in 0 until BUF_D) {
                val baseYZ = baseY + dz * BUF_STRIDE_Z
                for (dx in xStart until xEnd) {
                    pos.set(bx + dx, by + dy, bz + dz)
                    buf[baseYZ + dx] = classify(level.getBlockState(pos), level, pos)
                }
            }
        }
    }

    /** Re-classify the new Y slice(s) using the settled (final) buffer origins. */
    private fun refillYSlice(
        buf: ByteArray, ddy: Int,
        bx: Int, by: Int, bz: Int,
        level: Level, pos: BlockPos.MutableBlockPos,
    ) {
        val yStart = if (ddy > 0) BUF_H - ddy else 0
        val yEnd   = if (ddy > 0) BUF_H       else -ddy
        for (dy in yStart until yEnd) {
            val baseY = dy * BUF_STRIDE_Y
            for (dz in 0 until BUF_D) {
                val baseYZ = baseY + dz * BUF_STRIDE_Z
                for (dx in 0 until BUF_W) {
                    pos.set(bx + dx, by + dy, bz + dz)
                    buf[baseYZ + dx] = classify(level.getBlockState(pos), level, pos)
                }
            }
        }
    }

    // -- Full rebuild ----------------------------------------------------------

    private fun fullRebuild(
        buf: ByteArray,
        bx: Int, by: Int, bz: Int,
        level: Level, pos: BlockPos.MutableBlockPos,
    ) {
        for (dy in 0 until BUF_H) {
            val baseY = dy * BUF_STRIDE_Y
            for (dz in 0 until BUF_D) {
                val baseYZ = baseY + dz * BUF_STRIDE_Z
                for (dx in 0 until BUF_W) {
                    pos.set(bx + dx, by + dy, bz + dz)
                    buf[baseYZ + dx] = classify(level.getBlockState(pos), level, pos)
                }
            }
        }
    }

    // -- Block classification --------------------------------------------------

    private fun classify(state: BlockState, level: Level, pos: BlockPos): Byte {
        if (state.isAir) return BT_AIR
        val fluid = state.fluidState
        if (!fluid.isEmpty) return when {
            fluid.`is`(FluidTags.WATER) -> BT_WATER
            fluid.`is`(FluidTags.LAVA)  -> BT_LAVA
            else -> BT_WATER
        }
        if (state.`is`(BlockTags.CLIMBABLE)) return BT_LADDER
        // Cache max collision height per BlockState to distinguish full blocks, slabs, and
        // thin decoratives (carpets, pressure plates). Position-sensitive blocks (fences,
        // walls) always have height >= 1.0 in every configuration, so caching by state is safe.
        val maxHeight = heightByState.getOrPut(state) {
            val shape = state.getCollisionShape(level, pos)
            if (shape.isEmpty) 0.0 else shape.bounds().maxY
        }
        return when {
            maxHeight < 0.1  -> BT_AIR   // carpet, pressure plate, flower — walk through
            maxHeight < 0.75 -> BT_STEP  // slab (0.5), thin snow — auto-step, no jump needed
            else             -> BT_SOLID
        }
    }
}
