package org.cobalt.internal.grotto

import com.google.gson.JsonParser
import java.util.regex.Pattern
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.ChatEvent
import org.cobalt.api.event.impl.client.TickEvent

object CrystalHollowsDetector {

  private val mc = Minecraft.getInstance()
  private val jsonPattern = Pattern.compile("^\\{.+}$")
  private val parser = JsonParser()

  private var inCrystalHollows = false
  private var locrawPending = false
  private var locrawPendingSinceMs = 0L
  private var lastLocrawSentMs = 0L
  private var nextLocrawAllowedMs = 0L

  private const val LOCRAW_JOIN_DELAY_MS = 2500L
  private const val LOCRAW_MIN_INTERVAL_MS = 12000L
  private const val LOCRAW_TIMEOUT_MS = 4000L

  private var lastLevel: net.minecraft.client.multiplayer.ClientLevel? = null

  @JvmStatic
  fun isInCrystalHollows(): Boolean = inCrystalHollows

  @SubscribeEvent
  fun onTick(@Suppress("UNUSED_PARAMETER") event: TickEvent.End) {
    val level = mc.level
    if (level == null || mc.player == null) {
      resetState()
      return
    }

    if (lastLevel !== level) {
      lastLevel = level
      resetState()
    }

    trySendLocraw()
  }

  @SubscribeEvent
  fun onChat(event: ChatEvent.Receive) {
    val message = event.message ?: return
    var raw = ChatFormatting.stripFormatting(message) ?: return
    raw = raw.trim()

    if (!jsonPattern.matcher(raw).matches()) return

    val obj = runCatching { parser.parse(raw).asJsonObject }.getOrNull() ?: return
    if (!obj.has("server") && !obj.has("mode") && !obj.has("map") && !obj.has("gametype")) return

    event.setCancelled(true)
    locrawPending = false
    nextLocrawAllowedMs = System.currentTimeMillis() + LOCRAW_MIN_INTERVAL_MS

    val mode = if (obj.has("mode")) safeLower(obj.get("mode").asString) else ""
    val map = if (obj.has("map")) safeLower(obj.get("map").asString) else ""

    val nowInHollows = mode == "crystal_hollows" || map.contains("crystal hollows")
    if (nowInHollows != inCrystalHollows) {
      inCrystalHollows = nowInHollows
      announceState(nowInHollows)
    }
  }

  private fun trySendLocraw() {
    val player = mc.player ?: return
    val level = mc.level ?: return
    if (mc.isSingleplayer) return

    val now = System.currentTimeMillis()
    if (inCrystalHollows) return
    if (now < nextLocrawAllowedMs) return

    if (locrawPending) {
      if (now - locrawPendingSinceMs > LOCRAW_TIMEOUT_MS) {
        locrawPending = false
        nextLocrawAllowedMs = now + LOCRAW_MIN_INTERVAL_MS
      }
      return
    }

    if (now - lastLocrawSentMs < LOCRAW_MIN_INTERVAL_MS) return

    player.connection?.sendCommand("locraw")
    lastLocrawSentMs = now
    locrawPending = true
    locrawPendingSinceMs = now
  }

  private fun resetState() {
    val now = System.currentTimeMillis()
    inCrystalHollows = false
    locrawPending = false
    locrawPendingSinceMs = 0L
    nextLocrawAllowedMs = now + LOCRAW_JOIN_DELAY_MS
  }

  private fun safeLower(text: String?): String {
    return text?.trim()?.lowercase() ?: ""
  }

  private fun announceState(inHollows: Boolean) {
    if (mc.player == null) return
    if (inHollows) {
      GrottoChat.grotto("Crystal Hollows detected - scanner enabled.")
    } else {
      GrottoChat.grotto("Left Crystal Hollows - scanner disabled.")
    }
  }

}
