package org.cobalt.internal.chat

import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.ChatEvent
import org.cobalt.api.module.Module
import org.cobalt.api.module.setting.inGroup
import org.cobalt.api.module.setting.impl.CheckboxSetting

/**
 * Ports SkyHanni's ChatFilter, WatchdogHider, and ArachneChatMessageHider into
 * a single Cobalt module.  Each toggle maps to one SkyHanni config entry so
 * anything that was filterable there works here too.
 */
object ChatFilterModule : Module("Chat Filter") {

    private const val FORMAT = "\u00A7"
    private const val SPARKLE = "\u2726"
    private const val SLAYER_ARROW = "\u00BB"
    private const val RUNE_DIAMOND = "\u25C6"
    private const val SNOWMAN = "\u2603"
    private const val HOT_SPRING = "\u2668"
    private const val CLOVER = "\u2618"
    private const val COMET = "\u2604"
    private const val POINTER = "\u27A4"
    private const val PHONE = "\u2706"
    private const val BAR = "\u25AC"

    // -- Lobby ------------------------------------------------------------------
    private val filterLobbyJoins   = CheckboxSetting("Lobby Joins",    "Hide '... joined the lobby!' messages.",          false).inGroup("Lobby")
    private val filterMysteryBox   = CheckboxSetting("Mystery Boxes",  "Hide mystery box / mystery dust messages.",      false).inGroup("Lobby")
    private val filterPrototype    = CheckboxSetting("Prototype Lobby","Hide prototype lobby welcome & Hype messages.",  false).inGroup("Lobby")
    private val filterTournament   = CheckboxSetting("Tournaments",    "Hide Hypixel tournament announcement spam.",     false).inGroup("Lobby")

    // -- Warping ----------------------------------------------------------------
    private val filterWarping      = CheckboxSetting("Warping",        "Hide 'Sending to server...' / 'Warping...' lines.", false).inGroup("Warping")
    private val filterWelcome      = CheckboxSetting("Welcome",        "Hide 'Welcome to Hypixel SkyBlock!'.",           false).inGroup("Warping")
    private val filterProfileJoin  = CheckboxSetting("Profile Join",   "Hide 'You are playing on profile...' lines.",     false).inGroup("Warping")

    // -- Combat -----------------------------------------------------------------
    private val filterKillCombo    = CheckboxSetting("Kill Combo",     "Hide kill combo progress messages.",             false).inGroup("Combat")
    private val filterSlayer       = CheckboxSetting("Slayer Quest",   "Hide slayer quest start / end messages.",        false).inGroup("Combat")
    private val filterSlayerDrops  = CheckboxSetting("Slayer Drops",   "Hide slayer rare drop messages.",                false).inGroup("Combat")
    private val filterUselessDrops = CheckboxSetting("Useless Drops",  "Hide worthless rare drop messages.",             false).inGroup("Combat")
    private val filterSoloClass    = CheckboxSetting("Solo Class",     "Hide 'stats doubled' solo class messages.",      false).inGroup("Combat")
    private val filterFairy        = CheckboxSetting("Fairy",          "Hide fairy death / revive messages.",            false).inGroup("Combat")
    private val filterArachne      = CheckboxSetting("Arachne",        "Hide Arachne calling / crystal / venom spam.",   false).inGroup("Combat")
    private val filterAnnoyingSpam = CheckboxSetting("Annoying Spam",  "Hide Implosion/spirit sceptre hit messages.",    false).inGroup("Combat")

    // -- Economy ----------------------------------------------------------------
    private val filterBzAhMinis    = CheckboxSetting("BZ / AH Ops",   "Hide 'Processing bid...' / 'Claiming order...' etc.",false).inGroup("Economy")
    private val filterBzOrders     = CheckboxSetting("BZ Orders",     "Hide buy/sell order setup confirmation lines.",   false).inGroup("Economy")
    private val filterSacrifice    = CheckboxSetting("Sacrifice",     "Hide dragon sacrifice broadcast messages.",        false).inGroup("Economy")
    private val filterLegacyItems  = CheckboxSetting("Legacy Items",  "Hide legacy item exchange warnings.",             false).inGroup("Economy")

    // -- Garden -----------------------------------------------------------------
    private val filterGardenNoPest = CheckboxSetting("No Pest Msg",   "Hide 'There are no pests in your garden' spam.",  true).inGroup("Garden")
    private val filterJacobFortune = CheckboxSetting("Jacob Fortune", "Hide Jacob NPC fortune talisman messages.",       false).inGroup("Garden")
    private val filterParkour      = CheckboxSetting("Parkour",       "Hide parkour start/checkpoint/finish messages.",  false).inGroup("Garden")
    private val filterTeleportPad  = CheckboxSetting("Teleport Pads", "Hide teleport pad warp / error messages.",        false).inGroup("Garden")

    // -- Events -----------------------------------------------------------------
    private val filterGuildExp     = CheckboxSetting("Guild / Event EXP", "Hide GEXP and Event EXP earned messages.",    false).inGroup("Events")
    private val filterWinterGift   = CheckboxSetting("Winter Gift",   "Hide winter gift reward spam.",                   false).inGroup("Events")
    private val filterWinterIsland = CheckboxSetting("Winter Island", "Hide Snow Cannon mount messages.",                false).inGroup("Events")
    private val filterEventLevelUp = CheckboxSetting("Event Level Up","Hide event level-up broadcast messages.",         false).inGroup("Events")
    private val filterFireSale     = CheckboxSetting("Fire Sale",     "Hide fire sale announcement spam.",               false).inGroup("Events")
    private val filterRewardBundles= CheckboxSetting("Reward Bundles","Hide unclaimed seasonal reward reminder spam.",   false).inGroup("Events")
    private val filterFactoryUpgrade=CheckboxSetting("Factory Upgrade","Hide Chocolate Factory upgrade messages.",       false).inGroup("Events")
    private val filterHoppityEggs  = CheckboxSetting("Hoppity Eggs",  "Hide Hoppity's Hunt egg-appeared messages.",      false).inGroup("Events")
    private val filterSkyMall      = CheckboxSetting("Sky Mall",      "Hide Sky Mall buff-changed messages.",            false).inGroup("Events")
    private val filterLottery      = CheckboxSetting("Lottery",       "Hide Lottery buff-changed messages.",             false).inGroup("Events")

