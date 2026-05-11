package org.cobalt.internal.fishing.types

import org.cobalt.internal.fishing.FishingMacroModule
import org.cobalt.internal.fishing.FishingTypeProfile

object NormalFishingProfile : FishingTypeProfile {
  override val type = FishingMacroModule.FishingType.NORMAL
  override val displayName = "Normal"
}
