package org.phantom.api.event.impl.render

import net.minecraft.world.phys.Vec3
import org.phantom.api.event.Event

/** Posted at `Camera.setup` TAIL. A subscriber may set [positionOverride]. */
class CameraSetupEvent : Event() {
  var positionOverride: Vec3? = null
}