    // -- Misc -------------------------------------------------------------------
    private val filterWatchdog     = CheckboxSetting("Watchdog",      "Hide multi-line Watchdog ban announcement blocks.", false).inGroup("Misc")
    private val filterUselessWarn  = CheckboxSetting("Useless Warnings","Hide 'You can't use this in combat' etc.",      false).inGroup("Misc")
    private val filterUselessNotifs= CheckboxSetting("Useless Notifs","Hide mining speed, tipping, bank interest spam.", false).inGroup("Misc")
    private val filterPartyDivider = CheckboxSetting("Party Dividers","Hide ${FORMAT}9${FORMAT}m--- party separator lines.",             false).inGroup("Misc")
    private val filterAhDivider    = CheckboxSetting("AH Dividers",   "Hide ${FORMAT}b--- auction house separator lines.",       false).inGroup("Misc")
    private val filterEmpty        = CheckboxSetting("Empty Lines",   "Hide completely empty chat messages.",            false).inGroup("Misc")
    private val filterDungeonStats = CheckboxSetting("Dungeon Stats", "Hide solo-stats and rare-drop dungeon messages.",  false).inGroup("Misc")

    // -- Patterns --------------------------------------------------------------

    // Lobby joins
    private val lobbyJoinRx    = Regex("""(?: ${FORMAT}b>${FORMAT}c>${FORMAT}a>${FORMAT}r ${FORMAT}r)?.* ${FORMAT}6(?:joined|(?:spooked|slid) into) the lobby!(?:${FORMAT}r ${FORMAT}a<${FORMAT}c<${FORMAT}b<)?""")
    private val hypixelSmpRx   = Regex("""${FORMAT}2[\s]*?${FORMAT}aYou can now create your own Hypixel SMP server![\s]*?""")
    private val snowParticlesRx= Regex("""[\s]*?.*${FORMAT}bFor the best experience.*Snow.*Particles.*""", RegexOption.DOT_MATCHES_ALL)

    // Mystery box / dust
    private val mysteryBoxRx   = Regex("""${FORMAT}b${SPARKLE} ${FORMAT}r.* ${FORMAT}r${FORMAT}7found (?:a|an) ${FORMAT}r.*(?:Mystery Box|in a ${FORMAT}r${FORMAT}a(?:Holiday )?Mystery Box)${FORMAT}r${FORMAT}7!""")
    private val mysteryDustRx  = Regex("""${FORMAT}b${FORMAT}b${SPARKLE} ${FORMAT}r${FORMAT}7You earned ${FORMAT}r${FORMAT}b\d+ ${FORMAT}r${FORMAT}7Mystery Dust!""")
    private val petConsumRx    = Regex("""${FORMAT}b${FORMAT}b${SPARKLE} ${FORMAT}r${FORMAT}7You earned ${FORMAT}a\d+ ${FORMAT}7Pet Consumables items!""")

    // Warping
    private val warpingRx      = Regex("""${FORMAT}7(?:Sending to server|Request join for (?:Hub|Dungeon Hub #)) .*\.\.\.""")
    private val warpedToRx     = Regex("""${FORMAT}dWarped to (.*)${FORMAT}r${FORMAT}d!""")

    // Guild / Event EXP
    private val guildExpRx     = Regex("""${FORMAT}aYou earned ${FORMAT}r${FORMAT}[0-9a-f][\d,]+ (?:GEXP|Event EXP) (?:${FORMAT}r${FORMAT}a\+ ${FORMAT}r${FORMAT}[0-9a-f][\d,]+ Event EXP )?${FORMAT}r${FORMAT}afrom playing SkyBlock!""")

    // Kill combo
    private val killComboRx    = Regex("""${FORMAT}.${FORMAT}l\+(.*) Kill Combo(.*)""")
    private val killComboExpRx = Regex("""${FORMAT}cYour Kill Combo has expired! You reached a (.*) Kill Combo!""")

    // Slayer quest
    private val slayerStartRx  = Regex(""" {2}${FORMAT}r${FORMAT}5${FORMAT}lSLAYER QUEST STARTED!""")
    private val slayerEndRx    = Regex(""" {2}${FORMAT}r${FORMAT}a${FORMAT}lSLAYER QUEST COMPLETE!""")
    private val slayerXpRx     = Regex(""" {3}${FORMAT}r${FORMAT}5${FORMAT}l${SLAYER_ARROW} ${FORMAT}r${FORMAT}7Talk to Maddox to claim your (.*) Slayer XP!""")
    private val slayerLvlRx    = Regex(""" {3}${FORMAT}r${FORMAT}e(.*)Slayer LVL 9 ${FORMAT}r${FORMAT}5- ${FORMAT}r${FORMAT}a${FORMAT}lLVL MAXED OUT!""")
    private val slayerQuestRx  = Regex(""" {3}${FORMAT}5${FORMAT}l${SLAYER_ARROW} ${FORMAT}7Slay ${FORMAT}c(.*) Combat XP ${FORMAT}7worth of (.*)${FORMAT}7\.""")

