package org.cobalt.api.module.setting.impl

import org.cobalt.api.ui.theme.ThemeManager

/**
 * Singleton provider for globally synced rainbow phase computation.
 *
 * All ColorSettings using SyncedRainbow mode share the same phase from this provider.
 * Phase computation is delegated to ThemeManager for theme-level synchronization.
 */
object RainbowPhaseProvider {

  /**
   * Get the current hue value (0..1) for globally synced rainbow.
   *
   * @param speed Speed multiplier (default 1.0, higher = faster rotation)
   * @return Hue value in range 0..1 (wraps at 1.0)
   */
  fun getHue(speed: Float = 1f): Float {
    return ThemeManager.getRainbowHue(speed)
  }

}
