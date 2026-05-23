package org.phantom.loader.activation

import org.phantom.api.module.LoaderApi
import org.phantom.api.module.ModuleRegistry

/**
 * Lazy-initialized handles to the live loader state. Populated by
 * BootstrapStarter; consumed by the heartbeat thread and any future
 * UI bridge that needs to call requestActivate from outside the bootstrap.
 */
object ActiveLoaderState {
    @Volatile var registry: ModuleRegistry? = null
    @Volatile var activationExecutor: ActivationExecutor? = null
    @Volatile var loaderApi: LoaderApi? = null
}
