package org.cobalt.api.util.render

import java.awt.Color
import com.mojang.blaze3d.opengl.GlStateManager
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.renderer.LightTexture
import net.minecraft.gizmos.GizmoStyle
import net.minecraft.gizmos.Gizmos
import net.minecraft.util.ARGB
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.cobalt.api.event.impl.render.WorldRenderContext

object Render3D {

  @JvmStatic
  fun drawBox(context: WorldRenderContext, box: AABB, color: Color, esp: Boolean = false) {
    drawStyledBox(
      context = context,
      box = box,
      strokeColor = color,
      fillColor = Color(color.red, color.green, color.blue, 150),
      esp = esp,
      lineWidth = 2.5f
    )
  }

  @JvmStatic
  fun drawStyledBox(
    context: WorldRenderContext,
    box: AABB,
    strokeColor: Color,
    fillColor: Color? = null,
    esp: Boolean = false,
    lineWidth: Float = 2.5f,
  ) {
    if (!FrustumUtils.isVisible(context.frustum, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)) {
      return
    }

    val stroke = ARGB.color(strokeColor.alpha, strokeColor.red, strokeColor.green, strokeColor.blue)
    val fill = fillColor?.let { ARGB.color(it.alpha, it.red, it.green, it.blue) } ?: ARGB.color(0, 0, 0, 0)

    val style = GizmoStyle.strokeAndFill(stroke, lineWidth, fill)
    val props = Gizmos.cuboid(box, style)

    if (esp) {
      props.setAlwaysOnTop()
    }
  }

  @JvmStatic
  fun drawLine(
    context: WorldRenderContext,
    start: Vec3,
    end: Vec3,
    color: Color,
    esp: Boolean = false,
    thickness: Float = 1f,
  ) {
    if (!FrustumUtils.isVisible(
        context.frustum,
        minOf(start.x, end.x), minOf(start.y, end.y), minOf(start.z, end.z),
        maxOf(start.x, end.x), maxOf(start.y, end.y), maxOf(start.z, end.z)
      )
    ) return

    val argbColor = ARGB.color(color.alpha, color.red, color.green, color.blue)
    val props = Gizmos.line(start, end, argbColor, thickness)

    if (esp) {
      props.setAlwaysOnTop()
    }
  }

  /**
   * Renders a billboard text label in world space, visible through blocks.
   * The label always faces the camera.
   */
  @JvmStatic
  fun drawWorldLabel(context: WorldRenderContext, worldPos: Vec3, text: String, color: Color) {
    val mc = Minecraft.getInstance()
    val camera = context.camera
    val cam = camera.position()
    val matrices = context.matrixStack ?: PoseStack()
    val font = mc.font
    val buffer = mc.renderBuffers().bufferSource()
    val textWidth = font.width(text).toFloat()
    val scale = 0.025f

    try {
      GlStateManager._enableBlend()
      GlStateManager._blendFuncSeparate(770, 771, 1, 771)
      GlStateManager._disableDepthTest()
      GlStateManager._depthMask(false)

      matrices.pushPose()
      matrices.translate(worldPos.x - cam.x, worldPos.y - cam.y, worldPos.z - cam.z)
      matrices.mulPose(camera.rotation())
      matrices.scale(-scale, -scale, scale)

      font.drawInBatch(
        text,
        -textWidth / 2f,
        0f,
        color.rgb,
        false,
        matrices.last().pose(),
        buffer,
        Font.DisplayMode.SEE_THROUGH,
        0,
        LightTexture.FULL_BRIGHT,
      )

      matrices.popPose()
      buffer.endBatch()
    } finally {
      GlStateManager._depthMask(true)
      GlStateManager._enableDepthTest()
      GlStateManager._disableBlend()
    }
  }

}
