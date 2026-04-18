package org.cobalt.api.util.player

object MovementManager {

  @JvmField
  @Volatile
  var isLookLocked = false

  @JvmField
  @Volatile
  var isMovementLocked = false

  @JvmField
  @Volatile
  var hasForcedMovement = false

  @JvmField
  @Volatile
  var forcedForward = false

  @JvmField
  @Volatile
  var forcedBackward = false

  @JvmField
  @Volatile
  var forcedLeft = false

  @JvmField
  @Volatile
  var forcedRight = false

  @JvmField
  @Volatile
  var forcedJump = false

  @JvmField
  @Volatile
  var forcedShift = false

  @JvmField
  @Volatile
  var forcedSprint = false

  @JvmField
  @Volatile
  var forcedAttack = false

  @JvmField
  @Volatile
  var forcedUse = false

  /** When true, the mixin intercepts keyAttack/keyUse and returns the forced values.
   *  Must be set by callers that need to control those keys (e.g. DianaModule).
   *  Cleared automatically by clearForcedMovement(). */
  @JvmField
  @Volatile
  var forcedActionsEnabled = false

  @JvmStatic
  fun setLookLock(state: Boolean = true) {
    isLookLocked = state
  }

  @JvmStatic
  fun setMovementLock(state: Boolean = true) {
    isMovementLocked = state
    if (!state) {
      clearForcedMovement()
    }
  }

  @JvmStatic
  fun setForcedMovement(
    forward: Boolean,
    backward: Boolean,
    left: Boolean,
    right: Boolean,
    jump: Boolean,
    shift: Boolean,
    sprint: Boolean
  ) {
    forcedForward = forward
    forcedBackward = backward
    forcedLeft = left
    forcedRight = right
    forcedJump = jump
    forcedShift = shift
    forcedSprint = sprint
    hasForcedMovement = true
  }

  @JvmStatic
  fun clearForcedMovement() {
    hasForcedMovement = false
    forcedForward = false
    forcedBackward = false
    forcedLeft = false
    forcedRight = false
    forcedJump = false
    forcedShift = false
    forcedSprint = false
    forcedAttack = false
    forcedUse    = false
    forcedActionsEnabled = false
  }

}
