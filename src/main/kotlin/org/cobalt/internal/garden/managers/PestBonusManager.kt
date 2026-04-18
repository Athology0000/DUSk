package org.cobalt.internal.garden.managers

object PestBonusManager {

    @Volatile var bonusActive = false
    @Volatile var lastChecked = 0L

    fun reset() {
        bonusActive = false
        lastChecked = 0L
    }

    fun update() {
        val now = System.currentTimeMillis()
        if (now - lastChecked < 5000) return
        lastChecked = now
        bonusActive = PestTabListParser.parse().bonusActive
    }
}
