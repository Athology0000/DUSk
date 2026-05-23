package org.phantom.api.module

import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe registry of loaded modules + their lifecycle state.
 *
 * State transitions are enforced: terminal states (LOAD_FAILED) cannot transition
 * further. Unknown names always return NOT_LOADED.
 */
class ModuleRegistry {
    private data class Entry(
        val module: LoadedModule,
        @Volatile var state: ModuleState,
        @Volatile var failureReason: String? = null,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    fun register(module: LoadedModule) {
        entries[module.name] = Entry(module, ModuleState.LOADED)
    }

    fun moduleOf(name: String): LoadedModule? = entries[name]?.module
    fun stateOf(name: String): ModuleState = entries[name]?.state ?: ModuleState.NOT_LOADED
    fun failureReasonOf(name: String): String? = entries[name]?.failureReason

    fun markActive(name: String) = transition(name, ModuleState.ACTIVE)
    fun markInactive(name: String) = transition(name, ModuleState.INACTIVE)

    fun markLoadFailed(name: String, reason: String) {
        entries[name]?.let { e ->
            e.state = ModuleState.LOAD_FAILED
            e.failureReason = reason
        }
    }

    fun markActivationFailed(name: String, reason: String) {
        entries[name]?.let { e ->
            if (e.state != ModuleState.LOAD_FAILED) {
                e.state = ModuleState.ACTIVATION_FAILED
                e.failureReason = reason
            }
        }
    }

    /** All modules currently in ACTIVE state. */
    fun activeModules(): List<LoadedModule> =
        entries.values.filter { it.state == ModuleState.ACTIVE }.map { it.module }

    /** All known modules (any state). */
    fun allModules(): List<LoadedModule> = entries.values.map { it.module }

    private fun transition(name: String, to: ModuleState) {
        val entry = entries[name] ?: return
        if (entry.state == ModuleState.LOAD_FAILED) return // terminal
        entry.state = to
        entry.failureReason = null
    }
}
