package org.cobalt.api.pathfinder.pathing.processing.context

import org.cobalt.api.pathfinder.pathing.configuration.PathfinderConfiguration
import org.cobalt.api.pathfinder.pathing.context.EnvironmentContext
import org.cobalt.api.pathfinder.provider.NavigationPointProvider
import org.cobalt.api.pathfinder.wrapper.PathPosition

interface SearchContext {

  val startPathPosition: PathPosition
  val targetPathPosition: PathPosition
  val pathfinderConfiguration: PathfinderConfiguration
  val navigationPointProvider: NavigationPointProvider
  val sharedData: MutableMap<String, Any>
  val environmentContext: EnvironmentContext?

}
