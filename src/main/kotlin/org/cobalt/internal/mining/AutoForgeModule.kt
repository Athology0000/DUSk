package org.cobalt.internal.mining

import java.util.Locale
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.Slot
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.TickEvent
import org.cobalt.api.module.Module
import org.cobalt.api.pathfinder.jni.NativePathfinder
import org.cobalt.api.pathfinder.jni.PathStatus
import org.cobalt.api.pathfinder.minecraft.MinecraftPathingRules
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.module.setting.impl.InfoSetting
import org.cobalt.api.module.setting.impl.InfoType
import org.cobalt.api.module.setting.impl.TextSetting
import org.cobalt.api.util.AngleUtils
import org.cobalt.api.util.ChatUtils
import org.cobalt.api.util.InventoryUtils
import org.cobalt.api.util.getLoreLines
import org.cobalt.internal.pathfinding.PathfindingModule
import org.cobalt.internal.ui.panel.panels.UIModuleList

object AutoForgeModule : Module("Auto Forge") {

  private enum class State {
    IDLE,
    WAIT_ROUTE,
    OPEN_NPC,
    SELECT_SLOT,
    SELECT_RECIPE,
    CONFIRM_RECIPE,
    VERIFY_STARTED,
    COMPLETE,
  }

  private data class SlotText(
    val index: Int,
    val name: String,
    val combined: String,
  )

  private val mc = Minecraft.getInstance()

  private val enabled = CheckboxSetting(
    "Enabled",
    "Walk a saved forge route, open a forge NPC, and start the configured material.",
    false,
  )

  private val info = InfoSetting(
    "How It Works",
    "Starts a saved forge route once, opens the configured NPC, claims one ready slot if allowed, then starts the configured material.",
    InfoType.INFO,
  )

  private val statusText = TextSetting(
    "Status",
    "Current auto forge state.",
    "Idle",
  )

  private val routeNameText = TextSetting(
    "Forge Route",
    "Saved route used to walk to the forge NPC. Leave blank to start from your current position.",
    "",
  ).apply {
    uiGroup = UIModuleList.SIDE_GROUP
  }

  private val npcNameText = TextSetting(
    "Forge NPC",
    "Partial NPC name to open once the route finishes.",
    "forger",
  ).apply {
    uiGroup = UIModuleList.SIDE_GROUP
  }

  private val materialText = TextSetting(
    "Material",
    "Material name or unique keyword to start in the forge.",
    "",
  ).apply {
    uiGroup = UIModuleList.SIDE_GROUP
  }

  private val autoClaimReady = CheckboxSetting(
    "Auto Claim Ready",
    "Claim one ready forge slot before starting the new material.",
    true,
  ).apply {
    uiGroup = UIModuleList.SIDE_GROUP
  }

  private val closeOnDone = CheckboxSetting(
    "Close On Done",
    "Close the forge GUI after the material is started.",
    true,
  ).apply {
    uiGroup = UIModuleList.SIDE_GROUP
  }

  private var state = State.IDLE
  private var stateTick = 0L
  private var openAttempts = 0
  private var confirmAttempts = 0
  private var recipePageTurns = 0
  private var claimedReadyThisRun = false
  private var routeStartedByModule = false
  private var pendingUseRelease = false
  private var lastNpcPathTarget: BlockPos? = null
  private var npcPathOwned = false

  val isRunning: Boolean
    get() = enabled.value && state != State.IDLE

  init {
    addSetting(
      enabled,
      info,
      statusText,
      routeNameText,
      npcNameText,
      materialText,
      autoClaimReady,
      closeOnDone,
    )
    EventBus.register(this)
  }

  @SubscribeEvent
  fun onTick(@Suppress("UNUSED_PARAMETER") event: TickEvent.Start) {
    if (pendingUseRelease) {
      mc.options.keyUse?.setDown(false)
      pendingUseRelease = false
    }

    if (!enabled.value) {
      if (state != State.IDLE) {
        resetRuntime()
      }
      return
    }

    val player = mc.player ?: return
    stateTick++

    when (state) {
      State.IDLE -> startRun()
      State.WAIT_ROUTE -> handleWaitRoute()
      State.OPEN_NPC -> handleOpenNpc(player)
      State.SELECT_SLOT -> handleSelectSlot()
      State.SELECT_RECIPE -> handleSelectRecipe()
      State.CONFIRM_RECIPE -> handleConfirmRecipe()
      State.VERIFY_STARTED -> handleVerifyStarted()
      State.COMPLETE -> handleComplete()
    }
  }

