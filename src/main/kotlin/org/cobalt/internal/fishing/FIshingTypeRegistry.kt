package org.cobalt.internal.fishing

import org.cobalt.internal.fishing.types.BarnFishingProfile
import org.cobalt.internal.fishing.types.NormalFishingProfile
import org.cobalt.internal.fishing.types.StriderSurferFishingProfile
import org.cobalt.internal.fishing.types.TrophyFishingProfile
import org.cobalt.internal.fishing.types.WormFishingProfile

object FishingTypeRegistry {
  private val profiles: Map<FishingMacroModule.FishingType, FishingTypeProfile> =
    listOf(
      NormalFishingProfile,
      BarnFishingProfile,
      TrophyFishingProfile,
      WormFishingProfile,
      StriderSurferFishingProfile,
    ).associateBy { it.type }

  fun get(type: FishingMacroModule.FishingType): FishingTypeProfile {
    return profiles[type] ?: NormalFishingProfile
  }
}
