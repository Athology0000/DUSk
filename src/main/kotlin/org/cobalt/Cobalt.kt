package org.cobalt

import net.fabricmc.api.ClientModInitializer
import net.minecraft.network.protocol.game.ClientboundRespawnPacket
import org.cobalt.api.command.CommandManager
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.PacketEvent
import org.cobalt.api.hud.HudModuleManager
import org.cobalt.api.hud.modules.InventoryHudModule
import org.cobalt.api.hud.modules.MiningHudModule
import org.cobalt.api.hud.modules.WatermarkModule
import org.cobalt.api.module.ModuleManager
import org.cobalt.api.notification.NotificationManager
import org.cobalt.api.pathfinder.jni.ChunkSerializer
import org.cobalt.api.pathfinder.jni.NativePathfinder
import org.cobalt.api.rotation.RotationExecutor
import org.cobalt.api.util.TickScheduler
import org.cobalt.api.util.WorldScanner
import org.cobalt.internal.auth.DevUnlock
import org.cobalt.internal.chat.ChatFilterModule
import org.cobalt.internal.chat.RngDropDisplayModule
import org.cobalt.internal.combat.CombatHudModule
import org.cobalt.internal.combat.CombatMacroModule
import org.cobalt.internal.combat.CombatPatrolModule
import org.cobalt.internal.combat.slayer.*
import org.cobalt.internal.command.MainCommand
import org.cobalt.internal.diana.DianaHelperModule
import org.cobalt.internal.diana.DianaMacroModule
import org.cobalt.internal.dungeons.BloodCampHelperModule
import org.cobalt.internal.dungeons.DungeonMapModule
import org.cobalt.internal.dungeons.DungeonRoutesModule
import org.cobalt.internal.dungeons.DungeonsModule
import org.cobalt.internal.dungeons.MobEspModule as DungeonMobEspModule
import org.cobalt.internal.dungeons.gambling.DungeonChestGamblingModule
import org.cobalt.internal.etherwarp.EtherwarpHelperModule
import org.cobalt.internal.etherwarp.LeftClickEtherwarpModule
import org.cobalt.internal.etherwarp.SmoothAotvModule
import org.cobalt.internal.farming.FarmingMacroModule
import org.cobalt.internal.fishing.*
import org.cobalt.internal.garden.GardenAnalyzerModule
import org.cobalt.internal.garden.GardenHudModule
import org.cobalt.internal.garden.GardenMacroModule
import org.cobalt.internal.garden.PestWarningModule
import org.cobalt.internal.helper.Config
import org.cobalt.internal.loader.AddonLoader
import org.cobalt.internal.mining.*
import org.cobalt.internal.mining.excavator.ExcavatorMacroModule
import org.cobalt.internal.mining.failsafe.MiningFailsafeHudModule
import org.cobalt.internal.mining.lobby.LobbyHopperModule
import org.cobalt.internal.mining.ore.OreMacroModule
import org.cobalt.internal.mining.pingless.PinglessMiningModule
import org.cobalt.internal.mining.powder.PowderMacroModule
import org.cobalt.internal.mining.scatha.ScathaMacroModule
import org.cobalt.internal.pathfinding.PathfindingModule
import org.cobalt.internal.pathfinding.WorldCacheModule
import org.cobalt.internal.pathfinding.debug.PathPreviewRenderModule
import org.cobalt.internal.pathfinding.debug.PathRouteQuickAssignCommand
import org.cobalt.internal.pig.PigMacroModule
import org.cobalt.internal.qol.*
import org.cobalt.internal.rotation.RotationsModule
import org.cobalt.internal.routes.RouteEditMode
import org.cobalt.internal.routes.RouteStore
import org.cobalt.internal.seal.YearOfTheSealModule
import org.cobalt.internal.skyblock.HypixelManager
import org.cobalt.internal.skyblock.SkyblockEventManager
import org.cobalt.internal.spotify.SpotifyModule
import org.cobalt.internal.stats.MacroTimeTracker
import org.cobalt.internal.visual.*
import org.cobalt.internal.visual.MobEspModule as VisualMobEspModule
import org.cobalt.internal.wardrobe.WardrobeModule

@Suppress("UNUSED")
object Cobalt : ClientModInitializer {

