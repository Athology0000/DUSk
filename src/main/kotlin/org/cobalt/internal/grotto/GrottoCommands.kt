package org.cobalt.internal.grotto

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import org.cobalt.internal.mining.FairyModule

object GrottoCommands {

  private var registered = false
  private var detectedMansionCoreOffset: BlockPos? = null

  @JvmStatic
  fun register() {
    if (registered) return
    registered = true

    ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
      val setMansionCore = literal("setmansioncore")
        .then(
          argument("x", StringArgumentType.word())
            .then(
              argument("y", StringArgumentType.word())
                .then(
                  argument("z", StringArgumentType.word())
                    .executes { ctx ->
                      val x = StringArgumentType.getString(ctx, "x").toIntOrNull()
                      val y = StringArgumentType.getString(ctx, "y").toIntOrNull()
                      val z = StringArgumentType.getString(ctx, "z").toIntOrNull()

                      if (x == null || y == null || z == null) {
                        GrottoChat.usage("/setmansioncore <x> <y> <z>")
                        return@executes 0
                      }

                      detectedMansionCoreOffset = BlockPos(x, y, z)
                      GrottoChat.send(
                        Component.literal("Mansion core base set to ")
                          .withStyle(ChatFormatting.GREEN)
                          .append(Component.literal("$x,$y,$z").withStyle(ChatFormatting.WHITE))
                      )
                      Command.SINGLE_SUCCESS
                    }
                )
            )
        )

      dispatcher.register(setMansionCore)

      dispatcher.register(routeCmd("setupmansion", "Mansion", RouteOffsets.MANSION))
      dispatcher.register(routeCmd("setupoptimisedmansion", "OptimisedMansion", RouteOffsets.OPTIMISED_MANSION))
      dispatcher.register(routeCmd("setuppalace", "Palace", RouteOffsets.PALACE))
      dispatcher.register(routeCmd("setupovergrown", "Overgrown", RouteOffsets.OVERGROWN))
      dispatcher.register(routeCmd("setupshrine", "Shrine", RouteOffsets.SHRINE))
      dispatcher.register(routeCmd("setupwaterfall", "Waterfall", RouteOffsets.WATERFALL))

      dispatcher.register(literal("grotto").executes {
        GrottoScanner.toggle()
        Command.SINGLE_SUCCESS
      })

      dispatcher.register(literal("blocklookingat").executes {
        val info = LookedAtBlockUtils.getLookedAtBlockInfo()
        if (info == null) {
          GrottoChat.mh("${ChatFormatting.RED}No block info.")
          return@executes 0
        }

        GrottoChat.mh(
          "Block: ${ChatFormatting.WHITE}${info.name}${ChatFormatting.GRAY} " +
            "(${info.id}:${info.meta}) @ " +
            "${ChatFormatting.WHITE}${info.x},${info.y},${info.z}"
        )
        Command.SINGLE_SUCCESS
      })

      dispatcher.register(
        literal("scanblocks")
          .then(argument("radius", IntegerArgumentType.integer(1))
            .executes { ctx ->
              val radius = IntegerArgumentType.getInteger(ctx, "radius")
              val mc = Minecraft.getInstance()
              val player = mc.player
              val level = mc.level
              if (player == null || level == null) return@executes 0

              var r = radius
              if (r <= 0) {
                GrottoChat.mh("${ChatFormatting.RED}Radius must be > 0.")
                return@executes 0
              }
              if (r > 20) {
                r = 20
                GrottoChat.mh("Radius clamped to ${ChatFormatting.WHITE}20${ChatFormatting.GRAY} to avoid insane scans.")
              }

              val pos = player.blockPosition()
              GrottoChat.mh(
                "Scanning blocks in radius ${ChatFormatting.WHITE}$r${ChatFormatting.GRAY} around " +
                  "${ChatFormatting.WHITE}${pos.x},${pos.y},${pos.z}${ChatFormatting.GRAY}..."
              )

              val results = BlockScanUtils.scanAround(level, pos, r)
              if (results.isEmpty()) {
                GrottoChat.mh("${ChatFormatting.RED}No blocks found.")
                return@executes 0
              }

              val sorted = results.entries.sortedByDescending { it.value }
              GrottoChat.mh("Top blocks in area:")

              val maxEntries = 12
              sorted.take(maxEntries).forEach { entry ->
                GrottoChat.mh(
                  "${ChatFormatting.GRAY}- ${ChatFormatting.WHITE}${entry.key}${ChatFormatting.GRAY} x" +
                    "${ChatFormatting.WHITE}${entry.value}"
                )
              }

              if (sorted.size > maxEntries) {
                GrottoChat.mh(
                  "${ChatFormatting.GRAY}(${ChatFormatting.WHITE}${sorted.size - maxEntries}" +
                    "${ChatFormatting.GRAY} more types omitted...)"
                )
              }

              Command.SINGLE_SUCCESS
            })
          .executes {
            GrottoChat.mhUsage("/scanblocks <radius>")
            0
          }
      )

      dispatcher.register(buildBlacklistCommand("mhbl"))
      dispatcher.register(buildBlacklistCommand("mhbbl"))
    }
  }

  @JvmStatic
  fun setDetectedMansionCore(pos: BlockPos?) {
    detectedMansionCoreOffset = pos
  }

  private fun routeCmd(
    name: String,
    routeName: String,
    offsets: Array<RouteOffsets.Offset>
  ): LiteralArgumentBuilder<FabricClientCommandSource> {
    return literal(name)
      .then(argument("preset", StringArgumentType.word())
        .executes { ctx ->
          runRoute(
            ctx,
            routeName,
            offsets,
            StringArgumentType.getString(ctx, "preset"),
            null
          )
        }
        .then(argument("mode", StringArgumentType.word())
          .executes { ctx ->
            runRoute(
              ctx,
              routeName,
              offsets,
              StringArgumentType.getString(ctx, "preset"),
              StringArgumentType.getString(ctx, "mode")
            )
          })
      )
      .executes {
        GrottoChat.usage("/$name <polar|nebula|polinex|melody> [json]")
        0
      }
  }

  private fun runRoute(
    @Suppress("UNUSED_PARAMETER") ctx: com.mojang.brigadier.context.CommandContext<FabricClientCommandSource>,
    routeName: String,
    offsets: Array<RouteOffsets.Offset>,
    preset: String,
    mode: String?
  ): Int {
    if (!FairyModule.enabled.value) {
      GrottoChat.send(Component.literal("Fairy Grotto module is disabled.").withStyle(ChatFormatting.RED))
      return 0
    }

    val baseOverride = detectedMansionCoreOffset
    if (baseOverride != null && routeName == "OptimisedMansion") {
      GrottoChat.send(
        Component.literal("Using detected mansion core (base) at ")
          .withStyle(ChatFormatting.GREEN)
          .append(Component.literal("${baseOverride.x},${baseOverride.y},${baseOverride.z}")
            .withStyle(ChatFormatting.WHITE))
      )
      RouteUtils.generateRouteForClientAt(routeName, offsets, preset, mode, baseOverride)
      return Command.SINGLE_SUCCESS
    }

    if (routeName == "OptimisedMansion" && baseOverride == null) {
      GrottoChat.send(
        Component.literal("No mansion core detected yet; using your current position as base.")
          .withStyle(ChatFormatting.YELLOW)
      )
    }

    RouteUtils.generateRouteForClient(routeName, offsets, preset, mode)
    return Command.SINGLE_SUCCESS
  }

  private fun buildBlacklistCommand(name: String): LiteralArgumentBuilder<FabricClientCommandSource> {
    return literal(name)
      .then(argument("args", StringArgumentType.greedyString())
        .executes { ctx ->
          handleBlacklistArgs(StringArgumentType.getString(ctx, "args"))
        })
      .executes {
        GrottoChat.grotto("${ChatFormatting.RED}/mhbl add <x> <y> <z> | remove <x> <y> <z> | clear | addlookingat | removelookingat")
        0
      }
  }

  private fun handleBlacklistArgs(argString: String): Int {
    val mc = Minecraft.getInstance()
    if (mc.player == null || mc.level == null) return 0

    GrottoScanner.initBlacklistFile()
    val args = argString.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (args.isEmpty()) {
      GrottoChat.grotto("${ChatFormatting.RED}/mhbl add <x> <y> <z> | remove <x> <y> <z> | clear | addlookingat | removelookingat")
      return 0
    }

    when (args[0].lowercase()) {
      "clear" -> {
        GrottoScanner.clearPermanentBlacklist()
        GrottoChat.grotto("${ChatFormatting.GRAY}Cleared permanent blacklist (built-in stays).")
        return Command.SINGLE_SUCCESS
      }
      "addlookingat", "removelookingat" -> {
        val info = LookedAtBlockUtils.getLookedAtBlockInfo()
        if (info == null) {
          GrottoChat.grotto("${ChatFormatting.RED}Look at a block first.")
          return 0
        }
        val pos = BlockPos(info.x, info.y, info.z)
        val added = if (args[0].lowercase() == "addlookingat") {
          GrottoScanner.addPermanentBlacklist(pos)
        } else {
          GrottoScanner.removePermanentBlacklist(pos)
        }
        if (args[0].lowercase() == "addlookingat") {
          if (added) {
            GrottoChat.grotto("${ChatFormatting.GRAY}Added to permanent blacklist: ${fmtPos(pos)}")
          } else {
            GrottoChat.grotto("${ChatFormatting.YELLOW}Already blacklisted (or built-in): ${fmtPos(pos)}")
          }
        } else {
          if (added) {
            GrottoChat.grotto("${ChatFormatting.GRAY}Removed from permanent blacklist: ${fmtPos(pos)}")
          } else {
            GrottoChat.grotto("${ChatFormatting.YELLOW}That block wasn't in the permanent blacklist: ${fmtPos(pos)}")
          }
        }
        return Command.SINGLE_SUCCESS
      }
      "add", "remove" -> {
        if (args.size < 4) {
          GrottoChat.grotto("${ChatFormatting.RED}/mhbl add <x> <y> <z> | remove <x> <y> <z>")
          return 0
        }

        val x = args[1].toIntOrNull()
        val y = args[2].toIntOrNull()
        val z = args[3].toIntOrNull()
        if (x == null || y == null || z == null) {
          GrottoChat.grotto("${ChatFormatting.RED}Coords must be numbers.")
          return 0
        }

        val pos = BlockPos(x, y, z)
        val added = if (args[0].lowercase() == "add") {
          GrottoScanner.addPermanentBlacklist(pos)
        } else {
          GrottoScanner.removePermanentBlacklist(pos)
        }

        if (args[0].lowercase() == "add") {
          if (added) {
            GrottoChat.grotto("${ChatFormatting.GRAY}Added to permanent blacklist: ${fmtPos(pos)}")
          } else {
            GrottoChat.grotto("${ChatFormatting.YELLOW}Already blacklisted (or built-in): ${fmtPos(pos)}")
          }
        } else {
          if (added) {
            GrottoChat.grotto("${ChatFormatting.GRAY}Removed from permanent blacklist: ${fmtPos(pos)}")
          } else {
            GrottoChat.grotto("${ChatFormatting.YELLOW}That block wasn't in the permanent blacklist: ${fmtPos(pos)}")
          }
        }
        return Command.SINGLE_SUCCESS
      }
    }

    GrottoChat.grotto("${ChatFormatting.RED}/mhbl add <x> <y> <z> | remove <x> <y> <z> | clear | addlookingat | removelookingat")
    return 0
  }

  private fun fmtPos(pos: BlockPos): String {
    return "${ChatFormatting.WHITE}${pos.x},${pos.y},${pos.z}${ChatFormatting.GRAY}"
  }

}
