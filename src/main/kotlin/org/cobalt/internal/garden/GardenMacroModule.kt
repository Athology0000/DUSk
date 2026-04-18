package org.cobalt.internal.garden

import net.minecraft.client.Minecraft
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.ChatEvent
import org.cobalt.api.event.impl.client.TickEvent
import org.cobalt.api.hud.HudAnchor
import org.cobalt.api.hud.hudElement
import org.cobalt.api.module.Module
import org.cobalt.api.module.setting.inGroup
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.module.setting.impl.SliderSetting
import org.cobalt.api.module.setting.impl.TextSetting
import org.cobalt.internal.garden.managers.BookCombineManager
import org.cobalt.internal.garden.managers.BoosterCookieManager
import org.cobalt.internal.garden.managers.CropFeverManager
import org.cobalt.internal.garden.managers.DynamicRestManager
import org.cobalt.internal.garden.managers.EquipmentManager
import org.cobalt.internal.garden.managers.FlightManager
import org.cobalt.internal.garden.managers.GearManager
import org.cobalt.internal.garden.managers.GeorgeManager
import org.cobalt.internal.garden.managers.JunkManager
import org.cobalt.internal.garden.managers.PestAotvManager
import org.cobalt.internal.garden.managers.PestBonusManager
import org.cobalt.internal.garden.managers.PestCleaningSequencer
import org.cobalt.internal.garden.managers.PestManager
import org.cobalt.internal.garden.managers.PestPrepSwapManager
import org.cobalt.internal.garden.managers.PestReturnManager
import org.cobalt.internal.garden.managers.PetXpTracker
import org.cobalt.internal.garden.managers.ProfitManager
import org.cobalt.internal.garden.managers.RecoveryManager
import org.cobalt.internal.garden.managers.RestartManager
import org.cobalt.internal.garden.managers.RodManager
import org.cobalt.internal.garden.managers.VisitorManager
import org.cobalt.internal.garden.managers.WardrobeManager

object GardenMacroModule : Module("Garden Macro") {

    private val mc = Minecraft.getInstance()
    val isActive: Boolean get() = enabledSetting.value

    // -- State -----------------------------------------------------------------
    @Volatile var state = GardenState.OFF
        private set
    @Volatile var sessionStartTime = System.currentTimeMillis()
    @Volatile var autosellingManager: String? = null
    @Volatile private var wasConnected = true
    @Volatile private var autoRestartQueued = false
    /** System.currentTimeMillis() deadline before which tickFarming skips all automated triggers. */
    @Volatile private var startupGraceUntilMs = 0L

    fun setState(newState: GardenState) {
        state = newState  // @Volatile - safe from any thread, no mc.execute needed
    }

    // -- Settings --------------------------------------------------------------

    // General
    private val enabledSetting = CheckboxSetting("Enabled", "Run the garden macro.", false)

    // Scripts
    private val farmScriptSetting    = TextSetting("Farm Script",    "Taunahi script name for farming (e.g. netherwart:1).",  "netherwart:1").inGroup("Scripts")
    private val pestScriptSetting    = TextSetting("Pest Script",    "Taunahi script name for pest cleaning.",                "misc:pestCleaner").inGroup("Scripts")
    private val returnScriptSetting  = TextSetting("Return Script",  "Taunahi script name to run after pest clean.",          "misc:visitor").inGroup("Scripts")
    private val visitorScriptSetting = TextSetting("Visitor Script", "Taunahi script name for visitors.",                    "misc:visitor").inGroup("Scripts")
    private val autoRestartStoppedScriptSetting = CheckboxSetting("Auto Restart", "Restart the active Taunahi script if it stops because the equipment menu opened.", false).inGroup("Scripts")

