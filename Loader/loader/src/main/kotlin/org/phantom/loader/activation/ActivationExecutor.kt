package org.phantom.loader.activation

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Single-thread executor that serializes activation requests.
 *
 * Two concurrent calls to LoaderApi.requestActivate("X") result in exactly one
 * /auth/verify-module call from inside the worker; the second submit is queued
 * behind the first and runs after it completes.
 */
class ActivationExecutor {
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Phantom-Activation").apply { isDaemon = true }
    }

    fun submit(task: () -> Unit) {
        executor.submit(task)
    }

    fun shutdown() {
        executor.shutdown()
        executor.awaitTermination(2, TimeUnit.SECONDS)
    }
}
