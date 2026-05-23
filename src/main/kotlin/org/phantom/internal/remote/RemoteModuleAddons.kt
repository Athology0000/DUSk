package org.phantom.internal.remote

import org.phantom.api.addon.Addon
import org.phantom.api.hud.modules.InventoryHudModule
import org.phantom.api.hud.modules.MiningHudModule
import org.phantom.api.hud.modules.WatermarkModule
import org.phantom.api.module.Module
import org.phantom.internal.BuiltinModules
import org.phantom.internal.chat.ChatFilterModule
import org.phantom.internal.combat.CombatHudModule
import org.phantom.internal.combat.CombatMacroModule
import org.phantom.internal.combat.CombatPatrolModule
import org.phantom.internal.combat.slayer.BlazeSlayerMacroModule
import org.phantom.internal.combat.slayer.CocoonAlertModule
import org.phantom.internal.combat.slayer.EndermanSlayerMacroModule
import org.phantom.internal.combat.slayer.MinibossAlertModule
import org.phantom.internal.combat.slayer.SpiderSlayerMacroModule
import org.phantom.internal.combat.slayer.VampireSlayerMacroModule
import org.phantom.internal.combat.slayer.WolfSlayerMacroModule
import org.phantom.internal.combat.slayer.ZombieSlayerMacroModule
import org.phantom.internal.crimson.AutoDojoModule
import org.phantom.internal.diana.DianaHelperModule
import org.phantom.internal.diana.DianaMacroModule
import org.phantom.internal.dungeons.BloodCampHelperModule
import org.phantom.internal.dungeons.DungeonMapModule
import org.phantom.internal.dungeons.DungeonRoutesModule
import org.phantom.internal.dungeons.DungeonsModule
import org.phantom.internal.dungeons.autoroutes.AutoRoutesModule
import org.phantom.internal.etherwarp.EtherwarpHelperModule
import org.phantom.internal.etherwarp.LeftClickEtherwarpModule
import org.phantom.internal.etherwarp.SmoothAotvModule
import org.phantom.internal.farming.FarmingMacroModule
import org.phantom.internal.fishing.FishingHotspotModule
import org.phantom.internal.fishing.FishingMacroModule
import org.phantom.internal.fishing.FishingQolModule
import org.phantom.internal.grotto.FairyGrottoModule
import org.phantom.internal.mining.AutoForgeModule
import org.phantom.internal.mining.AutoLanternModule
import org.phantom.internal.mining.BlockMinerModule
import org.phantom.internal.mining.CommissionHudModule
import org.phantom.internal.mining.CommissionMacroModule
import org.phantom.internal.mining.GemstoneMinerModule
import org.phantom.internal.mining.MiningCoinPopupModule
import org.phantom.internal.mining.MiningMacroModule
import org.phantom.internal.mining.MiningModule
import org.phantom.internal.mining.NoFrillsMiningModule
import org.phantom.internal.mining.RoutesModule
import org.phantom.internal.mining.VeinDirectionModule
import org.phantom.internal.mining.VeinScannerModule
import org.phantom.internal.mining.WorldVeinCacherModule
import org.phantom.internal.mining.excavator.ExcavatorMacroModule
import org.phantom.internal.mining.failsafe.MiningFailsafeHudModule
import org.phantom.internal.mining.lobby.LobbyHopperModule
import org.phantom.internal.mining.ore.OreMacroModule
import org.phantom.internal.mining.pingless.PinglessMiningModule
import org.phantom.internal.mining.powder.PowderMacroModule
import org.phantom.internal.mining.scatha.ScathaMacroModule
import org.phantom.internal.mining.tunnels.TunnelMinerModule
import org.phantom.internal.pathfinding.PathfindingModule
import org.phantom.internal.pathfinding.debug.PathPreviewRenderModule
import org.phantom.internal.pig.PigMacroModule
import org.phantom.internal.qol.AutoStashModule
import org.phantom.internal.qol.ColoredEnchantsModule
import org.phantom.internal.qol.ItemLockingModule
import org.phantom.internal.qol.LagDetectorModule
import org.phantom.internal.qol.MissingEnchantsModule
import org.phantom.internal.qol.PriceTooltipModule
import org.phantom.internal.qol.QolModule
import org.phantom.internal.qol.RsaAutoGfsModule
import org.phantom.internal.qol.TimerModule
import org.phantom.internal.rotation.RotationsModule
import org.phantom.internal.seal.YearOfTheSealModule
import org.phantom.internal.spotify.SpotifyModule
import org.phantom.internal.visual.ArrowHitboxesModule
import org.phantom.internal.visual.BlockOutlineModule
import org.phantom.internal.visual.BlockOverlayModule
import org.phantom.internal.visual.CustomScoreboardModule
import org.phantom.internal.visual.DeadEntityCleanerModule
import org.phantom.internal.visual.DeployableHudModule
import org.phantom.internal.visual.FreecamModule
import org.phantom.internal.visual.FullBrightModule
import org.phantom.internal.visual.HotbarOverlayModule
import org.phantom.internal.visual.OrbitFreecamModule
import org.phantom.internal.visual.PetDisplayModule
import org.phantom.internal.visual.RsaEffectsModule
import org.phantom.internal.visual.RsaPresetWaypointsModule
import org.phantom.internal.visual.WitherImpactOverlayModule

