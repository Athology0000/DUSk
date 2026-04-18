package org.cobalt.internal.visual

import kotlin.math.PI
import kotlin.math.max
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.TitleScreen
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.render.NvgEvent
import org.cobalt.api.util.ui.NVGRenderer
import org.cobalt.api.util.ui.helper.Gradient
import org.cobalt.internal.ui.animation.RiseAnimation
import org.cobalt.internal.ui.animation.RiseEasing
import org.cobalt.render.TitleBackgroundRenderer
import org.cobalt.render.rise.GlowButton
import org.cobalt.render.rise.RenderTarget
import org.cobalt.render.rise.ShaderRegistry
import org.cobalt.render.rise.UiShaderDrawHelper
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30

object TitleScreenRenderer {

  private const val TITLE_TEXT = "Dutt Client"

  private val mc = Minecraft.getInstance()

  private var mouseGuiX = 0
  private var mouseGuiY = 0
  private var wasTitleScreen = false

  private val introAnimation = RiseAnimation(0.0)
  private val hoverAnimations = hashMapOf<String, RiseAnimation>()

  private val glowMaskTarget = RenderTarget()
  private val glowOutlineTarget = RenderTarget()
  private val glowBlurTargetA = RenderTarget()
  private val glowBlurTargetB = RenderTarget()
  private val titleGlowMaskTarget = RenderTarget(true)
  private val titleGlowBlurTargetA = RenderTarget()
  private val titleGlowBlurTargetB = RenderTarget()

  init {
    EventBus.register(this)
  }

  fun setMousePos(x: Int, y: Int) {
    mouseGuiX = x
    mouseGuiY = y
  }

  fun render(@Suppress("UNUSED_PARAMETER") guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, @Suppress("UNUSED_PARAMETER") partialTick: Float) {
    setMousePos(mouseX, mouseY)

    if (mc.screen !is TitleScreen) {
      resetState()
      return
    }

    if (!wasTitleScreen) {
      wasTitleScreen = true
      introAnimation.snap(0.0)
      introAnimation.run(1.0, 520L, RiseEasing.EASE_OUT_EXPO)
    }

    val screenWidth = mc.window.screenWidth.toFloat()
    val screenHeight = mc.window.screenHeight.toFloat()
    val guiScale = mc.window.guiScale.toFloat()
    val guiWidth = mc.window.guiScaledWidth.toFloat()
    val guiHeight = mc.window.guiScaledHeight.toFloat()
    val timeSeconds = (System.currentTimeMillis() % 1_000_000L) / 1000.0f
    val intro = introAnimation.getValue().toFloat().coerceIn(0f, 1f)
    val buttons = buildButtons(guiWidth, guiHeight, guiScale, intro)

    NVGRenderer.beginFrame(screenWidth, screenHeight)
    TitleBackgroundRenderer.renderToScreen(screenWidth.toInt(), screenHeight.toInt(), timeSeconds)
    NVGRenderer.endFrame()
    renderTitleLightBleed(screenWidth.toInt(), screenHeight.toInt(), guiScale, timeSeconds, intro)
    NVGRenderer.beginFrame(screenWidth, screenHeight)
    renderHoverBloom(buttons, screenWidth.toInt(), screenHeight.toInt())
    renderButtons(buttons, screenHeight.toInt(), guiScale, timeSeconds, intro)
    drawTitleText(screenWidth, screenHeight, guiScale, intro)
    drawButtonLabels(buttons, guiScale, timeSeconds, intro)
    NVGRenderer.endFrame()
  }

  @SubscribeEvent
  fun onNvg(@Suppress("UNUSED_PARAMETER") event: NvgEvent) {
    if (mc.screen !is TitleScreen) {
      resetState()
    }
  }

  private fun resetState() {
    if (!wasTitleScreen) {
      return
    }
    wasTitleScreen = false
    hoverAnimations.clear()
    introAnimation.snap(0.0)
  }

