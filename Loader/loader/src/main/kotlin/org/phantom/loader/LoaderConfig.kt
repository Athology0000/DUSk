package org.phantom.loader

import net.fabricmc.loader.api.FabricLoader

object LoaderConfig {
    private const val RELEASE_SERVER_BASE_URL = "https://valiant-cooperation-production.up.railway.app"
    // Pinned Ed25519 public key — the public half of the server's
    // MANIFEST_SIGNING_KEY. BootstrapManifestVerifier checks every manifest
    // signature against this, so it must match the key the server signs with.
    private const val RELEASE_MANIFEST_PUBLIC_KEY_B64 = "LLeG53gx/aRVy/jhuuKFHJqATd9+cN0Jm04RVxq3o34="

    private val isDev: Boolean
        get() = FabricLoader.getInstance().isDevelopmentEnvironment

    val serverBaseUrl: String
        get() = devOverride("phantom.server.url", "PHANTOM_SERVER_URL") ?: RELEASE_SERVER_BASE_URL

    val manifestPublicKeyB64: String
        get() = devOverride("phantom.manifest.publicKey", "PHANTOM_MANIFEST_PUBLIC_KEY")
            ?: RELEASE_MANIFEST_PUBLIC_KEY_B64

    val moduleKeyB64: String?
        get() = devOverride("phantom.module.key", "PHANTOM_MODULE_KEY")

    val allowLocalhostHttp: Boolean
        get() = isDev

    val heartbeatIntervalSeconds: Long
        get() = devOverride("phantom.heartbeat.intervalSeconds", "PHANTOM_HEARTBEAT_INTERVAL_SECONDS")
            ?.toLongOrNull()
            ?.coerceAtLeast(5L)
            ?: 30L

    val heartbeatFailureLimit: Int
        get() = devOverride("phantom.heartbeat.failureLimit", "PHANTOM_HEARTBEAT_FAILURE_LIMIT")
            ?.toIntOrNull()
            ?.coerceAtLeast(1)
            ?: 2

    val clientVersion: String
        get() = devOverride("phantom.client.version", "PHANTOM_CLIENT_VERSION") ?: "0.1.0"

    private fun devOverride(property: String, env: String): String? {
        if (!isDev) return null
        return System.getProperty(property)?.takeIf { it.isNotBlank() }
            ?: System.getenv(env)?.takeIf { it.isNotBlank() }
    }
}