    // Pest
    private val pestThresholdSetting      = SliderSetting("Pest Threshold",       "Alive pest count to trigger cleaning.",           4.0,  1.0, 8.0,   step = 1.0).inGroup("Pest")
    private val triggerPestOnChatSetting  = CheckboxSetting("Trigger Pest On Chat","Trigger pest clean from chat message.",           true).inGroup("Pest")
    private val pestChatTriggerDelaySetting = SliderSetting("Pest Chat Delay",    "Ms to wait after chat trigger before cleaning.",   0.0,  0.0, 5000.0).inGroup("Pest")
    private val aotvEnabledSetting        = CheckboxSetting("AOTV to Roof",        "Teleport to roof before pest clean.",              false).inGroup("Pest")
    private val roofPitchSetting          = SliderSetting("Roof Pitch",            "Camera pitch for roof teleport.",                  -80.0, -90.0, 90.0).inGroup("Pest")
    private val aotvRoofPitchHumanizationSetting = SliderSetting("Roof Pitch Humanization", "Random pitch offset for AOTV.", 3.0, 0.0, 10.0, step = 1.0).inGroup("Pest")
    private val prepSwapSetting           = CheckboxSetting("Prep Swap",            "Swap gear before threshold hit.",                  false).inGroup("Pest")
    private val aotvPlotsSetting          = TextSetting("AOTV Plots",               "Comma-separated plot names.",                      "").inGroup("Pest")
    private val breakBlocksBeforeAotvSetting = CheckboxSetting("Break Blocks Before AOTV", "Break blocks before AOTV teleport.", false).inGroup("Pest")
    private val delayPestForCropFeverSetting = CheckboxSetting("Delay Pest For Crop Fever", "Wait for crop fever to expire before pest.", false).inGroup("Pest")
    private val enablePlotTpRewarpSetting = CheckboxSetting("Enable Plot TP Rewarp", "Re-warp to garden after plot teleport.", false).inGroup("Pest")
    private val holdWUntilWallSetting     = CheckboxSetting("Hold W Until Wall",    "Hold forward key until hitting a wall.",           false).inGroup("Pest")
    private val plotTpNumberSetting       = TextSetting("Plot TP Number",            "Plot number to teleport to.",                      "0").inGroup("Pest")

    // Visitor
    private val autoVisitorSetting    = CheckboxSetting("Auto Visitor",    "Handle visitor offers automatically.", false).inGroup("Visitor")
    private val visitorThresholdSetting = SliderSetting("Visitor Threshold", "Time in seconds before handling visitor.", 5.0, 1.0, 30.0, step = 1.0).inGroup("Visitor")

    // Wardrobe
    private val autoWardrobeSetting        = CheckboxSetting("Auto Wardrobe",        "Master toggle for automatic wardrobe swaps.",  false).inGroup("Wardrobe")
    private val autoWardrobePestSetting    = CheckboxSetting("Auto Wardrobe (Pest)",    "Swap wardrobe slot when going to pest.",     true).inGroup("Wardrobe")
    private val autoWardrobeVisitorSetting = CheckboxSetting("Auto Wardrobe (Visitor)", "Swap wardrobe slot for visitors.",          false).inGroup("Wardrobe")
    private val farmingSlotSetting         = SliderSetting("Farming Slot",   "Wardrobe slot for farming.",        1.0, 1.0, 18.0, step = 1.0).inGroup("Wardrobe")
    private val pestSlotSetting            = SliderSetting("Pest Slot",      "Wardrobe slot for pest cleaning.",  2.0, 1.0, 18.0, step = 1.0).inGroup("Wardrobe")
    private val visitorSlotSetting         = SliderSetting("Visitor Slot",   "Wardrobe slot for visitors.",       3.0, 1.0, 18.0, step = 1.0).inGroup("Wardrobe")

    // Equipment (Skyblock equipment slots: bracelet, cloak, belt, necklace)
    private val autoEquipmentSetting    = CheckboxSetting("Auto Equipment",     "Automatically swap Skyblock equipment items.", true).inGroup("Equipment")
    private val farmingEquipmentSetting = TextSetting("Farming Equipment", "Comma-separated equipment keywords for farming.",  "lotus, blossom").inGroup("Equipment")
    private val pestEquipmentSetting    = TextSetting("Pest Equipment",    "Comma-separated equipment keywords for pest.",     "pesthunter, pest vest").inGroup("Equipment")
    private val visitorEquipmentSetting = TextSetting("Visitor Equipment", "Equipment preset name for visitors.", "").inGroup("Equipment")
    private val swapDelaySetting        = SliderSetting("Swap Delay",      "Ms between equipment swaps.",         300.0, 0.0, 2000.0).inGroup("Equipment")

    // Rod
    private val autoRodPestCdSetting       = CheckboxSetting("Auto Rod (Pest CD)",       "Use rod when pest cooldown is active.",  false).inGroup("Rod")
    private val autoRodPestSpawnSetting    = CheckboxSetting("Auto Rod (Pest Spawn)",    "Use rod when pest spawns.",              false).inGroup("Rod")
    private val autoRodReturnToFarmSetting = CheckboxSetting("Auto Rod (Return To Farm)","Use rod when returning to farm.",        false).inGroup("Rod")
    private val rodSwapDelaySetting        = SliderSetting("Rod Swap Delay", "Ms to wait between rod swaps.", 100.0, 0.0, 2000.0).inGroup("Rod")

