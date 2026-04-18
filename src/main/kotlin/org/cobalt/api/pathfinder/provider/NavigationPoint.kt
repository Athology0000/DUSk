package org.cobalt.api.pathfinder.provider

interface NavigationPoint {
  fun isTraversable(): Boolean
  fun hasFloor(): Boolean
  fun getFloorLevel(): Double
  fun isClimbable(): Boolean
  fun isLiquid(): Boolean
}
