package org.cobalt.internal.garden.managers

object PestManager {

    @Volatile var lastAliveCount = 0
    @Volatile var lastCooldownSeconds = 0
    @Volatile var cleaningCooldownUntil = 0L

    fun reset() {
        lastAliveCount = 0
        lastCooldownSeconds = 0
        cleaningCooldownUntil = 0L
    }

    fun update(threshold: Int): Boolean {
        val data = PestTabListParser.parse()
        lastAliveCount = data.alivePests
        lastCooldownSeconds = data.cooldownSeconds
        if (System.currentTimeMillis() < cleaningCooldownUntil) return false
        return data.alivePests >= threshold
    }

    fun startCooldown(durationMs: Long) {
        cleaningCooldownUntil = System.currentTimeMillis() + durationMs
        PestPrepSwapManager.reset()
    }
}
