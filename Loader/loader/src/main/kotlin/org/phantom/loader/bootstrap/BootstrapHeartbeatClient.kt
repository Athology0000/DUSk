package org.phantom.loader.bootstrap

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.phantom.api.module.ModuleRegistry
import org.phantom.internal.auth.Auth
import org.phantom.internal.auth.AuthState
import org.phantom.internal.loader.AddonLoader
import org.phantom.loader.LoaderConfig
import org.phantom.loader.LoaderLog
import org.phantom.loader.activation.ActiveLoaderState
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.concurrent.thread

object BootstrapHeartbeatClient {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    @Volatile
    private var worker: Thread? = null

    fun start(sessionToken: String) {
        worker?.interrupt()
        worker = thread(name = "Phantom-Heartbeat", isDaemon = true) {
            var failures = 0
            while (!Thread.currentThread().isInterrupted) {
                val outcome = send(sessionToken)
                if (outcome.ok) {
                    failures = 0
                } else if (++failures >= LoaderConfig.heartbeatFailureLimit) {
                    LoaderLog.error("Heartbeat trust lost. Deactivating modules.")
                    Auth.state = AuthState.FAILED
                    Auth.failureReason =
                        if (outcome.sessionInvalid) "session_invalid" else "Heartbeat trust lost"
                    Auth.statusMessage = "Phantom session revoked - heartbeat trust lost."
                    cascadeDeactivate()
                    Thread.currentThread().interrupt()
                }

                try {
                    Thread.sleep(Duration.ofSeconds(LoaderConfig.heartbeatIntervalSeconds).toMillis())
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }

    private data class SendOutcome(val ok: Boolean, val sessionInvalid: Boolean)

    private fun send(sessionToken: String): SendOutcome =
        runCatching {
            val request = HttpRequest.newBuilder()
                .uri(trustedUri("${LoaderConfig.serverBaseUrl.trimEnd('/')}/auth/heartbeat"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(HeartbeatRequest(sessionToken))))
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            SendOutcome(ok = response.statusCode() == 200, sessionInvalid = response.statusCode() == 401)
        }.onFailure {
            LoaderLog.error("Heartbeat failed: ${it.message}", it)
        }.getOrDefault(SendOutcome(ok = false, sessionInvalid = false))

    /**
     * Tears down every active LoadedModule, then runs the legacy unload for
     * non-LoadedModule addons. Idempotent — safe if either registry or
     * activation executor were never populated (e.g., bootstrap aborted early).
     */
    private fun cascadeDeactivate() {
        val registry: ModuleRegistry? = ActiveLoaderState.registry
        registry?.activeModules()?.forEach { module ->
            runCatching { module.onDeactivate() }
                .onFailure { LoaderLog.error("onDeactivate during cascade failed for ${module.name}", it) }
            registry.markInactive(module.name)
        }
        ActiveLoaderState.activationExecutor?.shutdown()
        // Legacy path: also unload non-LoadedModule addons. unloadLoadedAddons
        // already skips LoadedModule entries.
        runCatching { AddonLoader.unloadLoadedAddons() }
            .onFailure { LoaderLog.error("Legacy unloadLoadedAddons failed", it) }
    }
}