    // Economy
    private val autoGeorgeSellSetting      = CheckboxSetting("Auto George Sell",    "Automatically sell pets to George.",        false).inGroup("Economy")
    private val georgeRaritySetting        = TextSetting("George Rarity",            "Rarities to sell (LEGENDARY,MYTHIC).",      "LEGENDARY").inGroup("Economy")
    private val georgeSellThresholdSetting = SliderSetting("George Sell Threshold", "Rarity level (1-6) to sell at.",             3.0, 1.0, 6.0, step = 1.0).inGroup("Economy")
    private val autoBookCombineSetting     = CheckboxSetting("Auto Book Combine",   "Automatically combine enchanted books.",     false).inGroup("Economy")
    private val bookLevelSetting           = SliderSetting("Book Level",             "Enchant level to combine books at.",         5.0, 1.0, 10.0, step = 1.0).inGroup("Economy")
    private val bookThresholdSetting       = SliderSetting("Book Threshold",         "Number of books before triggering combine.", 7.0, 1.0, 20.0, step = 1.0).inGroup("Economy")
    private val bookCombineDelaySetting    = SliderSetting("Book Combine Delay",     "Ms to wait between book combine actions.",  300.0, 0.0, 2000.0).inGroup("Economy")
    private val alwaysActiveCombineSetting = CheckboxSetting("Always Active Combine","Combine books even when not farming.",      false).inGroup("Economy")
    private val autoDropJunkSetting        = CheckboxSetting("Auto Drop Junk",       "Automatically drop junk items.",            false).inGroup("Economy")
    private val junkItemsSetting           = TextSetting("Junk Items",               "Comma-separated item names to drop.",       "Fruit Bowl,Farming Exp Boost,Sunder VI").inGroup("Economy")
    private val junkThresholdSetting       = SliderSetting("Junk Threshold",         "Stack size before dropping junk.",          3.0, 1.0, 64.0, step = 1.0).inGroup("Economy")
    private val junkItemDropDelaySetting   = SliderSetting("Junk Drop Delay",        "Ms to wait between dropping items.",        300.0, 0.0, 2000.0).inGroup("Economy")
    private val autoBoosterCookieSetting   = CheckboxSetting("Auto Booster Cookie",  "Automatically use booster cookies.",        true).inGroup("Economy")
    private val boosterCookieItemsSetting  = TextSetting("Cookie Items",             "Comma-separated items to use cookies on.",  "Atmospheric Filter,Squeaky Toy,Beady Eyes,Clipped Wings,Overclocker,Mantid Claw,Flowering Bouquet,Bookworm,Chirping Stereo,Firefly,Capsule,Vinyl").inGroup("Economy")
    private val cookieItemSetting          = TextSetting("Cookie Item",              "Single item to apply booster cookie to.",   "").inGroup("Economy")
    private val autoStashManagerSetting    = CheckboxSetting("Auto Stash Manager",   "Automatically manage stash storage.",       false).inGroup("Economy")
    private val bazaarRefreshSetting       = SliderSetting("Bazaar Refresh",         "Seconds between Bazaar price updates.",     120.0, 30.0, 600.0).inGroup("Economy")

    // Rest
    private val workDurationSetting            = SliderSetting("Work Duration",              "Minutes to farm before resting.",      60.0, 1.0, 240.0).inGroup("Rest")
    private val workOffsetSetting              = SliderSetting("Work Offset",                "Random offset for work duration.",      5.0, 0.0, 30.0).inGroup("Rest")
    private val breakDurationSetting           = SliderSetting("Break Duration",             "Minutes to rest before resuming.",     10.0, 1.0, 60.0).inGroup("Rest")
    private val breakOffsetSetting             = SliderSetting("Break Offset",               "Random offset for break duration.",     2.0, 0.0, 15.0).inGroup("Rest")
    private val persistSessionTimerSetting     = CheckboxSetting("Persist Session Timer",    "Keep session timer across restarts.",  true).inGroup("Rest")
    private val autoResumeAfterDynamicRestSetting = CheckboxSetting("Auto Resume After Rest","Automatically resume after rest ends.", true).inGroup("Rest")