class PhantomCoreAddon : Addon() {
  override fun onLoad() {
    ItemLockingModule.loadPersistedState()
  }

  override fun onUnload() {}

  override fun getModules(): List<Module> = listOf(
    // HUD / core UI
    WatermarkModule(),
    InventoryHudModule(),

    // Pathfinding / rotation infrastructure modules (classes live in loader)
    PathfindingModule,
    PathPreviewRenderModule,
    RotationsModule,

    // Combat (top-level — slayer-specific is in PhantomSlayerAddon)
    CombatHudModule,
    CombatMacroModule,
    CombatPatrolModule,

    // Farming / fishing / pig / seal
    FarmingMacroModule,
    FishingMacroModule,
    FishingHotspotModule,
    FishingQolModule,
    PigMacroModule,
    YearOfTheSealModule,

    // Dungeons + helper
    DungeonsModule,
    DungeonMapModule,
    DungeonRoutesModule,
    AutoRoutesModule,
    BloodCampHelperModule,
    AutoDojoModule,

    // Etherwarp / movement helpers
    EtherwarpHelperModule,
    LeftClickEtherwarpModule,
    SmoothAotvModule,

    // QoL
    QolModule,
    AutoStashModule,
    ItemLockingModule,
    PriceTooltipModule,
    ColoredEnchantsModule,
    MissingEnchantsModule,
    LagDetectorModule,
    TimerModule,
    RsaAutoGfsModule,
    SpotifyModule,

    // Visuals
    FullBrightModule,
    FreecamModule,
    OrbitFreecamModule,
    BlockOverlayModule,
    BlockOutlineModule,
    ArrowHitboxesModule,
    CustomScoreboardModule,
    HotbarOverlayModule,
    PetDisplayModule,
    DeadEntityCleanerModule,
    DeployableHudModule,
    RsaEffectsModule,
    RsaPresetWaypointsModule,
    WitherImpactOverlayModule,

    // Chat
    ChatFilterModule,
  ).distinctBy { it.name.trim().lowercase() }
}

class PhantomRemoteAddon : Addon() {
  override fun onLoad() {
    ItemLockingModule.loadPersistedState()
  }

  override fun onUnload() {}

  override fun getModules(): List<Module> = BuiltinModules.all()
}

class PhantomMiningAddon : Addon() {
  override fun onLoad() {}

  override fun onUnload() {}

  override fun getModules(): List<Module> = listOf(
    MiningModule,
    MiningMacroModule,
    MiningHudModule,
    MiningCoinPopupModule,
    RoutesModule,
    VeinScannerModule,
    BlockMinerModule,
    GemstoneMinerModule,
    CommissionMacroModule,
    CommissionHudModule,
    AutoForgeModule,
    AutoLanternModule,
    FairyGrottoModule,
    VeinDirectionModule,
    NoFrillsMiningModule,
    MiningFailsafeHudModule,
    PinglessMiningModule,
    ExcavatorMacroModule,
    OreMacroModule,
    PowderMacroModule,
    ScathaMacroModule,
    LobbyHopperModule,
    TunnelMinerModule,
    WorldVeinCacherModule,
  ).distinctBy { it.name.trim().lowercase() }
}

class PhantomDianaAddon : Addon() {
  override fun onLoad() {}

  override fun onUnload() {}

  override fun getModules(): List<Module> = listOf(
    DianaMacroModule,
    DianaHelperModule,
  ).distinctBy { it.name.trim().lowercase() }
}

class PhantomSlayerAddon : Addon() {
  override fun onLoad() {}

  override fun onUnload() {}

  override fun getModules(): List<Module> = listOf(
    ZombieSlayerMacroModule,
    SpiderSlayerMacroModule,
    WolfSlayerMacroModule,
    EndermanSlayerMacroModule,
    BlazeSlayerMacroModule,
    VampireSlayerMacroModule,
    CocoonAlertModule,
    MinibossAlertModule,
  ).distinctBy { it.name.trim().lowercase() }
}
