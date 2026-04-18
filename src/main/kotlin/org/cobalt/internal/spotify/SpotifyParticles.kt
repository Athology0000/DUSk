package org.cobalt.internal.spotify

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import org.cobalt.api.util.ui.NVGRenderer

private data class Particle(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    /** Remaining lifetime fraction 0..1 (1 = just spawned). */
    var life: Float,
    val decayRate: Float,
    val baseColor: Int,
    val size: Float,
)

object SpotifyParticles {

    private val particles = mutableListOf<Particle>()
    private var spawnTimer = 0f
    private val rng = Random(System.nanoTime())

    private const val MAX_PARTICLES = 80

    fun update(dt: Float, barX: Float, barY: Float, barFillW: Float, color1: Int, color2: Int, playing: Boolean) {
        val iter = particles.iterator()
        while (iter.hasNext()) {
            val p = iter.next()
            p.x   += p.vx * dt
            p.y   += p.vy * dt
            p.vy  -= 18f * dt
            p.life -= p.decayRate * dt
            if (p.life <= 0f) iter.remove()
        }

        if (!playing || barFillW <= 0f) return

        spawnTimer += dt
        while (spawnTimer >= 0.05f && particles.size < MAX_PARTICLES) {
            spawnTimer -= 0.05f
            val x     = barX + rng.nextFloat() * barFillW
            val y     = barY + rng.nextFloat() * 6f
            val angle = rng.nextFloat() * PI.toFloat() * 2f
            val speed = rng.nextFloat() * 12f + 4f
            val size  = rng.nextFloat() * 2.2f + 0.6f
            val decay = rng.nextFloat() * 0.6f + 0.4f
            val color = if (rng.nextBoolean()) color1 else color2
            particles.add(Particle(x, y, cos(angle) * speed, sin(angle) * speed - speed * 0.4f, 1f, decay, color, size))
        }
    }

    fun render() {
        for (p in particles) {
            val alpha = (p.life * 0xFF).toInt().coerceIn(0, 0xFF)
            val col   = (p.baseColor and 0x00FFFFFF) or (alpha shl 24)
            NVGRenderer.circle(p.x, p.y, p.size, col)
        }
    }

    fun burst(barX: Float, barY: Float, barW: Float, color1: Int, color2: Int) {
        repeat(30) {
            val x     = barX + rng.nextFloat() * barW
            val y     = barY
            val angle = rng.nextFloat() * PI.toFloat() * 2f
            val speed = rng.nextFloat() * 30f + 10f
            val size  = rng.nextFloat() * 3f + 1f
            val color = if (rng.nextBoolean()) color1 else color2
            if (particles.size < MAX_PARTICLES) {
                particles.add(Particle(x, y, cos(angle) * speed, sin(angle) * speed - speed * 0.6f, 1f, 1.2f, color, size))
            }
        }
    }

    fun clear() = particles.clear()
}
