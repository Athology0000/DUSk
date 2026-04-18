package org.cobalt.api.pathfinder.jni

data class WorldBufferResult(
    val buf: ByteArray,
    val bx: Int,
    val by: Int,
    val bz: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WorldBufferResult) return false
        return bx == other.bx && by == other.by && bz == other.bz && buf.contentEquals(other.buf)
    }

    override fun hashCode(): Int {
        var result = buf.contentHashCode()
        result = 31 * result + bx
        result = 31 * result + by
        result = 31 * result + bz
        return result
    }
}
