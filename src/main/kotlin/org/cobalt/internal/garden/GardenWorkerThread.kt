package org.cobalt.internal.garden

import java.util.concurrent.LinkedBlockingDeque

object GardenWorkerThread {

    private val queue = LinkedBlockingDeque<Pair<String, () -> Unit>>()
    @Volatile private var thread: Thread? = null

    fun submit(name: String, block: () -> Unit) {
        ensureRunning()
        queue.offer(name to block)
    }

    fun shutdown() {
        thread?.interrupt()
        queue.clear()
        try { thread?.join(2000) } catch (_: InterruptedException) {}
        thread = null
    }

    private fun ensureRunning() {
        val t = thread
        if (t != null && t.isAlive) return
        thread = Thread({
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val (_, task) = queue.take()
                    task()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }, "GardenWorkerThread").also { it.isDaemon = true; it.start() }
    }
}
