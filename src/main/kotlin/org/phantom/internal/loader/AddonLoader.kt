package org.phantom.internal.loader

import com.google.gson.Gson
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipFile
import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.loader.impl.launch.FabricLauncherBase
import org.phantom.api.addon.Addon
import org.phantom.api.addon.AddonMetadata
import org.phantom.api.module.LoadedModule
import org.phantom.api.module.ModuleManager
import org.phantom.api.util.ui.NVGRenderer
import org.phantom.api.util.ui.helper.Image
import org.spongepowered.asm.mixin.Mixins

object AddonLoader {

  private const val LOCAL_ADDONS_PROPERTY = "phantom.localAddons"
  private val addonsDir: Path = Paths.get("config/phantom/addons/")
  private val addons = mutableListOf<Pair<AddonMetadata, Addon>>()
  private val addonsById = mutableMapOf<String, AddonMetadata>()
  private val activatedAddons = mutableSetOf<Int>()
  private val gson = Gson()
  private val remoteClassLoader by lazy {
    InMemoryAddonClassLoader(
      sourceName = "remote-phantom-modules",
      parent = javaClass.classLoader
    )
  }

  fun findAddons() {
    if (FabricLauncherBase.getLauncher().isDevelopment) {
      for (entry in FabricLoader.getInstance().getEntrypointContainers("phantom", Addon::class.java)) {
        val modMeta = entry.provider.metadata
        val metadata = AddonMetadata(
          id = modMeta.id,
          name = modMeta.name,
          version = modMeta.version?.toString() ?: "unknown",
          entrypoints = listOf(entry.entrypoint.javaClass.name),
          mixins = listOf()
        )

        val addonInstance: Addon = try {
          entry.entrypoint
        } catch (e: Throwable) {
          throw RuntimeException("Failed to initialize addon \"${modMeta.name}\"", e)
        }

        addons += metadata to addonInstance
        addonsById[metadata.id] = metadata
      }
    }

    if (!allowLocalAddons()) {
      return
    }

    if (!Files.isDirectory(addonsDir)) {
      Files.createDirectories(addonsDir)
      return
    }

    try {
      Files.newDirectoryStream(addonsDir, "*.jar").use { stream ->
        for (jarPath in stream) {
          try {
            loadFromPath(jarPath, activate = false)
          } catch (e: Exception) {
            e.printStackTrace()
          }
        }
      }
    } catch (e: Exception) {
      e.printStackTrace()
    }
  }

  private fun loadAddon(jarPath: Path): List<Pair<AddonMetadata, Addon>> {
    ZipFile(jarPath.toFile()).use { zip ->
      val jsonEntry = zip.getEntry("phantom.addon.json")
        ?: throw IllegalStateException("Missing phantom.addon.json in $jarPath")

      val metadata = zip.getInputStream(jsonEntry).use { input ->
        gson.fromJson(input.reader(), AddonMetadata::class.java)
      }

      require(metadata.entrypoints.isNotEmpty()) {
        "Addon ${metadata.id} has no entrypoints defined"
      }

      synchronized(Mixins::class.java) {
        for (mixin in metadata.mixins) {
          Mixins.addConfiguration(mixin)
        }
      }

      val loaded = mutableListOf<Pair<AddonMetadata, Addon>>()

      for (entrypoint in metadata.entrypoints) {
        val classPath = entrypoint.replace('.', '/') + ".class"
        checkNotNull(zip.getEntry(classPath)) { "Entrypoint class '$entrypoint' does not exist inside ${jarPath.fileName}" }

        val instance = Class.forName(entrypoint).let {
          try {
            it.getField("INSTANCE").get(null)
          } catch (_: NoSuchFieldException) {
            val constructor = it.getDeclaredConstructor()
            constructor.isAccessible = true
            constructor.newInstance()
          }
        }

        check(instance is Addon) { "Entrypoint '$entrypoint' must implement Addon" }
        loaded += metadata to instance
      }

      registerLoadedAddons(metadata, loaded)
      return loaded
    }
  }