  private fun buildButtons(guiWidth: Float, guiHeight: Float, scale: Float, intro: Float): List<GlowButton> {
    val centerX = guiWidth / 2f
    val specs = listOf(
      ButtonSpec("Singleplayer", centerX - 100f, guiHeight / 4f + 48f, 200f),
      ButtonSpec("Multiplayer", centerX - 100f, guiHeight / 4f + 72f, 200f),
      ButtonSpec("Options", centerX - 100f, guiHeight / 4f + 132f, 98f),
      ButtonSpec("Quit", centerX + 2f, guiHeight / 4f + 132f, 98f),
    )

    return specs.map { spec ->
      val hovered =
        mouseGuiX >= spec.x && mouseGuiX <= spec.x + spec.width &&
          mouseGuiY >= spec.y && mouseGuiY <= spec.y + spec.height
      val hoverAnimation = hoverAnimations.getOrPut(spec.label) { RiseAnimation(0.0) }
      hoverAnimation.run(if (hovered) 1.0 else 0.0, 160L, RiseEasing.EASE_OUT_CUBIC)
      val hover = hoverAnimation.getValue().toFloat().coerceIn(0f, 1f)
      val introOffset = (1f - intro) * 18f

      GlowButton(
        spec.label,
        spec.x * scale,
        (spec.y + introOffset) * scale,
        spec.width * scale,
        spec.height * scale,
        7f * scale,
        hover,
      )
    }
  }

  private fun renderHoverBloom(buttons: List<GlowButton>, screenWidth: Int, screenHeight: Int) {
    val hoveredButtons = buttons.filter { it.hoverProgress > 0.03f }
    if (hoveredButtons.isEmpty()) {
      return
    }

    val previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING)
    val previousViewport = IntArray(4)
    GL11.glGetIntegerv(GL11.GL_VIEWPORT, previousViewport)

    glowMaskTarget.ensureSize(screenWidth, screenHeight)
    glowOutlineTarget.ensureSize(screenWidth, screenHeight)
    glowBlurTargetA.ensureSize(screenWidth, screenHeight)
    glowBlurTargetB.ensureSize(screenWidth, screenHeight)

    GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer)
    GL11.glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3])

    try {
      glowMaskTarget.clear(0f, 0f, 0f, 0f)
      hoveredButtons.forEach { button ->
        val hover = button.hoverProgress
        val bloomAlpha = (88f + 132f * hover).toInt()
        UiShaderDrawHelper.drawRoundedRect(
          button.x - 3f,
          button.y - 3f,
          button.width + 6f,
          button.height + 6f,
          button.radius + 3f,
          withAlpha(0x9FDBFF, bloomAlpha),
          screenHeight,
        )
      }
    } finally {
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer)
      GL11.glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3])
    }

    ShaderRegistry.OUTLINE.render(glowMaskTarget, glowOutlineTarget, 2.0f, 0x6FB8FFFF)
    ShaderRegistry.BLOOM.render(glowOutlineTarget, glowBlurTargetA, glowBlurTargetB, 8)
    ShaderRegistry.SCREEN_SPACE.renderTextureAdditive(glowBlurTargetB.textureId, 1.0f)
    ShaderRegistry.SCREEN_SPACE.renderTextureAdditive(glowOutlineTarget.textureId, 0.72f)
  }

  private fun renderButtons(buttons: List<GlowButton>, screenHeight: Int, scale: Float, timeSeconds: Float, intro: Float) {
    buttons.forEach { button ->
      val hover = button.hoverProgress
      val baseAlpha = (92f + 38f * intro).toInt()
      val fillAlpha = (74f + 52f * hover + 24f * intro).toInt()
      val sheenAlpha = (12f + 38f * hover + 18f * intro).toInt()
      val borderAlpha = (72f + 88f * hover + 28f * intro).toInt()

      UiShaderDrawHelper.drawRoundedRect(
        button.x + 2f * scale,
        button.y + 3f * scale,
        button.width,
        button.height,
        button.radius,
        withAlpha(0x02060C, (26f + 22f * intro).toInt()),
        screenHeight,
      )
      UiShaderDrawHelper.drawGradientRoundedRect(
        button.x,
        button.y,
        button.width,
        button.height,
        button.radius,
        withAlpha(0x111A29, baseAlpha),
        withAlpha(0x0A111B, fillAlpha),
        Gradient.TopToBottom,
        screenHeight,
      )
      UiShaderDrawHelper.drawAnimatedGradientRoundedRect(
        button.x + 1f * scale,
        button.y + 1f * scale,
        button.width - 2f * scale,
        button.height * (0.56f + hover * 0.08f),
        max(0f, button.radius - 1f * scale),
        withAlpha(0x64D4FF, sheenAlpha),
        withAlpha(0xBE8EFF, (sheenAlpha * 0.85f).toInt()),
        Gradient.LeftToRight,
        timeSeconds,
        screenHeight,
      )
      UiShaderDrawHelper.drawGradientOutline(
        button.x,
        button.y,
        button.width,
        button.height,
        button.radius,
        max(1f, 1.05f * scale),
        withAlpha(0x73C4FF, borderAlpha),
        withAlpha(0xB78DFF, (borderAlpha * 0.92f).toInt()),
        Gradient.LeftToRight,
        screenHeight,
      )
      if (hover > 0.02f) {
        UiShaderDrawHelper.drawRoundedOutline(
          button.x + 1.5f * scale,
          button.y + 1.5f * scale,
          button.width - 3f * scale,
          button.height - 3f * scale,
          max(0f, button.radius - 1.5f * scale),
          max(1f, 1.0f * scale),
          withAlpha(0xE2F6FF, (18f + 46f * hover).toInt()),
          screenHeight,
        )
      }
    }
  }

  private fun renderTitleLightBleed(screenWidth: Int, screenHeight: Int, scale: Float, timeSeconds: Float, intro: Float) {
    if (intro <= 0f) {
      return
    }

    val titleSize = 31f * scale
    val titleWidth = NVGRenderer.textWidth(TITLE_TEXT, titleSize)
    val titleX = screenWidth / 2f - titleWidth / 2f
    val baseY = screenHeight * 0.197f - (1f - intro) * 18f * scale
    val pulse = kotlin.math.sin(timeSeconds * 1.12f) * 0.5f + 0.5f
    val tightSpread = (0.85f + pulse * 0.18f) * scale
    val wideSpread = (1.55f + pulse * 0.32f) * scale
    val tightAlpha = ((96f + pulse * 20f) * intro).toInt().coerceIn(0, 255)
    val wideAlpha = ((34f + pulse * 14f) * intro).toInt().coerceIn(0, 255)
    val coreAlpha = ((142f + pulse * 18f) * intro).toInt().coerceIn(0, 255)
    val compositeAlpha = (0.34f + pulse * 0.08f) * intro
    val tightOffsets =
      arrayOf(
        floatArrayOf(-1f, 0f),
        floatArrayOf(1f, 0f),
        floatArrayOf(0f, -1f),
        floatArrayOf(0f, 1f),
        floatArrayOf(-0.72f, -0.72f),
        floatArrayOf(0.72f, -0.72f),
        floatArrayOf(-0.72f, 0.72f),
        floatArrayOf(0.72f, 0.72f),
      )
    val wideOffsets =
      arrayOf(
        floatArrayOf(-1f, 0f),
        floatArrayOf(1f, 0f),
        floatArrayOf(0f, -1f),
        floatArrayOf(0f, 1f),
        floatArrayOf(-0.82f, -0.82f),
        floatArrayOf(0.82f, -0.82f),
        floatArrayOf(-0.82f, 0.82f),
        floatArrayOf(0.82f, 0.82f),
      )
    val previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING)
    val previousViewport = IntArray(4)
    GL11.glGetIntegerv(GL11.GL_VIEWPORT, previousViewport)

    titleGlowMaskTarget.ensureSize(screenWidth, screenHeight)
    titleGlowBlurTargetA.ensureSize(screenWidth, screenHeight)
    titleGlowBlurTargetB.ensureSize(screenWidth, screenHeight)

    try {
      titleGlowMaskTarget.clear(0f, 0f, 0f, 0f)
      NVGRenderer.beginFrame(
        screenWidth.toFloat(),
        screenHeight.toFloat(),
        titleGlowMaskTarget.fboId,
        titleGlowMaskTarget.width,
        titleGlowMaskTarget.height,
      )
      wideOffsets.forEach { offset ->
        NVGRenderer.text(
          TITLE_TEXT,
          titleX + offset[0] * wideSpread,
          baseY + offset[1] * wideSpread,
          titleSize,
          withAlpha(0xFFFFFF, wideAlpha),
        )
      }
      tightOffsets.forEach { offset ->
        NVGRenderer.text(
          TITLE_TEXT,
          titleX + offset[0] * tightSpread,
          baseY + offset[1] * tightSpread,
          titleSize,
          withAlpha(0xFFFFFF, tightAlpha),
        )
      }
      NVGRenderer.text(TITLE_TEXT, titleX, baseY, titleSize, withAlpha(0xFFFFFF, coreAlpha))
      NVGRenderer.endFrame()
    } finally {
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer)
      GL11.glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3])
    }

    ShaderRegistry.BLOOM.render(titleGlowMaskTarget, titleGlowBlurTargetA, titleGlowBlurTargetB, 10)
    ShaderRegistry.SCREEN_SPACE.renderTextureAdditive(titleGlowBlurTargetB.textureId, compositeAlpha)
    ShaderRegistry.SCREEN_SPACE.renderTextureAdditive(titleGlowMaskTarget.textureId, compositeAlpha * 0.26f)
  }

  private fun drawTitleText(screenWidth: Float, screenHeight: Float, scale: Float, intro: Float) {
    val titleSize = 31f * scale
    val titleWidth = NVGRenderer.textWidth(TITLE_TEXT, titleSize)
    val titleX = screenWidth / 2f - titleWidth / 2f
    val baseY = screenHeight * 0.197f - (1f - intro) * 18f * scale
    val titleAlpha = (255f * intro).toInt().coerceIn(0, 255)
    val shadowAlpha = (124f * intro).toInt().coerceIn(0, 255)
    val farShadowAlpha = (42f * intro).toInt().coerceIn(0, 255)

    NVGRenderer.text(
      TITLE_TEXT,
      titleX,
      baseY + 5.1f * scale,
      titleSize,
      withAlpha(0x02040A, farShadowAlpha),
    )
    NVGRenderer.text(
      TITLE_TEXT,
      titleX,
      baseY + 2.4f * scale,
      titleSize,
      withAlpha(0x04070C, shadowAlpha),
    )

    NVGRenderer.text(TITLE_TEXT, titleX, baseY, titleSize, withAlpha(0xFFFFFF, titleAlpha))
  }

  private fun drawButtonLabels(buttons: List<GlowButton>, scale: Float, timeSeconds: Float, intro: Float) {
    buttons.forEach { button ->
      val textSize = 9.2f * scale
      val hover = button.hoverProgress
      val textAlpha = (176f + 62f * intro + 18f * hover).toInt().coerceIn(0, 255)
      val textColor = withAlpha(0xEAF4FF, textAlpha)

      if (button.label == "Options") {
        val gearRadius = button.height * 0.28f
        val labelWidth = NVGRenderer.textWidth(button.label, textSize)
        val gap = 4f * scale
        val totalWidth = gearRadius * 2f + gap + labelWidth
        val startX = button.x + (button.width - totalWidth) / 2f
        val gearX = startX + gearRadius
        val gearY = button.y + button.height / 2f
        drawGearIcon(gearX, gearY, gearRadius, timeSeconds.toDouble(), hover)
        NVGRenderer.text(
          button.label,
          startX + gearRadius * 2f + gap,
          button.y + (button.height - textSize) / 2f + 0.5f * scale,
          textSize,
          textColor,
        )
      } else {
        val labelWidth = NVGRenderer.textWidth(button.label, textSize)
        NVGRenderer.text(
          button.label,
          button.x + (button.width - labelWidth) / 2f,
          button.y + (button.height - textSize) / 2f + 0.5f * scale,
          textSize,
          textColor,
        )
      }
    }
  }

  private fun drawGearIcon(cx: Float, cy: Float, outerRadius: Float, time: Double, hover: Float) {
    val teeth = 8
    val rotation = (time * (0.42 + hover * 0.18)).toFloat()
    val discRadius = outerRadius * 0.66f
    val toothWidth = outerRadius * 0.32f
    val toothStart = discRadius - outerRadius * 0.08f
    val toothLength = outerRadius - toothStart
    val color = withAlpha(0xEDF4FF, (194f + 42f * hover).toInt())

    repeat(teeth) { index ->
      val angle = rotation + index * (2f * PI.toFloat() / teeth)
      NVGRenderer.push()
      NVGRenderer.translate(cx, cy)
      NVGRenderer.rotate(angle)
      NVGRenderer.rect(-toothWidth / 2f, toothStart, toothWidth, toothLength, color, toothWidth * 0.24f)
      NVGRenderer.pop()
    }

    NVGRenderer.circle(cx, cy, discRadius, color)
    NVGRenderer.circle(cx, cy, discRadius * 0.42f, 0xEA060A12.toInt())
  }

  private fun withAlpha(rgb: Int, alpha: Int): Int {
    return (alpha.coerceIn(0, 255) shl 24) or (rgb and 0x00FFFFFF)
  }

  private data class ButtonSpec(
    val label: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float = 20f,
  )
}
