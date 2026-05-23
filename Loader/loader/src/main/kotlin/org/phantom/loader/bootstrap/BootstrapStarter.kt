package org.phantom.loader.bootstrap

import net.minecraft.client.Minecraft
import org.phantom.PhantomPublicInit
import org.phantom.api.module.AuthSnapshot
import org.phantom.api.module.ModuleRegistry
import org.phantom.internal.auth.Auth
import org.phantom.internal.auth.AuthState
import org.phantom.internal.loader.AddonLoader
import org.phantom.loader.LoaderLog
import org.phantom.loader.PhantomSession
import org.phantom.loader.activation.ActivationExecutor
import org.phantom.loader.activation.ActiveLoaderState
import org.phantom.loader.activation.DefaultLoaderApi

object BootstrapStarter {
    fun start() {
        val session = PhantomSession.read()
        if (!session.isValid) {
            return fail("Missing Phantom session token.")
        }

        Auth.state = AuthState.VERIFYING
        Auth.statusMessage = "Verifying Phantom session..."

        try {
            val minecraftUsername = Minecraft.getInstance().user.name.trim()
            val auth = BootstrapAuthClient.verifySession(session.token, minecraftUsername)
            if (!auth.authorized) {
                return fail("Auth failed: ${auth.reason.ifBlank { "not authorized" }}")
            }

            Auth.alias = auth.username.ifBlank { auth.alias }
            Auth.accountId = auth.accountId
            Auth.minecraftBound = auth.minecraftBound
            Auth.minecraftUsername = auth.minecraftUsername?.ifBlank { minecraftUsername } ?: minecraftUsername
            Auth.minecraftUuid = auth.minecraftUuid.orEmpty()
            Auth.entitledModules = auth.enabledModules.toSet()

            require(auth.manifestUrl.isNotBlank()) { "Auth response did not include a manifest URL." }

            Auth.state = AuthState.LOADING
            Auth.statusMessage = "Loading protected Phantom modules..."

            val manifest = BootstrapManifestVerifier.fetchAndVerify(
                manifestUrl = auth.manifestUrl,
                sessionToken = session.token,
                expectedSignature = auth.manifestSignature,
            )

            manifest.nativeComponents
                .filter { it.required }
                .forEach { BootstrapContentClient.downloadAndInstallNative(it, session.token) }

            PhantomPublicInit.init()

            val modules = manifest.modules
                .sortedWith(compareBy<ManifestModule> { it.initOrder }.thenBy { it.name })
                .filter { module ->
                    module.required ||
                        "*" in auth.enabledModules ||
                        module.name in auth.enabledModules ||
                        module.name.removePrefix("phantom-") in auth.enabledModules
                }

            Auth.modulesTotal = modules.size

            val registry = ModuleRegistry()
            val activationExecutor = ActivationExecutor()
            val authSnapshot = AuthSnapshot(
                accountId = auth.accountId,
                username = auth.username.ifBlank { auth.alias },
                minecraftUsername = Auth.minecraftUsername,
                planTier = auth.planTier,
                entitledModules = auth.enabledModules.toSet(),
            )
            val loaderApi = DefaultLoaderApi(
                sessionToken = session.token,
                auth = authSnapshot,
                registry = registry,
                executor = activationExecutor,
                onSessionInvalid = { triggerSessionInvalidCascade(registry, activationExecutor) },
            )

            modules.forEachIndexed { index, manifestModule ->
                runCatching { loadModule(manifestModule, manifest, session.token) }
                    .onSuccess {
                        val loaded = AddonLoader.findLoaded(manifestModule.name)
                        if (loaded != null) {
                            registry.register(loaded)
                            runCatching { loaded.onLoad(loaderApi) }
                                .onFailure {
                                    registry.markLoadFailed(manifestModule.name, it.message ?: "onLoad threw")
                                    LoaderLog.error("onLoad failed for ${manifestModule.name}", it)
                                }
                        }
                    }
                    .onFailure {
                        if (manifestModule.name == "phantom-core") throw it
                        LoaderLog.error("Failed to load ${manifestModule.name}", it)
                    }
                Auth.modulesLoaded = index + 1
            }

            // Auto-activate only modules whose manifest entry says so.
            modules
                .filter { it.activationPolicy == ActivationPolicy.AUTO }
                .forEach { manifestModule ->
                    val loaded = registry.moduleOf(manifestModule.name) ?: return@forEach
                    runCatching { loaded.onActivate() }
                        .onSuccess { registry.markActive(manifestModule.name) }
                        .onFailure {
                            registry.markActivationFailed(manifestModule.name, it.message ?: "onActivate threw")
                            LoaderLog.error("Auto onActivate failed for ${manifestModule.name}", it)
                            if (manifestModule.name == "phantom-core") throw it
                        }
                }

            // Legacy path: activate any Addon entrypoints that DON'T implement
            // LoadedModule. AddonLoader.activateLoadedAddons now skips
            // LoadedModule-implementing addons (those went through the new
            // lifecycle above).
            AddonLoader.activateLoadedAddons()

            ActiveLoaderState.registry = registry
            ActiveLoaderState.activationExecutor = activationExecutor
            ActiveLoaderState.loaderApi = loaderApi

            BootstrapHeartbeatClient.start(session.token)

            Auth.state = AuthState.READY
            Auth.statusMessage = "Phantom ready."
            LoaderLog.info("Bootstrap complete. Loaded ${modules.size} protected module bundle(s).")
        } catch (t: Throwable) {
            fail("Bootstrap failed: ${t.message}", t)
        }
    }

    private fun loadModule(module: ManifestModule, manifest: ContentManifest, sessionToken: String) {
        val encrypted = BootstrapContentClient.downloadModule(module, sessionToken)
        var decrypted: ByteArray? = null
        try {
            decrypted = ModuleCrypto.decryptAesGcm(manifest.moduleKey, encrypted)
            val actualHash = sha256Hex(decrypted)
            require(actualHash.equals(module.sha256, ignoreCase = true)) {
                "Module ${module.name} SHA-256 mismatch."
            }
            AddonLoader.loadFromBytes("${module.name}.jar", decrypted, activate = false)
        } finally {
            encrypted.fill(0)
            decrypted?.fill(0)
        }
    }

    private fun fail(message: String, throwable: Throwable? = null) {
        Auth.state = AuthState.FAILED
        Auth.failureReason = message
        Auth.statusMessage = message
        LoaderLog.error(message, throwable)
    }

    private fun triggerSessionInvalidCascade(
        registry: ModuleRegistry,
        executor: ActivationExecutor,
    ) {
        Auth.state = AuthState.FAILED
        Auth.failureReason = "Session invalidated by server"
        Auth.statusMessage = "Phantom session invalidated. Restart Minecraft."
        registry.activeModules().forEach { module ->
            runCatching { module.onDeactivate() }
                .onFailure { LoaderLog.error("onDeactivate during cascade failed for ${module.name}", it) }
            registry.markInactive(module.name)
        }
        executor.shutdown()
    }
}