  private fun startRun() {
    if (normalizedMaterialQuery().isBlank()) {
      disable("Set a forge material first.")
      return
    }
    if (normalizedNpcQuery().isBlank()) {
      disable("Set a forge NPC first.")
      return
    }

    openAttempts = 0
    confirmAttempts = 0
    recipePageTurns = 0
    claimedReadyThisRun = false
    routeStartedByModule = false

    val routeName = routeNameText.value.trim()
    if (routeName.isNotEmpty()) {
      val started =
        RoutesModule.loadAndStartAutomationRoute(
          routeName,
          startNearest = false,
          loop = false,
          automationSource = "forge automation",
        )
      if (!started) {
        disable("Could not start forge route \"$routeName\".")
        return
      }
      routeStartedByModule = true
      setStatus("Following forge route...")
      transition(State.WAIT_ROUTE)
      return
    }

    setStatus("Opening forge NPC...")
    transition(State.OPEN_NPC)
  }

  private fun handleWaitRoute() {
    if (RoutesModule.isRunning) {
      setStatus("Following forge route...")
      return
    }

    routeStartedByModule = false
    setStatus("Opening forge NPC...")
    transition(State.OPEN_NPC)
  }

  private fun handleOpenNpc(player: Player) {
    val screen = mc.screen as? AbstractContainerScreen<*>
    if (screen != null) {
      stopNpcNavigation()
      if (isForgeLikeScreen(screen)) {
        setStatus("Forge menu opened.")
        transition(State.SELECT_SLOT)
        return
      }
      if (stateTick > NON_FORGE_SCREEN_TIMEOUT_TICKS) {
        disable("Opened non-forge menu \"${screen.title.string}\".")
      }
      return
    }

    val npc = findForgeInteractionEntity(player)
    if (npc == null) {
      if (stateTick > NPC_LOOKUP_TIMEOUT_TICKS) {
        disable("Could not find forge NPC \"${npcNameText.value.trim()}\" nearby.")
      } else {
        setStatus("Looking for ${npcNameText.value.trim()}...")
      }
      return
    }

    if (player.distanceToSqr(npc) > NPC_INTERACT_DISTANCE_SQ) {
      val walkTarget = findWalkTargetNear(npc.blockPosition())
      if (lastNpcPathTarget != walkTarget || !nativePathActive() || stateTick % 40L == 1L) {
        PathfindingModule.ensureEnabledForAutomation("auto forge")
        PathfindingModule.startTo(walkTarget.x + 0.5, walkTarget.y.toDouble(), walkTarget.z + 0.5)
        lastNpcPathTarget = walkTarget
        npcPathOwned = true
      }
      if (stateTick > NPC_LOOKUP_TIMEOUT_TICKS) {
        disable("Reached the forge route but ${npcNameText.value.trim()} is still out of reach.")
      } else {
        setStatus("Walking to ${npcNameText.value.trim()}...")
      }
      return
    }

    stopNpcNavigation()
    faceEntity(npc)
    if (stateTick == 1L) {
      setStatus("Facing ${npcNameText.value.trim()}...")
      return
    }
    if (stateTick % 4L != 0L) return
    if (openAttempts >= MAX_OPEN_ATTEMPTS) {
      disable("Could not open ${npcNameText.value.trim()} after $MAX_OPEN_ATTEMPTS attempts.")
      return
    }

    mc.options.keyUse?.setDown(false)
    mc.options.keyUse?.setDown(true)
    pendingUseRelease = true
    openAttempts++
    setStatus("Opening ${npcNameText.value.trim()}... (attempt $openAttempts)")
  }

