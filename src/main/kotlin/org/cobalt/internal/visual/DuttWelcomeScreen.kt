package org.cobalt.internal.visual

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component
import org.cobalt.api.util.ui.NVGRenderer
import org.cobalt.api.util.ui.helper.Gradient
import org.cobalt.render.HudGlassBlurRenderer
import org.cobalt.render.TitleBackgroundRenderer
import org.lwjgl.glfw.GLFW

class DuttWelcomeScreen : Screen(Component.literal("Welcome")) {

  private val mc: Minecraft = Minecraft.getInstance()

  private lateinit var aliasField: EditBox
  private lateinit var continueButton: Button

  private var draftAlias = DuttStartupGate.getAlias()
  private var errorMessage: String? = null

  private var fieldX = 0
  private var baseFieldY = 0
  private var fieldWidth = 220
  private var fieldHeight = 20
  private var baseButtonY = 0

  override fun init() {
    val centerX = width / 2
    fieldX = centerX - fieldWidth / 2
    baseFieldY = height / 2 + 6
    baseButtonY = baseFieldY + 32

    aliasField = EditBox(font, fieldX, baseFieldY, fieldWidth, fieldHeight, Component.literal("Alias"))
    aliasField.setBordered(false)
    aliasField.setSuggestion("enter alias")
    aliasField.setTextColor(0xFFF3F7FF.toInt())
    aliasField.setValue(draftAlias)
    aliasField.setResponder { value ->
      draftAlias = value
      errorMessage = null
      continueButton.active = value.trim().isNotEmpty()
    }
    addRenderableWidget(aliasField)

    continueButton =
      addRenderableWidget(
        Button.builder(Component.literal("Continue")) {
          submit()
        }.bounds(fieldX, baseButtonY, fieldWidth, 20).build()
      )
    continueButton.active = draftAlias.trim().isNotEmpty()
    setInitialFocus(aliasField)
    aliasField.setFocused(true)
    aliasField.moveCursorToEnd(false)
  }

  override fun keyPressed(input: KeyEvent): Boolean {
    if (input.key == GLFW.GLFW_KEY_ENTER || input.key == GLFW.GLFW_KEY_KP_ENTER) {
      submit()
      return true
    }
    return super.keyPressed(input)
  }

  override fun shouldCloseOnEsc(): Boolean {
    return false
  }

  override fun onClose() {
    // The welcome gate must be completed before title is shown.
  }

  override fun renderBackground(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
  }

  override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
    val screenWidth = mc.window.screenWidth.toFloat()
    val screenHeight = mc.window.screenHeight.toFloat()
    val guiScale = mc.window.guiScale.toFloat()
    val timeSeconds = (System.currentTimeMillis() % 1_000_000L) / 1000.0f
    val cardWidth = 300f * guiScale
    val cardHeight = 126f * guiScale
    val cardX = screenWidth / 2f - cardWidth / 2f
    val cardY = baseFieldY * guiScale - 34f * guiScale
    val inputX = fieldX * guiScale
    val inputY = baseFieldY * guiScale
    val inputWidth = fieldWidth * guiScale
    val inputHeight = fieldHeight * guiScale

    NVGRenderer.beginFrame(screenWidth, screenHeight)
    TitleBackgroundRenderer.renderToScreen(screenWidth.toInt(), screenHeight.toInt(), timeSeconds)
    NVGRenderer.endFrame()

    HudGlassBlurRenderer.renderBlurRect(cardX, cardY, cardWidth, cardHeight, 12f * guiScale, 1.45f)
    HudGlassBlurRenderer.renderBlurRect(inputX, inputY, inputWidth, inputHeight, 8f * guiScale, 1.0f)

    NVGRenderer.beginFrame(screenWidth, screenHeight)
    drawWelcomeCard(screenWidth, screenHeight, guiScale, cardX, cardY, cardWidth, cardHeight)
    NVGRenderer.endFrame()

