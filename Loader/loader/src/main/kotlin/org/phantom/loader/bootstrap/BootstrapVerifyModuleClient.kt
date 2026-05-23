package org.phantom.loader.bootstrap

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.phantom.loader.LoaderConfig
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Outcomes the loader's activation flow distinguishes:
 *   - Authorized: proceed with onActivate
 *   - Denied:    server explicitly said no — leave INACTIVE, surface reason
 *   - SessionInvalid: trigger the heartbeat-failure cascade (deactivate all)
 *   - Error:     network/timeout/non-200 — treat strictest (no activation)
 */
sealed class VerifyModuleOutcome {
    object Authorized : VerifyModuleOutcome()
    data class Denied(val reason: String) : VerifyModuleOutcome()
    object SessionInvalid : VerifyModuleOutcome()
    data class Error(val cause: Throwable) : VerifyModuleOutcome()
}

object BootstrapVerifyModuleClient {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    fun verify(sessionToken: String, moduleName: String, minecraftUsername: String): VerifyModuleOutcome {
        val body = json.encodeToString(VerifyModuleRequest(moduleName, minecraftUsername))
        val request = HttpRequest.newBuilder()
            .uri(trustedUri("${LoaderConfig.serverBaseUrl.trimEnd('/')}/auth/verify-module"))
            .timeout(Duration.ofSeconds(8))
            .header("Authorization", "Bearer $sessionToken")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        return runCatching {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            when (response.statusCode()) {
                401 -> VerifyModuleOutcome.SessionInvalid
                200, 403 -> {
                    val parsed = json.decodeFromString<VerifyModuleResponse>(response.body())
                    if (parsed.authorized) VerifyModuleOutcome.Authorized
                    else VerifyModuleOutcome.Denied(parsed.reason.ifBlank { "not_authorized" })
                }
                else -> VerifyModuleOutcome.Error(IllegalStateException("HTTP ${response.statusCode()}"))
            }
        }.getOrElse { VerifyModuleOutcome.Error(it) }
    }
}
