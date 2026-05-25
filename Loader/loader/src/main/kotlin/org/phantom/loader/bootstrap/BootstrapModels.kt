package org.phantom.loader.bootstrap

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Determines whether a manifest module activates immediately after onLoad,
 * or waits for a successful POST /auth/verify-module per user toggle.
 */
@Serializable
enum class ActivationPolicy {
    @SerialName("auto")             AUTO,
    @SerialName("verify_on_toggle") VERIFY_ON_TOGGLE;

    companion object {
        fun fromWire(raw: String?): ActivationPolicy =
            when (raw?.trim()?.lowercase()) {
                "auto" -> AUTO
                "verify_on_toggle", null, "" -> VERIFY_ON_TOGGLE
                else -> VERIFY_ON_TOGGLE
            }
    }
}

@Serializable
data class VerifySessionRequest(
    @SerialName("session_token") val sessionToken: String,
    @SerialName("minecraft_username") val minecraftUsername: String,
    val hwid: String,
)

@Serializable
data class VerifySessionResponse(
    val authorized: Boolean = false,
    val reason: String = "",
    val alias: String = "",
    val username: String = "",
    @SerialName("account_id") val accountId: String = "",
    @SerialName("plan_tier") val planTier: String = "",
    @SerialName("enabled_modules") val enabledModules: List<String> = emptyList(),
    @SerialName("enabled_features") val enabledFeatures: List<String> = emptyList(),
    @SerialName("minecraft_bound") val minecraftBound: Boolean = false,
    @SerialName("minecraft_username") val minecraftUsername: String? = null,
    @SerialName("minecraft_uuid") val minecraftUuid: String? = null,
    @SerialName("manifest_url") val manifestUrl: String = "",
    @SerialName("manifest_signature") val manifestSignature: String = "",
)

@Serializable
data class HeartbeatRequest(
    @SerialName("session_token") val sessionToken: String,
    val activity: List<String> = emptyList(),
)

@Serializable
data class ContentManifest(
    val id: String = "",
    @SerialName("build_id") val buildId: String,
    val channel: String,
    @SerialName("minimum_loader_version") val minimumLoaderVersion: String = "0.1.0",
    @SerialName("module_key") val moduleKey: String? = null,
    val modules: List<ManifestModule> = emptyList(),
    @SerialName("native_components") val nativeComponents: List<ManifestNative> = emptyList(),
    val signature: String = "",
)

@Serializable
data class ManifestModule(
    val name: String,
    val url: String,
    val sha256: String,
    val required: Boolean = false,
    @SerialName("init_order") val initOrder: Int = 0,
    @SerialName("activation_policy") val activationPolicy: ActivationPolicy = ActivationPolicy.VERIFY_ON_TOGGLE,
)

@Serializable
data class ManifestNative(
    val name: String,
    val url: String,
    val sha256: String,
    val required: Boolean = false,
)

@Serializable
data class SignedManifestPayload(
    @SerialName("build_id") val buildId: String,
    val channel: String,
    @SerialName("minimum_loader_version") val minimumLoaderVersion: String,
    @SerialName("module_key") val moduleKey: String? = null,
    val modules: List<ManifestModule>,
    @SerialName("native_components") val nativeComponents: List<ManifestNative>,
)

@Serializable
data class VerifyModuleRequest(
    @SerialName("module_name") val moduleName: String,
    @SerialName("minecraft_username") val minecraftUsername: String,
)

@Serializable
data class VerifyModuleResponse(
    val authorized: Boolean = false,
    val reason: String = "",
)
