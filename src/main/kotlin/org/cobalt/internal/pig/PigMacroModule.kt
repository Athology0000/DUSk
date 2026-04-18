package org.cobalt.internal.pig

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.ChatFormatting
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.ChatEvent
import org.cobalt.api.event.impl.client.TickEvent
import org.cobalt.api.hud.HudAnchor
import org.cobalt.api.hud.hudElement
import org.cobalt.api.module.Module
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.module.setting.impl.InfoSetting
import org.cobalt.api.module.setting.impl.InfoType
import org.cobalt.api.module.setting.impl.SliderSetting
import org.cobalt.api.module.setting.impl.TextSetting
import org.cobalt.api.ui.theme.ThemeManager
import org.cobalt.api.util.AngleUtils
import org.cobalt.api.util.ChatUtils
import org.cobalt.api.util.InventoryUtils
import org.cobalt.api.util.ui.NVGRenderer
import org.cobalt.api.util.ui.helper.Gradient
import org.cobalt.api.pathfinder.jni.NativePathfinder
import org.cobalt.api.pathfinder.jni.PathStatus
import org.cobalt.api.util.player.MovementManager
import org.cobalt.internal.pathfinding.OverlayRenderEngine

object PigMacroModule : Module("Pig Macro") {

    private val mc = Minecraft.getInstance()
    val isActive: Boolean get() = enabledSetting.value

    // -- State -----------------------------------------------------------------

    /**
     * IDLE -> SPRINT_TO_PIG -> DEPLOYING_ORB -> WAIT_FOR_ORB_CHAT -> HERDING -> COLLECTING -> WAITING
     *
     *   SPRINT_TO_PIG     : sprint directly toward pig (no pathfinder - pig moves).
     *   DEPLOYING_ORB     : equip orb, hold right-click on pig for 4 ticks.
     *                       Long slot-swap settle delay so server registers the change.
     *   WAIT_FOR_ORB_CHAT : wait for "Bring the pig back!" -> random 0-2 s delay -> HERDING.
     *   HERDING phases:
     *     AOTV_BEHIND   : one-shot AOTV, aims at ground block behind pig (pig.y-1).
     *     WALK_PRESSURE : sprint toward pig so it flees toward orb.
     *                     Rod-click every N ticks. Punch pig if stuck for too long.
     *     SPRINT_BEHIND : sprint directly to behind-pig position (no pathfinder).
     *   COLLECTING        : pathfind to static orb anchor, hold right-click 4 ticks.
     */
    private enum class State {
        IDLE,
        SPRINT_TO_PIG,
        DEPLOYING_ORB,
        WAIT_FOR_ORB_CHAT,
        HERDING,
        COLLECTING,
        WAITING,
    }

    private enum class HerdPhase { AOTV_BEHIND, WALK_PRESSURE, SPRINT_BEHIND }

    private var state      = State.IDLE
    private var wasEnabled = false

    private var targetPig: LivingEntity? = null
    private var orbAnchor: Vec3?         = null

    private val waterBlacklist = mutableSetOf<Int>()

    private var herdPhase     = HerdPhase.AOTV_BEHIND
    private var walkTicks     = 0
    private var ticksSinceRod = 0
    private var actionTicks   = 0   // ticks held for current key-hold action
    private var actionDelay   = 0   // inter-action pause
    private var stateTimeout  = 0   // WAIT_FOR_ORB_CHAT fallback

    // Stuck detection - track pig distance to orb each tick
    private var lastPigOrbDist = Double.MAX_VALUE
    private var pigStuckTicks  = 0

    // Pathfinding targets - repath when entity moves too far from last target
    private var lastBehindPathPos:  BlockPos? = null
    private var lastSprintToPigPos: BlockPos? = null
    // Rotate behind-approach angle when pig is repeatedly stuck (45 deg per stuck event)
    private var stuckAngleOffsetDeg = 0.0

    // Pig velocity tracking - for overlay velocity ray
    private var pigPrevPos: Vec3? = null

    // Ticks we have waited for pig to land before firing AOTV
    private var pigLandWaitTicks = 0

    // Grace period after arriving from SPRINT_BEHIND: skip alignment check for N ticks
    // so a single tick of pig sideways movement doesn't immediately bounce us back.
    private var walkGraceTicks = 0

    private val HERDING_TAG = "pig-herding"

    private var sessionStartMs = 0L
    private var captureCount   = 0

    // -- Settings --------------------------------------------------------------

    private val enabledSetting = CheckboxSetting("Enabled", "Start or stop the shiny pig macro.", false)

    private val infoSetting = InfoSetting(
        "Shiny Pig",
        "Sprint to pig -> deploy orb -> AOTV behind -> walk pressure + rod -> punch if stuck -> collect.",
        InfoType.INFO
    )

    private val statusSetting    = TextSetting("Status", "Current macro state.", "Idle")
    private val pigKeywordSetting = TextSetting("Pig Keyword", "Name substring for shiny pigs.", "Shiny")
    private val shinyOrbKeywordSetting = TextSetting("Shiny Orb Item", "Hotbar keyword for Shiny Orb.", "Shiny Orb")
    private val shinyRodKeywordSetting = TextSetting("Shiny Rod Item", "Hotbar keyword for Shiny Rod.", "Shiny Rod")
    private val aotvKeywordSetting     = TextSetting("AOTV Item", "Hotbar keyword for Aspect of the Void.", "Aspect of the Void")