    // Slayer drops
    private val slayerDropRxList = listOf(
        Regex("""${FORMAT}b${FORMAT}lRARE DROP! ${FORMAT}r${FORMAT}7\(${FORMAT}r${FORMAT}f${FORMAT}r${FORMAT}7(.*)x ${FORMAT}r${FORMAT}f${FORMAT}r${FORMAT}9(?:Revenant Viscera|Foul Flesh|Toxic Arrow Poison|Twilight Arrow Poison)${FORMAT}r${FORMAT}7\) (.*)"""),
        Regex("""${FORMAT}b${FORMAT}lRARE DROP! ${FORMAT}r${FORMAT}7\(${FORMAT}r${FORMAT}f${FORMAT}r${FORMAT}9(?:Revenant Viscera|Foul Flesh)${FORMAT}r${FORMAT}7\) (.*)"""),
        Regex("""${FORMAT}6${FORMAT}lRARE DROP! ${FORMAT}r${FORMAT}5Golden Powder (.*)"""),
        Regex("""${FORMAT}[59]${FORMAT}lVERY RARE DROP! {2}${FORMAT}r${FORMAT}7\(.*\) (.*)"""),
        Regex("""${FORMAT}6${FORMAT}lRARE DROP! ${FORMAT}r${FORMAT}9Arachne's Keeper Fragment (.+)"""),
        Regex("""${FORMAT}6${FORMAT}lRARE DROP! ${FORMAT}r${FORMAT}5Travel Scroll to Spider's Den Top of Nest (.+)"""),
        Regex("""${FORMAT}9${FORMAT}lVERY RARE DROP! {2}${FORMAT}r${FORMAT}7\(${FORMAT}r${FORMAT}f${FORMAT}r${FORMAT}a${RUNE_DIAMOND} Bite Rune I${FORMAT}r${FORMAT}7\) (.+)"""),
        Regex("""${FORMAT}5${FORMAT}lVERY RARE DROP! {2}${FORMAT}r${FORMAT}7\(${FORMAT}r${FORMAT}9Bane of Arthropods VI${FORMAT}r${FORMAT}7\) (.+)"""),
        Regex("""${FORMAT}9${FORMAT}lVERY RARE DROP! {2}${FORMAT}r${FORMAT}7\(${FORMAT}r${FORMAT}f${FORMAT}r${FORMAT}7\d+x ${FORMAT}r${FORMAT}f${FORMAT}r${FORMAT}9(?:Glowstone|Blaze Rod|Magma Cream|Nether Wart) Distillate${FORMAT}r${FORMAT}7\) (.*)"""),
        Regex("""${FORMAT}d${FORMAT}lCRAZY RARE DROP! {2}${FORMAT}r${FORMAT}7\(${FORMAT}r${FORMAT}f${FORMAT}r${FORMAT}fPocket Espresso Machine${FORMAT}r${FORMAT}7\) (.*)"""),
        Regex("""${FORMAT}5${FORMAT}lVERY RARE DROP! {2}${FORMAT}r${FORMAT}7\(${FORMAT}r${FORMAT}f${FORMAT}r${FORMAT}5(?:${RUNE_DIAMOND} End Rune I|Sinful Dice|Revenant Catalyst|Undead Catalyst|${RUNE_DIAMOND} Endersnake Rune I|Transmission Tuner)${FORMAT}r${FORMAT}7\) (.*)"""),
        Regex("""${FORMAT}9${FORMAT}lVERY RARE DROP! {2}${FORMAT}r${FORMAT}7\(${FORMAT}r${FORMAT}f${FORMAT}r${FORMAT}9(?:Null Atom|Mana Steal I)${FORMAT}r${FORMAT}7\) (.*)"""),
        Regex("""${FORMAT}6${FORMAT}lRARE DROP! ${FORMAT}r${FORMAT}5Bundle of Magma Arrows${FORMAT}r${FORMAT}7\) (.*)"""),
        Regex("""${FORMAT}9${FORMAT}lVERY RARE DROP! {2}${FORMAT}r${FORMAT}7\(${FORMAT}r${FORMAT}f${FORMAT}r${FORMAT}fWisp's Ice-Flavored Water I Splash Potion${FORMAT}r${FORMAT}7\) (.*)"""),
        Regex("""${FORMAT}5${FORMAT}lVERY RARE DROP! {2}${FORMAT}r${FORMAT}7\(${FORMAT}r${FORMAT}f${FORMAT}r${FORMAT}6Hazmat Enderman${FORMAT}r${FORMAT}7\) .*"""),
    )

    // Useless drops
    private val uselessDropRxList = listOf(
        Regex("""${FORMAT}6${FORMAT}lRARE DROP! ${FORMAT}r${FORMAT}aEnchanted Ender Pearl (.*)"""),
        Regex("""${FORMAT}6${FORMAT}lRARE DROP! ${FORMAT}r${FORMAT}f(?:Carrot|Potato) (.*)"""),
        Regex("""${FORMAT}6${FORMAT}lRARE DROP! ${FORMAT}r${FORMAT}9Machine Gun Bow (.*)"""),
        Regex("""${FORMAT}6${FORMAT}lRARE DROP! ${FORMAT}r${FORMAT}5(?:Earth Shard|Zombie Lord Chestplate) (.*)"""),
    )

    // Economy / annoying spam
    private val annoyingSpamRxList = listOf(
        Regex("""${FORMAT}7Your (?:Implosion|Molten Wave|Spirit Sceptre) hit (.*) for ${FORMAT}r${FORMAT}c(.*) ${FORMAT}r${FORMAT}7damage\."""),
        Regex("""${FORMAT}cYou need a tool with a ${FORMAT}r${FORMAT}aBreaking Power ${FORMAT}r${FORMAT}cof ${FORMAT}r${FORMAT}6(\d)${FORMAT}r${FORMAT}c to mine .*"""),
    )

    // Bazaar / AH confirmation patterns
    private val bzOrderRxList = listOf(
        Regex("""${FORMAT}eBuy Order Setup! ${FORMAT}r${FORMAT}a(.*)${FORMAT}r${FORMAT}7x (.*) ${FORMAT}r${FORMAT}7for ${FORMAT}r${FORMAT}6(.*) coins${FORMAT}r${FORMAT}7\."""),
        Regex("""${FORMAT}eSell Offer Setup! ${FORMAT}r${FORMAT}a(.*)${FORMAT}r${FORMAT}7x (.*) ${FORMAT}r${FORMAT}7for ${FORMAT}r${FORMAT}6(.*) coins${FORMAT}r${FORMAT}7\."""),
        Regex("""${FORMAT}cCancelled! ${FORMAT}r${FORMAT}7Refunded ${FORMAT}r${FORMAT}6(.*) coins ${FORMAT}r${FORMAT}7from cancelling buy order!"""),
        Regex("""${FORMAT}cCancelled! ${FORMAT}r${FORMAT}7Refunded ${FORMAT}r${FORMAT}a(.*)${FORMAT}r${FORMAT}7x (.*) ${FORMAT}r${FORMAT}7from cancelling sell offer!"""),
    )

