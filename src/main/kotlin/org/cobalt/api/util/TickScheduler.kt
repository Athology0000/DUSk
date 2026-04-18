package org.cobalt.api.util

import java.util.*
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.TickEvent

object TickScheduler {

  private val taskQueue = PriorityQueue<ScheduledTask>(Comparator.comparingLong(ScheduledTask::executeTick))
  private var currentTick: Long = 0

  private data class ScheduledTask(val executeTick: Long, val action: Runnable)

  init {
    EventBus.register(this)
  }

  @JvmStatic
  fun schedule(delayTicks: Long, action: Runnable) {
    taskQueue.offer(ScheduledTask(currentTick + delayTicks, action))
  }

  @SubscribeEvent
  fun onClientTick(event: TickEvent.End) {
    currentTick++
    var task: ScheduledTask?

    while (taskQueue.peek().also { task = it } != null && currentTick >= task!!.executeTick) {
      taskQueue.poll().action.run()
    }
  }

}
