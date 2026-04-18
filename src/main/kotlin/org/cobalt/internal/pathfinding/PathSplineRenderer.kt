package org.cobalt.internal.pathfinding

import net.minecraft.world.phys.Vec3

object PathSplineRenderer {

    private const val STEPS = 10
    private const val AOTV_XZ_THRESHOLD = 2.0

    data class SplineResult(
        val points: List<Vec3>,
        val isAotv: BooleanArray  // parallel to points; true = this point is on an AOTV segment
    )

    fun buildSpline(nodes: List<Vec3>): SplineResult {
        if (nodes.size < 2) return SplineResult(nodes.toList(), BooleanArray(nodes.size))

        val points = ArrayList<Vec3>((nodes.size - 1) * STEPS + 1)
        val aotv   = ArrayList<Boolean>((nodes.size - 1) * STEPS + 1)

        for (i in 0 until nodes.size - 1) {
            val p0 = nodes[(i - 1).coerceAtLeast(0)]
            val p1 = nodes[i]
            val p2 = nodes[i + 1]
            val p3 = nodes[(i + 2).coerceAtMost(nodes.size - 1)]

            val segIsAotv = xzDist(p1, p2) > AOTV_XZ_THRESHOLD && p1.y == p2.y

            for (step in 0 until STEPS) {
                val t = step.toDouble() / STEPS
                points.add(catmullRom(p0, p1, p2, p3, t))
                aotv.add(segIsAotv)
            }
        }
        // Add final point
        points.add(nodes.last())
        aotv.add(false)

        return SplineResult(points, aotv.toBooleanArray())
    }

    private fun catmullRom(p0: Vec3, p1: Vec3, p2: Vec3, p3: Vec3, t: Double): Vec3 {
        val t2 = t * t
        val t3 = t2 * t
        return Vec3(
            0.5 * (2*p1.x + (-p0.x+p2.x)*t + (2*p0.x-5*p1.x+4*p2.x-p3.x)*t2 + (-p0.x+3*p1.x-3*p2.x+p3.x)*t3),
            0.5 * (2*p1.y + (-p0.y+p2.y)*t + (2*p0.y-5*p1.y+4*p2.y-p3.y)*t2 + (-p0.y+3*p1.y-3*p2.y+p3.y)*t3),
            0.5 * (2*p1.z + (-p0.z+p2.z)*t + (2*p0.z-5*p1.z+4*p2.z-p3.z)*t2 + (-p0.z+3*p1.z-3*p2.z+p3.z)*t3)
        )
    }

    private fun xzDist(a: Vec3, b: Vec3): Double {
        val dx = a.x - b.x; val dz = a.z - b.z
        return kotlin.math.sqrt(dx*dx + dz*dz)
    }
}