    // Advanced
    private val hideFilteredChatSetting           = CheckboxSetting("Hide Chat Spam",          "Filter bot-related chat messages.",     false).inGroup("Advanced")
    private val guiOnlyInGardenSetting            = CheckboxSetting("GUI Only In Garden",       "Only show GUI when in the garden.",     true).inGroup("Advanced")
    private val autoRecoverUnexpectedDisconnectSetting = CheckboxSetting("Auto Recover Disconnect", "Reconnect after unexpected disconnects.", true).inGroup("Advanced")
    private val maxRecoverySetting                = SliderSetting("Max Recovery",               "Max auto-reconnect attempts.",          15.0, 1.0, 30.0, step = 1.0).inGroup("Advanced")
    private val reconnectMinSetting               = SliderSetting("Reconnect Min",              "Min seconds before reconnecting.",      30.0, 5.0, 120.0).inGroup("Advanced")
    private val reconnectMaxSetting               = SliderSetting("Reconnect Max",              "Max seconds before reconnecting.",      60.0, 5.0, 120.0).inGroup("Advanced")
    private val rotationTimeSetting               = SliderSetting("Rotation Time",              "Ms for camera rotation movements.",    500.0, 50.0, 2000.0).inGroup("Advanced")
    private val guiClickDelaySetting              = SliderSetting("GUI Click Delay",            "Ms to wait between GUI clicks.",       500.0, 50.0, 2000.0).inGroup("Advanced")
    private val additionalRandomDelaySetting      = SliderSetting("Additional Random Delay",    "Extra random ms added to GUI actions.", 0.0, 0.0, 1000.0).inGroup("Advanced")
    private val gardenWarpDelaySetting            = SliderSetting("Garden Warp Delay",          "Ms to wait after warping to garden.",  3000.0, 0.0, 10000.0).inGroup("Advanced")
    private val unflyModeSetting                  = TextSetting("Unfly Mode",                   "DOUBLE_TAP_SPACE or SNEAK.",            "DOUBLE_TAP_SPACE").inGroup("Advanced")
    private val petTrackerListSetting             = TextSetting("Pet Tracker List",             "Pets to track (ID:Name:Level:Rarity).", "PET_ROSE_DRAGON:Rose Dragon:200:LEGENDARY").inGroup("Advanced")

    // -- HUD -------------------------------------------------------------------
    private val hudWidth = 200f

    private val gardenHudElement = hudElement("garden-macro-hud", "Garden Macro HUD", "Macro status and profit tracking") {
        anchor  = HudAnchor.TOP_LEFT
        offsetX = 10f
        offsetY = 10f

        val showProfitSetting = setting(CheckboxSetting("Show Profit", "Show profit section.", true))
        val showRestSetting   = setting(CheckboxSetting("Show Rest",   "Show rest countdown.", true))

        fun computeHeight(): Float {
            var h = 48f
            if (showRestSetting.value) h += 36f
            if (showProfitSetting.value) h += 62f
            return h
        }

        width  { hudWidth }
        height { computeHeight() }

        render { x, y, _ ->
            if (!enabledSetting.value && state == GardenState.OFF) return@render
            GardenHud.render(x, y, hudWidth, computeHeight(), state, showProfitSetting.value, showRestSetting.value, sessionStartTime)
        }
    }

    // -- init ------------------------------------------------------------------
    init {
        addSetting(
            enabledSetting,
            // Scripts
            farmScriptSetting, pestScriptSetting, returnScriptSetting, visitorScriptSetting, autoRestartStoppedScriptSetting,
            // Pest
            pestThresholdSetting, triggerPestOnChatSetting, pestChatTriggerDelaySetting,
            aotvEnabledSetting, roofPitchSetting, aotvRoofPitchHumanizationSetting,
            prepSwapSetting, aotvPlotsSetting, breakBlocksBeforeAotvSetting, delayPestForCropFeverSetting,
            enablePlotTpRewarpSetting, holdWUntilWallSetting, plotTpNumberSetting,
            // Visitor
            autoVisitorSetting, visitorThresholdSetting,
            // Wardrobe
            autoWardrobeSetting, autoWardrobePestSetting, autoWardrobeVisitorSetting,
            farmingSlotSetting, pestSlotSetting, visitorSlotSetting,
            // Equipment
            autoEquipmentSetting, farmingEquipmentSetting, pestEquipmentSetting, visitorEquipmentSetting, swapDelaySetting,
            // Rod
            autoRodPestCdSetting, autoRodPestSpawnSetting, autoRodReturnToFarmSetting, rodSwapDelaySetting,
            // Economy
            autoGeorgeSellSetting, georgeRaritySetting, georgeSellThresholdSetting,
            autoBookCombineSetting, bookLevelSetting, bookThresholdSetting, bookCombineDelaySetting, alwaysActiveCombineSetting,
            autoDropJunkSetting, junkItemsSetting, junkThresholdSetting, junkItemDropDelaySetting,
            autoBoosterCookieSetting, boosterCookieItemsSetting, cookieItemSetting,
            autoStashManagerSetting, bazaarRefreshSetting,
            // Rest
            workDurationSetting, workOffsetSetting, breakDurationSetting, breakOffsetSetting,
            persistSessionTimerSetting, autoResumeAfterDynamicRestSetting,
            // Advanced
            hideFilteredChatSetting, guiOnlyInGardenSetting,
            autoRecoverUnexpectedDisconnectSetting, maxRecoverySetting, reconnectMinSetting, reconnectMaxSetting,
            rotationTimeSetting, guiClickDelaySetting, additionalRandomDelaySetting, gardenWarpDelaySetting,
            unflyModeSetting, petTrackerListSetting
        )

        EventBus.register(this)
    }

