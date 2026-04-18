package org.cobalt.internal.garden.managers

object CropFeverManager {

    @Volatile var feverActive = false
    @Volatile var feverDetectedAt = 0L
    private const val FEVER_TIMEOUT_MS = 65_000L

    fun reset() {
        feverActive = false
        feverDetectedAt = 0L
    }

    fun onChatMessage(message: String) {
        if (message.contains("CROP FEVER", ignoreCase = true)) {
            feverActive = true
            feverDetectedAt = System.currentTimeMillis()
        }
    }

    fun update() {
        if (feverActive && System.currentTimeMillis() - feverDetectedAt > FEVER_TIMEOUT_MS) {
            feverActive = false
        }
    }

    fun shouldDelay() = feverActive
}
