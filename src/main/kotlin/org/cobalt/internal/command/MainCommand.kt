package org.cobalt.internal.command

import kotlin.random.Random
import org.cobalt.api.command.Command
import org.cobalt.api.command.annotation.DefaultHandler
import org.cobalt.api.command.annotation.SubCommand
import org.cobalt.api.notification.NotificationManager
import org.cobalt.api.rotation.EasingType
import org.cobalt.api.rotation.RotationExecutor
import org.cobalt.api.rotation.strategy.TimedEaseStrategy
import org.cobalt.api.util.ChatUtils
import org.cobalt.api.util.helper.Rotation
import org.cobalt.internal.pathfinding.PathfindingModule
import org.cobalt.internal.stats.MacroTimeTracker
import org.cobalt.internal.ui.screen.UIConfig

internal object MainCommand : Command(name = "dutt", aliases = arrayOf("cobalt", "cb")) {

  @DefaultHandler
  fun main() {
    UIConfig.openUI()
  }

  @SubCommand
  fun rotate(yaw: Double, pitch: Double, duration: Int) {
    RotationExecutor.rotateTo(
      Rotation(yaw.toFloat(), pitch.toFloat()),
      TimedEaseStrategy(
        yawEasing = EasingType.EASE_OUT_EXPO,
        pitchEasing = EasingType.EASE_OUT_EXPO,
        duration = duration.toLong()
      )
    )
  }

  @SubCommand
  fun rotate() {
    val yaw = Random.nextFloat() * 360f - 180f
    val pitch = Random.nextFloat() * 180f - 90f

    RotationExecutor.rotateTo(
      Rotation(yaw, pitch),
      TimedEaseStrategy(
        yawEasing = EasingType.EASE_OUT_EXPO,
        pitchEasing = EasingType.EASE_OUT_EXPO,
        duration = 400L
      )
    )
  }

  @SubCommand
  fun start(x: Double, y: Double, z: Double) {
    PathfindingModule.startTo(x, y, z)
  }

  @SubCommand
  fun stop() {
    PathfindingModule.stopPath()
  }

  @SubCommand
  fun pathstart() {
    PathfindingModule.startFromSettings()
  }

  @SubCommand
  fun pathstop() {
    PathfindingModule.stopPath()
  }

  @SubCommand
  fun setpos(x: Double, y: Double, z: Double) {
    PathfindingModule.setTargetOnly(x, y, z)
  }

  @SubCommand
  fun setposhere() {
    PathfindingModule.setTargetAtPlayer()
  }

  @SubCommand
  fun notification(title: String, description: String) {
    NotificationManager.queue(title, description, 2000L)
  }

  @SubCommand
  fun today() {
    val snapshot = MacroTimeTracker.snapshot()
    ChatUtils.sendMessage("Today: ${formatDuration(snapshot.todayTotalMs)}")
  }

  @SubCommand
  fun tdd() {
    val snapshot = MacroTimeTracker.snapshot()
    ChatUtils.sendMessage("Macroed Today: ${formatDuration(snapshot.todayTotalMs)}")
    ChatUtils.sendMessage("Macroed Lifetime: ${formatDuration(snapshot.lifetimeTotalMs)}")
    emitBreakdown("Today Macros", snapshot.todayByMacro)
    emitBreakdown("Lifetime Macros", snapshot.lifetimeByMacro)
  }

  @SubCommand
  fun entityscan() {
    val mc = net.minecraft.client.Minecraft.getInstance()
    val player = mc.player
    val level = mc.level
    if (player == null || level == null) {
      ChatUtils.sendMessage("No world loaded.")
      return
    }

    val range = 5.0
    val rangeSq = range * range
    var count = 0

    for (entity in level.entitiesForRendering()) {
      if (entity == player) continue
      val dx = entity.x - player.x
      val dy = entity.y - player.y
      val dz = entity.z - player.z
      val distSq = dx * dx + dy * dy + dz * dz
      if (distSq > rangeSq) continue
      count++

      val name = entity.name.string
      ChatUtils.sendMessage(
        "[EntityScan] ${entity.type.descriptionId} \"$name\" @ " +
          "${"%.1f".format(entity.x)}, ${"%.1f".format(entity.y)}, ${"%.1f".format(entity.z)}"
      )
    }

    ChatUtils.sendMessage("[EntityScan] Found $count entities within $range blocks.")
  }

  private fun emitBreakdown(title: String, durations: List<MacroTimeTracker.MacroDuration>) {
    if (durations.isEmpty()) {
      ChatUtils.sendMessage("$title: None")
      return
    }

    ChatUtils.sendMessage(title)
    durations.forEach { duration ->
      ChatUtils.sendMessage("${duration.name}: ${formatDuration(duration.durationMs)}")
    }
  }

  private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
      "%02d:%02d:%02d".format(hours, minutes, seconds)
    } else {
      "%02d:%02d".format(minutes, seconds)
    }
  }
}
