package org.cobalt.internal.visual

import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.ByteBufferBuilder
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.OptionalDouble
import java.util.OptionalInt
import java.util.function.Supplier
import javax.imageio.ImageIO
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.texture.DynamicTexture
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.TickEvent
import org.cobalt.api.module.Module
import org.cobalt.api.module.setting.impl.ActionSetting
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.module.setting.impl.InfoSetting
import org.cobalt.api.module.setting.impl.InfoType
import org.cobalt.api.module.setting.impl.ModeSetting
import org.cobalt.api.module.setting.impl.SliderSetting
import org.cobalt.api.module.setting.impl.TextSetting
import org.cobalt.api.module.setting.inGroup
import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4f
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object SkyboxChangerModule : Module("Skybox Changer") {

  private data class BuiltInPreset(
    val displayName: String,
    val resourceName: String
  )

  private val builtInPresets = listOf(
    BuiltInPreset("Nimbus Ember", "preset-6.png"),
    BuiltInPreset("Violet Expanse", "preset-7.png"),
    BuiltInPreset("Starfield Nebulae", "preset-8.png")
  )

  private val enabledSetting = CheckboxSetting(
    "Enabled",
    "Replace vanilla sky rendering with an imported skybox.",
    false
  )

  private val importInfo = InfoSetting(
    "Import Format",
    "A single 2:1 PNG/JPG becomes a seamless cubemap. You can also use a GIF, a folder of ordered frames, or a folder with front/back/left/right/up/down subfolders.",
    InfoType.INFO
  ).inGroup("Import")

  private val presetInfo = InfoSetting(
    "Preset Slots",
    "Nimbus Ember, Violet Expanse, and Starfield Nebulae are bundled with the mod. Use Custom if you want to import your own panorama, GIF, or cubemap from disk.",
    InfoType.INFO
  ).inGroup("Presets")

  private val presetSetting = ModeSetting(
    "Preset",
    "Choose one of the bundled sky presets or keep using a custom path.",
    0,
    arrayOf("Custom", *builtInPresets.map { it.displayName }.toTypedArray())
  ).inGroup("Presets")

  private val pathSetting = TextSetting(
    "Skybox Path",
    "Relative paths resolve from the Minecraft game folder.",
    "cobalt/skyboxes/custom"
  ).inGroup("Import")

  private val frameDurationSetting = SliderSetting(
    "Frame Duration",
    "Milliseconds per animation frame.",
    100.0,
    16.0,
    1000.0,
    step = 1.0
  ).inGroup("Import")

  private val animationInfo = InfoSetting(
    "Sky Motion",
    "Animate the sky by slowly rotating the imported panorama or cubemap around you.",
    InfoType.INFO
  ).inGroup("Animation")

  private val animateSkySetting = CheckboxSetting(
    "Animate Sky",
    "Continuously drift the skybox instead of leaving it static.",
    true
  ).inGroup("Animation")

  private val horizontalDriftSetting = SliderSetting(
    "Horizontal Drift",
    "Degrees per second of horizontal sky movement.",
    0.35,
    0.0,
    8.0,
    step = 0.01
  ).inGroup("Animation")

  private val verticalDriftRangeSetting = SliderSetting(
    "Vertical Drift Range",
    "Maximum up/down drift in degrees.",
    2.5,
    0.0,
    20.0,
    step = 0.1
  ).inGroup("Animation")

  private val verticalDriftCycleSetting = SliderSetting(
    "Vertical Drift Cycle",
    "Seconds for one full up/down motion.",
    24.0,
    2.0,
    120.0,
    step = 0.5
  ).inGroup("Animation")

  private var lastLoadStatus = "Idle"

  private val reloadSetting = ActionSetting(
    "Reload Skybox",
    "Reload skybox textures from disk.",
    "Reload",
    { if (lastLoadStatus.startsWith("Loaded")) "Reload" else "Retry" }
  ) {
    reloadQueued = true
  }.inGroup("Import")

  @Volatile private var reloadQueued = true
  @Volatile private var loadedSignature = ""
  @Volatile private var loadedSkybox: ImportedSkybox? = null

  init {
    addSetting(
      enabledSetting,
      presetInfo,
      presetSetting,
      importInfo,
      pathSetting,
      frameDurationSetting,
      animationInfo,
      animateSkySetting,
      horizontalDriftSetting,
      verticalDriftRangeSetting,
      verticalDriftCycleSetting,
      reloadSetting
    )
    EventBus.register(this)
  }

  @SubscribeEvent
  fun onTick(@Suppress("UNUSED_PARAMETER") event: TickEvent.End) {
    if (!enabledSetting.value) return

    migrateLegacyPresetSelection()
    val signature = currentConfigurationSignature()
    if (reloadQueued || signature != loadedSignature) {
      reloadQueued = false
      loadedSignature = signature
      loadedSkybox?.close()
      loadedSkybox = loadConfiguredSkybox()
    }
  }

  @JvmStatic
  fun renderCustomSky(fogBuffer: GpuBufferSlice): Boolean {
    if (!enabledSetting.value) return false

    val skybox = loadedSkybox ?: return false
    SkyboxRenderer.render(
      skybox,
      fogBuffer,
      frameDurationSetting.value.toLong(),
      animationState()
    )
    return true
  }

  private fun animationState(): SkyboxAnimationState {
    return SkyboxAnimationState(
      enabled = animateSkySetting.value,
      horizontalDegreesPerSecond = horizontalDriftSetting.value.toFloat(),
      verticalDegrees = verticalDriftRangeSetting.value.toFloat(),
      verticalCycleSeconds = verticalDriftCycleSetting.value.toFloat()
    )
  }

  private fun loadSkybox(path: Path): ImportedSkybox? {
    try {
      ensureImportPathExists(path)

      val textures = when {
        Files.isRegularFile(path) -> loadFileSkybox(path, "single")

        Files.isDirectory(path) -> loadDirectorySkybox(path)

        else -> return failLoad("Path not found")
      }

      if (textures == null || textures.any { it.value.isEmpty() }) {
        return failLoad("Missing skybox frames")
      }

      lastLoadStatus = "Loaded ${textures.values.maxOf { it.size }} frame(s)"
      return ImportedSkybox(textures)
    } catch (t: Throwable) {
      t.printStackTrace()
      return failLoad("Load failed")
    }
  }

  private fun loadConfiguredSkybox(): ImportedSkybox? {
    val preset = selectedBuiltInPreset()
    if (preset != null) {
      return loadBundledPresetSkybox(preset)
    }
    return loadSkybox(resolveConfiguredPath())
  }

  private fun currentConfigurationSignature(): String {
    val preset = selectedBuiltInPreset()
    if (preset != null) {
      return "preset:${preset.resourceName}"
    }
    return resolveConfiguredPath().toAbsolutePath().normalize().toString()
  }

  private fun selectedBuiltInPreset(): BuiltInPreset? {
    migrateLegacyPresetSelection()
    return builtInPresets.getOrNull(presetSetting.value - 1)
  }

  private fun migrateLegacyPresetSelection() {
    val migratedValue = when (presetSetting.value) {
      0 -> 0
      in 1..builtInPresets.size -> presetSetting.value
      6 -> 1
      7 -> 2
      in 1..5 -> 0
      else -> 0
    }
    if (presetSetting.value != migratedValue) {
      presetSetting.value = migratedValue
    }
  }

  private fun loadBundledPresetSkybox(preset: BuiltInPreset): ImportedSkybox? {
    try {
      val resourcePath = "/assets/cobalt/skyboxes/presets/${preset.resourceName}"
      val image = SkyboxChangerModule::class.java.getResourceAsStream(resourcePath)?.use { input ->
        ImageIO.read(input)
      } ?: return failLoad("Missing preset ${preset.displayName}")

      val textures = mapImageToSkybox(image, preset.resourceName.substringBeforeLast('.'))
      lastLoadStatus = "Loaded ${preset.displayName}"
      return ImportedSkybox(textures)
    } catch (t: Throwable) {
      t.printStackTrace()
      return failLoad("Preset load failed")
    }
  }

  private fun loadFileSkybox(path: Path, labelPrefix: String): Map<SkyboxFace, List<DynamicTexture>>? {
    val lower = path.fileName.toString().lowercase()
    return if (lower.endsWith(".gif")) {
      loadGifSkybox(path, labelPrefix)
    } else {
      loadStaticSkybox(path, labelPrefix)
    }
  }

  private fun loadStaticSkybox(path: Path, labelPrefix: String): Map<SkyboxFace, List<DynamicTexture>>? {
    val image = ImageIO.read(path.toFile()) ?: return null
    return mapImageToSkybox(image, labelPrefix)
  }

  private fun loadGifSkybox(path: Path, labelPrefix: String): Map<SkyboxFace, List<DynamicTexture>>? {
    val frames = readGifFrames(path)
    if (frames.isEmpty()) return null

    if (!isPanoramaFrame(frames.first())) {
      val textures = frames.mapIndexed { frameIndex, frame ->
        bufferedImageToTexture(frame, "$labelPrefix-$frameIndex")
      }
      return SkyboxFace.entries.associateWith { textures }
    }

    val faceFrames = mutableFaceFrameMap()
    frames.forEachIndexed { frameIndex, frame ->
      val cubemap = convertPanoramaToCubemap(frame, "$labelPrefix-$frameIndex")
      cubemap.forEach { (face, texture) ->
        faceFrames.getValue(face) += texture
      }
    }
    return freezeFaceFrameMap(faceFrames)
  }

  private fun loadDirectorySkybox(root: Path): Map<SkyboxFace, List<DynamicTexture>>? {
    val cubemapSources = SkyboxFace.entries.associateWith { findFaceSources(root, it) }
    val hasCubemapLayout = cubemapSources.any { it.value.isNotEmpty() }

    if (hasCubemapLayout) {
      if (cubemapSources.any { it.value.isEmpty() }) {
        return null
      }
      return cubemapSources.mapValues { (face, sources) ->
        loadTextureFrames(sources, face.name.lowercase())
      }
    }

    val frames = listImageFiles(root)
    if (frames.isEmpty()) return null
    return loadSharedSkyboxSources(frames, "shared")
  }

  private fun findFaceSources(root: Path, face: SkyboxFace): List<Path> {
    val rootImages = listImageFiles(root)

    face.aliases.forEach { alias -> 
      val aliasDir = root.resolve(alias)
      if (Files.isDirectory(aliasDir)) {
        val files = listImageFiles(aliasDir)
        if (files.isNotEmpty()) return files
      }
    }

    face.aliases.forEach { alias ->
      val file = rootImages.firstOrNull { imageStem(it).equals(alias, ignoreCase = true) }
      if (file != null) return listOf(file)
    }

    return emptyList()
  }

  private fun loadSharedSkyboxSources(
    sources: List<Path>,
    labelPrefix: String
  ): Map<SkyboxFace, List<DynamicTexture>>? {
    val faceFrames = mutableFaceFrameMap()

    sources.forEachIndexed { sourceIndex, source ->
      val loaded = loadFileSkybox(source, "$labelPrefix-$sourceIndex")
      if (loaded == null) {
        closeFaceFrames(faceFrames)
        return null
      }

      loaded.forEach { (face, textures) ->
        faceFrames.getValue(face) += textures
      }
    }

    return freezeFaceFrameMap(faceFrames)
  }

  private fun loadTextureFrames(source: Path, labelPrefix: String): List<DynamicTexture> {
    return loadTextureFrames(listOf(source), labelPrefix)
  }

  private fun loadTextureFrames(sources: List<Path>, labelPrefix: String): List<DynamicTexture> {
    val textures = mutableListOf<DynamicTexture>()
    sources.forEachIndexed { sourceIndex, source ->
      val lower = source.fileName.toString().lowercase()
      if (lower.endsWith(".gif")) {
        loadGifFrames(source, "$labelPrefix-$sourceIndex").forEach { textures += it }
      } else {
        textures += loadStaticTexture(source, "$labelPrefix-$sourceIndex")
      }
    }
    return textures
  }

  private fun readGifFrames(path: Path): List<BufferedImage> {
    val frames = mutableListOf<BufferedImage>()
    ImageIO.createImageInputStream(path.toFile()).use { input ->
      val readers = ImageIO.getImageReaders(input)
      if (!readers.hasNext()) return emptyList()

      val reader = readers.next()
      try {
        reader.input = input
        val frameCount = reader.getNumImages(true)
        for (frameIndex in 0 until frameCount) {
          frames += reader.read(frameIndex)
        }
      } finally {
        reader.dispose()
      }
    }
    return frames
  }

  private fun loadStaticTexture(path: Path, label: String): DynamicTexture {
    Files.newInputStream(path).use { input ->
      val image = NativeImage.read(input)
      return DynamicTexture(Supplier { "Skybox:$label" }, image)
    }
  }

  private fun loadGifFrames(path: Path, labelPrefix: String): List<DynamicTexture> {
    return readGifFrames(path).mapIndexed { frameIndex, frame ->
      bufferedImageToTexture(frame, "$labelPrefix-$frameIndex")
    }
  }

  private fun bufferedImageToTexture(image: BufferedImage, label: String): DynamicTexture {
    val out = ByteArrayOutputStream()
    ImageIO.write(image, "png", out)
    val native = NativeImage.read(out.toByteArray())
    return DynamicTexture(Supplier { "Skybox:$label" }, native)
  }

  private fun mapImageToSkybox(
    image: BufferedImage,
    labelPrefix: String
  ): Map<SkyboxFace, List<DynamicTexture>> {
    if (!isPanoramaFrame(image)) {
      val texture = bufferedImageToTexture(image, labelPrefix)
      return SkyboxFace.entries.associateWith { listOf(texture) }
    }

    val cubemap = convertPanoramaToCubemap(image, labelPrefix)
    return cubemap.mapValues { listOf(it.value) }
  }

  private fun isPanoramaFrame(image: BufferedImage): Boolean {
    if (image.width < 4 || image.height < 2) return false
    val ratio = image.width.toDouble() / image.height.toDouble()
    return ratio in 1.85..2.15
  }

  private fun convertPanoramaToCubemap(
    panorama: BufferedImage,
    labelPrefix: String
  ): Map<SkyboxFace, DynamicTexture> {
    val faceSize = (panorama.width / 4).coerceAtLeast(1)
      .coerceAtMost((panorama.height / 2).coerceAtLeast(1))

    return SkyboxFace.entries.associateWith { face ->
      val faceImage = BufferedImage(faceSize, faceSize, BufferedImage.TYPE_INT_ARGB)
      for (y in 0 until faceSize) {
        val v = (2.0 * (y + 0.5) / faceSize) - 1.0
        for (x in 0 until faceSize) {
          val u = (2.0 * (x + 0.5) / faceSize) - 1.0
          val direction = cubemapDirection(face, u, v)
          faceImage.setRGB(x, y, samplePanorama(panorama, direction))
        }
      }
      bufferedImageToTexture(faceImage, "$labelPrefix-${face.name.lowercase()}")
    }
  }

  private fun cubemapDirection(face: SkyboxFace, u: Double, v: Double): Triple<Double, Double, Double> {
    val direction = when (face) {
      SkyboxFace.FRONT -> Triple(u, v, 1.0)
      SkyboxFace.BACK -> Triple(u, -v, -1.0)
      SkyboxFace.LEFT -> Triple(-1.0, -u, v)
      SkyboxFace.RIGHT -> Triple(1.0, u, v)
      SkyboxFace.UP -> Triple(u, 1.0, -v)
      SkyboxFace.DOWN -> Triple(u, -1.0, v)
    }

    val length = sqrt(
      (direction.first * direction.first) +
        (direction.second * direction.second) +
        (direction.third * direction.third)
    )

    return Triple(
      direction.first / length,
      direction.second / length,
      direction.third / length
    )
  }

  private fun samplePanorama(
    panorama: BufferedImage,
    direction: Triple<Double, Double, Double>
  ): Int {
    val longitude = atan2(direction.first, direction.third)
    val latitude = asin(direction.second.coerceIn(-1.0, 1.0))

    val wrappedX = wrapUnit(longitude / (Math.PI * 2.0) + 0.5) * panorama.width
    val clampedY = (0.5 - (latitude / Math.PI))
      .coerceIn(0.0, 1.0) * (panorama.height - 1)

    val x0 = floor(wrappedX).toInt() % panorama.width
    val x1 = (x0 + 1) % panorama.width
    val y0 = floor(clampedY).toInt().coerceIn(0, panorama.height - 1)
    val y1 = (y0 + 1).coerceAtMost(panorama.height - 1)

    val tx = wrappedX - floor(wrappedX)
    val ty = clampedY - floor(clampedY)

    val c00 = panorama.getRGB(x0, y0)
    val c10 = panorama.getRGB(x1, y0)
    val c01 = panorama.getRGB(x0, y1)
    val c11 = panorama.getRGB(x1, y1)

    return interpolateArgb(c00, c10, c01, c11, tx, ty)
  }

  private fun wrapUnit(value: Double): Double {
    val wrapped = value % 1.0
    return if (wrapped < 0.0) wrapped + 1.0 else wrapped
  }

  private fun interpolateArgb(
    topLeft: Int,
    topRight: Int,
    bottomLeft: Int,
    bottomRight: Int,
    tx: Double,
    ty: Double
  ): Int {
    val alpha = bilerp(
      (topLeft ushr 24) and 0xFF,
      (topRight ushr 24) and 0xFF,
      (bottomLeft ushr 24) and 0xFF,
      (bottomRight ushr 24) and 0xFF,
      tx,
      ty
    )
    val red = bilerp(
      (topLeft ushr 16) and 0xFF,
      (topRight ushr 16) and 0xFF,
      (bottomLeft ushr 16) and 0xFF,
      (bottomRight ushr 16) and 0xFF,
      tx,
      ty
    )
    val green = bilerp(
      (topLeft ushr 8) and 0xFF,
      (topRight ushr 8) and 0xFF,
      (bottomLeft ushr 8) and 0xFF,
      (bottomRight ushr 8) and 0xFF,
      tx,
      ty
    )
    val blue = bilerp(
      topLeft and 0xFF,
      topRight and 0xFF,
      bottomLeft and 0xFF,
      bottomRight and 0xFF,
      tx,
      ty
    )

    return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
  }

  private fun bilerp(
    topLeft: Int,
    topRight: Int,
    bottomLeft: Int,
    bottomRight: Int,
    tx: Double,
    ty: Double
  ): Int {
    val top = topLeft + ((topRight - topLeft) * tx)
    val bottom = bottomLeft + ((bottomRight - bottomLeft) * tx)
    return (top + ((bottom - top) * ty)).roundToInt().coerceIn(0, 255)
  }

  private fun mutableFaceFrameMap(): MutableMap<SkyboxFace, MutableList<DynamicTexture>> {
    return SkyboxFace.entries.associateWith { mutableListOf<DynamicTexture>() }.toMutableMap()
  }

  private fun freezeFaceFrameMap(
    faceFrames: Map<SkyboxFace, MutableList<DynamicTexture>>
  ): Map<SkyboxFace, List<DynamicTexture>> {
    return faceFrames.mapValues { it.value.toList() }
  }

  private fun closeFaceFrames(faceFrames: Map<SkyboxFace, List<DynamicTexture>>) {
    faceFrames.values.flatten().toSet().forEach { it.close() }
  }

  private fun listImageFiles(dir: Path): List<Path> {
    if (!Files.isDirectory(dir)) return emptyList()

    return Files.newDirectoryStream(dir).use { stream ->
      stream
        .filter { Files.isRegularFile(it) && isSupportedImage(it) }
        .sortedBy { it.fileName.toString().lowercase() }
        .toList()
    }
  }

  private fun isSupportedImage(path: Path): Boolean {
    val lower = path.fileName.toString().lowercase()
    return lower.endsWith(".png") ||
      lower.endsWith(".jpg") ||
      lower.endsWith(".jpeg") ||
      lower.endsWith(".gif")
  }

  private fun imageStem(path: Path): String {
    val name = path.fileName.toString()
    val dot = name.lastIndexOf('.')
    return if (dot <= 0) name else name.substring(0, dot)
  }

  private fun resolveConfiguredPath(): Path {
    val gameDir = Minecraft.getInstance().gameDirectory.toPath()
    val raw = pathSetting.value.trim().ifEmpty { "cobalt/skyboxes/custom" }
    val configured = Path.of(raw)
    if (configured.isAbsolute) return configured.normalize()

    return gameDir.resolve(configured).normalize()
  }

  private fun ensureImportPathExists(path: Path) {
    if (Files.exists(path)) return

    val lower = path.fileName?.toString()?.lowercase() ?: ""
    if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".gif")) {
      path.parent?.let { Files.createDirectories(it) }
    } else {
      Files.createDirectories(path)
    }
  }

  private fun failLoad(reason: String): ImportedSkybox? {
    lastLoadStatus = reason
    return null
  }

  private data class ImportedSkybox(
    private val faceFrames: Map<SkyboxFace, List<DynamicTexture>>
  ) {
    fun frame(face: SkyboxFace, frameDurationMs: Long, nowMs: Long): DynamicTexture? {
      val frames = faceFrames[face].orEmpty()
      if (frames.isEmpty()) return null

      val duration = frameDurationMs.coerceAtLeast(16L)
      val index = ((nowMs / duration) % frames.size).toInt()
      return frames[index]
    }

    fun close() {
      faceFrames.values.flatten().toSet().forEach { it.close() }
    }
  }

  private data class SkyboxAnimationState(
    val enabled: Boolean,
    val horizontalDegreesPerSecond: Float,
    val verticalDegrees: Float,
    val verticalCycleSeconds: Float
  )

  private enum class SkyboxFace(val aliases: Set<String>) {
    FRONT(setOf("front", "north")),
    BACK(setOf("back", "south")),
    LEFT(setOf("left", "west")),
    RIGHT(setOf("right", "east")),
    UP(setOf("up", "top")),
    DOWN(setOf("down", "bottom")),
  }

  private object SkyboxRenderer {

    private const val SKY_RADIUS = 100.0f
    private const val STATIC_VERTEX_BUFFER_USAGE = 40
    private const val DEG_TO_RAD = (Math.PI / 180.0).toFloat()
    private const val TWO_PI = Math.PI * 2.0
    private val animationStartNs = System.nanoTime()
    private val faceBuffers = LinkedHashMap<SkyboxFace, GpuBuffer>()

    fun render(
      skybox: ImportedSkybox,
      fogBuffer: GpuBufferSlice,
      frameDurationMs: Long,
      animation: SkyboxAnimationState
    ) {
      ensureFaceBuffers()

      val mc = Minecraft.getInstance()
      val target = mc.mainRenderTarget
      val colorView = target.colorTextureView ?: return
      val depthView = target.depthTextureView ?: return
      val indexBufferSource = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS)
      val quadIndexBuffer = indexBufferSource.getBuffer(6)
      val now = System.currentTimeMillis()
      val modelView = animatedModelView(now, animation)
      val transforms = RenderSystem.getDynamicUniforms().writeTransform(
        modelView,
        Vector4f(1.0f, 1.0f, 1.0f, 1.0f),
        Vector3f(),
        Matrix4f()
      )

      RenderSystem.setShaderFog(fogBuffer)

      RenderSystem.getDevice().createCommandEncoder().createRenderPass(
        Supplier { "cobalt_skybox" },
        colorView,
        OptionalInt.empty(),
        depthView,
        OptionalDouble.empty()
      ).use { pass ->
        pass.setPipeline(RenderPipelines.END_SKY)
        RenderSystem.bindDefaultUniforms(pass)
        pass.setUniform("DynamicTransforms", transforms)
        pass.setIndexBuffer(quadIndexBuffer, indexBufferSource.type())

        SkyboxFace.entries.forEach { face ->
          val texture = skybox.frame(face, frameDurationMs, now) ?: return@forEach
          val vertexBuffer = faceBuffers[face] ?: return@forEach
          pass.bindTexture("Sampler0", texture.textureView, texture.sampler)
          pass.setVertexBuffer(0, vertexBuffer)
          pass.drawIndexed(0, 0, 6, 1)
        }
      }
    }

    private fun animatedModelView(nowMs: Long, animation: SkyboxAnimationState): Matrix4f {
      val matrix = Matrix4f(RenderSystem.getModelViewMatrix())
      if (!animation.enabled) return matrix

      val elapsedSeconds = (System.nanoTime() - animationStartNs) / 1_000_000_000.0
      val horizontalDegrees = (elapsedSeconds * animation.horizontalDegreesPerSecond.toDouble()) % 360.0
      val horizontalRadians = (horizontalDegrees * (Math.PI / 180.0)).toFloat()
      matrix.rotateY(horizontalRadians)

      if (animation.verticalDegrees > 0.0f) {
        val cycleSeconds = animation.verticalCycleSeconds.coerceAtLeast(0.1f).toDouble()
        val verticalRadians = sin((elapsedSeconds / cycleSeconds) * TWO_PI).toFloat() *
          animation.verticalDegrees * DEG_TO_RAD
        matrix.rotateX(verticalRadians)
      }

      return matrix
    }

    private fun ensureFaceBuffers() {
      if (faceBuffers.isNotEmpty()) return

      SkyboxFace.entries.forEach { face ->
        faceBuffers[face] = buildFaceBuffer(face)
      }
    }

    private fun buildFaceBuffer(face: SkyboxFace): GpuBuffer {
      val bytes = ByteBufferBuilder.exactlySized(4 * DefaultVertexFormat.POSITION_TEX_COLOR.vertexSize)
      val builder = BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR)
      val rotation = faceRotation(face)

      builder.addVertex(rotation, -SKY_RADIUS, -SKY_RADIUS, -SKY_RADIUS).setUv(0.0f, 0.0f).setColor(-1)
      builder.addVertex(rotation, -SKY_RADIUS, -SKY_RADIUS, SKY_RADIUS).setUv(0.0f, 1.0f).setColor(-1)
      builder.addVertex(rotation, SKY_RADIUS, -SKY_RADIUS, SKY_RADIUS).setUv(1.0f, 1.0f).setColor(-1)
      builder.addVertex(rotation, SKY_RADIUS, -SKY_RADIUS, -SKY_RADIUS).setUv(1.0f, 0.0f).setColor(-1)

      val mesh = builder.buildOrThrow()
      return try {
        RenderSystem.getDevice().createBuffer(
          Supplier { "cobalt_skybox_${face.name.lowercase()}" },
          STATIC_VERTEX_BUFFER_USAGE,
          mesh.vertexBuffer()
        )
      } finally {
        mesh.close()
        bytes.close()
      }
    }

    private fun faceRotation(face: SkyboxFace): Matrix4f {
      val matrix = Matrix4f()
      when (face) {
        SkyboxFace.DOWN -> Unit
        SkyboxFace.BACK -> matrix.rotationX((Math.PI / 2.0).toFloat())
        SkyboxFace.FRONT -> matrix.rotationX((-Math.PI / 2.0).toFloat())
        SkyboxFace.UP -> matrix.rotationX(Math.PI.toFloat())
        SkyboxFace.RIGHT -> matrix.rotationZ((Math.PI / 2.0).toFloat())
        SkyboxFace.LEFT -> matrix.rotationZ((-Math.PI / 2.0).toFloat())
      }
      return matrix
    }
  }
}