    private val searchRangeSetting = SliderSetting("Search Range", "Radius to search for shiny pigs.", 32.0, 8.0, 64.0, step = 1.0)
    private val deployRangeSetting = SliderSetting("Deploy Range", "Distance from pig to deploy orb.", 2.5, 1.0, 4.0, step = 0.5)
    private val behindDistanceSetting = SliderSetting("Behind Distance", "Blocks behind pig to stand when herding.", 3.0, 1.5, 6.0, step = 0.5)
    private val aotvHoldTicksSetting  = SliderSetting("AOTV Hold Ticks", "Ticks to hold right-click for AOTV.", 5.0, 2.0, 10.0, step = 1.0)
    private val rodIntervalSetting    = SliderSetting("Rod Interval", "Ticks between rod clicks during walking pressure.", 15.0, 5.0, 40.0, step = 1.0)
    private val stuckThresholdSetting = SliderSetting("Stuck Threshold", "Ticks pig must be stuck before punching it.", 60.0, 20.0, 120.0, step = 5.0)
    private val collectRangeSetting   = SliderSetting("Collect Range", "Distance to orb before clicking to collect.", 3.0, 1.0, 8.0, step = 0.5)
    private val waitBetweenSetting    = SliderSetting("Wait Between", "Ticks to wait before hunting next pig.", 40.0, 10.0, 200.0, step = 5.0)
    private val otherPlayerExclusionSetting = SliderSetting("Player Exclusion Radius", "Skip pigs within N blocks of another player.", 6.0, 2.0, 20.0, step = 1.0)
    private val abandonDistanceSetting = SliderSetting("Abandon Distance", "Pig->orb distance at which to give up and find a new pig.", 25.0, 10.0, 60.0, step = 1.0)
    private val autoLoopSetting = CheckboxSetting("Auto Loop", "Automatically hunt the next pig after collecting.", true)
    private val walkModeSetting = CheckboxSetting("Walk Mode", "Walk toward pig to make it flee to the orb instead of using AOTV knockback.", false)

    // -- HUD -------------------------------------------------------------------

    private const val W      = 200f
    private const val H      = 90f
    private const val CORNER = 10f
    private const val PAD    = 10f

    val pigHud = hudElement("pig-macro-hud", "Pig Macro", "Shiny pig macro status HUD") {
        anchor  = HudAnchor.TOP_RIGHT
        offsetX = 10f; offsetY = 10f
        width  { W }; height { H }

        render { x, y, _ ->
            val now   = System.currentTimeMillis()
            val twoPi = (Math.PI * 2).toFloat()
            val c1    = ThemeManager.currentTheme.accent
            val c2    = ThemeManager.currentTheme.accentSecondary
            val dim   = 0xBBAFCFFF.toInt()

            NVGRenderer.rect(x, y, W, H, 0xFF0A0E1A.toInt(), CORNER)
            NVGRenderer.gradientRect(x, y, W, H * 0.5f, 0x14FFFFFF, 0x00000000, Gradient.TopToBottom, CORNER)
            val shiftX = cos((now % 10000L).toFloat() / 10000f * twoPi) * (W * 0.42f)
            NVGRenderer.hollowGradientRectShifted(x, y, W, H, 1.5f, c1, c2, Gradient.LeftToRight, CORNER, shiftX, 0f)

            var cy = y + 14f
            NVGRenderer.text("SHINY PIG MACRO", x + PAD, cy, 11f, c1)

            val stateStr   = state.name.replace('_', ' ')
            val stateColor = when (state) {
                State.COLLECTING        -> 0xFF4CFF72.toInt()
                State.HERDING           -> 0xFFFF8C4C.toInt()
                State.WAIT_FOR_ORB_CHAT -> 0xFF4CC8FF.toInt()
                State.DEPLOYING_ORB     -> 0xFF4CC8FF.toInt()
                State.SPRINT_TO_PIG     -> 0xFFFFD84C.toInt()
                State.WAITING           -> 0xFF888888.toInt()
                State.IDLE              -> 0xFF444444.toInt()
            }
            val stateW = NVGRenderer.textWidth(stateStr, 9f)
            NVGRenderer.rect(x + W - PAD - stateW - 8f, cy - 9f, stateW + 8f, 12f, (stateColor and 0x00FFFFFF) or 0x33000000, 3f)
            NVGRenderer.text(stateStr, x + W - PAD - stateW - 4f, cy, 9f, stateColor)
            cy += 14f

            if (sessionStartMs > 0L) NVGRenderer.text("Time: ${formatDuration(now - sessionStartMs)}", x + PAD, cy, 10f, dim)
            else                      NVGRenderer.text("Time: --:--", x + PAD, cy, 10f, dim)
            cy += 13f

            NVGRenderer.text("Captures: $captureCount", x + PAD, cy, 10f, dim)
            cy += 13f

            val pig = targetPig
            if (pig != null && pig.isAlive) NVGRenderer.text("Target: %.1fm".format(mc.player?.distanceTo(pig) ?: 0.0), x + PAD, cy, 10f, dim)
            else                            NVGRenderer.text("Target: none", x + PAD, cy, 10f, dim)
        }
    }

    // -- Init ------------------------------------------------------------------

    init {
        addSetting(
            enabledSetting, infoSetting, statusSetting,
            pigKeywordSetting, shinyOrbKeywordSetting, shinyRodKeywordSetting, aotvKeywordSetting,
            searchRangeSetting, deployRangeSetting, behindDistanceSetting,
            aotvHoldTicksSetting, rodIntervalSetting, stuckThresholdSetting,
            collectRangeSetting, waitBetweenSetting, otherPlayerExclusionSetting,
            abandonDistanceSetting, autoLoopSetting, walkModeSetting,
        )
        EventBus.register(this)
    }

    // -- Tick ------------------------------------------------------------------