    super.render(guiGraphics, mouseX, mouseY, partialTick)
  }

  private fun submit() {
    val alias = aliasField.value.trim()
    if (alias.isEmpty()) {
      errorMessage = "Enter an alias to continue."
      continueButton.active = false
      return
    }

    DuttStartupGate.unlock(alias)
    mc.setScreen(TitleScreen())
  }

  private fun drawWelcomeCard(
    screenWidth: Float,
    screenHeight: Float,
    guiScale: Float,
    cardX: Float,
    cardY: Float,
    cardWidth: Float,
    cardHeight: Float,
  ) {
    val centerX = screenWidth / 2f
    val fieldVisualY = baseFieldY * guiScale
    val buttonVisualY = baseButtonY * guiScale
    val focused = ::aliasField.isInitialized && aliasField.isFocused

    drawGlassCardOverlay(cardX, cardY, cardWidth, cardHeight, guiScale, focused)
    drawGlassInputBackground(
      fieldX * guiScale,
      fieldVisualY,
      fieldWidth * guiScale,
      fieldHeight * guiScale,
      guiScale,
      focused,
    )
    drawWelcomeHeading(centerX, screenHeight * 0.30f, guiScale)

    val subtitle = "Enter your alias to authenticate."
    val subtitleSize = 9.5f * guiScale
    val subtitleWidth = NVGRenderer.textWidth(subtitle, subtitleSize)
    NVGRenderer.text(
      subtitle,
      centerX - subtitleWidth / 2f,
      screenHeight * 0.30f + 34f * guiScale,
      subtitleSize,
      StartupVisuals.withAlpha(0xAFC3DA, 208),
    )

    val label = "Alias"
    val labelSize = 8.7f * guiScale
    NVGRenderer.text(
      label,
      fieldX * guiScale + 4f * guiScale,
      fieldVisualY - 12f * guiScale,
      labelSize,
      StartupVisuals.withAlpha(0xD6E7F7, 216),
    )

    val error = errorMessage
    if (error != null) {
      val errorSize = 8.4f * guiScale
      val errorWidth = NVGRenderer.textWidth(error, errorSize)
      NVGRenderer.text(
        error,
        centerX - errorWidth / 2f,
        buttonVisualY + 28f * guiScale,
        errorSize,
        StartupVisuals.withAlpha(0xFF9CB3, 224),
      )
    }
  }

  private fun drawWelcomeHeading(centerX: Float, y: Float, guiScale: Float) {
    val title = "Welcome"
    val titleSize = 33f * guiScale
    val titleWidth = NVGRenderer.textWidth(title, titleSize)
    val titleX = centerX - titleWidth / 2f

    NVGRenderer.text(
      title,
      titleX,
      y + 2f * guiScale,
      titleSize,
      StartupVisuals.withAlpha(0x03060B, 56),
    )
    NVGRenderer.text(
      title,
      titleX,
      y,
      titleSize,
      StartupVisuals.withAlpha(0xF3F7FF, 238),
    )
  }

  private fun drawGlassCardOverlay(x: Float, y: Float, width: Float, height: Float, scale: Float, focused: Boolean) {
    val radius = 12f * scale
    val glow = if (focused) 1f else 0f

    NVGRenderer.rect(x, y + 3f * scale, width, height, StartupVisuals.withAlpha(0x010308, 12), radius + 1f)
    NVGRenderer.gradientRect(
      x,
      y,
      width,
      height,
      StartupVisuals.withAlpha(0x0A1320, (18f + glow * 6f).toInt()),
      StartupVisuals.withAlpha(0x060A12, (26f + glow * 8f).toInt()),
      Gradient.TopToBottom,
      radius,
    )
    NVGRenderer.gradientRect(
      x + 1f * scale,
      y + 1f * scale,
      width - 2f * scale,
      height * 0.42f,
      StartupVisuals.withAlpha(0x76CFFF, (6f + glow * 6f).toInt()),
      StartupVisuals.withAlpha(0xD0A8FF, (5f + glow * 5f).toInt()),
      Gradient.LeftToRight,
      radius - 1f * scale,
    )
    NVGRenderer.hollowGradientRect(
      x,
      y,
      width,
      height,
      maxOf(1f, 0.9f * scale),
      StartupVisuals.withAlpha(0x6F90FF, (48f + glow * 18f).toInt()),
      StartupVisuals.withAlpha(0x6FD7FF, (56f + glow * 22f).toInt()),
      Gradient.LeftToRight,
      radius,
    )
    NVGRenderer.hollowRect(
      x + 1f * scale,
      y + 1f * scale,
      width - 2f * scale,
      height - 2f * scale,
      maxOf(1f, 0.7f * scale),
      StartupVisuals.withAlpha(0xF4F8FF, (8f + glow * 8f).toInt()),
      radius - 1f * scale,
    )
  }

  private fun drawGlassInputBackground(x: Float, y: Float, width: Float, height: Float, scale: Float, focused: Boolean) {
    val glow = if (focused) 1f else 0f
    val radius = 8f * scale

    NVGRenderer.gradientRect(
      x,
      y,
      width,
      height,
      StartupVisuals.withAlpha(0x09111C, (16f + glow * 10f).toInt()),
      StartupVisuals.withAlpha(0x060A12, (22f + glow * 12f).toInt()),
      Gradient.TopToBottom,
      radius,
    )
    NVGRenderer.gradientRect(
      x + 1f * scale,
      y + 1f * scale,
      width - 2f * scale,
      height * 0.44f,
      StartupVisuals.withAlpha(0x6FCBFF, (5f + glow * 8f).toInt()),
      StartupVisuals.withAlpha(0xFFFFFF, (3f + glow * 3f).toInt()),
      Gradient.LeftToRight,
      radius - 1f * scale,
    )
    NVGRenderer.hollowGradientRect(
      x,
      y,
      width,
      height,
      maxOf(1f, 0.85f * scale),
      StartupVisuals.withAlpha(0x4C5D86, if (focused) 96 else 68),
      StartupVisuals.withAlpha(0x7FD3FF, if (focused) 132 else 88),
      Gradient.LeftToRight,
      radius,
    )
  }
}
