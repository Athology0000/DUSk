package org.phantom.api.module

/**
 * Contract every server-loaded module implements.
 *
 * Lifecycle:
 *   - onLoad(api) — called once per MC session on the bootstrap thread, in dependency order.
 *     Allowed: read api.auth, register descriptors with api.registry, allocate buffers.
 *     Forbidden: subscribe to EventBus, touch the Minecraft world, spawn long-running threads.
 *
 *   - onActivate() — called when activation is authorized (post /auth/verify-module=true).
 *     Allowed: api.eventBus.register(this), start scheduled tasks.
 *     Forbidden: blocking network I/O on this thread.
 *
 *   - onDeactivate() — called when user toggles off OR heartbeat cascade fires.
 *     Must unregister from EventBus and cancel any scheduled work. Idempotent.
 */
interface LoadedModule {
    val name: String
    fun onLoad(api: LoaderApi)
    fun onActivate()
    fun onDeactivate()
}
