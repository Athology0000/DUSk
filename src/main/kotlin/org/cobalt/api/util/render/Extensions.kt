package org.cobalt.api.util.render

import java.awt.Color
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.cobalt.api.event.impl.render.WorldRenderContext

fun WorldRenderContext.drawBox(box: AABB, color: Color, esp: Boolean = false) {
  Render3D.drawBox(this, box, color, esp)
}

fun WorldRenderContext.drawLine(
  start: Vec3,
  end: Vec3,
  color: Color,
  esp: Boolean = false,
  thickness: Float = 1f,
) {
  Render3D.drawLine(this, start, end, color, esp, thickness)
}