    @SubscribeEvent
    fun onTick(@Suppress("UNUSED_PARAMETER") event: TickEvent.Start) {
        if (!enabledSetting.value) {
            if (wasEnabled) stop()
            wasEnabled = false
            return
        }

        val player = mc.player ?: run { stop(); wasEnabled = false; return }
        val level  = mc.level  ?: run { stop(); wasEnabled = false; return }

        if (!wasEnabled) start()
        wasEnabled = true

        if (actionDelay > 0) { actionDelay--; return }

        when (state) {

            // -- IDLE ----------------------------------------------------------
            State.IDLE -> {
                val range           = searchRangeSetting.value
                val keyword         = pigKeywordSetting.value
                val exclusionRadius = otherPlayerExclusionSetting.value

                val stand = level.entitiesForRendering()
                    .asSequence()
                    .filterIsInstance<ArmorStand>()
                    .filter { as_ ->
                        as_.distanceTo(player) <= range
                            && as_.customName?.string?.contains(keyword, ignoreCase = true) == true
                            && level.entitiesForRendering().none { other ->
                                other is Player && other != player
                                    && other.distanceTo(as_) <= exclusionRadius
                            }
                    }
                    .minByOrNull { it.distanceTo(player) }

                if (stand != null) {
                    val pig = level.entitiesForRendering()
                        .asSequence()
                        .filterIsInstance<LivingEntity>()
                        .filter { entity ->
                            entity.isAlive
                                && entity.id !in waterBlacklist
                                && !entity.isInWater
                                && isPig(entity)
                                && entity.distanceTo(stand) <= 3.0
                        }
                        .minByOrNull { it.distanceTo(stand) }

                    if (pig != null) {
                        targetPig = pig
                        transition(State.SPRINT_TO_PIG)
                        ChatUtils.sendMessage("Pig macro: found shiny pig, sprinting.")
                    }
                }
            }

            // -- SPRINT_TO_PIG -------------------------------------------------
            // Pathfind to pig, repathing whenever pig moves > 2 blocks so we
            // route around walls rather than running into them.
            State.SPRINT_TO_PIG -> {
                val pig = livePig() ?: run {
                    if (nativeActive()) nativeStop(null)
                    lastSprintToPigPos = null; releaseKeys(); transition(State.IDLE); return
                }

                if (player.distanceTo(pig) <= deployRangeSetting.value) {
                    if (nativeActive()) nativeStop(null)
                    lastSprintToPigPos = null
                    releaseKeys()
                    actionDelay = humanDelay(2, 4)
                    transition(State.DEPLOYING_ORB)
                } else {
                    val pigBlock = BlockPos(pig.blockX, pig.blockY, pig.blockZ)
                    val last     = lastSprintToPigPos
                    // Only cancel a running path if pig moved > 5 blocks from the path
                    // target - frequent stop/start prevents the player from ever moving.
                    val needRepath = last == null || last.distSqr(pigBlock) > 25.0
                    if (!nativeActive() || needRepath) {
                        if (nativeActive()) nativeStop(null)
                        NativePathfinder.setTarget(pigBlock.x + 0.5, pigBlock.y.toDouble(), pigBlock.z + 0.5)
                        lastSprintToPigPos = pigBlock
                    }
                    NativePathfinder.tick()?.applyToPlayer()
                }
            }

            // -- DEPLOYING_ORB -------------------------------------------------
            // Phase 1 (slot wrong): chase pig while swapping slot - don't idle.
            // Phase 2 (slot correct, actionTicks==0): stop, face pig, wait 1 tick
            //   for pick() to update hitResult.
            // Phase 3 (actionTicks 1-8): toggle use off->on each tick for 8 chances.
            // Transition to WAIT_FOR_ORB_CHAT (chat confirms deploy) or retry.
            State.DEPLOYING_ORB -> {
                val pig = livePig() ?: run { transition(State.IDLE); return }

                val orbSlot = InventoryUtils.findItemInHotbar(shinyOrbKeywordSetting.value)
                if (orbSlot == -1) {
                    ChatUtils.sendMessage("Pig macro: Shiny Orb not found in hotbar - stopping.")
                    stop(); return
                }

                // Phase 1: slot not yet correct - chase pig while we wait for it to register
                if (player.inventory.selectedSlot != orbSlot) {
                    InventoryUtils.holdHotbarSlot(orbSlot)
                    // Keep chasing so pig doesn't escape during the server round-trip
                    faceEntity(pig)
                    if (player.distanceTo(pig) > 1.5) {
                        mc.options.keySprint.setDown(true)
                        mc.options.keyUp.setDown(true)
                    } else {
                        mc.options.keyUp.setDown(false)
                        mc.options.keySprint.setDown(false)
                    }
                    actionTicks = 0   // reset so phase 2 always starts fresh when slot is ready
                    return
                }

                // Slot is correct - stop chasing
                releaseKeys()

                // If pig escaped beyond interaction distance, pathfind back
                if (player.distanceTo(pig) > deployRangeSetting.value + 2.0) {
                    actionTicks = 0
                    lastSprintToPigPos = null   // force fresh repath
                    transition(State.SPRINT_TO_PIG)
                    return
                }

                faceEntity(pig)

                // actionTicks == 0: rotation was just set this tick; pick() ran before us
                // and hitResult is still stale - return and let it update next tick.
                if (actionTicks == 0) {
                    actionTicks = 1
                    return
                }

                // actionTicks 1-8: toggle off->on each tick - 8 attempts to hit the pig entity.
                if (actionTicks <= 8) {
                    // If pig escaped beyond MC's 3-block reach, re-approach before clicking
                    if (player.distanceTo(pig) > 3.0) {
                        mc.options.keyUse.setDown(false)
                        actionTicks = 0
                        lastSprintToPigPos = null
                        transition(State.SPRINT_TO_PIG)
                        return
                    }
                    faceEntity(pig)   // keep tracking in case pig moved
                    mc.options.keyUse.setDown(false)
                    mc.options.keyUse.setDown(true)
                    // Set orbAnchor on the very first use toggle - the "Bring the pig back!"
                    // chat fires within 1-2 ticks of the first right-click, before the loop
                    // finishes.  If orbAnchor isn't set by then, all direction math falls back
                    // to nx=1,nz=0 (arbitrary) and the pig goes the wrong way every time.
                    if (actionTicks == 1) {
                        orbAnchor = Vec3(pig.x, pig.y, pig.z)
                    }
                    actionTicks++
                    return
                }

                mc.options.keyUse.setDown(false)
                actionTicks = 0

                // orbAnchor was already set on actionTicks==1 above; keep it.
                herdPhase      = if (walkModeSetting.value) HerdPhase.SPRINT_BEHIND else HerdPhase.AOTV_BEHIND
                walkTicks      = 0
                ticksSinceRod  = 0
                lastPigOrbDist = Double.MAX_VALUE
                pigStuckTicks  = 0
                pigPrevPos     = null
                stateTimeout   = 200
                actionDelay    = humanDelay(3, 8)
                transition(State.WAIT_FOR_ORB_CHAT)
            }

            // -- WAIT_FOR_ORB_CHAT ---------------------------------------------
            State.WAIT_FOR_ORB_CHAT -> {
                stateTimeout--
                if (stateTimeout <= 0) {
                    ChatUtils.sendMessage("Pig macro: no chat confirmation - proceeding anyway.")
                    actionTicks = 0; actionDelay = humanDelay(5, 12)
                    transition(State.HERDING)
                }
            }

            // -- HERDING -------------------------------------------------------
            State.HERDING -> {
                val pig = targetPig

                if (pig != null && pig.isAlive && pig.isInWater) {
                    ChatUtils.sendMessage("Pig macro: pig in water - blacklisting.")
                    waterBlacklist.add(pig.id)
                    releaseKeys()
                    if (nativeActive()) nativeStop(null)
                    OverlayRenderEngine.clearTag(HERDING_TAG)
                    lastBehindPathPos = null; stuckAngleOffsetDeg = 0.0
                    targetPig = null; orbAnchor = null; pigPrevPos = null
                    lastPigOrbDist = Double.MAX_VALUE; pigStuckTicks = 0
                    actionDelay = humanDelay(8, 15)
                    transition(State.WAITING); return
                }

                if (pig == null || pig.isRemoved || !pig.isAlive) {
                    releaseKeys()
                    if (nativeActive()) nativeStop(null)
                    OverlayRenderEngine.clearTag(HERDING_TAG)
                    lastBehindPathPos = null; pigPrevPos = null
                    lastPigOrbDist = Double.MAX_VALUE; pigStuckTicks = 0
                    actionDelay = humanDelay(5, 12); actionTicks = 0
                    transition(State.COLLECTING)
                    return
                }

                // Direction unit vector from orb -> pig (XZ plane) - resolved early so
                // it can be used in the proximity check AND the behind-pos computation.
                val anchor = orbAnchor
                val odx = if (anchor != null) pig.x - anchor.x else 1.0
                val odz = if (anchor != null) pig.z - anchor.z else 0.0
                val olen = sqrt(odx * odx + odz * odz)
                val nx   = if (olen > 0.1) odx / olen else 1.0
                val nz   = if (olen > 0.1) odz / olen else 0.0
                val D    = behindDistanceSetting.value

                // -- Pig velocity (XZ, from previous tick) -------------------------
                val pigVel = pigPrevPos?.let { Vec3(pig.x - it.x, 0.0, pig.z - it.z) } ?: Vec3.ZERO
                pigPrevPos = Vec3(pig.x, pig.y, pig.z)


                // Abandonment check: pig went the wrong way - give up, find a new one
                if (anchor != null && dist3(pig.x, pig.y, pig.z, anchor) > abandonDistanceSetting.value) {
                    releaseKeys()
                    if (nativeActive()) nativeStop(null)
                    OverlayRenderEngine.clearTag(HERDING_TAG)
                    lastBehindPathPos = null; stuckAngleOffsetDeg = 0.0
                    targetPig = null; orbAnchor = null; pigPrevPos = null
                    lastPigOrbDist = Double.MAX_VALUE; pigStuckTicks = 0
                    actionDelay = humanDelay(5, 15)
                    transition(State.WAITING)
                    return
                }

                // Proximity fallback: pig walked into orb - start collecting
                if (anchor != null && dist3(pig.x, pig.y, pig.z, anchor) <= 2.0) {
                    releaseKeys()
                    if (nativeActive()) nativeStop(null)
                    OverlayRenderEngine.clearTag(HERDING_TAG)
                    lastBehindPathPos = null; stuckAngleOffsetDeg = 0.0; pigPrevPos = null
                    lastPigOrbDist = Double.MAX_VALUE; pigStuckTicks = 0
                    actionDelay = humanDelay(8, 20); actionTicks = 0
                    transition(State.COLLECTING)
                    ChatUtils.sendMessage("Pig macro: pig reached orb, collecting.")
                    return
                }

                // Rotate the push direction by stuckAngleOffsetDeg so the player approaches
                // from a different angle when the pig is repeatedly stuck against a wall.
                val angleRad = Math.toRadians(stuckAngleOffsetDeg)
                val rotCos = cos(angleRad); val rotSin = sin(angleRad)
                val rnx = nx * rotCos - nz * rotSin
                val rnz = nx * rotSin + nz * rotCos

                val behindX = pig.x + rnx * D
                val behindZ = pig.z + rnz * D

                // D blocks behind pig (on the far side from orb) - where player stands to push
                val behindPos = Vec3(behindX, pig.y, behindZ)

                // Scan downward from the pig's Y to find the actual ground surface at
                // the behindPos XZ column - pig may be airborne after being flung.
                val behindGroundY = floorYAt(level, behindX, behindZ, pig.y)
                val behindGround  = Vec3(behindX, behindGroundY, behindZ)

                // -- Overlay: pig->orb path + velocity ray + player guide -----------
                if (anchor != null) {
                    val pigLineColor   = OverlayRenderEngine.Color(0x4C, 0xFF, 0x72, 0xCC)  // green
                    val behindLinColor = OverlayRenderEngine.Color(0xFF, 0xD8, 0x4C, 0xCC)  // yellow
                    val orbMarker      = OverlayRenderEngine.Color(0xFF, 0xD8, 0x4C, 0xAA)  // gold
                    val behindMarker   = OverlayRenderEngine.Color(0x7A, 0x3B, 0xC4, 0x99)  // purple
                    // Pig -> orb (desired path)
                    OverlayRenderEngine.addLine(level, pig.x, pig.y + 0.6, pig.z, anchor.x, anchor.y + 0.6, anchor.z, pigLineColor, 2.4f, 3, HERDING_TAG)
                    // Orb position marker
                    OverlayRenderEngine.addBox(level, anchor.x - 0.4, anchor.y - 0.05, anchor.z - 0.4, anchor.x + 0.4, anchor.y + 0.9, anchor.z + 0.4, orbMarker.withAlpha(0x44), orbMarker, 2.2f, 3, HERDING_TAG)
                    // Player -> behindPos
                    OverlayRenderEngine.addLine(level, player.x, player.y + 0.5, player.z, behindPos.x, behindPos.y + 0.5, behindPos.z, behindLinColor, 1.8f, 3, HERDING_TAG)
                    // BehindPos marker
                    OverlayRenderEngine.addBox(level, behindPos.x - 0.25, behindPos.y, behindPos.z - 0.25, behindPos.x + 0.25, behindPos.y + 0.08, behindPos.z + 0.25, behindMarker, behindMarker.withAlpha(0xCC), 1.6f, 3, HERDING_TAG)
                    // Pig velocity ray - shows where pig is currently heading
                    // Red = heading wrong way (away from/across orb), Green = on track
                    val velLen = sqrt(pigVel.x * pigVel.x + pigVel.z * pigVel.z)
                    if (velLen > 0.02) {
                        // dot(pig_velocity_normalised, pig->orb direction)
                        val pigToOrbDot = (pigVel.x / velLen) * (-nx) + (pigVel.z / velLen) * (-nz)
                        val onTrack = pigToOrbDot > 0.5
                        val rayColor = if (onTrack)
                            OverlayRenderEngine.Color(0x4C, 0xFF, 0x72, 0xEE)   // green
                        else
                            OverlayRenderEngine.Color(0xFF, 0x4C, 0x4C, 0xEE)   // red
                        val rayScale = 8.0 / velLen
                        OverlayRenderEngine.addLine(
                            level,
                            pig.x, pig.y + 0.6, pig.z,
                            pig.x + pigVel.x * rayScale, pig.y + 0.6, pig.z + pigVel.z * rayScale,
                            rayColor, 2.8f, 3, HERDING_TAG
                        )
                    }
                }

                val distToPlayer = player.distanceTo(pig)

                when (herdPhase) {

                    // -- AOTV_BEHIND -------------------------------------------
                    // One-shot AOTV at the start of herding. Aims at the ground
                    // block behind pig (pig.y - 1) so AOTV lands on the ground,
                    // not in the air at pig body height.
                    HerdPhase.AOTV_BEHIND -> {
                        val aotvSlot = InventoryUtils.findItemInHotbar(aotvKeywordSetting.value)
                        if (aotvSlot == -1) {
                            ChatUtils.sendMessage("Pig macro: AOTV not found - sprinting instead.")
                            walkTicks = 0; ticksSinceRod = 0
                            herdPhase = HerdPhase.SPRINT_BEHIND; return
                        }

                        if (player.inventory.selectedSlot != aotvSlot) {
                            InventoryUtils.holdHotbarSlot(aotvSlot)
                            actionDelay = humanDelay(3, 6)
                            return
                        }

                        // Wait for pig to land - if airborne the ground block at behindPos
                        // XZ may be correct but pig could still be bouncing; more importantly
                        // the pig's flee direction is undefined until it's on the ground.
                        // Allow up to 60 ticks (3 s) before proceeding regardless.
                        if (!pig.onGround() && pigLandWaitTicks < 60) {
                            pigLandWaitTicks++
                            return
                        }
                        pigLandWaitTicks = 0

                        // Look at ground behind pig - AOTV teleports to block crosshair hits
                        facePos(behindGround)

                        when (actionTicks) {
                            0 -> {
                                // Rotation just set; pick() ran before us this tick and hasn't
                                // seen the new angle yet. Return so it updates next tick.
                                actionTicks = 1
                            }
                            1 -> {
                                // hit result now points at the ground block behind pig.
                                // Toggle off->on to guarantee a fresh consumeClick() event.
                                mc.options.keyUse.setDown(false)
                                mc.options.keyUse.setDown(true)
                                actionTicks = 2
                            }
                            else -> {
                                mc.options.keyUse.setDown(false)
                                actionTicks    = 0
                                walkTicks      = 0
                                ticksSinceRod  = 0
                                walkGraceTicks = 12
                                actionDelay    = humanDelay(2, 5)
                                herdPhase      = HerdPhase.WALK_PRESSURE
                            }
                        }
                    }

                    // -- WALK_PRESSURE -----------------------------------------
                    // Knockback-based herding: player stands BEHIND the pig (on
                    // the far side from the orb) and punches it with AOTV every
                    // few ticks.  Knockback = direction normalize(pig - player),
                    // which is toward the orb when the player is correctly positioned.
                    // Rod-click also used as a secondary boost.
                    HerdPhase.WALK_PRESSURE -> {
                        mc.options.keyUse.setDown(false)
                        mc.options.keyAttack.setDown(false)

                        // -- Walk mode: pathfind toward pig so it flees toward orb ---
                        if (walkModeSetting.value) {
                            if (anchor != null) {
                                val pigOrbDist = dist3(pig.x, pig.y, pig.z, anchor)
                                if (pigOrbDist < lastPigOrbDist - 0.3) {
                                    lastPigOrbDist = pigOrbDist; pigStuckTicks = 0
                                } else {
                                    pigStuckTicks++
                                }
                                if (pigStuckTicks >= stuckThresholdSetting.value.toInt()) {
                                    stuckAngleOffsetDeg = (stuckAngleOffsetDeg + 45.0) % 360.0
                                    pigStuckTicks = 0; lastPigOrbDist = pigOrbDist
                                    if (nativeActive()) nativeStop(null)
                                    lastBehindPathPos = null
                                    herdPhase = HerdPhase.SPRINT_BEHIND
                                    releaseKeys()
                                    return
                                }
                            }
                            // Navigate toward pig via pathfinder - repath when pig moves > 3 blocks
                            val pigBlock = BlockPos(pig.blockX, pig.blockY, pig.blockZ)
                            val needRepath = lastBehindPathPos == null || lastBehindPathPos!!.distSqr(pigBlock) > 9.0
                            if (!nativeActive() || needRepath) {
                                if (nativeActive()) nativeStop(null)
                                NativePathfinder.setTarget(pigBlock.x + 0.5, pigBlock.y.toDouble(), pigBlock.z + 0.5)
                                lastBehindPathPos = pigBlock
                            }
                            NativePathfinder.tick()?.applyToPlayer()
                            return
                        }

                        // -- Alignment check (knockback mode only) -----------------
                        // behindSide > 0 = player is on the far (orb-opposite) side
                        // of the pig - the side that produces knockback toward orb.
                        if (walkGraceTicks > 0) {
                            walkGraceTicks--
                        } else {
                            val behindSide = (player.x - pig.x) * nx + (player.z - pig.z) * nz
                            if (behindSide < -0.3) {
                                releaseKeys()
                                walkTicks      = 0
                                ticksSinceRod  = 0
                                walkGraceTicks = 0
                                herdPhase      = HerdPhase.SPRINT_BEHIND
                                return
                            }
                        }

                        if (distToPlayer > D + 3.0) {
                            // Too far - reposition
                            releaseKeys()
                            walkTicks     = 0
                            ticksSinceRod = 0
                            actionDelay   = humanDelay(1, 3)
                            herdPhase     = HerdPhase.SPRINT_BEHIND
                            return
                        }

                        // Track stuck for angle-rotation escape
                        if (anchor != null) {
                            val pigOrbDist = dist3(pig.x, pig.y, pig.z, anchor)
                            if (pigOrbDist < lastPigOrbDist - 0.3) {
                                lastPigOrbDist = pigOrbDist; pigStuckTicks = 0
                            } else {
                                pigStuckTicks++
                            }
                            if (pigStuckTicks >= stuckThresholdSetting.value.toInt()) {
                                stuckAngleOffsetDeg = (stuckAngleOffsetDeg + 45.0) % 360.0
                                pigStuckTicks  = 0
                                lastPigOrbDist = pigOrbDist
                                walkGraceTicks = 0
                                herdPhase      = HerdPhase.SPRINT_BEHIND  // reapproach from new angle
                                releaseKeys()
                                return
                            }
                        }

                        // Always face pig
                        faceEntity(pig)

                        if (distToPlayer > 2.5) {
                            // Sprint toward the pig to close the gap
                            mc.options.keySprint.setDown(true)
                            mc.options.keyUp.setDown(true)
                        } else {
                            // Within punch range - stop moving and land a hit
                            mc.options.keyUp.setDown(false)
                            mc.options.keySprint.setDown(false)

                            walkTicks++
                            if (walkTicks >= aotvHoldTicksSetting.value.toInt()) {
                                walkTicks = 0
                                // Equip AOTV for maximum knockback, then punch
                                val aotvSlotKb = InventoryUtils.findItemInHotbar(aotvKeywordSetting.value)
                                if (aotvSlotKb != -1) {
                                    if (player.inventory.selectedSlot != aotvSlotKb)
                                        InventoryUtils.holdHotbarSlot(aotvSlotKb)
                                    mc.options.keyAttack.setDown(true)
                                    actionDelay = 2
                                    return
                                }
                                // Fallback: rod right-click if no AOTV
                                val rodSlotKb = InventoryUtils.findItemInHotbar(shinyRodKeywordSetting.value)
                                if (rodSlotKb != -1) {
                                    if (player.inventory.selectedSlot != rodSlotKb)
                                        InventoryUtils.holdHotbarSlot(rodSlotKb)
                                    mc.options.keyUse.setDown(true)
                                    actionDelay = 2
                                    return
                                }
                            }
                        }
                    }

                    // -- SPRINT_BEHIND -----------------------------------------
                    // Pathfind to behind-pig position so the player routes around
                    // walls. Repath whenever pig moves > 1.5 blocks.
                    HerdPhase.SPRINT_BEHIND -> {
                        val distToTarget = dist3(player.x, player.y, player.z, behindPos)

                        if (distToTarget <= 2.0) {
                            if (nativeActive()) nativeStop(null)
                            lastBehindPathPos = null
                            releaseKeys()
                            walkTicks      = 0
                            ticksSinceRod  = 0
                            walkGraceTicks = 15
                            actionDelay    = humanDelay(1, 2)
                            herdPhase      = HerdPhase.WALK_PRESSURE
                        } else {
                            val behindBlock = BlockPos(
                                behindPos.x.toInt(), behindPos.y.toInt(), behindPos.z.toInt()
                            )
                            val last = lastBehindPathPos
                            // Cancel an active path only if the behind-target moved > 4 blocks
                            val needRepath = last == null || last.distSqr(behindBlock) > 16.0
                            if (!nativeActive() || needRepath) {
                                if (nativeActive()) nativeStop(null)
                                NativePathfinder.setTarget(behindBlock.x + 0.5, behindBlock.y.toDouble(), behindBlock.z + 0.5)
                                lastBehindPathPos = behindBlock
                            }
                            NativePathfinder.tick()?.applyToPlayer()
                        }
                    }
                }
            }

            // -- COLLECTING ----------------------------------------------------
            // Pathfind to orb anchor (static position - pathfinder works here).
            // Hold right-click for 4 ticks to collect.
            State.COLLECTING -> {
                val anchor = orbAnchor ?: run { transition(State.IDLE); return }
                val distToOrb = dist3(player.x, player.y, player.z, anchor)

                if (distToOrb > collectRangeSetting.value) {
                    // Always restart pathfinder when not active - handles the case where
                    // pathfinder finished but player is still too far (e.g. terrain blocked exact tile)
                    if (!nativeActive()) {
                        NativePathfinder.setTarget(anchor.x, anchor.y, anchor.z)
                    }
                    NativePathfinder.tick()?.applyToPlayer()
                    return
                }

                // Arrived - check we're truly within MC's interaction reach before clicking
                if (distToOrb > 3.0) {
                    if (nativeActive()) nativeStop(null)
                    NativePathfinder.setTarget(anchor.x, anchor.y, anchor.z)
                    NativePathfinder.tick()?.applyToPlayer()
                    return
                }

                if (nativeActive()) nativeStop(null)
                releaseKeys()

                // Find the orb entity (ArmorStand or any entity near the anchor).
                // Facing the entity directly gives a reliable EntityHitResult so
                // the right-click registers as a collect, not a block interaction.
                val orbEntity = level.entitiesForRendering()
                    .asSequence()
                    .filter { it != player }
                    .minByOrNull { dist3(it.x, it.y, it.z, anchor) }
                    ?.takeIf { dist3(it.x, it.y, it.z, anchor) <= 5.0 }

                if (orbEntity != null) faceEntity(orbEntity) else facePos(anchor)

                // actionTicks == 0: rotation just set, pick() hasn't updated hit result yet.
                if (actionTicks == 0) {
                    actionTicks = 1
                    return
                }

                // actionTicks 1-8: toggle off->on each tick - 8 chances to hit the orb.
                if (actionTicks <= 8) {
                    if (orbEntity != null) faceEntity(orbEntity) else facePos(anchor)
                    mc.options.keyUse.setDown(false)
                    mc.options.keyUse.setDown(true)
                    actionTicks++
                    return
                }

                mc.options.keyUse.setDown(false)
                actionTicks = 0
                captureCount++
                ChatUtils.sendMessage("Pig macro: collected orb - capture #$captureCount!")
                targetPig = null; orbAnchor = null

                if (autoLoopSetting.value) {
                    actionDelay = waitBetweenSetting.value.toInt() + humanDelay(-5, 10)
                    transition(State.WAITING)
                } else {
                    stop()
                }
            }

            // -- WAITING -------------------------------------------------------
            State.WAITING -> transition(State.IDLE)
        }

        statusSetting.value = state.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
    }