    // Winter island
    private val winterIslandRx = Regex("""${FORMAT}r${FORMAT}f${SNOWMAN} ${FORMAT}r${FORMAT}7${FORMAT}r(.*) ${FORMAT}r${FORMAT}7mounted a ${FORMAT}r${FORMAT}fSnow Cannon${FORMAT}r${FORMAT}7!""")

    // Fire sale
    private val fireSaleMainRx  = Regex("""${FORMAT}6${FORMAT}k${FORMAT}lA${FORMAT}r ${FORMAT}c${FORMAT}lFIRE SALE ${FORMAT}r${FORMAT}6${FORMAT}k${FORMAT}lA""", RegexOption.DOT_MATCHES_ALL)
    private val fireSaleRxList  = listOf(
        Regex("""${FORMAT}c${HOT_SPRING} ${FORMAT}eFire Sales? for .* ${FORMAT}e(?:are|is) starting soon!"""),
        Regex("""${FORMAT}c\s*${HOT_SPRING} .* (?:Skin|Rune|Dye) ${FORMAT}e(?:for a limited time )?\(.* ${FORMAT}eleft\)(?:${FORMAT}c|!)"""),
        Regex("""${FORMAT}c${HOT_SPRING} ${FORMAT}eVisit the Community Shop in the next ${FORMAT}c.* ${FORMAT}eto grab yours! ${FORMAT}a${FORMAT}l\[WARP]"""),
        Regex("""${FORMAT}c${HOT_SPRING} ${FORMAT}eA Fire Sale for .* ${FORMAT}eis starting soon!"""),
        Regex("""${FORMAT}c${HOT_SPRING} ${FORMAT}r${FORMAT}eFire Sales? for .* ${FORMAT}r${FORMAT}eended!"""),
        Regex("""${FORMAT}c {3}${HOT_SPRING} ${FORMAT}eAnd \d+ more!"""),
    )

    // Events
    private val eventLvlRxList  = listOf(
        Regex("""(?:${FORMAT}f)? +${FORMAT}r${FORMAT}7You are now ${FORMAT}r${FORMAT}.Event Level ${FORMAT}r${FORMAT}.*${FORMAT}r${FORMAT}7!"""),
        Regex("""(?:${FORMAT}f)? +${FORMAT}r${FORMAT}7You earned ${FORMAT}r${FORMAT}.* Event Silver${FORMAT}r${FORMAT}7!"""),
        Regex("""(?:${FORMAT}f)? +${FORMAT}r${FORMAT}.${FORMAT}k#${FORMAT}r${FORMAT}. LEVEL UP! ${FORMAT}r${FORMAT}.${FORMAT}k#"""),
    )

    // Factory upgrade / Hoppity
    private val factoryUpgradeRxList = listOf(
        Regex(""".* ${FORMAT}r${FORMAT}7has been promoted to ${FORMAT}r${FORMAT}7\[.*${FORMAT}r${FORMAT}7] ${FORMAT}r${FORMAT}.*${FORMAT}r${FORMAT}7!"""),
        Regex("""${FORMAT}7Your ${FORMAT}r${FORMAT}aRabbit Barn ${FORMAT}r${FORMAT}7capacity has been increased to ${FORMAT}r${FORMAT}a.* Rabbits${FORMAT}r${FORMAT}7!"""),
        Regex("""${FORMAT}7You will now produce ${FORMAT}r${FORMAT}6.* Chocolate ${FORMAT}r${FORMAT}7per click!"""),
        Regex("""${FORMAT}7You upgraded to ${FORMAT}r${FORMAT}d.*?${FORMAT}r${FORMAT}7!"""),
    )
    private val hoppityAppearRx = Regex("""${FORMAT}d${FORMAT}lHOPPITY'S HUNT ${FORMAT}r${FORMAT}dA .* ${FORMAT}r${FORMAT}dhas appeared!""")
    private val hoppityBeginRx  = Regex("""${FORMAT}dHoppity's Hunt ${FORMAT}r${FORMAT}ehas begun!.*""")

    // Sacrifice
    private val sacrificeRxList = listOf(
        Regex("""${FORMAT}c${FORMAT}lSACRIFICE! (.*) ${FORMAT}r${FORMAT}eturned (.*) ${FORMAT}r${FORMAT}einto (.*) Dragon Essence${FORMAT}r${FORMAT}e!"""),
        Regex("""${FORMAT}c${FORMAT}lBONUS LOOT! ${FORMAT}r${FORMAT}eThey also received (.*) ${FORMAT}r${FORMAT}efrom their sacrifice!"""),
    )

    // Reward bundles (seasonal reminders)
    private val rewardBundleRxList = listOf(
        Regex("""(?:${FORMAT}.)*You haven't claimed your (?:${FORMAT}.)*\w+ Rewards (?:${FORMAT}.)*yet!"""),
        Regex("""(?:${FORMAT}.)*Talk to the (?:${FORMAT}.)*.+(?:${FORMAT}.)*in the (?:${FORMAT}.)*.+(?:${FORMAT}.)*!"""),
    )

    // Jacob fortune (NPC message outside garden)
    private val jacobFortuneRx  = Regex("""${FORMAT}e\[NPC] Jacob${FORMAT}f: ${FORMAT}rYour ${FORMAT}9Anita's \w+ ${FORMAT}fis giving you ${FORMAT}6\+\d{1,2}${CLOVER} .+ Fortune ${FORMAT}fduring the contest!""")

    // SkyMall / Lottery
    private val skymallRxList   = listOf("${FORMAT}bNew day! ${FORMAT}r${FORMAT}eYour ${FORMAT}r${FORMAT}2Sky Mall ${FORMAT}r${FORMAT}ebuff changed!", "${FORMAT}8${FORMAT}oYou can disable this messaging by toggling Sky Mall in your /hotm!")
    private val lotteryRxList   = listOf("${FORMAT}bNew day! ${FORMAT}r${FORMAT}eYour ${FORMAT}r${FORMAT}2Lottery ${FORMAT}r${FORMAT}ebuff changed!", "${FORMAT}8${FORMAT}oYou can disable this messaging by toggling Lottery in your /hotf!")

