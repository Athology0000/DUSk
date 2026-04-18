package org.cobalt.internal.garden

import net.minecraft.client.Minecraft

object ScriptBridge {

    private val mc get() = Minecraft.getInstance()
    @Volatile private var intentionalStopUntilMs = 0L
    @Volatile private var lastStartedScriptCommand: String? = null

    // Taunahi scripts - sent as chat messages (Taunahi intercepts .ez- prefix)
    fun startFarming(script: String)       = startScript(script)
    fun startPestScript(script: String)    = startScript(script)
    fun startVisitorScript(script: String) = startScript(script)
    fun startReturnScript(script: String)  = startScript(script)

    fun hasLastStartedScript(): Boolean {
        return !lastStartedScriptCommand.isNullOrBlank()
    }

    fun restartLastScript() {
        lastStartedScriptCommand?.takeIf { it.isNotBlank() }?.let(::sendChat)
    }

    fun markIntentionalStop(graceMs: Long = 8_000L) {
        intentionalStopUntilMs = System.currentTimeMillis() + graceMs.coerceAtLeast(0L)
    }

    fun wasIntentionalStopRecently(): Boolean {
        return System.currentTimeMillis() < intentionalStopUntilMs
    }

    fun stopScript() {
        markIntentionalStop()
        // Release held keys before stopping (mirrors ihanuat behaviour)
        mc.execute {
            mc.options.keyUp.isDown    = false
            mc.options.keyDown.isDown  = false
            mc.options.keyLeft.isDown  = false
            mc.options.keyRight.isDown = false
            mc.options.keyJump.isDown  = false
            mc.options.keyShift.isDown = false
        }
        sendChat(".ez-stopscript")
    }

    // Server slash commands
    fun setSpawn()    = sendCommand("setspawn")
    fun warpGarden()  = sendCommand("warp garden")

    private fun startScript(script: String) {
        if (script.isBlank()) return
        val command = ".ez-startscript $script"
        lastStartedScriptCommand = command
        sendChat(command)
    }

    private fun sendChat(msg: String) {
        mc.execute { mc.player?.connection?.sendChat(msg) }
    }

    private fun sendCommand(cmd: String) {
        mc.execute { mc.player?.connection?.sendCommand(cmd) }
    }
}
