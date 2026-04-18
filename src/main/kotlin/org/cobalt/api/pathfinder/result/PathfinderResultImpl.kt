package org.cobalt.api.pathfinder.result

import org.cobalt.api.pathfinder.pathing.result.Path
import org.cobalt.api.pathfinder.pathing.result.PathState
import org.cobalt.api.pathfinder.pathing.result.PathfinderResult

class PathfinderResultImpl(
  private val pathState: PathState,
  private val path: Path,
) : PathfinderResult {

  override fun successful(): Boolean {
    return pathState == PathState.FOUND ||
      pathState == PathState.FALLBACK ||
      pathState == PathState.MAX_ITERATIONS_REACHED
  }

  override fun hasFailed(): Boolean {
    return pathState == PathState.FAILED ||
      pathState == PathState.ABORTED ||
      pathState == PathState.LENGTH_LIMITED
  }

  override fun hasFallenBack(): Boolean = pathState == PathState.FALLBACK
  override fun getPathState(): PathState = pathState
  override fun getPath(): Path = path

}