    // Solo class / solo stats / dungeon fairy
    private val soloClassRx     = Regex("""${FORMAT}6Your ${FORMAT}r${FORMAT}a(Healer|Mage|Berserk|Archer|Tank) ${FORMAT}r${FORMAT}6stats are doubled because you are the only player using this class!""")
    private val soloStatsRx     = Regex("""${FORMAT}a\[(Healer|Mage|Berserk|Archer|Tank)].*""")
    private val fairyRxList     = listOf(
        Regex("""${FORMAT}d[\w']+ the Fairy${FORMAT}r${FORMAT}f: You killed me! Take this ${FORMAT}r${FORMAT}6Revive Stone ${FORMAT}r${FORMAT}fso that my death is not in vain!"""),
        Regex("""${FORMAT}d[\w']+ the Fairy${FORMAT}r${FORMAT}f: You killed me! I'll revive you so that my death is not in vain!"""),
        Regex("""${FORMAT}d[\w']+ the Fairy${FORMAT}r${FORMAT}f: Have a great life!"""),
    )
    private val dungeonRareDropRx = Regex("""${FORMAT}6${FORMAT}lRARE REWARD! ${FORMAT}r${FORMAT}bLeebys ${FORMAT}r${FORMAT}efound a (.*) ${FORMAT}r${FORMAT}ein their (.*) Chest${FORMAT}r${FORMAT}e!""")

    // Garden pest
    private val noPestRxList    = listOf(
        Regex("""${FORMAT}aNo pests are currently in your garden!"""),
        Regex("""${FORMAT}eThere are no pests in your garden!"""),
        Regex("""${FORMAT}cThere are no pests currently in your garden""", RegexOption.IGNORE_CASE),
    )

    // Parkour
    private val parkourRxList   = listOf(
        Regex("""${FORMAT}aStarted parkour (.*)!"""),
        Regex("""${FORMAT}aFinished parkour (.*) in (.*)!"""),
        Regex("""${FORMAT}aReached checkpoint #(.*) for parkour (.*)!"""),
        Regex("""${FORMAT}4Wrong checkpoint for parkour (.*)!"""),
        Regex("""${FORMAT}4You haven't reached all checkpoints for parkour (.*)!"""),
    )

    // Teleport pad
    private val teleportPadRx   = Regex("""${FORMAT}aWarped from the (.*) ${FORMAT}r${FORMAT}ato the (.*)${FORMAT}r${FORMAT}a!""")

    // Arachne
    private val arachneCallingRx  = Regex("""${FORMAT}4${COMET} ${FORMAT}r.* ${FORMAT}r${FORMAT}eplaced an ${FORMAT}r${FORMAT}9Arachne's Calling${FORMAT}r${FORMAT}e!.*""")
    private val arachneCrystalRx  = Regex("""${FORMAT}4${COMET} ${FORMAT}r.* ${FORMAT}r${FORMAT}eplaced an Arachne Crystal! Something is awakening!""")
    private val arachneSpawnRx    = Regex("""${FORMAT}c\[BOSS] Arachne${FORMAT}r${FORMAT}f: (?:The Era of Spiders begins now\.|Ahhhh\.\.\.A Calling\.\.\.)""")
    private val arachneVenomRx    = Regex("""${FORMAT}dArachne(?:'s (?:Keeper|Brood))? used ${FORMAT}r${FORMAT}2Venom Shot ${FORMAT}r${FORMAT}don you hitting you for ${FORMAT}r${FORMAT}c[\d.,]+ damage ${FORMAT}r${FORMAT}dand infecting you with venom\.""")

    // -- Watchdog state --------------------------------------------------------
    private var inWatchdog           = false
    private var watchdogBlockedLines = 0

    // -- Arachne state ---------------------------------------------------------
    private var hideArachneDeadMsg = false

    // -- Exact-string fast sets -------------------------------------------------

    private val lobbyExact = setOf(
        "  ${FORMAT}r${FORMAT}f${FORMAT}l${POINTER} ${FORMAT}r${FORMAT}6You have reached your Hype limit! Add Hype to Prototype Lobby minigames by right-clicking with the Hype Diamond!",
    )
    private val warpingExact = setOf(
        "${FORMAT}7Warping...", "${FORMAT}7Warping you to your SkyBlock island...", "${FORMAT}7Warping using transfer token...",
        "${FORMAT}7Finding player...", "${FORMAT}7Sending a visit request...",
    )
    private val lobbyContains = setOf(
        "${FORMAT}r${FORMAT}6${FORMAT}lWelcome to the Prototype Lobby${FORMAT}r",
        "${FORMAT}r${FORMAT}e${FORMAT}6${FORMAT}lHYPIXEL${FORMAT}e is hosting a ${FORMAT}b${FORMAT}lBED WARS DOUBLES${FORMAT}e tournament!",
        "${FORMAT}r${FORMAT}e${FORMAT}6${FORMAT}lHYPIXEL BED WARS DOUBLES${FORMAT}e tournament is live!",
        "${FORMAT}r${FORMAT}e${FORMAT}6${FORMAT}lHYPIXEL${FORMAT}e is hosting a ${FORMAT}b${FORMAT}lTNT RUN${FORMAT}e tournament!",
        "${FORMAT}aYou are still radiating with ${FORMAT}bGenerosity${FORMAT}r${FORMAT}a!",
    )
    private val warpingContains = setOf<String>() // handled in warpingExact

