package org.cobalt.api.notification

import net.minecraft.client.Minecraft
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.render.NvgEvent
import org.cobalt.api.util.ui.NVGRenderer
import org.cobalt.internal.ui.notification.UINotification

object NotificationManager {

  private val mc: Minecraft =
    Minecraft.getInstance()

  private val notifQueue = mutableListOf<UINotification>()
  private val activeNotifications = mutableListOf<UINotification>()

  fun queue(title: String, description: String, duration: Long = 2000L) {
    notifQueue.add(UINotification(title, description, duration))
  }

  @Suppress("unused")
  @SubscribeEvent
  fun onRender(event: NvgEvent) {
    val window = mc.window
    val screenWidth = window.screenWidth.toFloat()
    val screenHeight = window.screenHeight.toFloat()

    updateNotifications(screenHeight)
    NVGRenderer.beginFrame(screenWidth, screenHeight)

    activeNotifications.forEach { notif ->
      val xOffset = notif.xOffset(screenWidth)
      val yOffset = notif.yOffset

      NVGRenderer.push()
      NVGRenderer.translate(xOffset, yOffset)
      notif.render()
      NVGRenderer.pop()
    }

    NVGRenderer.endFrame()
  }

  private fun updateNotifications(screenHeight: Float) {
    val currentTime = System.currentTimeMillis()

    activeNotifications.forEach { it.checkExpiry(currentTime) }
    activeNotifications.removeIf { it.isDone() }

    while (activeNotifications.size < 3 && notifQueue.isNotEmpty()) {
      val notif = notifQueue.removeAt(0)
      val targetY = screenHeight - (activeNotifications.size + 1) * (notif.height + 10f) - 10f

      notif.targetY = targetY
      notif.previousY = targetY
      notif.start(currentTime)

      activeNotifications.add(notif)
    }

    activeNotifications.forEachIndexed { index, notif ->
      val newTargetY = screenHeight - (index + 1) * (notif.height + 10f) - 10f
      notif.moveTo(newTargetY)
    }
  }

  fun clear() {
    notifQueue.clear()
    activeNotifications.clear()
  }

}