  private fun handleSelectSlot() {
    val screen = requireForgeScreen() ?: return
    if (stateTick < 3L || stateTick % 3L != 0L) return

    if (autoClaimReady.value && !claimedReadyThisRun) {
      val claimSlot = findClaimReadySlot(screen)
      if (claimSlot >= 0) {
        InventoryUtils.clickSlot(claimSlot)
        claimedReadyThisRun = true
        setStatus("Claiming ready forge slot...")
        transition(State.SELECT_SLOT)
        return
      }
    }

    if (findStartForgeSlot(screen) >= 0) {
      transition(State.CONFIRM_RECIPE)
      return
    }

    if (findMaterialRecipeSlot(screen) >= 0) {
      transition(State.SELECT_RECIPE)
      return
    }

    val emptySlot = findEmptyForgeSlot(screen)
    if (emptySlot >= 0) {
      InventoryUtils.clickSlot(emptySlot)
      setStatus("Opening empty forge slot...")
      transition(State.SELECT_RECIPE)
      return
    }

    if (stateTick >= SLOT_SELECTION_TIMEOUT_TICKS && hasBusyForgeSlot(screen)) {
      disable("No free forge slots were available.")
      return
    }

    if (stateTick >= SLOT_SELECTION_TIMEOUT_TICKS) {
      disable("Could not find an empty forge slot.")
    }
  }

  private fun handleSelectRecipe() {
    val screen = requireForgeScreen() ?: return
    if (stateTick < 3L || stateTick % 3L != 0L) return

    if (findStartForgeSlot(screen) >= 0) {
      transition(State.CONFIRM_RECIPE)
      return
    }

    val recipeSlot = findMaterialRecipeSlot(screen)
    if (recipeSlot >= 0) {
      InventoryUtils.clickSlot(recipeSlot)
      setStatus("Selecting ${materialText.value.trim()}...")
      transition(State.CONFIRM_RECIPE)
      return
    }

    val nextPageSlot = findNextPageSlot(screen)
    if (nextPageSlot >= 0 && recipePageTurns < MAX_RECIPE_PAGE_TURNS) {
      InventoryUtils.clickSlot(nextPageSlot)
      recipePageTurns++
      setStatus("Searching recipes... (page ${recipePageTurns + 1})")
      transition(State.SELECT_RECIPE)
      return
    }

    if (findEmptyForgeSlot(screen) >= 0) {
      transition(State.SELECT_SLOT)
      return
    }

    if (stateTick >= RECIPE_SELECTION_TIMEOUT_TICKS) {
      disable("Could not find recipe \"${materialText.value.trim()}\".")
    }
  }

  private fun handleConfirmRecipe() {
    val screen = requireForgeScreen() ?: return
    if (stateTick < 3L || stateTick % 3L != 0L) return

    val startSlot = findStartForgeSlot(screen)
    if (startSlot >= 0) {
      InventoryUtils.clickSlot(startSlot)
      confirmAttempts = 1
      setStatus("Starting ${materialText.value.trim()}...")
      transition(State.VERIFY_STARTED)
      return
    }

    if (findMaterialRecipeSlot(screen) >= 0) {
      transition(State.SELECT_RECIPE)
      return
    }

    if (findEmptyForgeSlot(screen) >= 0) {
      transition(State.SELECT_SLOT)
      return
    }

    if (stateTick >= CONFIRM_SELECTION_TIMEOUT_TICKS) {
      disable("Could not confirm recipe \"${materialText.value.trim()}\".")
    }
  }

  private fun handleVerifyStarted() {
    val screen = mc.screen as? AbstractContainerScreen<*>
    if (screen == null) {
      finishSuccess("Started ${materialText.value.trim()}.")
      return
    }

    if (!isForgeLikeScreen(screen) && stateTick > NON_FORGE_SCREEN_TIMEOUT_TICKS) {
      finishSuccess("Started ${materialText.value.trim()}.")
      return
    }

    if (hasStartedMaterial(screen)) {
      finishSuccess("Started ${materialText.value.trim()}.")
      return
    }

    if (stateTick >= VERIFY_RETRY_TICKS) {
      val startSlot = findStartForgeSlot(screen)
      if (startSlot >= 0 && confirmAttempts < MAX_CONFIRM_ATTEMPTS) {
        InventoryUtils.clickSlot(startSlot)
        confirmAttempts++
        setStatus("Retrying forge start... (attempt $confirmAttempts)")
        transition(State.VERIFY_STARTED)
        return
      }
    }

    if (stateTick >= VERIFY_START_TIMEOUT_TICKS) {
      disable("Forge start did not confirm for ${materialText.value.trim()}.")
    }
  }