    private val bzAhMiniExact = setOf(
        "${FORMAT}7Putting item in escrow...", "${FORMAT}7Putting coins in escrow...",
        "${FORMAT}7Setting up the auction...", "${FORMAT}7Processing purchase...", "${FORMAT}7Processing bid...",
        "${FORMAT}7Claiming BIN auction...",
        "${FORMAT}6[Bazaar] ${FORMAT}r${FORMAT}7Submitting sell offer...", "${FORMAT}6[Bazaar] ${FORMAT}r${FORMAT}7Submitting buy order...",
        "${FORMAT}6[Bazaar] ${FORMAT}r${FORMAT}7Executing instant sell...", "${FORMAT}6[Bazaar] ${FORMAT}r${FORMAT}7Executing instant buy...",
        "${FORMAT}6[Bazaar] ${FORMAT}r${FORMAT}7Cancelling order...", "${FORMAT}6[Bazaar] ${FORMAT}r${FORMAT}7Claiming order...",
        "${FORMAT}6[Bazaar] ${FORMAT}r${FORMAT}7Putting goods in escrow...",
        "${FORMAT}8Depositing coins...", "${FORMAT}8Withdrawing coins...",
    )
    private val slayerExact = setOf(
        "  ${FORMAT}r${FORMAT}6${FORMAT}lNICE! SLAYER BOSS SLAIN!", "${FORMAT}eYou received kill credit for assisting on a slayer miniboss!",
    )
    private val uselessDropExact = setOf(
        "${FORMAT}6${FORMAT}lRARE DROP! ${FORMAT}r${FORMAT}aEnchanted Ender Pearl",
        "${FORMAT}6${FORMAT}lRARE DROP! ${FORMAT}r${FORMAT}aEnchanted End Stone",
        "${FORMAT}6${FORMAT}lRARE DROP! ${FORMAT}r${FORMAT}5Crystal Fragment",
    )
    private val uselessNotifExact = setOf(
        "${FORMAT}eYour previous ${FORMAT}r${FORMAT}6Plasmaflux Power Orb ${FORMAT}r${FORMAT}ewas removed!",
        "${FORMAT}aYou used your ${FORMAT}r${FORMAT}6Mining Speed Boost ${FORMAT}r${FORMAT}aPickaxe Ability!",
        "${FORMAT}cYour Mining Speed Boost has expired!",
        "${FORMAT}a${FORMAT}r${FORMAT}6Mining Speed Boost ${FORMAT}r${FORMAT}ais now available!",
        "${FORMAT}aYou have just received ${FORMAT}r${FORMAT}60 coins ${FORMAT}r${FORMAT}aas interest in your personal bank account!",
        "${FORMAT}aSince you've been away you earned ${FORMAT}r${FORMAT}60 coins ${FORMAT}r${FORMAT}aas interest in your personal bank account!",
        "${FORMAT}aYou have just received ${FORMAT}r${FORMAT}60 coins ${FORMAT}r${FORMAT}aas interest in your co-op bank account!",
    )
    private val uselessNotifPatterns = listOf(
        Regex("""(?:${FORMAT}a)?${FORMAT}aYou tipped \d+ players? in \d+(?: different)? games?!"""),
    )
    private val uselessWarningExact = setOf(
        "${FORMAT}cYou are sending commands too fast! Please slow down.",
        "${FORMAT}cYou can't use this while in combat!",
        "${FORMAT}cYou can not modify your equipped armor set!",
        "${FORMAT}cPlease wait a few seconds between refreshing!",
        "${FORMAT}cThis item is not salvageable!",
        "${FORMAT}cPlace a Dungeon weapon or armor piece above the anvil to salvage it!",
        "${FORMAT}cWhoa! Slow down there!",
        "${FORMAT}cWait a moment before confirming!",
        "${FORMAT}cYou cannot open the SkyBlock menu while in combat!",
    )
    private val annoyingSpamExact = setOf(
        "${FORMAT}cThere are blocks in the way!",
        "${FORMAT}aYour Blessing enchant got you double drops!",
        "${FORMAT}cYou can't use the wardrobe in combat!",
        "${FORMAT}6${FORMAT}lGOOD CATCH! ${FORMAT}r${FORMAT}bYou found a ${FORMAT}r${FORMAT}fFish Bait${FORMAT}r${FORMAT}b.",
        "${FORMAT}6${FORMAT}lGOOD CATCH! ${FORMAT}r${FORMAT}bYou found a ${FORMAT}r${FORMAT}aGrand Experience Bottle${FORMAT}r${FORMAT}b.",
        "${FORMAT}6${FORMAT}lGOOD CATCH! ${FORMAT}r${FORMAT}bYou found a ${FORMAT}r${FORMAT}aBlessed Bait${FORMAT}r${FORMAT}b.",
        "${FORMAT}6${FORMAT}lGOOD CATCH! ${FORMAT}r${FORMAT}bYou found a ${FORMAT}r${FORMAT}fDark Bait${FORMAT}r${FORMAT}b.",
        "${FORMAT}6${FORMAT}lGOOD CATCH! ${FORMAT}r${FORMAT}bYou found a ${FORMAT}r${FORMAT}fLight Bait${FORMAT}r${FORMAT}b.",
        "${FORMAT}6${FORMAT}lGOOD CATCH! ${FORMAT}r${FORMAT}bYou found a ${FORMAT}r${FORMAT}aHot Bait${FORMAT}r${FORMAT}b.",
        "${FORMAT}6${FORMAT}lGOOD CATCH! ${FORMAT}r${FORMAT}bYou found a ${FORMAT}r${FORMAT}fSpooky Bait${FORMAT}r${FORMAT}b.",
        "${FORMAT}e[NPC] Jacob${FORMAT}f: ${FORMAT}rMy contest has started!",
        "${FORMAT}eObtain a ${FORMAT}r${FORMAT}6Booster Cookie ${FORMAT}r${FORMAT}efrom the community shop in the hub!",
    )
    private val fireSaleExact = setOf(
        "${FORMAT}6${FORMAT}k${FORMAT}lA${FORMAT}r ${FORMAT}c${FORMAT}lFIRE SALE ${FORMAT}r${FORMAT}6${FORMAT}k${FORMAT}lA",
        "${FORMAT}c${HOT_SPRING} ${FORMAT}eSelling multiple items for a limited time!",
    )
    private val ahDividerExact = setOf(
        "${FORMAT}b-----------------------------------------------------",
        "${FORMAT}eVisit the Auction House to collect your item!",
    )
    private val powderMiningExact = setOf(
        "${FORMAT}aYou uncovered a treasure chest!",
        "${FORMAT}aYou received ${FORMAT}r${FORMAT}f1 ${FORMAT}r${FORMAT}aWishing Compass${FORMAT}r${FORMAT}a.",
        "${FORMAT}aYou received ${FORMAT}r${FORMAT}f1 ${FORMAT}r${FORMAT}9Ascension Rope${FORMAT}r${FORMAT}a.",
        "${FORMAT}aYou received ${FORMAT}r${FORMAT}f1 ${FORMAT}r${FORMAT}aOil Barrel${FORMAT}r${FORMAT}a.",
        "${FORMAT}6You have successfully picked the lock on this chest!",
    )
    private val legacyItemsRx = Regex("""${FORMAT}cYou currently have one or more Legacy Items in your inventory or sacks.*""")
    private val teleportPadExact = setOf("${FORMAT}4This Teleport Pad does not have a destination set!")

