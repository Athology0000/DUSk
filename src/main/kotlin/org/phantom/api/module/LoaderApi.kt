package org.phantom.api.module

import org.phantom.api.event.EventBus

/**
 * The only surface a server-loaded module reaches into the loader through.
 * Implementations live in the loader; module code must depend only on this interface.
 */
interface LoaderApi {
    val eventBus: EventBus
    val registry: ModuleRegistry
    val auth: AuthSnapshot

    /** Request activation of the named module. Calls /auth/verify-module then onActivate(). */
    fun requestActivate(moduleName: String, callback: (Result) -> Unit)

    /** Deactivate the named module locally (no server call). */
    fun requestDeactivate(moduleName: String)

    /** Loader log; module code uses this for stack-trace attribution. */
    fun logInfo(message: String)
    fun logError(message: String, throwable: Throwable? = null)
}
