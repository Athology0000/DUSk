package org.cobalt.internal.visual

object DuttStartupGate {

  private var unlocked = false
  private var alias = ""

  fun isUnlocked(): Boolean {
    return unlocked
  }

  fun getAlias(): String {
    return alias
  }

  fun unlock(value: String) {
    alias = value
    unlocked = true
  }
}
