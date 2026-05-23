package org.phantom.loader.activation

import org.phantom.api.event.EventBus
import org.phantom.api.module.AuthSnapshot
import org.phantom.api.module.LoaderApi
import org.phantom.api.module.ModuleRegistry
import org.phantom.api.module.ModuleState
import org.phantom.api.module.Result
import org.phantom.loader.LoaderLog
import org.phantom.loader.bootstrap.BootstrapVerifyModuleClient
import org.phantom.loader.bootstrap.VerifyModuleOutcome

class DefaultLoaderApi(
    private val sessionToken: String,
    override val auth: AuthSnapshot,
    override val registry: ModuleRegistry,
    private val executor: ActivationExecutor,
    private val onSessionInvalid: () -> Unit,
) : LoaderApi {

    override val eventBus: EventBus get() = EventBus

    override fun requestActivate(moduleName: String, callback: (Result) -> Unit) {
        executor.submit {
            val module = registry.moduleOf(moduleName)
            if (module == null) {
                callback(Result.Error(IllegalStateException("not loaded: $moduleName")))
                return@submit
            }
            // Idempotent: already active → return Ok without re-verifying.
            if (registry.stateOf(moduleName) == ModuleState.ACTIVE) {
                callback(Result.Ok)
                return@submit
            }

            val outcome = BootstrapVerifyModuleClient.verify(
                sessionToken = sessionToken,
                moduleName = moduleName,
                minecraftUsername = auth.minecraftUsername,
            )

            when (outcome) {
                is VerifyModuleOutcome.Authorized -> {
                    try {
                        module.onActivate()
                        registry.markActive(moduleName)
                        callback(Result.Ok)
                    } catch (t: Throwable) {
                        try { module.onDeactivate() } catch (_: Throwable) {}
                        registry.markActivationFailed(moduleName, t.message ?: "activate threw")
                        logError("Activation of $moduleName threw", t)
                        callback(Result.Error(t))
                    }
                }
                is VerifyModuleOutcome.Denied -> callback(Result.Denied(outcome.reason))
                is VerifyModuleOutcome.SessionInvalid -> {
                    onSessionInvalid()
                    callback(Result.Denied("session_invalid"))
                }
                is VerifyModuleOutcome.Error -> callback(Result.Error(outcome.cause))
            }
        }
    }

    override fun requestDeactivate(moduleName: String) {
        executor.submit {
            val module = registry.moduleOf(moduleName) ?: return@submit
            if (registry.stateOf(moduleName) != ModuleState.ACTIVE) return@submit
            try {
                module.onDeactivate()
            } catch (t: Throwable) {
                logError("Deactivation of $moduleName threw", t)
            } finally {
                registry.markInactive(moduleName)
            }
        }
    }

    override fun logInfo(message: String) = LoaderLog.info(message)
    override fun logError(message: String, throwable: Throwable?) {
        LoaderLog.error(message, throwable)
    }
}
