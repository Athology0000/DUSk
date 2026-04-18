package org.cobalt.api.event.impl.render

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Camera
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.culling.Frustum
import org.cobalt.api.event.Event

@Suppress("UNUSED_PARAMETER")
abstract class WorldRenderEvent(val context: WorldRenderContext) : Event() {

  class Start(context: WorldRenderContext) : WorldRenderEvent(context)
  class Last(context: WorldRenderContext) : WorldRenderEvent(context)

}

class WorldRenderContext {

  var matrixStack: PoseStack? = null
  lateinit var consumers: MultiBufferSource
  lateinit var camera: Camera
  lateinit var frustum: Frustum

}