    // -- Chat ------------------------------------------------------------------

    @SubscribeEvent
    fun onChat(event: ChatEvent.Receive) {
        if (!enabledSetting.value) return
        val msg = ChatFormatting.stripFormatting(event.message ?: return)
            ?.lowercase()?.trim() ?: return

        // Schedule on main game thread - ChatEvent fires on Netty IO thread
        mc.execute {
            if (!enabledSetting.value) return@execute

            // "Oink! Bring the pig back to the Shiny Orb!" - pig flung, AOTV behind it
            if (msg.contains("bring the pig back to the shiny orb")) {
                if (state == State.WAIT_FOR_ORB_CHAT || state == State.DEPLOYING_ORB) {
                    // Guarantee orbAnchor is set - the chat fires within 1-2 ticks of the
                    // first right-click, potentially before DEPLOYING_ORB's loop sets it.
                    // Use pig position (orb lands near pig), fall back to player position.
                    if (orbAnchor == null) {
                        val pig = targetPig
                        orbAnchor = if (pig != null && pig.isAlive) Vec3(pig.x, pig.y, pig.z)
                                    else mc.player?.let { Vec3(it.x, it.y, it.z) }
                    }
                    val delay = humanDelay(0, 40)
                    actionTicks   = 0; walkTicks = 0; ticksSinceRod = 0
                    lastBehindPathPos = null; stuckAngleOffsetDeg = 0.0
                    herdPhase     = if (walkModeSetting.value) HerdPhase.SPRINT_BEHIND else HerdPhase.AOTV_BEHIND
                    actionDelay   = delay
                    transition(State.HERDING)
                }
            }

            // "SHINY! The orb is charged! Click on it for loot!"
            if (msg.contains("the orb is charged") || msg.contains("click on it for loot")) {
                if (state == State.HERDING || state == State.WAIT_FOR_ORB_CHAT) {
                    releaseKeys()
                    if (nativeActive()) nativeStop(null)
                    OverlayRenderEngine.clearTag(HERDING_TAG)
                    targetPig          = null
                    lastPigOrbDist     = Double.MAX_VALUE
                    pigStuckTicks      = 0
                    pigPrevPos         = null
                    actionTicks        = 0
                    actionDelay        = humanDelay(0, 40)
                    transition(State.COLLECTING)
                }
            }

            // Orb expired
            if (msg.contains("shiny orb") && msg.contains("expired")) {
                releaseKeys()
                if (nativeActive()) nativeStop(null)
                OverlayRenderEngine.clearTag(HERDING_TAG)
                targetPig = null; orbAnchor = null; pigPrevPos = null
                lastPigOrbDist = Double.MAX_VALUE; pigStuckTicks = 0
                lastBehindPathPos = null; stuckAngleOffsetDeg = 0.0
                herdPhase = if (walkModeSetting.value) HerdPhase.SPRINT_BEHIND else HerdPhase.AOTV_BEHIND
                walkTicks = 0; ticksSinceRod = 0; actionTicks = 0
                actionDelay = humanDelay(8, 20)
                transition(State.WAITING)
            }
        }
    }