  private fun handleComplete() {
    if (stateTick == 1L && closeOnDone.value) {
      mc.player?.closeContainer()
    }
  }

  private fun requireForgeScreen(): AbstractContainerScreen<*>? {
    val screen = mc.screen as? AbstractContainerScreen<*>
    if (screen == null) {
      if (state == State.VERIFY_STARTED) {
        finishSuccess("Started ${materialText.value.trim()}.")
      } else {
        setStatus("Reopening forge NPC...")
        openAttempts = 0
        transition(State.OPEN_NPC)
      }
      return null
    }
    if (!isForgeLikeScreen(screen) && stateTick >= NON_FORGE_SCREEN_TIMEOUT_TICKS) {
      disable("Opened non-forge menu \"${screen.title.string}\".")
      return null
    }
    return screen
  }

  private fun findForgeInteractionEntity(player: Player): Entity? {
    val level = mc.level ?: return null
    val targetName = normalizedNpcQuery()
    if (targetName.isBlank()) return null

    val anchor =
      level.entitiesForRendering()
        .asSequence()
        .filter { it != player }
        .filter { normalizeText(it.name.string).contains(targetName) }
        .sortedWith(compareBy<Entity>({ if (it is ArmorStand) 1 else 0 }, { player.distanceToSqr(it) }))
        .firstOrNull()
        ?: return null

    if (anchor !is ArmorStand) {
      return anchor
    }

    return level.entitiesForRendering()
      .asSequence()
      .filter { it != player && it !is ArmorStand && it.distanceToSqr(anchor) <= NPC_ANCHOR_ENTITY_RANGE_SQ }
      .sortedWith(compareBy<Entity>({ if (it is Player) 0 else 1 }, { anchor.distanceToSqr(it) }))
      .firstOrNull() ?: anchor
  }

  private fun findWalkTargetNear(base: BlockPos): BlockPos {
    val level = mc.level ?: return base
    if (MinecraftPathingRules.isWalkable(level, base)) return base

    for (radius in 1..3) {
      for (dy in -2..2) {
        for (dx in -radius..radius) {
          for (dz in -radius..radius) {
            val candidate = base.offset(dx, dy, dz)
            if (MinecraftPathingRules.isWalkable(level, candidate)) {
              return candidate
            }
          }
        }
      }
    }
    return base
  }

  private fun faceEntity(entity: Entity) {
    val player = mc.player ?: return
    val rotation = AngleUtils.getRotation(entity)
    player.setYRot(rotation.yaw)
    player.setXRot(rotation.pitch)
    player.yHeadRot = rotation.yaw
    player.yBodyRot = rotation.yaw
  }

  private fun getForgeSlotTexts(screen: AbstractContainerScreen<*>): List<SlotText> {
    return getForgeCandidateSlots(screen).mapNotNull { slot ->
      if (!slot.hasItem()) return@mapNotNull null
      val name = normalizeText(slot.item.hoverName.string)
      if (name.isBlank()) return@mapNotNull null
      val lore =
        slot.item.getLoreLines()
          .map(::normalizeComponentText)
          .filter { it.isNotBlank() }
      SlotText(slot.index, name, listOf(name).plus(lore).joinToString("\n"))
    }
  }

  private fun getForgeCandidateSlots(screen: AbstractContainerScreen<*>): List<Slot> {
    val containerSlots = screen.menu.slots.filterNot { it.container is Inventory }
    return if (containerSlots.isNotEmpty()) containerSlots else screen.menu.slots
  }

  private fun isForgeLikeScreen(screen: AbstractContainerScreen<*>): Boolean {
    val title = normalizeText(screen.title.string)
    if (title.contains("forge")) return true
    return getForgeSlotTexts(screen).any { text ->
      text.combined.contains("forge") ||
        text.combined.contains("empty forge slot") ||
        text.combined.contains("click to forge") ||
        text.combined.contains("ready to collect") ||
        text.combined.contains("time remaining")
    }
  }

