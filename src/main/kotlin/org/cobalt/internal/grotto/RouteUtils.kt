package org.cobalt.internal.grotto

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3

object RouteUtils {

  @JvmStatic
  fun generateRouteForClient(
    routeName: String,
    offsets: Array<RouteOffsets.Offset>,
    preset: String,
    mode: String?,
  ) {
    val client = Minecraft.getInstance()
    val player = client.player ?: return
    val base = player.blockPosition()
    generateRouteForClientAt(routeName, offsets, preset, mode, base)
  }

  @JvmStatic
  fun generateRouteForClientAt(
    routeName: String,
    offsets: Array<RouteOffsets.Offset>,
    preset: String,
    mode: String?,
    base: BlockPos,
  ) {
    val client = Minecraft.getInstance()
    val level = client.level ?: return

    val points = ArrayList<Vec3>(offsets.size)
    for (o in offsets) {
      val p = base.offset(o.x, o.y, o.z)
      points.add(Vec3(p.x + 0.5, p.y + 0.5, p.z + 0.5))
    }

    if (mode != null && mode.equals("json", true)) {
      exportJson(routeName, offsets, preset, base)
    }

    GrottoRouteRenderer.setRoute(level, points)
    GrottoChat.autoRoutes(
      "Loaded route: $routeName preset=$preset" +
        (mode?.let { " mode=$it" } ?: "") +
        " base=${base.x},${base.y},${base.z}"
    )
  }

  private fun exportJson(
    routeName: String,
    offsets: Array<RouteOffsets.Offset>,
    preset: String,
    base: BlockPos,
  ) {
    val clientId = ClientId.from(preset)
    if (clientId == null) {
      GrottoChat.autoRoutes("Unknown client \"$preset\". Valid: polar, nebula, polinex, melody.")
      return
    }

    val payload = when (clientId) {
      ClientId.POLAR -> formatPolarRoute(routeName, offsets, base.x, base.y, base.z)
      ClientId.NEBULA -> formatNebulaRoute(offsets, base.x, base.y, base.z)
      ClientId.POLINEX -> formatPolinexRoute(offsets, base.x, base.y, base.z)
      ClientId.MELODY -> formatMelodyRoute(offsets, base.x, base.y, base.z)
    }

    val outFile = getOutFileForClient(clientId, routeName, false)
    try {
      ensureParent(outFile)
      writeUtf8(outFile, payload)
      GrottoChat.autoRoutes("Wrote route to ${outFile.absolutePath}")
    } catch (ex: IOException) {
      GrottoChat.autoRoutes("Failed to write route: ${ex.message}")
    }
  }

  private enum class ClientId(val id: String) {
    POLAR("polar"),
    NEBULA("nebula"),
    POLINEX("polinex"),
    MELODY("melody");

    companion object {
      fun from(value: String?): ClientId? {
        if (value == null) return null
        val v = value.trim().lowercase()
        return values().firstOrNull { it.id == v }
      }
    }
  }

  private fun getOutFileForClient(clientId: ClientId, name: String, dm: Boolean): File {
    val client = Minecraft.getInstance()
    val mcDir = client.gameDirectory
    val globalDir = getGlobalMinecraftDir()
    val lower = name.lowercase()
    return when (clientId) {
      ClientId.POLAR -> File(if (dm) mcDir else globalDir, "PolarClient/routes/gemstone/$lower.json")
      ClientId.NEBULA -> File(mcDir, "Nebula/GemstoneMiner/routes/$lower.json")
      ClientId.POLINEX -> File(if (dm) mcDir else globalDir, "polinex/gemstone/$lower.json")
      ClientId.MELODY -> File(mcDir, "Melody/ARWayPoints/$name.txt")
    }
  }

