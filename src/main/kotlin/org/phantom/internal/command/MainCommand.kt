package org.phantom.internal.command

import java.util.Locale
import kotlin.math.sqrt
import kotlin.random.Random
import net.minecraft.ChatFormatting
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.item.ItemEntity
import org.phantom.api.command.Command
import org.phantom.api.command.annotation.DefaultHandler
import org.phantom.api.command.annotation.SubCommand
import org.phantom.api.notification.NotificationManager
import org.phantom.api.rotation.EasingType
import org.phantom.api.rotation.RotationExecutor
import org.phantom.api.rotation.strategy.TimedEaseStrategy
import org.phantom.api.util.ChatUtils
import org.phantom.api.util.helper.Rotation
import org.phantom.api.util.player.MovementManager
import org.phantom.internal.pathfinding.PathfindingModule
import org.phantom.internal.mining.VeinScannerModule
import org.phantom.internal.stats.MacroTimeTracker
import org.phantom.internal.ui.screen.UIConfig

internal object MainCommand : Command(name = "phantom", aliases = arrayOf("phantom", "cb")) {

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
  fun pathto(x: Double, y: Double, z: Double) {
    PathfindingModule.startTo(x, y, z)
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
  fun vein() {
    VeinScannerModule.enterScannerMode()
  }

  @SubCommand("vein export")
  fun veinExport() {
    VeinScannerModule.exportLookedVein()
  }

  @SubCommand
  fun unlock() {
    PathfindingModule.stopPath()
    MovementManager.forceClearAll()
    RotationExecutor.stopRotating()
    ChatUtils.sendMessage("Phantom: cleared movement/look locks and released forced inputs.")
  }

  @SubCommand
  fun moveclear() {
    unlock()
  }

  @SubCommand
  fun notification(title: String, description: String) {
    NotificationManager.queue(title, description, 2000L)
  }

  @SubCommand
  fun sendcoords() {
    val mc = net.minecraft.client.Minecraft.getInstance()
    val player = mc.player
    if (player == null) {
      ChatUtils.sendMessage("No world loaded.")
      return
    }

    val pos = player.blockPosition()
    val message = "x: ${pos.x}, y: ${pos.y}, z: ${pos.z}"
    player.connection.sendChat(message)
    ChatUtils.sendMessage("Sent coordinates: $message")
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

      sendEntityScanResult(entity, sqrt(distSq), dx, dy, dz)
    }

    ChatUtils.sendMessage("[EntityScan] Found $count entities within $range blocks.")
  }

  private fun sendEntityScanResult(entity: Entity, distance: Double, dx: Double, dy: Double, dz: Double) {
    val registryId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.type).toString()
    val shortUuid = entity.uuid.toString().take(8)
    val flags = entityScanFlags(entity)
    val customName = cleanEntityScanText(entity.customName?.string)
    val displayName = cleanEntityScanText(entity.displayName.string)
    val normalName = cleanEntityScanText(entity.name.string)
    val scoreboardName = cleanEntityScanText(entity.scoreboardName)
    val extra = entityScanExtra(entity)

    ChatUtils.sendMessage(
      "[EntityScan] #${entity.id} $registryId ${entity.javaClass.simpleName} " +
        "dist=${fmt(distance)} uuid=$shortUuid"
    )
    ChatUtils.sendMessage(
      "  names: name=${quote(normalName)} display=${quote(displayName)} " +
        "custom=${quote(customName)} scoreboard=${quote(scoreboardName)}"
    )
    ChatUtils.sendMessage(
      "  pos=(${fmt(entity.x)}, ${fmt(entity.y)}, ${fmt(entity.z)}) " +
        "rel=(${fmt(dx)}, ${fmt(dy)}, ${fmt(dz)}) " +
        "rot=(${fmt(entity.yRot.toDouble())}, ${fmt(entity.xRot.toDouble())}) " +
        "box=${fmt(entity.bbWidth.toDouble())}x${fmt(entity.bbHeight.toDouble())} flags=$flags"
    )
    if (extra.isNotEmpty()) ChatUtils.sendMessage("  $extra")
  }

  private fun entityScanFlags(entity: Entity): String {
    val flags = ArrayList<String>()
    if (entity.isAlive) flags += "alive" else flags += "dead"
    if (entity.isRemoved) flags += "removed"
    if (entity.isInvisible) flags += "invisible"
    if (entity.isNoGravity) flags += "noGravity"
    if (entity.onGround()) flags += "ground"
    return flags.joinToString(",")
  }

  private fun entityScanExtra(entity: Entity): String {
    return when (entity) {
      is LivingEntity -> {
        val held = entity.mainHandItem
        val heldName = if (held.isEmpty) "empty" else "${held.count}x ${cleanEntityScanText(held.hoverName.string)}"
        "living: hp=${fmt(entity.health.toDouble())}/${fmt(entity.maxHealth.toDouble())} " +
          "absorb=${fmt(entity.absorptionAmount.toDouble())} armor=${entity.armorValue} " +
          "mainHand=${quote(heldName)}"
      }
      is ItemEntity -> {
        val stack = entity.item
        "item: count=${stack.count} name=${quote(cleanEntityScanText(stack.hoverName.string))} " +
          "id=${BuiltInRegistries.ITEM.getKey(stack.item)}"
      }
      else -> ""
    }
  }

  private fun cleanEntityScanText(text: String?): String {
    return ChatFormatting.stripFormatting(text.orEmpty())
      ?.replace(Regex("""\s+"""), " ")
      ?.trim()
      .orEmpty()
  }

  private fun quote(value: String): String = if (value.isBlank()) "\"\"" else "\"$value\""

  private fun fmt(value: Double): String = String.format(Locale.US, "%.2f", value)

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