    // -- GardenConfig sync -----------------------------------------------------
    private fun syncConfig() {
        // Scripts
        GardenConfig.farmScript   = farmScriptSetting.value
        GardenConfig.pestScript   = pestScriptSetting.value
        GardenConfig.returnScript = returnScriptSetting.value
        GardenConfig.visitorScript = visitorScriptSetting.value
        GardenConfig.autoRestartStoppedScript = autoRestartStoppedScriptSetting.value

        // Pest
        GardenConfig.pestThreshold            = pestThresholdSetting.value.toInt()
        GardenConfig.triggerPestOnChat        = triggerPestOnChatSetting.value
        GardenConfig.pestChatTriggerDelayMs   = pestChatTriggerDelaySetting.value.toLong()
        GardenConfig.aotvEnabled              = aotvEnabledSetting.value
        GardenConfig.roofPitch                = roofPitchSetting.value
        GardenConfig.aotvRoofPitchHumanization = aotvRoofPitchHumanizationSetting.value.toInt()
        GardenConfig.breakBlocksBeforeAotv    = breakBlocksBeforeAotvSetting.value
        GardenConfig.delayPestForCropFever    = delayPestForCropFeverSetting.value
        GardenConfig.enablePlotTpRewarp       = enablePlotTpRewarpSetting.value
        GardenConfig.holdWUntilWall           = holdWUntilWallSetting.value
        GardenConfig.plotTpNumber             = plotTpNumberSetting.value

        // Visitor
        GardenConfig.visitorThreshold = visitorThresholdSetting.value.toInt()

        // Wardrobe
        GardenConfig.autoWardrobeEnabled  = autoWardrobeSetting.value
        GardenConfig.autoWardrobePest     = autoWardrobePestSetting.value
        GardenConfig.autoWardrobeVisitor  = autoWardrobeVisitorSetting.value
        GardenConfig.farmingWardrobeSlot  = farmingSlotSetting.value.toInt()
        GardenConfig.pestWardrobeSlot     = pestSlotSetting.value.toInt()
        GardenConfig.visitorWardrobeSlot  = visitorSlotSetting.value.toInt()

        // Equipment
        GardenConfig.autoEquipment    = autoEquipmentSetting.value
        GardenConfig.farmingEquipment = farmingEquipmentSetting.value
        GardenConfig.pestEquipment    = pestEquipmentSetting.value
        GardenConfig.visitorEquipment = visitorEquipmentSetting.value
        GardenConfig.swapDelayMs      = swapDelaySetting.value.toLong()

        // Rod
        GardenConfig.autoRodPestCd        = autoRodPestCdSetting.value
        GardenConfig.autoRodPestSpawn     = autoRodPestSpawnSetting.value
        GardenConfig.autoRodReturnToFarm  = autoRodReturnToFarmSetting.value
        GardenConfig.rodSwapDelayMs       = rodSwapDelaySetting.value.toLong()

        // Economy
        GardenConfig.autoGeorgeSell       = autoGeorgeSellSetting.value
        GardenConfig.georgeRarity         = georgeRaritySetting.value
        GardenConfig.georgeSellThreshold  = georgeSellThresholdSetting.value.toInt()
        GardenConfig.autoBookCombine      = autoBookCombineSetting.value
        GardenConfig.bookCombineLevel     = bookLevelSetting.value.toInt()
        GardenConfig.bookThreshold        = bookThresholdSetting.value.toInt()
        GardenConfig.bookCombineDelayMs   = bookCombineDelaySetting.value.toLong()
        GardenConfig.alwaysActiveCombine  = alwaysActiveCombineSetting.value
        GardenConfig.autoDropJunk         = autoDropJunkSetting.value
        GardenConfig.junkItems            = junkItemsSetting.value
        GardenConfig.junkThreshold        = junkThresholdSetting.value.toInt()
        GardenConfig.junkItemDropDelayMs  = junkItemDropDelaySetting.value.toLong()
        GardenConfig.autoBoosterCookie    = autoBoosterCookieSetting.value
        GardenConfig.boosterCookieItems   = boosterCookieItemsSetting.value
        GardenConfig.cookieItem           = cookieItemSetting.value
        GardenConfig.autoStashManager     = autoStashManagerSetting.value
        GardenConfig.bazaarRefreshSecs    = bazaarRefreshSetting.value.toLong()

        // Rest
        GardenConfig.workDurationMins            = workDurationSetting.value.toLong()
        GardenConfig.workOffsetMins              = workOffsetSetting.value.toLong()
        GardenConfig.breakDurationMins           = breakDurationSetting.value.toLong()
        GardenConfig.breakOffsetMins             = breakOffsetSetting.value.toLong()
        GardenConfig.persistSessionTimer         = persistSessionTimerSetting.value
        GardenConfig.autoResumeAfterDynamicRest  = autoResumeAfterDynamicRestSetting.value

        // Recovery
        GardenConfig.autoRecoverUnexpectedDisconnect = autoRecoverUnexpectedDisconnectSetting.value
        GardenConfig.maxRecoveryAttempts         = maxRecoverySetting.value.toInt()
        GardenConfig.reconnectDelayMin           = reconnectMinSetting.value.toLong()
        GardenConfig.reconnectDelayMax           = reconnectMaxSetting.value.toLong()

        // Advanced timing
        GardenConfig.rotationTimeMs          = rotationTimeSetting.value.toLong()
        GardenConfig.guiClickDelayMs         = guiClickDelaySetting.value.toLong()
        GardenConfig.additionalRandomDelayMs = additionalRandomDelaySetting.value.toLong()
        GardenConfig.gardenWarpDelayMs       = gardenWarpDelaySetting.value.toLong()
        GardenConfig.unflyMode               = unflyModeSetting.value
        GardenConfig.petTrackerList          = petTrackerListSetting.value

        // Misc
        GardenConfig.guiOnlyInGarden = guiOnlyInGardenSetting.value
    }