  private fun getGlobalMinecraftDir(): File {
    val appData = System.getenv("APPDATA")
    if (!appData.isNullOrBlank()) {
      val dir = File(appData, ".minecraft")
      if (dir.exists() || dir.mkdirs()) {
        return dir
      }
    }
    val dir = File(System.getProperty("user.home"), ".minecraft")
    if (dir.exists() || dir.mkdirs()) {
      return dir
    }
    return Minecraft.getInstance().gameDirectory
  }

  @Throws(IOException::class)
  private fun ensureParent(file: File) {
    val parent = file.parentFile ?: return
    if (!parent.exists() && !parent.mkdirs() && !parent.exists()) {
      throw IOException("Could not create directories: ${parent.absolutePath}")
    }
  }

  @Throws(IOException::class)
  private fun writeUtf8(file: File, contents: String?) {
    Files.writeString(file.toPath(), contents ?: "", StandardCharsets.UTF_8)
  }

  private fun formatPolarRoute(
    name: String,
    offsets: Array<RouteOffsets.Offset>,
    baseX: Int,
    baseY: Int,
    baseZ: Int,
  ): String {
    val sb = StringBuilder()
    sb.append("{\n")
    sb.append("  \"name\": \"").append(escapeJson(name)).append("\",\n")
    sb.append("  \"type\": \"gemstone\",\n")
    sb.append("  \"positions\": [\n")
    for (i in offsets.indices) {
      val o = offsets[i]
      val x = baseX + o.x
      val y = baseY + o.y
      val z = baseZ + o.z
      sb.append("    { \"x\":").append(x)
        .append(", \"y\":").append(y)
        .append(", \"z\":").append(z)
        .append(", \"action\": \"VEIN\" }")
      sb.append(if (i == offsets.size - 1) "\n" else ",\n")
    }
    sb.append("  ],\n")
    sb.append("  \"targets\": [],\n")
    sb.append("  \"location\": \"CRYSTAL_HOLLOWS\"\n")
    sb.append("}\n")
    return sb.toString()
  }

  private fun formatNebulaRoute(
    offsets: Array<RouteOffsets.Offset>,
    baseX: Int,
    baseY: Int,
    baseZ: Int,
  ): String {
    val sb = StringBuilder()
    sb.append("[\n")
    for (i in offsets.indices) {
      val o = offsets[i]
      val x = baseX + o.x
      val y = baseY + o.y
      val z = baseZ + o.z
      sb.append("  { \"pos\": { \"x\":").append(x)
        .append(", \"y\":").append(y)
        .append(", \"z\":").append(z)
        .append(" } }")
      sb.append(if (i == offsets.size - 1) "\n" else ",\n")
    }
    sb.append("]\n")
    return sb.toString()
  }

  private fun formatPolinexRoute(
    offsets: Array<RouteOffsets.Offset>,
    baseX: Int,
    baseY: Int,
    baseZ: Int,
  ): String {
    val sb = StringBuilder()
    sb.append("[\n")
    for (i in offsets.indices) {
      val o = offsets[i]
      val x = baseX + o.x
      val y = baseY + o.y
      val z = baseZ + o.z
      sb.append("  { \"x\":").append(x)
        .append(", \"y\":").append(y)
        .append(", \"z\":").append(z)
        .append(", \"moveType\": \"WARP\" }")
      sb.append(if (i == offsets.size - 1) "\n" else ",\n")
    }
    sb.append("]\n")
    return sb.toString()
  }

  private fun formatMelodyRoute(
    offsets: Array<RouteOffsets.Offset>,
    baseX: Int,
    baseY: Int,
    baseZ: Int,
  ): String {
    val sb = StringBuilder()
    for (o in offsets) {
      val x = baseX + o.x
      val y = baseY + o.y
      val z = baseZ + o.z
      sb.append(x).append(":").append(y).append(":").append(z).append("%")
    }
    return sb.toString()
  }

  private fun escapeJson(text: String?): String {
    if (text == null) return ""
    return text.replace("\\", "\\\\").replace("\"", "\\\"")
  }
}