    // -- Init ------------------------------------------------------------------

    init {
        addSetting(
            // Lobby
            filterLobbyJoins, filterMysteryBox, filterPrototype, filterTournament,
            // Warping
            filterWarping, filterWelcome, filterProfileJoin,
            // Combat
            filterKillCombo, filterSlayer, filterSlayerDrops, filterUselessDrops,
            filterSoloClass, filterFairy, filterArachne, filterAnnoyingSpam,
            // Economy
            filterBzAhMinis, filterBzOrders, filterSacrifice, filterLegacyItems,
            // Garden
            filterGardenNoPest, filterJacobFortune, filterParkour, filterTeleportPad,
            // Events
            filterGuildExp, filterWinterGift, filterWinterIsland, filterEventLevelUp,
            filterFireSale, filterRewardBundles, filterFactoryUpgrade, filterHoppityEggs,
            filterSkyMall, filterLottery,
            // Misc
            filterWatchdog, filterUselessWarn, filterUselessNotifs, filterPartyDivider,
            filterAhDivider, filterEmpty, filterDungeonStats,
        )
        EventBus.register(this)
    }

    // -- Event -----------------------------------------------------------------

    @SubscribeEvent
    fun onChat(event: ChatEvent.Receive) {
        val msg = event.message ?: return
        if (shouldCancel(msg)) event.setCancelled(true)
    }

