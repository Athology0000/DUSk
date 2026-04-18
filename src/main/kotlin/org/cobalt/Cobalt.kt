package org.cobalt

import net.fabricmc.api.ClientModInitializer
import net.minecraft.network.protocol.game.ClientboundRespawnPacket
import org.cobalt.api.command.CommandManager
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.PacketEvent
import org.cobalt.api.hud.HudModuleManager
import org.cobalt.api.hud.modules.WatermarkModule
import org.cobalt.api.hud.modules.InventoryHudModule
import org.cobalt.api.module.ModuleManager
import org.cobalt.api.notification.NotificationManager
import org.cobalt.api.pathfinder.jni.NativePathfinder
import org.cobalt.api.rotation.RotationExecutor
import org.cobalt.api.util.TickScheduler
import org.cobalt.internal.combat.CombatHudModule
import org.cobalt.internal.combat.CombatMacroModule
import org.cobalt.internal.combat.CombatPatrolModule
import org.cobalt.internal.command.MainCommand
import org.cobalt.internal.dungeons.BloodCampHelperModule
import org.cobalt.internal.dungeons.DungeonMapModule
import org.cobalt.internal.dungeons.DungeonRoutesModule
import org.cobalt.internal.dungeons.DungeonsModule
import org.cobalt.internal.etherwarp.EtherwarpHelperModule
import org.cobalt.internal.etherwarp.LeftClickEtherwarpModule
import org.cobalt.internal.etherwarp.SmoothAotvModule
import org.cobalt.internal.helper.Config
import org.cobalt.internal.loader.AddonLoader
import org.cobalt.internal.mining.MiningModule
import org.cobalt.internal.mining.FairyModule
import org.cobalt.internal.mining.RoutesModule
import org.cobalt.internal.mining.MiningMacroModule
import org.cobalt.api.hud.modules.MiningHudModule
import org.cobalt.internal.mining.MiningCoinPopupModule
import org.cobalt.api.hud.modules.CommissionMacroModule
import org.cobalt.internal.mining.AutoForgeModule
import org.cobalt.internal.mining.VeinDirectionModule
import org.cobalt.internal.mining.AutoLanternModule
import org.cobalt.internal.mining.NoFrillsMiningModule
import org.cobalt.internal.mining.CommissionHudModule
import org.cobalt.internal.pathfinding.PathfindingModule
import org.cobalt.internal.qol.AutoStashModule
import org.cobalt.internal.qol.ItemLockingModule
import org.cobalt.internal.qol.PriceTooltipModule
import org.cobalt.internal.qol.QolModule
import org.cobalt.internal.stats.MacroTimeTracker
import org.cobalt.internal.visual.BlockOverlayModule
import org.cobalt.internal.visual.BlockOutlineModule
import org.cobalt.internal.visual.DarkModeModule
import org.cobalt.internal.visual.FreecamModule
import org.cobalt.internal.visual.FullBrightModule
import org.cobalt.internal.visual.SkyboxChangerModule
import org.cobalt.internal.rotation.RotationsModule
import org.cobalt.internal.visual.DeployableHudModule
import org.cobalt.internal.visual.HotbarOverlayModule
import org.cobalt.internal.visual.MobEspModule
import org.cobalt.internal.visual.OrbitFreecamModule
import org.cobalt.internal.visual.PetDisplayModule
import org.cobalt.internal.visual.TitleScreenRenderer
import org.cobalt.internal.visual.WitherImpactOverlayModule
import org.cobalt.internal.garden.GardenAnalyzerModule
import org.cobalt.internal.garden.GardenMacroModule
import org.cobalt.internal.farming.FarmingMacroModule
import org.cobalt.internal.pig.PigMacroModule
import org.cobalt.internal.spotify.SpotifyModule
import org.cobalt.internal.chat.ChatFilterModule
import org.cobalt.internal.diana.DianaMacroModule
import org.cobalt.internal.diana.DianaHelperModule
import org.cobalt.internal.wardrobe.WardrobeModule
import org.cobalt.internal.garden.GardenHudModule
import org.cobalt.internal.routes.RouteEditMode
import org.cobalt.internal.routes.RouteStore

@Suppress("UNUSED")
object Cobalt : ClientModInitializer {


  override fun onInitializeClient() {
    ModuleManager.addModules(
      listOf(
        WatermarkModule(),
        InventoryHudModule(),
        MiningModule,
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
        CombatMacroModule,
        CombatPatrolModule,
        CombatHudModule,
        DungeonsModule,
        BloodCampHelperModule,
        DungeonMapModule,
        DungeonRoutesModule,
        EtherwarpHelperModule,
        LeftClickEtherwarpModule,
        SmoothAotvModule,
        PathfindingModule,
        FullBrightModule,
        SkyboxChangerModule,
        DarkModeModule,
        FreecamModule,
        DeployableHudModule,
        OrbitFreecamModule,
        BlockOverlayModule,
        BlockOutlineModule,
        MobEspModule,
        WitherImpactOverlayModule,
        QolModule,
        ItemLockingModule,
        PriceTooltipModule,
        AutoStashModule,
        RotationsModule,
        HotbarOverlayModule,
        PetDisplayModule,
        GardenAnalyzerModule,
        GardenMacroModule,
        FarmingMacroModule,
        PigMacroModule,
        SpotifyModule,
        ChatFilterModule,
        DianaMacroModule,
        DianaHelperModule,
        WardrobeModule,
        GardenHudModule,
      )
    )

    AddonLoader.getAddons().map { it.second }.forEach {
      it.onLoad()
      ModuleManager.addModules(it.getModules())
    }

    CommandManager.register(MainCommand)
    CommandManager.dispatchAll()
    MacroTimeTracker.load()

    listOf(
      TickScheduler, MainCommand, NotificationManager,
      RotationExecutor, HudModuleManager, TitleScreenRenderer, MacroTimeTracker,
      RouteEditMode,
    ).forEach { EventBus.register(it) }
    NativePathfinder.init()
    Config.loadModulesConfig()
    RouteStore.migrate()
    RouteStore.loadAssignments()
    ItemLockingModule.loadPersistedState()
    WardrobeModule.loadFavorites()
    EventBus.register(this)
    println("Dutt Client Initialized")
  }

  @SubscribeEvent
  fun onRespawn(event: PacketEvent.Incoming) {
    if (event.packet is ClientboundRespawnPacket) NativePathfinder.onLevelChange()
  }

}