  private fun loadAddon(sourceName: String, jarBytes: ByteArray): List<Pair<AddonMetadata, Addon>> {
    val classLoader = remoteClassLoader
    classLoader.addJar(jarBytes)

    val metadataJson = classLoader.getEntryBytes("phantom.addon.json")
      ?: throw IllegalStateException("Missing phantom.addon.json in $sourceName")

    val metadata = gson.fromJson(metadataJson.inputStream().reader(), AddonMetadata::class.java)

    require(metadata.entrypoints.isNotEmpty()) {
      "Addon ${metadata.id} has no entrypoints defined"
    }

    require(metadata.mixins.isEmpty()) {
      "Remote in-memory addon ${metadata.id} declares mixins. Remote mixin configs must be packaged locally at pre-launch."
    }

    val loaded = mutableListOf<Pair<AddonMetadata, Addon>>()

    for (entrypoint in metadata.entrypoints) {
      val classPath = entrypoint.replace('.', '/') + ".class"
      checkNotNull(classLoader.getEntryBytes(classPath)) {
        "Entrypoint class '$entrypoint' does not exist inside $sourceName"
      }

      val instance = Class.forName(entrypoint, true, classLoader).let {
        try {
          it.getField("INSTANCE").get(null)
        } catch (_: NoSuchFieldException) {
          val constructor = it.getDeclaredConstructor()
          constructor.isAccessible = true
          constructor.newInstance()
        }
      }

      check(instance is Addon) { "Entrypoint '$entrypoint' must implement Addon" }
      loaded += metadata to instance
    }

    registerLoadedAddons(metadata, loaded)
    return loaded
  }

  private fun registerLoadedAddons(metadata: AddonMetadata, loaded: List<Pair<AddonMetadata, Addon>>) {
    addons += loaded
    addonsById[metadata.id] = metadata
  }

  fun loadFromPath(jarPath: java.nio.file.Path, activate: Boolean = true): List<Pair<AddonMetadata, Addon>> {
    return try {
      FabricLauncherBase.getLauncher().addToClassPath(jarPath)
      loadAddon(jarPath).also {
        if (activate) activateLoadedAddons()
      }
    } catch (e: Exception) {
      e.printStackTrace()
      emptyList()
    }
  }

  fun loadFromBytes(sourceName: String, jarBytes: ByteArray, activate: Boolean = true): List<Pair<AddonMetadata, Addon>> {
    return try {
      loadAddon(sourceName, jarBytes).also {
        if (activate) activateLoadedAddons()
      }
    } catch (e: Exception) {
      e.printStackTrace()
      emptyList()
    }
  }

  /**
   * Activates every loaded addon EXCEPT ones whose entrypoint implements
   * the new LoadedModule lifecycle — those go through the loader's
   * activation executor instead (see DefaultLoaderApi.requestActivate).
   */
  fun activateLoadedAddons() {
    addons.forEach { (_, addon) ->
      if (addon is LoadedModule) return@forEach
      val identity = System.identityHashCode(addon)
      if (activatedAddons.add(identity)) {
        addon.onLoad()
        ModuleManager.addModules(addon.getModules())
      }
    }
  }

  /**
   * Returns the first loaded addon entrypoint matching the given manifest
   * module name AND implementing the new LoadedModule lifecycle. Null if
   * no such entrypoint is loaded (legacy addons go through the old path).
   */
  fun findLoaded(name: String): LoadedModule? {
    return addons
      .firstOrNull { (meta, addon) -> meta.id == name && addon is LoadedModule }
      ?.second as? LoadedModule
  }

  fun unloadLoadedAddons() {
    addons.asReversed().forEach { (_, addon) ->
      runCatching {
        if (addon !is LoadedModule) {
          ModuleManager.removeModules(addon.getModules())
          addon.onUnload()
        }
      }.onFailure { it.printStackTrace() }
      activatedAddons.remove(System.identityHashCode(addon))
    }
    addons.clear()
    addonsById.clear()
  }

  fun getAddons(): List<Pair<AddonMetadata, Addon>> {
    return addons.toList()
  }

  fun getAddonIcon(addonId: String): Image? {
    return NVGRenderer.createImage(
      addonsById[addonId]?.icon ?: return null
    )
  }

  private fun allowLocalAddons(): Boolean {
    if (FabricLoader.getInstance().isDevelopmentEnvironment) return true

    return (System.getProperty(LOCAL_ADDONS_PROPERTY)
      ?.trim()
      ?.lowercase()
      in setOf("1", "true", "yes", "on")
      )
  }

}

