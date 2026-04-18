package org.cobalt.internal.garden

/** Shared config values populated by GardenMacroModule each tick. */
object GardenConfig {

    // Scripts
    @Volatile var farmScript: String = "netherwart:1"
    @Volatile var pestScript: String = "misc:pestCleaner"
    @Volatile var returnScript: String = "misc:visitor"
    @Volatile var visitorScript: String = "misc:visitor"
    @Volatile var autoRestartStoppedScript: Boolean = false

    // Pest
    @Volatile var pestThreshold: Int = 4
    @Volatile var triggerPestOnChat: Boolean = true
    @Volatile var pestChatTriggerDelayMs: Long = 0L
    @Volatile var aotvEnabled: Boolean = false
    @Volatile var roofPitch: Double = -80.0
    @Volatile var aotvRoofPitchHumanization: Int = 3
    @Volatile var breakBlocksBeforeAotv: Boolean = false
    @Volatile var enablePlotTpRewarp: Boolean = false
    @Volatile var holdWUntilWall: Boolean = false
    @Volatile var plotTpNumber: String = "0"
    @Volatile var delayPestForCropFever: Boolean = false

    // Visitor
    @Volatile var visitorThreshold: Int = 5

    // Wardrobe
    @Volatile var autoWardrobeEnabled: Boolean = false
    @Volatile var autoWardrobePest: Boolean = true
    @Volatile var autoWardrobeVisitor: Boolean = false
    @Volatile var farmingWardrobeSlot: Int = 1
    @Volatile var pestWardrobeSlot: Int = 2
    @Volatile var visitorWardrobeSlot: Int = 3

    // Equipment (bracelet, cloak, belt, necklace slots)
    @Volatile var autoEquipment: Boolean = true
    @Volatile var farmingEquipment: String = "lotus, blossom"
    @Volatile var pestEquipment: String = "pesthunter, pest vest"
    @Volatile var visitorEquipment: String = ""
    @Volatile var swapDelayMs: Long = 300L

    // Rod
    @Volatile var autoRodPestCd: Boolean = false
    @Volatile var autoRodPestSpawn: Boolean = false
    @Volatile var autoRodReturnToFarm: Boolean = false
    @Volatile var rodSwapDelayMs: Long = 100L

    // Economy
    @Volatile var autoGeorgeSell: Boolean = false
    @Volatile var georgeRarity: String = "LEGENDARY"
    @Volatile var georgeSellThreshold: Int = 3
    @Volatile var autoBookCombine: Boolean = false
    @Volatile var bookCombineLevel: Int = 5
    @Volatile var bookThreshold: Int = 7
    @Volatile var bookCombineDelayMs: Long = 300L
    @Volatile var alwaysActiveCombine: Boolean = false
    @Volatile var autoDropJunk: Boolean = false
    @Volatile var junkItems: String = "Fruit Bowl,Farming Exp Boost,Sunder VI"
    @Volatile var junkThreshold: Int = 3
    @Volatile var junkItemDropDelayMs: Long = 300L
    @Volatile var autoBoosterCookie: Boolean = true
    @Volatile var boosterCookieItems: String = "Atmospheric Filter,Squeaky Toy,Beady Eyes,Clipped Wings,Overclocker,Mantid Claw,Flowering Bouquet,Bookworm,Chirping Stereo,Firefly,Capsule,Vinyl"
    @Volatile var autoStashManager: Boolean = false
    @Volatile var bazaarRefreshSecs: Long = 120L

    // Rest
    @Volatile var workDurationMins: Long = 60L
    @Volatile var workOffsetMins: Long = 5L
    @Volatile var breakDurationMins: Long = 10L
    @Volatile var breakOffsetMins: Long = 2L
    @Volatile var persistSessionTimer: Boolean = true
    @Volatile var autoResumeAfterDynamicRest: Boolean = true

    // Recovery
    @Volatile var autoRecoverUnexpectedDisconnect: Boolean = true
    @Volatile var maxRecoveryAttempts: Int = 15
    @Volatile var reconnectDelayMin: Long = 30L
    @Volatile var reconnectDelayMax: Long = 60L

    // Advanced timing
    @Volatile var rotationTimeMs: Long = 500L
    @Volatile var guiClickDelayMs: Long = 500L
    @Volatile var additionalRandomDelayMs: Long = 0L
    @Volatile var gardenWarpDelayMs: Long = 3000L
    @Volatile var unflyMode: String = "DOUBLE_TAP_SPACE"

    // Pet tracking
    @Volatile var petTrackerList: String = "PET_ROSE_DRAGON:Rose Dragon:200:LEGENDARY"

    // Misc
    @Volatile var cookieItem: String = ""
    @Volatile var guiOnlyInGarden: Boolean = true
}
