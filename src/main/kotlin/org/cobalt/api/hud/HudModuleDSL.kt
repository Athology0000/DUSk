package org.cobalt.api.hud

import org.cobalt.api.module.Module
import org.cobalt.api.module.setting.Setting
import org.cobalt.api.module.setting.SettingsContainer

/**
 * Creates and registers a [HudElement] on this module using a DSL builder.
 *
 * Usage:
 * ```
 * class MyModule : Module("My Module") {
 *   val hud = hudElement("my-hud", "My HUD", "Shows something") {
 *     anchor = HudAnchor.TOP_RIGHT
 *     offsetX = 10f
 *     offsetY = 10f
 *
 *     val showDecimals = setting(CheckboxSetting("Decimals", "", false))
 *
 *     width { 80f }
 *     height { 20f }
 *     render { screenX, screenY, scale -> /* use showDecimals.value */ }
 *   }
 * }
 * ```
 *
 * @param id Stable unique identifier used for serialization.
 * @param name Display name shown in the HUD editor.
 * @param description Optional description for the settings popup.
 * @param init Builder block - configure position, settings, size, and rendering.
 * @return The constructed [HudElement] instance.
 */
fun Module.hudElement(
  id: String,
  name: String,
  description: String = "",
  init: HudElementBuilder.() -> Unit
): HudElement {
  val builder = HudElementBuilder(id, name, description)
  builder.init()
  val element = builder.build()
  addHudElement(element)
  return element
}

/**
 * Builder for configuring a [HudElement] inside the [hudElement] DSL block.
 *
 * Register settings with [setting] and read their values via `.value`.
 * Do **not** use `by` delegation for settings inside this builder - it won't compile
 * because Kotlin local delegates require a different type signature.
 */
class HudElementBuilder(
  private val id: String,
  private val name: String,
  private val description: String = ""
) : SettingsContainer {

  private var widthProvider: () -> Float = { 100f }
  private var heightProvider: () -> Float = { 20f }

  /** Screen anchor point. Determines which edge/corner offsets are relative to. */
  var anchor: HudAnchor = HudAnchor.TOP_LEFT

  /** Horizontal offset from the [anchor] edge, in pixels. */
  var offsetX: Float = 10f

  /** Vertical offset from the [anchor] edge, in pixels. */
  var offsetY: Float = 10f

  /** Default render scale (clamped to 0.5-3.0 on load). */
  var scale: Float = 1.0f

  /** Minimum allowed render scale for this HUD element. */
  var minScale: Float = 0.5f

  /** Maximum allowed render scale for this HUD element. */
  var maxScale: Float = 3.0f
  private var preRenderLambda: ((Float, Float, Float) -> Unit)? = null
  private var renderLambda: ((Float, Float, Float) -> Unit)? = null
  private var postRenderLambda: ((Float, Float, Float) -> Unit)? = null

  private val settingsList = mutableListOf<Setting<*>>()

  override fun addSetting(vararg settings: Setting<*>) {
    settingsList.addAll(listOf(*settings))
  }

  override fun getSettings(): List<Setting<*>> {
    return settingsList
  }

  /** Sets the dynamic width provider. Called every frame - can return values based on setting state. */
  fun width(provider: () -> Float) {
    widthProvider = provider
  }

  /** Sets the dynamic height provider. Called every frame - can return values based on setting state. */
  fun height(provider: () -> Float) {
    heightProvider = provider
  }

  /**
   * Registers a setting on this HUD element and returns it.
   * Access the current value via `.value`:
   * ```
   * val speed = setting(SliderSetting("Speed", "Movement speed", 1.0, 0.1, 5.0))
   * render { _, _, _ -> /* use speed.value */ }
   * ```
   */
  fun <T, S : Setting<T>> setting(setting: S): S {
    addSetting(setting)
    return setting
  }

  /** Sets a pre-render callback, called before NVG rendering begins. */
  fun preRender(block: (screenX: Float, screenY: Float, scale: Float) -> Unit) {
    preRenderLambda = block
  }

  /** Sets the render callback, called every frame when this element is enabled. */
  fun render(block: (screenX: Float, screenY: Float, scale: Float) -> Unit) {
    renderLambda = block
  }

  /** Sets a post-render callback, called after NVG rendering completes. */
  fun postRender(block: (screenX: Float, screenY: Float, scale: Float) -> Unit) {
    postRenderLambda = block
  }

  fun build(): HudElement {
    val capturedRender = renderLambda ?: { _, _, _ -> }
    val capturedWidth = widthProvider
    val capturedHeight = heightProvider
    val capturedSettings = settingsList.toList()
    val capturedAnchor = anchor
    val capturedOffsetX = offsetX
    val capturedOffsetY = offsetY
    val capturedScale = scale
    val capturedMinScale = minScale
    val capturedMaxScale = maxScale
    val capturedPreRender = preRenderLambda
    val capturedPostRender = postRenderLambda

    return object : HudElement(id, name, description) {
      override val defaultAnchor = capturedAnchor
      override val defaultOffsetX = capturedOffsetX
      override val defaultOffsetY = capturedOffsetY
      override val defaultScale = capturedScale
      override val minScale = capturedMinScale
      override val maxScale = capturedMaxScale

      init {
        capturedSettings.forEach { addSetting(it) }
        resetPosition()
      }

      override fun getBaseWidth(): Float = capturedWidth()
      override fun getBaseHeight(): Float = capturedHeight()
      override fun renderPre(screenX: Float, screenY: Float, scale: Float) {
        capturedPreRender?.invoke(screenX, screenY, scale)
      }
      override fun render(screenX: Float, screenY: Float, scale: Float) {
        capturedRender(screenX, screenY, scale)
      }
      override fun renderPost(screenX: Float, screenY: Float, scale: Float) {
        capturedPostRender?.invoke(screenX, screenY, scale)
      }
    }
  }
}