    // -- Events ----------------------------------------------------------------

    @SubscribeEvent
    fun onTick(event: TickEvent.End) {
        val enabled = enabledSetting.value

        // Start/stop on enabled toggle
        if (enabled && state == GardenState.OFF) {
            startMacro()
            return
        }
        if (!enabled && state != GardenState.OFF) {
            stopMacro()
            return
        }
        if (!enabled) return

        syncConfig()

        // Disconnect detection
        val connected = mc.connection != null
        if (!connected && wasConnected && state != GardenState.RECOVERING && state != GardenState.RESTING) {
            if (GardenConfig.autoRecoverUnexpectedDisconnect) {
                setState(GardenState.RECOVERING)
                RecoveryManager.onDisconnect {
                    setState(GardenState.FARMING)
                    ScriptBridge.startFarming(GardenConfig.farmScript)
                }
            } else {
                stopMacro()
            }
        }
        wasConnected = connected

        if (!connected) return

        // Route to managers
        CropFeverManager.update()
        PestBonusManager.update()
        RestartManager.update { stopMacro() }
        ProfitManager.refreshBazaarIfNeeded()
        PetXpTracker.update()

        when (state) {
            GardenState.FARMING -> tickFarming()
            GardenState.RESTING -> { /* DynamicRestManager manages the timer */ }
            GardenState.CLEANING -> {
                // Safety: if sequencer is no longer running but state is stuck, recover
                if (!PestCleaningSequencer.isRunning) {
                    setState(GardenState.FARMING)
                    ScriptBridge.startFarming(GardenConfig.farmScript)
                }
            }
            else -> { /* Other states managed by their worker tasks */ }
        }
    }

    @SubscribeEvent
    fun onChat(event: ChatEvent.Receive) {
        val msg = event.message?.replace(Regex("\u00A7[0-9a-fk-or]"), "") ?: return

        // Chat spam filter
        if (hideFilteredChatSetting.value) {
            val lower = msg.lowercase()
            if (lower.contains("pet killed") || lower.contains("macro started") || lower.contains("script started")) {
                event.setCancelled(true)
                return
            }
        }

        handleUnexpectedScriptStop(msg)
        CropFeverManager.onChatMessage(msg)
        PestCleaningSequencer.onChatMessage(msg)
        RestartManager.onChatMessage(msg) { stopMacro() }
        ProfitManager.onChatMessage(msg)
        VisitorManager.onChatMessage(msg)
    }

