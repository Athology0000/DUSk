package org.cobalt.internal.helper

import java.awt.Color
import java.util.UUID
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.scores.PlayerTeam

object ClientGlowEspManager {

  data class GlowTarget(
    val entity: LivingEntity,
    val argb: Int,
    val priority: Int = 0,
  )

  private data class GlowRequest(
    val scoreHolder: String,
    val argb: Int,
    val priority: Int,
  )

  private data class AppliedGlow(
    val scoreHolder: String,
  )

  private val requestsByScope = mutableMapOf<String, MutableMap<UUID, GlowRequest>>()
  private val appliedByEntity = mutableMapOf<UUID, AppliedGlow>()

  fun sync(scope: String, level: ClientLevel, targets: Collection<GlowTarget>) {
    val next = LinkedHashMap<UUID, GlowRequest>(targets.size)
    for (target in targets) {
      val entity = target.entity
      if (!entity.isAlive) continue
      next[entity.uuid] = GlowRequest(entity.scoreboardName, target.argb, target.priority)
    }

    val previous = requestsByScope[scope]
    val affected = LinkedHashSet<UUID>()
    previous?.keys?.let(affected::addAll)
    affected.addAll(next.keys)

    if (next.isEmpty()) {
      requestsByScope.remove(scope)
    } else {
      requestsByScope[scope] = next
    }

    reconcile(level, affected)
  }

  fun clear(scope: String, level: ClientLevel? = Minecraft.getInstance().level) {
    val removed = requestsByScope.remove(scope)?.keys ?: return
    if (level == null) {
      for (uuid in removed) {
        if (bestRequest(uuid) == null) {
          appliedByEntity.remove(uuid)
        }
      }
      return
    }
    reconcile(level, LinkedHashSet(removed))
  }

  private fun reconcile(level: ClientLevel, affected: Set<UUID>) {
    if (affected.isEmpty()) return

    val scoreboard = level.scoreboard
    val loadedLivingById =
      level.entitiesForRendering()
        .filterIsInstance<LivingEntity>()
        .associateBy { it.uuid }

    for (uuid in affected) {
      val request = bestRequest(uuid)
      val applied = appliedByEntity[uuid]

      if (request == null) {
        loadedLivingById[uuid]?.setGlowingTag(false)
        if (applied != null) {
          scoreboard.removePlayerFromTeam(applied.scoreHolder)
          appliedByEntity.remove(uuid)
        }
        continue
      }

      val living = loadedLivingById[uuid]
      if (living == null || !living.isAlive) {
        continue
      }

      val team = ensureGlowTeam(scoreboard, request.argb)
      val currentTeam = scoreboard.getPlayersTeam(request.scoreHolder)
      if (currentTeam == null || currentTeam.name != team.name) {
        if (currentTeam != null && currentTeam.name.startsWith(GLOW_TEAM_PREFIX)) {
          scoreboard.removePlayerFromTeam(request.scoreHolder, currentTeam)
        }
        scoreboard.addPlayerToTeam(request.scoreHolder, team)
      }

      if (applied != null && applied.scoreHolder != request.scoreHolder) {
        scoreboard.removePlayerFromTeam(applied.scoreHolder)
      }

      living.setGlowingTag(true)
      appliedByEntity[uuid] = AppliedGlow(request.scoreHolder)
    }
  }

  private fun bestRequest(uuid: UUID): GlowRequest? {
    var best: GlowRequest? = null
    for (scopeRequests in requestsByScope.values) {
      val candidate = scopeRequests[uuid] ?: continue
      if (best == null || candidate.priority >= best.priority) {
        best = candidate
      }
    }
    return best
  }

  private fun ensureGlowTeam(
    scoreboard: net.minecraft.world.scores.Scoreboard,
    argb: Int,
  ): PlayerTeam {
    val formatting = nearestChatFormatting(argb)
    val teamName = "$GLOW_TEAM_PREFIX${formatting.ordinal.toString(16)}"
    val team = scoreboard.getPlayerTeam(teamName) ?: scoreboard.addPlayerTeam(teamName)
    team.setColor(formatting)
    return team
  }

  private fun nearestChatFormatting(argb: Int): ChatFormatting {
    val source = Color(argb, true)
    return CHAT_FORMATTING_COLORS.minByOrNull { (_, candidate) ->
      val dr = source.red - candidate.red
      val dg = source.green - candidate.green
      val db = source.blue - candidate.blue
      dr * dr + dg * dg + db * db
    }?.first ?: ChatFormatting.WHITE
  }

  private val CHAT_FORMATTING_COLORS = listOf(
    ChatFormatting.BLACK to Color(0x000000),
    ChatFormatting.DARK_BLUE to Color(0x0000AA),
    ChatFormatting.DARK_GREEN to Color(0x00AA00),
    ChatFormatting.DARK_AQUA to Color(0x00AAAA),
    ChatFormatting.DARK_RED to Color(0xAA0000),
    ChatFormatting.DARK_PURPLE to Color(0xAA00AA),
    ChatFormatting.GOLD to Color(0xFFAA00),
    ChatFormatting.GRAY to Color(0xAAAAAA),
    ChatFormatting.DARK_GRAY to Color(0x555555),
    ChatFormatting.BLUE to Color(0x5555FF),
    ChatFormatting.GREEN to Color(0x55FF55),
    ChatFormatting.AQUA to Color(0x55FFFF),
    ChatFormatting.RED to Color(0xFF5555),
    ChatFormatting.LIGHT_PURPLE to Color(0xFF55FF),
    ChatFormatting.YELLOW to Color(0xFFFF55),
    ChatFormatting.WHITE to Color(0xFFFFFF),
  )

  private const val GLOW_TEAM_PREFIX = "cbg_"
}
