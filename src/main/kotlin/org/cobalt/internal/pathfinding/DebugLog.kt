package org.cobalt.internal.pathfinding

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object DebugLog {
	var statusChatEnabled = true
	var debugChatEnabled = false
	var tag = "[Dutt]"
	var tickChatInterval = 20
	var debugFileName = "dutt-debug.txt"
	var statusFileEnabled = true
	var debugFileEnabled = false

	private val lastChatTickByModule = HashMap<String, Long>()
	private val sessionFileByModule = HashMap<String, File>()
	private val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
		.withZone(ZoneId.systemDefault())

	fun status(client: Minecraft, module: String, message: String) {
		if (statusFileEnabled && debugFileEnabled) {
			writeLine(client, module, message, null)
		}
		if (statusChatEnabled) {
			client.player?.displayClientMessage(Component.literal("$tag[$module] $message"), false)
		}
	}

	fun debug(client: Minecraft, module: String, message: String, overlay: Boolean = true) {
		if (!debugChatEnabled) {
			return
		}
		client.player?.displayClientMessage(Component.literal("$tag[$module] $message"), overlay)
	}

	fun debugTick(client: Minecraft, module: String, message: String, gameTime: Long) {
		writeLine(client, module, message, gameTime)
		val lastTick = lastChatTickByModule[module] ?: Long.MIN_VALUE
		if (tickChatInterval > 0 && gameTime - lastTick >= tickChatInterval) {
			lastChatTickByModule[module] = gameTime
			client.player?.displayClientMessage(Component.literal("$tag[$module] $message"), false)
		}
	}

	fun debugTickFile(client: Minecraft, module: String, message: String, gameTime: Long) {
		if (!debugFileEnabled) {
			return
		}
		writeLine(client, module, message, gameTime)
	}

	fun startSession(client: Minecraft, module: String) {
		if (!debugFileEnabled) {
			return
		}
		val dir = client.gameDirectory ?: return
		val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault()).format(Instant.now())
		val file = File(dir, "dutt-debug-$module-$stamp.txt")
		sessionFileByModule[module] = file
		writeLine(client, module, "Session start", null)
	}

	fun endSession(client: Minecraft, module: String, message: String? = null) {
		if (!debugFileEnabled) {
			sessionFileByModule.remove(module)
			return
		}
		if (message != null) {
			writeLine(client, module, message, null)
		}
		writeLine(client, module, "Session end", null)
		sessionFileByModule.remove(module)
	}

	private fun writeLine(client: Minecraft, module: String, message: String, gameTime: Long?) {
		val dir = client.gameDirectory ?: return
		val file = sessionFileByModule[module] ?: File(dir, debugFileName)
		val time = timeFormatter.format(Instant.now())
		val tickText = if (gameTime != null) " t=$gameTime" else ""
		try {
			FileWriter(file, true).use { writer ->
				writer.append(time)
				writer.append(tickText)
				writer.append(" ")
				writer.append(tag)
				writer.append("[")
				writer.append(module)
				writer.append("] ")
				writer.append(message)
				writer.append("\n")
			}
		} catch (_: Exception) {
			// ignore file errors
		}
	}
}
