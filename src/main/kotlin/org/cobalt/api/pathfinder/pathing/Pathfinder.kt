package org.cobalt.api.pathfinder.pathing

import java.util.concurrent.CompletionStage
import org.cobalt.api.pathfinder.pathing.context.EnvironmentContext
import org.cobalt.api.pathfinder.pathing.result.PathfinderResult
import org.cobalt.api.pathfinder.wrapper.PathPosition

interface Pathfinder {

  fun findPath(start: PathPosition, target: PathPosition): CompletionStage<PathfinderResult> {
    return findPath(start, target, null)
  }

  fun findPath(
    start: PathPosition,
    target: PathPosition,
    context: EnvironmentContext?,
  ): CompletionStage<PathfinderResult>

  fun abort()

}