    private fun tickFarming() {
        // Startup grace: let the farming script get going before any automated triggers fire
        if (System.currentTimeMillis() < startupGraceUntilMs) return

        // Rest check
        if (DynamicRestManager.shouldRest()) {
            setState(GardenState.RESTING)
            DynamicRestManager.startRest {
                if (GardenConfig.autoResumeAfterDynamicRest) {
                    setState(GardenState.FARMING)
                    ScriptBridge.startFarming(GardenConfig.farmScript)
                } else {
                    stopMacro()
                }
            }
            return
        }

        // Visitor check
        if (autoVisitorSetting.value && VisitorManager.shouldHandle() && !CropFeverManager.shouldDelay()) {
            setState(GardenState.VISITING)
            VisitorManager.startVisitorSequence { setState(GardenState.FARMING) }
            return
        }

        // AUTOSELLING - priority: George > BookCombine > Junk
        if (autosellingManager == null) {
            if (GardenConfig.autoGeorgeSell && GeorgeManager.shouldSell()) {
                autosellingManager = "george"
                setState(GardenState.AUTOSELLING)
                GeorgeManager.startSell { autosellingManager = null; setState(GardenState.FARMING) }
                return
            }
            if (GardenConfig.autoBookCombine && BookCombineManager.shouldCombine()) {
                autosellingManager = "book"
                setState(GardenState.AUTOSELLING)
                BookCombineManager.startCombine { autosellingManager = null; setState(GardenState.FARMING) }
                return
            }
            if (GardenConfig.autoDropJunk && JunkManager.shouldDrop()) {
                autosellingManager = "junk"
                setState(GardenState.AUTOSELLING)
                JunkManager.startDrop { autosellingManager = null; setState(GardenState.FARMING) }
                return
            }
        }

        // Refresh pest tab-list data now so lastCooldownSeconds is current when the
        // prep-swap check runs below (previously update() was called after the check,
        // causing it to always read one-tick-stale cooldown data and miss the window).
        val pestShouldClean = PestManager.update(GardenConfig.pestThreshold)

        // Once the post-clean grace has expired, let PestPrepSwapManager start its
        // fallback timer in case the tab list cooldown regex never matches.
        if (System.currentTimeMillis() >= PestManager.cleaningCooldownUntil) {
            PestPrepSwapManager.markActive()
        }

        // Pest prep flow - at the 140-second spawn window, swap to pest gear,
        // rod swap, set spawn while flying, then return to farming gear.
        if (prepSwapSetting.value && !PestPrepSwapManager.swapDone &&
            System.currentTimeMillis() >= PestManager.cleaningCooldownUntil) {
            if (PestPrepSwapManager.shouldPrepSwap(PestManager.lastCooldownSeconds) &&
                PestPrepSwapManager.markStarted()) {
                GardenWorkerThread.submit("prep-swap") {
                    var prepCompleted = false
                    try {
                        mc.execute { ScriptBridge.stopScript() }
                        Thread.sleep((20L..40L).random() + 300L)

                        if (GardenConfig.autoWardrobeEnabled || GardenConfig.autoEquipment) {
                            GearManager.swapForPest()
                            Thread.sleep((10L..30L).random() + 350L)
                        }

                        if (GardenConfig.autoRodPestCd) {
                            RodManager.useRod(force = true)
                            Thread.sleep((10L..30L).random() + 200L)
                        }

                        FlightManager.startFlying()
                        Thread.sleep((10L..30L).random() + 150L)
                        mc.execute { ScriptBridge.setSpawn() }
                        Thread.sleep((10L..30L).random() + 250L)
                        FlightManager.stopFlying()
                        Thread.sleep((10L..30L).random() + 150L)

                        if (GardenConfig.autoWardrobeEnabled || GardenConfig.autoEquipment) {
                            GearManager.swapForFarming()
                            Thread.sleep((10L..30L).random() + 350L)
                        }

                        prepCompleted = true
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    } finally {
                        if (prepCompleted) {
                            PestPrepSwapManager.markDone()
                            mc.execute { ScriptBridge.startFarming(GardenConfig.farmScript) }
                        } else {
                            PestPrepSwapManager.markFailed()
                        }
                    }
                }
            }
        }

        // Pest cleaning trigger
        val cropFeverDelay = GardenConfig.delayPestForCropFever && CropFeverManager.shouldDelay()
        val pestSpawnedAfterPrep = prepSwapSetting.value &&
            PestPrepSwapManager.swapDone &&
            PestManager.lastAliveCount > 0
        if (!cropFeverDelay && !PestPrepSwapManager.isRunning() &&
            (pestShouldClean || pestSpawnedAfterPrep)) {
            setState(GardenState.CLEANING)
            PestCleaningSequencer.startSequence {
                setState(GardenState.FARMING)
                ScriptBridge.startFarming(GardenConfig.farmScript)
            }
        }
    }

    private fun handleUnexpectedScriptStop(message: String) {
        if (!GardenConfig.autoRestartStoppedScript || !enabledSetting.value || state == GardenState.OFF) return

        val lower = message.lowercase()
        if (!lower.contains("taunahi") ||
            !lower.contains("script stopped") ||
            !lower.contains("menu opened (your equipment and stats)")) {
            return
        }
        if (ScriptBridge.wasIntentionalStopRecently()) return

        queueUnexpectedScriptRestart()
    }