  private fun findClaimReadySlot(screen: AbstractContainerScreen<*>): Int {
    var bestIndex = -1
    var bestScore = Int.MIN_VALUE
    for (slot in getForgeSlotTexts(screen)) {
      val score =
        when {
          slot.combined.contains("claim item") -> 200
          slot.combined.contains("click to claim") -> 180
          slot.combined.contains("ready to collect") -> 170
          slot.combined.contains("click to collect") -> 160
          slot.combined.contains("item is ready") -> 150
          else -> Int.MIN_VALUE
        }
      if (score > bestScore) {
        bestScore = score
        bestIndex = slot.index
      }
    }
    return if (bestScore > 0) bestIndex else -1
  }

  private fun findEmptyForgeSlot(screen: AbstractContainerScreen<*>): Int {
    var bestIndex = -1
    var bestScore = Int.MIN_VALUE
    for (slot in getForgeSlotTexts(screen)) {
      if (isNavigationSlot(slot.combined)) continue
      val score =
        when {
          slot.combined.contains("empty forge slot") -> 220
          slot.name.contains("empty slot") -> 210
          slot.combined.contains("forge item") && slot.combined.contains("slot") -> 190
          slot.combined.contains("click to forge") && slot.combined.contains("slot") -> 180
          else -> Int.MIN_VALUE
        }
      if (score > bestScore) {
        bestScore = score
        bestIndex = slot.index
      }
    }
    return if (bestScore > 0) bestIndex else -1
  }

  private fun findMaterialRecipeSlot(screen: AbstractContainerScreen<*>): Int {
    val query = normalizedMaterialQuery()
    val tokens = materialTokens()
    if (query.isBlank() || tokens.isEmpty()) return -1

    var bestIndex = -1
    var bestScore = Int.MIN_VALUE
    for (slot in getForgeSlotTexts(screen)) {
      if (isNavigationSlot(slot.combined)) continue
      if (slot.combined.contains("empty forge slot")) continue
      if (slot.combined.contains("claim item")) continue

      var score = 0
      if (slot.name.contains(query)) score += 180
      if (slot.combined.contains(query)) score += 120

      var matchedAll = true
      for (token in tokens) {
        if (slot.name.contains(token)) {
          score += 45
        } else if (slot.combined.contains(token)) {
          score += 20
        } else {
          matchedAll = false
          break
        }
      }
      if (!matchedAll) continue

      if (slot.combined.contains("click to forge")) score += 20
      if (slot.combined.contains("not unlocked") || slot.combined.contains("coming soon")) score -= 200

      if (score > bestScore) {
        bestScore = score
        bestIndex = slot.index
      }
    }
    return if (bestScore > 0) bestIndex else -1
  }

  private fun findStartForgeSlot(screen: AbstractContainerScreen<*>): Int {
    val query = normalizedMaterialQuery()
    var bestIndex = -1
    var bestScore = Int.MIN_VALUE

    for (slot in getForgeSlotTexts(screen)) {
      if (isNavigationSlot(slot.combined)) continue

      var score =
        when {
          slot.combined.contains("click to forge") && query.isNotBlank() && slot.combined.contains(query) -> 180
          slot.combined.contains("start forge") -> 170
          slot.combined.contains("begin forging") -> 160
          slot.combined.contains("start forging") -> 150
          slot.combined.contains("click to start") -> 140
          slot.combined.contains("confirm forge") -> 130
          else -> Int.MIN_VALUE
        }

      if (score == Int.MIN_VALUE) continue
      if (query.isNotBlank() && slot.combined.contains(query)) score += 20
      if (slot.combined.contains("not enough") || slot.combined.contains("insufficient")) score -= 160

      if (score > bestScore) {
        bestScore = score
        bestIndex = slot.index
      }
    }

    return if (bestScore > 0) bestIndex else -1
  }

  private fun findNextPageSlot(screen: AbstractContainerScreen<*>): Int {
    return getForgeSlotTexts(screen)
      .firstOrNull { slot -> slot.combined.contains("next page") || slot.name == "next" }
      ?.index ?: -1
  }