    private fun shouldCancel(msg: String): Boolean {

        // -- Empty --------------------------------------------------------------
        if (filterEmpty.value && msg.isBlank()) return true

        // -- Watchdog (multi-line block hider) ----------------------------------
        if (filterWatchdog.value) {
            when (msg) {
                "${FORMAT}f" -> { /* potential start - let through, track below */ }
                "${FORMAT}4[WATCHDOG ANNOUNCEMENT]" -> { inWatchdog = true; watchdogBlockedLines = 0; return true }
                "${FORMAT}c" -> if (inWatchdog) { inWatchdog = false; return true }
            }
            if (inWatchdog) {
                watchdogBlockedLines++
                if (watchdogBlockedLines > 10) { inWatchdog = false; watchdogBlockedLines = 0 }
                return true
            }
        }

        // -- Arachne (stateful separator blocks) -------------------------------
        if (filterArachne.value) {
            if (msg == "${FORMAT}a${FORMAT}l${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}") {
                hideArachneDeadMsg = !hideArachneDeadMsg; return true
            }
            if (msg == "                              ${FORMAT}r${FORMAT}6${FORMAT}lARACHNE DOWN!") { hideArachneDeadMsg = true }
            if (hideArachneDeadMsg) return true
            if (arachneVenomRx.containsMatchIn(msg)) return true
            if (arachneCallingRx.containsMatchIn(msg)) return true
            if (arachneCrystalRx.containsMatchIn(msg)) return true
            if (arachneSpawnRx.containsMatchIn(msg)) return true
        }

        // -- Lobby --------------------------------------------------------------
        if (filterLobbyJoins.value) {
            if (lobbyJoinRx.containsMatchIn(msg)) return true
            if (hypixelSmpRx.containsMatchIn(msg)) return true
        }
        if (filterMysteryBox.value) {
            if (mysteryBoxRx.containsMatchIn(msg)) return true
            if (mysteryDustRx.containsMatchIn(msg)) return true
            if (petConsumRx.containsMatchIn(msg)) return true
        }
        if (filterPrototype.value && (msg in lobbyExact || lobbyContains.any { msg.contains(it) })) return true
        if (filterTournament.value && lobbyContains.filter { it.contains("HYPIXEL") }.any { msg.contains(it) }) return true

        // -- Warping ------------------------------------------------------------
        if (filterWarping.value) {
            if (msg in warpingExact) return true
            if (warpingRx.containsMatchIn(msg)) return true
            if (warpedToRx.containsMatchIn(msg)) return true
        }
        if (filterWelcome.value && msg == "${FORMAT}eWelcome to ${FORMAT}r${FORMAT}aHypixel SkyBlock${FORMAT}r${FORMAT}e!") return true
        if (filterProfileJoin.value && (msg.startsWith("${FORMAT}aYou are playing on profile: ${FORMAT}e") || msg.startsWith("${FORMAT}8Profile ID: "))) return true

        // -- Guild / Event EXP --------------------------------------------------
        if (filterGuildExp.value && guildExpRx.containsMatchIn(msg)) return true

        // -- Kill combo ---------------------------------------------------------
        if (filterKillCombo.value) {
            if (msg == "${FORMAT}6${FORMAT}l+50 Kill Combo") return true
            if (killComboRx.containsMatchIn(msg)) return true
            if (killComboExpRx.containsMatchIn(msg)) return true
        }

        // -- Party dividers -----------------------------------------------------
        if (filterPartyDivider.value && msg == "${FORMAT}9${FORMAT}m-----------------------------------------------------") return true

        // -- AH dividers --------------------------------------------------------
        if (filterAhDivider.value && msg in ahDividerExact) return true

        // -- Slayer -------------------------------------------------------------
        if (filterSlayer.value) {
            if (msg in slayerExact) return true
            if (msg.startsWith("${FORMAT}e${PHONE} RING... ")) return true
            if (slayerStartRx.containsMatchIn(msg)) return true
            if (slayerEndRx.containsMatchIn(msg)) return true
            if (slayerXpRx.containsMatchIn(msg)) return true
            if (slayerLvlRx.containsMatchIn(msg)) return true
            if (slayerQuestRx.containsMatchIn(msg)) return true
        }

        // -- Slayer drops -------------------------------------------------------
        if (filterSlayerDrops.value && slayerDropRxList.any { it.containsMatchIn(msg) }) return true

        // -- Useless drops ------------------------------------------------------
        if (filterUselessDrops.value) {
            if (msg in uselessDropExact) return true
            if (uselessDropRxList.any { it.containsMatchIn(msg) }) return true
        }

        // -- Dungeon stats & rare drops -----------------------------------------
        if (filterDungeonStats.value) {
            if (soloClassRx.containsMatchIn(msg)) return true
            if (soloStatsRx.containsMatchIn(msg)) return true
            if (fairyRxList.any { it.containsMatchIn(msg) }) return true
            if (dungeonRareDropRx.containsMatchIn(msg)) return true
        }

        // -- Sacrifice ----------------------------------------------------------
        if (filterSacrifice.value && sacrificeRxList.any { it.containsMatchIn(msg) }) return true

        // -- Legacy items -------------------------------------------------------
        if (filterLegacyItems.value && legacyItemsRx.containsMatchIn(msg)) return true

        // -- Useless notifications ----------------------------------------------
        if (filterUselessNotifs.value) {
            if (msg in uselessNotifExact) return true
            if (uselessNotifPatterns.any { it.containsMatchIn(msg) }) return true
        }

        // -- Useless warnings ---------------------------------------------------
        if (filterUselessWarn.value && msg in uselessWarningExact) return true

        // -- Annoying spam ------------------------------------------------------
        if (filterAnnoyingSpam.value) {
            if (msg in annoyingSpamExact) return true
            if (annoyingSpamRxList.any { it.containsMatchIn(msg) }) return true
        }

        // -- Bazaar / AH mini operations ----------------------------------------
        if (filterBzAhMinis.value && msg in bzAhMiniExact) return true

        // -- Bazaar order confirmations -----------------------------------------
        if (filterBzOrders.value && bzOrderRxList.any { it.containsMatchIn(msg) }) return true

        // -- Winter island ------------------------------------------------------
        if (filterWinterIsland.value && winterIslandRx.containsMatchIn(msg)) return true

        // -- Winter gift --------------------------------------------------------
        // Matches generic gift reward patterns (coins, XP, north stars, potions, books, enchants)
        if (filterWinterGift.value && isWinterGift(msg)) return true

        // -- SkyMall ------------------------------------------------------------
        if (filterSkyMall.value && msg in skymallRxList) return true

        // -- Lottery ------------------------------------------------------------
        if (filterLottery.value && msg in lotteryRxList) return true

        // -- Event level-up -----------------------------------------------------
        if (filterEventLevelUp.value) {
            if (msg == "${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}${BAR}") return true
            if (eventLvlRxList.any { it.containsMatchIn(msg) }) return true
        }

        // -- Fire sale ----------------------------------------------------------
        if (filterFireSale.value) {
            if (msg in fireSaleExact) return true
            if (fireSaleMainRx.containsMatchIn(msg)) return true
            if (fireSaleRxList.any { it.containsMatchIn(msg) }) return true
        }

        // -- Reward bundles -----------------------------------------------------
        if (filterRewardBundles.value && rewardBundleRxList.any { it.containsMatchIn(msg) }) return true

        // -- Chocolate factory upgrades -----------------------------------------
        if (filterFactoryUpgrade.value && factoryUpgradeRxList.any { it.containsMatchIn(msg) }) return true

        // -- Hoppity's Hunt -----------------------------------------------------
        if (filterHoppityEggs.value && (hoppityAppearRx.containsMatchIn(msg) || hoppityBeginRx.containsMatchIn(msg))) return true

        // -- Jacob fortune ------------------------------------------------------
        if (filterJacobFortune.value && jacobFortuneRx.containsMatchIn(msg)) return true

        // -- Garden: No Pests ---------------------------------------------------
        if (filterGardenNoPest.value && noPestRxList.any { it.containsMatchIn(msg) }) return true

        // -- Parkour ------------------------------------------------------------
        if (filterParkour.value) {
            if (parkourRxList.any { it.containsMatchIn(msg) }) return true
            if (msg in setOf("${FORMAT}4Cancelled parkour! You cannot fly.", "${FORMAT}4Cancelled parkour! You cannot use item abilities.", "${FORMAT}4Cancelled parkour!")) return true
        }

        // -- Teleport pads ------------------------------------------------------
        if (filterTeleportPad.value) {
            if (teleportPadRx.containsMatchIn(msg)) return true
            if (msg in teleportPadExact) return true
        }

        // -- Solo class ---------------------------------------------------------
        if (filterSoloClass.value && soloClassRx.containsMatchIn(msg)) return true

        return false
    }

    // Winter gift messages share a generic reward structure; match a few key prefixes
    private val winterGiftRxList = listOf(
        Regex("""${FORMAT}aYou received ${FORMAT}r${FORMAT}6\d[\d,.]* Coins ${FORMAT}r${FORMAT}afrom a (?:Great|Good|Normal|Perfect|Magical)? ?Winter Gift!"""),
        Regex("""${FORMAT}aYou received ${FORMAT}r${FORMAT}b\d[\d,.]* ${FORMAT}r${FORMAT}6SkyBlock XP ${FORMAT}r${FORMAT}afrom a .*Winter Gift!"""),
        Regex("""${FORMAT}aYou received ${FORMAT}r${FORMAT}b\d[\d,.]* ${FORMAT}r${FORMAT}bNorth Stars ${FORMAT}r${FORMAT}afrom a .*Winter Gift!"""),
        Regex("""${FORMAT}aYou received .* ${FORMAT}r${FORMAT}afrom a .*Winter Gift!"""),
        Regex("""${FORMAT}7Right-click the gift to open it!"""),
        Regex("""${FORMAT}a\+\d+ (?:Farming|Mining|Combat|Foraging|Fishing|Enchanting|Alchemy) XP"""),
    )
    private fun isWinterGift(msg: String) = winterGiftRxList.any { it.containsMatchIn(msg) }
}