    private fun queueUnexpectedScriptRestart() {
        if (autoRestartQueued) return
        if (resolveUnexpectedStopRestartAction() == null) return

        autoRestartQueued = true
        GardenWorkerThread.submit("auto-script-restart") {
            try {
                val deadline = System.currentTimeMillis() + AUTO_RESTART_WAIT_TIMEOUT_MS
                var menuClosedAt = 0L
                while (System.currentTimeMillis() < deadline) {
                    if (Thread.currentThread().isInterrupted) return@submit
                    if (mc.connection == null || mc.player == null) {
                        menuClosedAt = 0L
                        Thread.sleep(AUTO_RESTART_POLL_MS)
                        continue
                    }

                    if (mc.screen != null) {
                        menuClosedAt = 0L
                        mc.execute {
                            if (mc.screen != null) {
                                mc.player?.closeContainer()
                                if (mc.screen != null) mc.setScreen(null)
                            }
                        }
                        Thread.sleep(AUTO_RESTART_POLL_MS)
                        continue
                    }

                    if (menuClosedAt == 0L) {
                        menuClosedAt = System.currentTimeMillis()
                    }
                    if (System.currentTimeMillis() - menuClosedAt >= AUTO_RESTART_AFTER_MENU_CLOSE_DELAY_MS) {
                        break
                    }
                    Thread.sleep(AUTO_RESTART_POLL_MS)
                }

                if (mc.connection == null || mc.player == null || mc.screen != null) return@submit
                resolveUnexpectedStopRestartAction()?.invoke()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                autoRestartQueued = false
            }
        }
    }

    private fun resolveUnexpectedStopRestartAction(): (() -> Unit)? {
        if (!enabledSetting.value || state == GardenState.OFF) return null
        if (RestartManager.restartDetected || RecoveryManager.isRecovering || DynamicRestManager.isResting) return null
        if (autosellingManager != null || state == GardenState.AUTOSELLING) return null
        if (PestPrepSwapManager.isRunning()) return null
        if (ScriptBridge.hasLastStartedScript()) {
            return { ScriptBridge.restartLastScript() }
        }

        return when {
            PestReturnManager.isReturning && GardenConfig.returnScript.isNotBlank() -> {
                { ScriptBridge.startReturnScript(GardenConfig.returnScript) }
            }
            (VisitorManager.isHandlingVisitor || state == GardenState.VISITING) && GardenConfig.visitorScript.isNotBlank() -> {
                { ScriptBridge.startVisitorScript(GardenConfig.visitorScript) }
            }
            (PestCleaningSequencer.isRunning || state == GardenState.CLEANING) && GardenConfig.pestScript.isNotBlank() -> {
                { ScriptBridge.startPestScript(GardenConfig.pestScript) }
            }
            state == GardenState.FARMING && GardenConfig.farmScript.isNotBlank() -> {
                { ScriptBridge.startFarming(GardenConfig.farmScript) }
            }
            else -> null
        }
    }

    private fun startMacro() {
        syncConfig()
        if (!GardenConfig.persistSessionTimer) {
            sessionStartTime = System.currentTimeMillis()
        }
        state = GardenState.FARMING
        // 5-second grace period before automated triggers (pest/visitor/economy) can fire
        startupGraceUntilMs = System.currentTimeMillis() + 5_000L
        // Reset all managers
        listOf<() -> Unit>(
            PestManager::reset, PestCleaningSequencer::reset, PestAotvManager::reset,
            PestPrepSwapManager::reset, PestReturnManager::reset, PestBonusManager::reset,
            CropFeverManager::reset, VisitorManager::reset, WardrobeManager::reset,
            EquipmentManager::reset, GearManager::reset, RodManager::reset,
            GeorgeManager::reset, BookCombineManager::reset, JunkManager::reset,
            BoosterCookieManager::reset, ProfitManager::reset, PetXpTracker::reset,
            DynamicRestManager::reset, RecoveryManager::reset, RestartManager::reset,
        ).forEach { it() }
        autosellingManager = null
        wasConnected = true
        autoRestartQueued = false
        // Stop any running script first, then start farming after a small delay
        GardenWorkerThread.submit("macro-start") {
            mc.execute { ScriptBridge.stopScript() }
            Thread.sleep((20L..40L).random() + 300L)
            mc.execute { ScriptBridge.startFarming(GardenConfig.farmScript) }
        }
    }

    private fun stopMacro() {
        autoRestartQueued = false
        GardenWorkerThread.shutdown()
        ScriptBridge.stopScript()
        state = GardenState.OFF
    }

    private const val AUTO_RESTART_WAIT_TIMEOUT_MS = 8_000L
    private const val AUTO_RESTART_POLL_MS = 100L
    private const val AUTO_RESTART_AFTER_MENU_CLOSE_DELAY_MS = 140L
}
