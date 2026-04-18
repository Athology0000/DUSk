package org.cobalt.api.pathfinder.pathing.processing

import org.cobalt.api.pathfinder.pathing.processing.context.EvaluationContext

interface NodeProcessor : Processor {

  fun isValid(context: EvaluationContext): Boolean = true
  fun calculateCostContribution(context: EvaluationContext): Cost = Cost.ZERO

}
