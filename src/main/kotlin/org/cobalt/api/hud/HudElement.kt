package org.cobalt.api.hud

import org.cobalt.api.module.setting.Setting
import org.cobalt.api.module.setting.SettingsContainer
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.module.setting.impl.SliderSetting
import org.cobalt.api.module.setting.inGroup

/**
 * A HUD overlay element rendered on the in-game screen.
 *
 * Created via the [hudElement][org.cobalt.api.hud.hudElement] DSL inside a [Module][org.cobalt.api.module.Module].
 * Each element is independently draggable, scalable, and toggleable through the HUD editor.
 * Position, scale, enabled state, and settings are automatically persisted.
 *
 * @property id Unique identifier used for serialization. Must be stable across versions.
 * @property name Display name shown in the HUD editor and settings popup.
 * @property description Optional description shown in the UI.
 */
abstract class HudElement(
  val id: String,
  val name: String,
  val description: String = "",
  defaultBlurBackground: Boolean = false,
  defaultBlurStrength: Double = 14.0,
  private val managedBlurBackground: Boolean = true,
) : SettingsContainer {

  /** Whether this element is rendered. Toggled by the user in the HUD editor. */
  var enabled: Boolean = true

  /** Screen anchor point. Determines which edge/corner offsets are relative to. */
  var anchor: HudAnchor = HudAnchor.TOP_LEFT

  /** Horizontal offset from the [anchor] edge, in pixels. */
  var offsetX: Float = 10f

  /** Vertical offset from the [anchor] edge, in pixels. */
  var offsetY: Float = 10f

  /** Render scale factor. */
  var scale: Float = 1.0f
    set(value) {
      field = value.coerceIn(minScale, maxScale)
    }

  /** Minimum allowed render scale for this element. */
  open val minScale: Float = 0.5f

  /** Maximum allowed render scale for this element. */
  open val maxScale: Float = 3.0f

  protected open val defaultAnchor: HudAnchor = HudAnchor.TOP_LEFT
  protected open val defaultOffsetX: Float = 10f
  protected open val defaultOffsetY: Float = 10f
  protected open val defaultScale: Float = 1.0f

  private val settingsList = mutableListOf<Setting<*>>()

  private val blurBackgroundSetting =
    CheckboxSetting(
      "Blur Background",
      "Use the shader blur behind this HUD element.",
      defaultBlurBackground,
    ).inGroup("Appearance")

  private val blurStrengthSetting =
    SliderSetting(
      "Blur Strength",
      "How strongly this HUD blurs the scene behind it.",
      defaultBlurStrength,
      2.0,
      24.0,
      0.5,
    ).inGroup("Appearance")

  init {
    addSetting(blurBackgroundSetting, blurStrengthSetting)
  }

  override fun addSetting(vararg settings: Setting<*>) {
    settingsList.addAll(listOf(*settings))
  }

  override fun getSettings(): List<Setting<*>> {
    return settingsList
  }

  /** Returns the unscaled width of this element in pixels. */
  abstract fun getBaseWidth(): Float

  /** Returns the unscaled height of this element in pixels. */
  abstract fun getBaseHeight(): Float

  /**
   * Called before NVG rendering begins. Use this for raw GL background passes that should sit
   * underneath the HUD element (for example framebuffer blur).
   */
  open fun renderPre(screenX: Float, screenY: Float, scale: Float) {}

  /**
   * Called every frame when this element is [enabled].
   * Draw using [NVGRenderer][org.cobalt.api.util.ui.NVGRenderer] - coordinates are pre-translated,
   * so draw relative to (0, 0).
   */
  abstract fun render(screenX: Float, screenY: Float, scale: Float)

  /**
   * Called after NVG rendering completes. Use this for non-NVG rendering (e.g. GuiGraphics).
   * Coordinates are pre-translated, so draw relative to (0, 0).
   */
  open fun renderPost(screenX: Float, screenY: Float, scale: Float) {}

  fun getScaledWidth(): Float = getBaseWidth() * scale
  fun getScaledHeight(): Float = getBaseHeight() * scale

  fun isBlurBackgroundEnabled(): Boolean = blurBackgroundSetting.value
  fun getBlurStrength(): Float = blurStrengthSetting.value.toFloat()
  fun usesManagedBlurBackground(): Boolean = managedBlurBackground

  fun getScreenPosition(screenWidth: Float, screenHeight: Float): Pair<Float, Float> =
    anchor.computeScreenPosition(
      offsetX, offsetY,
      getScaledWidth(), getScaledHeight(),
      screenWidth, screenHeight
    )

  /** Resets position, anchor, and scale to the defaults set in the DSL builder. */
  fun resetPosition() {
    anchor = defaultAnchor
    offsetX = defaultOffsetX
    offsetY = defaultOffsetY
    scale = defaultScale
  }

  /** Resets all settings to their default values. */
  fun resetSettings() {
    for (setting in getSettings()) {
      @Suppress("UNCHECKED_CAST")
      val typedSetting = setting as Setting<Any?>
      typedSetting.value = typedSetting.defaultValue
    }
  }

  fun containsPoint(px: Float, py: Float, screenWidth: Float, screenHeight: Float): Boolean {
    val (sx, sy) = getScreenPosition(screenWidth, screenHeight)
    return px >= sx && px <= sx + getScaledWidth() &&
      py >= sy && py <= sy + getScaledHeight()
  }
}