  private fun hasBusyForgeSlot(screen: AbstractContainerScreen<*>): Boolean {
    return getForgeSlotTexts(screen).any { slot ->
      slot.combined.contains("time remaining") ||
        slot.combined.contains("ready to collect") ||
        slot.combined.contains("collect in") ||
        slot.combined.contains("ready in")
    }
  }

  private fun hasStartedMaterial(screen: AbstractContainerScreen<*>): Boolean {
    val query = normalizedMaterialQuery()
    if (query.isBlank()) return false
    return getForgeSlotTexts(screen).any { slot ->
      slot.combined.contains(query) &&
        (
          slot.combined.contains("time remaining") ||
            slot.combined.contains("collect in") ||
            slot.combined.contains("ready in") ||
            slot.combined.contains("ready to collect")
          )
    }
  }

  private fun isNavigationSlot(text: String): Boolean {
    return text.contains("close") ||
      text.contains("go back") ||
      text == "back" ||
      text.contains("next page") ||
      text.contains("previous page")
  }

  private fun normalizedNpcQuery(): String = normalizeText(npcNameText.value)

  private fun normalizedMaterialQuery(): String = normalizeText(materialText.value)

  private fun materialTokens(): List<String> {
    return normalizedMaterialQuery()
      .split(' ')
      .map { it.trim() }
      .filter { it.length >= 2 }
  }

  private fun normalizeComponentText(component: net.minecraft.network.chat.Component): String {
    return normalizeText(component.string)
  }

  private fun normalizeText(raw: String): String {
    return ChatFormatting.stripFormatting(raw)
      ?.lowercase(Locale.US)
      ?.replace("&", " and ")
      ?.replace(Regex("[^a-z0-9' ]"), " ")
      ?.replace(Regex("\\s+"), " ")
      ?.trim()
      .orEmpty()
  }

  private fun nativePathActive(): Boolean {
    return NativePathfinder.status.let { it != PathStatus.IDLE && it != PathStatus.ARRIVED && it != PathStatus.FAILED }
  }

  private fun stopNpcNavigation() {
    if (npcPathOwned && nativePathActive()) {
      PathfindingModule.stopPath()
    }
    lastNpcPathTarget = null
    npcPathOwned = false
  }

  private fun finishSuccess(message: String) {
    routeStartedByModule = false
    setStatus(message)
    ChatUtils.sendMessage("Auto Forge: $message")
    transition(State.COMPLETE)
  }

  private fun disable(reason: String) {
    setStatus(reason)
    ChatUtils.sendMessage("Auto Forge: $reason Disabling.")
    enabled.value = false
  }

  private fun resetRuntime() {
    if (routeStartedByModule && RoutesModule.isRunning) {
      RoutesModule.stopForAutomation()
    }
    routeStartedByModule = false
    stopNpcNavigation()
    pendingUseRelease = false
    openAttempts = 0
    confirmAttempts = 0
    recipePageTurns = 0
    claimedReadyThisRun = false
    mc.options.keyUse?.setDown(false)
    state = State.IDLE
    stateTick = 0L
    statusText.value = "Idle"
  }

  private fun transition(next: State) {
    state = next
    stateTick = 0L
  }

  private fun setStatus(message: String) {
    statusText.value = message
  }

  private const val MAX_OPEN_ATTEMPTS = 8
  private const val MAX_CONFIRM_ATTEMPTS = 3
  private const val MAX_RECIPE_PAGE_TURNS = 6
  private const val NPC_LOOKUP_TIMEOUT_TICKS = 120L
  private const val NON_FORGE_SCREEN_TIMEOUT_TICKS = 30L
  private const val SLOT_SELECTION_TIMEOUT_TICKS = 50L
  private const val RECIPE_SELECTION_TIMEOUT_TICKS = 70L
  private const val CONFIRM_SELECTION_TIMEOUT_TICKS = 50L
  private const val VERIFY_RETRY_TICKS = 8L
  private const val VERIFY_START_TIMEOUT_TICKS = 40L
  private const val NPC_INTERACT_DISTANCE_SQ = 20.25
  private const val NPC_ANCHOR_ENTITY_RANGE_SQ = 16.0
}
