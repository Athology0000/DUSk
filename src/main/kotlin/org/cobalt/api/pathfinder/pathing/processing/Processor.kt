package org.cobalt.api.pathfinder.pathing.processing

import org.cobalt.api.pathfinder.pathing.processing.context.SearchContext

interface Processor {
  fun initializeSearch(context: SearchContext) {
  }

  fun finalizeSearch(context: SearchContext) {
  }

}
