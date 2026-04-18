package org.cobalt.api.pathfinder.pathfinder.processing

import org.cobalt.api.pathfinder.pathing.configuration.PathfinderConfiguration
import org.cobalt.api.pathfinder.pathing.context.EnvironmentContext
import org.cobalt.api.pathfinder.pathing.processing.context.SearchContext
import org.cobalt.api.pathfinder.provider.NavigationPointProvider
import org.cobalt.api.pathfinder.wrapper.PathPosition

class SearchContextImpl(
  override val startPathPosition: PathPosition,
  override val targetPathPosition: PathPosition,
  override val pathfinderConfiguration: PathfinderConfiguration,
  override val navigationPointProvider: NavigationPointProvider,
  override val environmentContext: EnvironmentContext?,
) : SearchContext {

  override val sharedData: MutableMap<String, Any> = HashMap()

}
