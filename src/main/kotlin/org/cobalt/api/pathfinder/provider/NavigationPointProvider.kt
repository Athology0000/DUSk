package org.cobalt.api.pathfinder.provider

import org.cobalt.api.pathfinder.pathing.context.EnvironmentContext
import org.cobalt.api.pathfinder.wrapper.PathPosition

interface NavigationPointProvider {

  fun getNavigationPoint(position: PathPosition): NavigationPoint {
    return getNavigationPoint(position, null)
  }

  fun getNavigationPoint(position: PathPosition, environmentContext: EnvironmentContext?): NavigationPoint

}