    // -- Helpers ---------------------------------------------------------------

    /**
     * Scans downward from [fromY] at the given XZ column and returns the Y
     * coordinate of the top surface of the first non-air block found.
     * Falls back to [fromY] if nothing is found within 40 blocks.
     */
    private fun floorYAt(
        level: net.minecraft.world.level.Level,
        x: Double, z: Double, fromY: Double
    ): Double {
        val bx     = x.toInt()
        val bz     = z.toInt()
        val startY = fromY.toInt().coerceIn(-64, 319)
        for (y in startY downTo maxOf(startY - 40, -64)) {
            if (!level.getBlockState(BlockPos(bx, y, bz)).isAir) return (y + 1).toDouble()
        }
        return fromY
    }

    private fun humanDelay(min: Int, max: Int): Int = Random.nextInt(min, max + 1)

    private fun livePig(): LivingEntity? = targetPig?.takeIf { !it.isRemoved && it.isAlive }

    private fun isPig(entity: LivingEntity): Boolean =
        entity.type.descriptionId == "entity.minecraft.pig"

    private fun transition(newState: State) { state = newState }

    private fun faceEntity(entity: Entity) {
        val player = mc.player ?: return
        val rot    = AngleUtils.getRotation(entity)
        player.setYRot(rot.yaw)
        player.setXRot(rot.pitch)
        player.yHeadRot = rot.yaw
    }

