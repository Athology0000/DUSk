package org.cobalt.internal.ui.notification

import org.cobalt.api.ui.theme.ThemeManager
import org.cobalt.api.util.ui.NVGRenderer
import org.cobalt.internal.ui.UIComponent
import org.cobalt.internal.ui.animation.BounceAnimation
import org.cobalt.internal.ui.animation.EaseOutAnimation

internal class UINotification(
  private val title: String,
  private val description: String,
  val duration: Long,
) : UIComponent(
  x = 0F,
  y = 0F,
  width = 350F,
  height = calculateHeight(title, description)
) {

  private var slideInAnim = BounceAnimation(600L)
  private var slideDownAnim = EaseOutAnimation(400L)
  private var slideOutAnim = EaseOutAnimation(400L)

  var targetY: Float = 0f
  var previousY: Float = 0f

  private var isExpired: Boolean = false
  private var expiryTime: Long = 0L

  fun start(currentTime: Long) {
    slideInAnim = BounceAnimation(600L).apply { start() }
    slideDownAnim = EaseOutAnimation(200L)
    slideOutAnim = EaseOutAnimation(400L)
    expiryTime = currentTime + duration + 600L
    isExpired = false
  }

  fun checkExpiry(currentTime: Long) {
    if (!isExpired && currentTime >= expiryTime) {
      isExpired = true
      slideOutAnim = EaseOutAnimation(400L).apply { start() }
    }
  }

  fun moveTo(newTargetY: Float) {
    if (newTargetY != targetY) {
      previousY = targetY + slideDownAnim.get(previousY - targetY, 0f, false)
      targetY = newTargetY
      slideDownAnim = EaseOutAnimation(200L).apply { start() }
    }
  }

  override fun render() {
    NVGRenderer.rect(0f, 0f, width, height, ThemeManager.currentTheme.notificationBackground, 5f)

    val titleHeight = NVGRenderer.getWrappedStringHeight(title, width - 30f, 16f)
    NVGRenderer.drawWrappedString(title, 15f, 15f, width - 30f, 16f, ThemeManager.currentTheme.notificationText)

    NVGRenderer.drawWrappedString(
      description,
      15f,
      15f + titleHeight + 10f,
      width - 30f,
      14f,
      ThemeManager.currentTheme.notificationTextSecondary,
      NVGRenderer.interFont
    )
  }

  fun xOffset(screenWidth: Float): Float {
    return if (isExpired) {
      slideOutAnim.get(screenWidth - width - 10F, screenWidth, false)
    } else {
      slideInAnim.get(screenWidth, screenWidth - width - 10F, false)
    }
  }

  val yOffset: Float
    get() = targetY + slideDownAnim.get(previousY - targetY, 0f, false)

  fun isDone(): Boolean =
    isExpired && !slideOutAnim.isAnimating()

  companion object {
    private fun calculateHeight(title: String, description: String): Float {
      val titleHeight = NVGRenderer.getWrappedStringHeight(title, 320F, 16F)
      val descHeight = NVGRenderer.getWrappedStringHeight(description, 320F, 14F)
      return maxOf(100F, titleHeight + descHeight + 45F)
    }
  }

}