  override fun onInitializeClient() {
    registerModules()
    loadAddons()
    registerCommands()
    registerCoreEventListeners()

    NativePathfinder.init()
    ChunkSerializer.register()
    HypixelManager.init()
    WorldScanner

    Config.loadModulesConfig()
    RouteStore.migrate()
    RouteStore.loadAssignments()
    ItemLockingModule.loadPersistedState()
    WardrobeModule.loadFavorites()
    DevUnlock.apply("remote auth disabled")

    EventBus.register(this)
    println("Dutt Client Initialized")
  }

  private fun registerModules() {
    ModuleManager.addModules(
      listOf(
        WatermarkModule(),
        InventoryHudModule(),

        MiningModule,
        BlockMinerModule,
        GemstoneMinerModule,
        MiningHudModule,
        MiningCoinPopupModule,
        FairyModule,
        RoutesModule,
        MiningMacroModule,
        CommissionMacroModule,
        CommissionHudModule,
        AutoForgeModule,
        VeinDirectionModule,
        AutoLanternModule,
        NoFrillsMiningModule,
        ExcavatorMacroModule,
        OreMacroModule,
        PowderMacroModule,
        ScathaMacroModule,
        PinglessMiningModule,
        MiningFailsafeHudModule,
        LobbyHopperModule,

        CombatMacroModule,
        ZombieSlayerMacroModule,
        WolfSlayerMacroModule,
        SpiderSlayerMacroModule,
        EndermanSlayerMacroModule,
        VampireSlayerMacroModule,
        BlazeSlayerMacroModule,
        CombatPatrolModule,
        CombatHudModule,

        DungeonsModule,
        DungeonMobEspModule,
        DungeonChestGamblingModule,
        BloodCampHelperModule,
        DungeonMapModule,
        DungeonRoutesModule,

        EtherwarpHelperModule,
        LeftClickEtherwarpModule,
        SmoothAotvModule,
        PathfindingModule,
        PathPreviewRenderModule,
        WorldCacheModule,

        FullBrightModule,
        SkyboxChangerModule,
        CustomScoreboardModule,
        DeadEntityCleanerModule,
        FreecamModule,
        DeployableHudModule,
        OrbitFreecamModule,
        BlockOverlayModule,
        BlockOutlineModule,
        VisualMobEspModule,
        WitherImpactOverlayModule,

        QolModule,
        ItemLockingModule,
        PriceTooltipModule,
        CraftHelperModule,
        AutoStashModule,
        RsaAutoGfsModule,
        RotationsModule,
        HotbarOverlayModule,
        PetDisplayModule,
        RsaEffectsModule,
        RsaPresetWaypointsModule,

        GardenAnalyzerModule,
        GardenMacroModule,
        PestWarningModule,
        GardenHudModule,
        FarmingMacroModule,
        FishingMacroModule,
        FishingHotspotModule,
        FishingCastPreviewModule,
        SoulWhipPreviewModule,
        MageBeamModule,
        FishingQolModule,
        YearOfTheSealModule,
        PigMacroModule,
        SpotifyModule,
        ChatFilterModule,
        RngDropDisplayModule,
        DianaMacroModule,
        DianaHelperModule,
        WardrobeModule,
      ).distinctBy { it.name.trim().lowercase() }
    )
  }

  private fun loadAddons() {
    AddonLoader.getAddons()
      .map { it.second }
      .forEach { addon ->
        addon.onLoad()
        ModuleManager.addModules(addon.getModules())
      }
  }

  private fun registerCommands() {
    CommandManager.register(MainCommand)
    PathRouteQuickAssignCommand.register()
    CommandManager.dispatchAll()
    MacroTimeTracker.load()
  }

  private fun registerCoreEventListeners() {
    listOf(
      TickScheduler,
      MainCommand,
      NotificationManager,
      RotationExecutor,
      HudModuleManager,
      TitleScreenRenderer,
      MacroTimeTracker,
      RouteEditMode,
      SkyblockEventManager,
    ).forEach { EventBus.register(it) }
  }

  @SubscribeEvent
  fun onRespawn(event: PacketEvent.Incoming) {
    if (event.packet is ClientboundRespawnPacket) {
      NativePathfinder.onLevelChange()
      ChunkSerializer.invalidate()
      RouteStore.clearAllLoaded()
      RouteEditMode.onLevelChange()
      CommissionMacroModule.onLevelChange()
      RoutesModule.onLevelChange()
      MiningMacroModule.onLevelChange()
      GemstoneMinerModule.onLevelChange()
      YearOfTheSealModule.onLevelChange()
    }
  }
}