    private fun facePos(target: Vec3) {
        val player = mc.player ?: return
        val dx     = target.x - player.x
        val dy     = target.y - (player.y + player.eyeHeight)
        val dz     = target.z - player.z
        val hDist  = sqrt(dx * dx + dz * dz)
        player.setYRot(Math.toDegrees(atan2(-dx, dz)).toFloat())
        player.setXRot(Math.toDegrees(-atan2(dy, hDist)).toFloat())
        player.yHeadRot = player.yRot
    }

    private fun dist3(x: Double, y: Double, z: Double, v: Vec3): Double {
        val dx = x - v.x; val dy = y - v.y; val dz = z - v.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun start() {
        sessionStartMs = System.currentTimeMillis()
        captureCount   = 0
        targetPig      = null; orbAnchor = null
        waterBlacklist.clear()
        herdPhase      = HerdPhase.AOTV_BEHIND
        walkTicks      = 0; ticksSinceRod = 0; actionTicks = 0
        actionDelay    = 0; stateTimeout = 0
        lastPigOrbDist = Double.MAX_VALUE; pigStuckTicks = 0
        lastBehindPathPos = null; lastSprintToPigPos = null; stuckAngleOffsetDeg = 0.0
        pigPrevPos        = null
        walkGraceTicks    = 0
        pigLandWaitTicks  = 0
        state             = State.IDLE
        statusSetting.value = "Idle"
        ChatUtils.sendMessage("Shiny pig macro started.")
    }

    private fun nativeActive(): Boolean =
        NativePathfinder.status.let { it != PathStatus.IDLE && it != PathStatus.ARRIVED && it != PathStatus.FAILED }

    private fun nativeStop(reason: String?) {
        NativePathfinder.stop()
        MovementManager.setMovementLock(false)
        if (reason != null) ChatUtils.sendMessage(reason)
    }

    private fun stop() {
        releaseKeys()
        if (nativeActive()) nativeStop(null)
        OverlayRenderEngine.clearTag(HERDING_TAG)
        state          = State.IDLE
        targetPig      = null; orbAnchor = null
        waterBlacklist.clear()
        herdPhase      = HerdPhase.AOTV_BEHIND
        walkTicks      = 0; ticksSinceRod = 0; actionTicks = 0
        actionDelay    = 0; stateTimeout = 0
        lastPigOrbDist = Double.MAX_VALUE; pigStuckTicks = 0
        lastBehindPathPos = null; lastSprintToPigPos = null; stuckAngleOffsetDeg = 0.0
        pigPrevPos       = null
        walkGraceTicks   = 0
        pigLandWaitTicks = 0
        sessionStartMs   = 0L
        statusSetting.value = "Idle"
    }

    private fun releaseKeys() {
        mc.options.keyUp.setDown(false)
        mc.options.keyLeft.setDown(false)
        mc.options.keyRight.setDown(false)
        mc.options.keyDown.setDown(false)
        mc.options.keySprint.setDown(false)
        mc.options.keyJump.setDown(false)
        mc.options.keyUse.setDown(false)
        mc.options.keyAttack.setDown(false)
    }

    private fun formatDuration(ms: Long): String {
        val s = (ms / 1000L).coerceAtLeast(0L)
        val h = s / 3600L; val m = (s % 3600L) / 60L; val sec = s % 60L
        return if (h > 0) "%02d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }
}
