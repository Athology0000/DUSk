package org.cobalt.internal.garden.managers

import net.minecraft.client.Minecraft
import org.cobalt.internal.garden.GardenConfig

object FlightManager {

    fun startFlying() {
        tapJump()
        Thread.sleep(120L)
        tapJump()
        Thread.sleep(250L)
    }

    fun stopFlying() {
        when (GardenConfig.unflyMode.trim().uppercase()) {
            "SNEAK" -> {
                tapShift()
                Thread.sleep(150L)
            }

            else -> {
                tapJump()
                Thread.sleep(120L)
                tapJump()
                Thread.sleep(250L)
            }
        }
    }

    private fun tapJump() {
        val mc = Minecraft.getInstance()
        mc.execute { mc.options.keyJump.setDown(true) }
        Thread.sleep(80L)
        mc.execute { mc.options.keyJump.setDown(false) }
    }

    private fun tapShift() {
        val mc = Minecraft.getInstance()
        mc.execute { mc.options.keyShift.setDown(true) }
        Thread.sleep(80L)
        mc.execute { mc.options.keyShift.setDown(false) }
    }
}
