package org.cobalt.internal.garden.managers

object PestPrepSwapManager {

    @Volatile var swapDone = false
    @Volatile private var swapRunning = false
    /** System.currentTimeMillis() when we became eligible to run the prep-swap
     *  (i.e., the post-clean cooldown expired). 0 means not yet eligible. */
    @Volatile private var activeSince = 0L

    fun reset() {
        swapDone = false
        swapRunning = false
        activeSince = 0L
    }

    fun isRunning(): Boolean = swapRunning

    /** Call each tick once cleaningCooldownUntil has passed, so the fallback timer starts. */
    fun markActive() {
        if (activeSince == 0L) activeSince = System.currentTimeMillis()
    }

    fun shouldPrepSwap(cooldownSeconds: Int): Boolean {
        if (swapDone || swapRunning) return false
        // Tab-list cooldown detected: fire once the 140-second prep window is reached.
        if (cooldownSeconds in 1..140) return true
        // Fallback: if the tab list never yields a cooldown (regex mismatch / format unknown),
        // fire 140 s after we became eligible so the prep flow still runs once per cycle.
        if (cooldownSeconds == 0 && activeSince > 0L &&
            System.currentTimeMillis() - activeSince >= 140_000L) return true
        return false
    }

    fun markStarted(): Boolean {
        if (swapDone || swapRunning) return false
        swapRunning = true
        return true
    }

    fun markDone() {
        swapDone = true
        swapRunning = false
    }

    fun markFailed() {
        swapRunning = false
    }
}
