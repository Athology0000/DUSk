package org.phantom.loader.bootstrap

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.phantom.loader.LoaderConfig
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

object BootstrapAuthClient {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    fun verifySession(
        sessionToken: String,
        minecraftUsername: String,
        hwid: String,
    ): VerifySessionResponse {
        val uri = trustedUri("${LoaderConfig.serverBaseUrl.trimEnd('/')}/auth/verify-session")
        val request = HttpRequest.newBuilder()
            .uri(uri)
            .timeout(Duration.ofSeconds(12))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(VerifySessionRequest(sessionToken, minecraftUsername, hwid))))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            return VerifySessionResponse(
                authorized = false,
                reason = "verify-session returned HTTP ${response.statusCode()}",
            )
        }

        return json.decodeFromString(response.body())
    }
}
