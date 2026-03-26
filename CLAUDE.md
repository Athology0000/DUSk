# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

```bash
# Build the mod JAR
./gradlew build

# Run Minecraft client with the mod loaded (development)
./gradlew runClient

# Build output is in build/libs/
```

There are no unit tests in this project.

## Project Overview

Cobalt is a Fabric Minecraft mod (MC 1.21.11, Java 21, Kotlin) that provides a modular automation framework. It uses official Mojang mappings via Fabric Loom. The entry point is `org.cobalt.Cobalt` (client) and `org.cobalt.PreLaunch` (pre-launch).

## Source Layout

```
src/main/
  java/org/cobalt/
    bridge/module/     # Java interfaces for Mixin injection targets
    init/              # MixinAutoDiscover (pre-launch mixin registration)
    mixin/             # All Mixin classes (client/, render/, network/, rsa/)
    render/            # Java-side renderers (DarkModeRenderer, etc.)
  kotlin/org/cobalt/
    api/               # Public API — everything addons can use
      addon/           # Addon base class and metadata
      command/         # Command base class, CommandManager, annotations
      event/           # EventBus, Event base, all event types
      hud/             # HudElement, HudModuleManager, hudElement() DSL
      module/          # Module base class, ModuleManager, Setting system
      notification/    # NotificationManager
      pathfinder/      # A* pathfinder, nodes, path execution, movement rules
      rotation/        # RotationExecutor, IRotationStrategy, strategies
      ui/theme/        # Theme system (ThemeManager, ThemePalette, presets)
      util/            # ChatUtils, AngleUtils, NVGRenderer, MovementManager, etc.
    internal/          # Built-in module implementations (not public API)
      chat/            # ChatFilterModule
      combat/          # CombatMacroModule, CombatHudModule
      dungeons/        # DungeonsModule
      etherwarp/       # EtherwarpHelperModule, LeftClickEtherwarpModule
      farming/         # FarmingMacroModule
      garden/          # GardenMacroModule, GardenConfig, ScriptBridge, managers/
      grotto/          # GrottoModule, route scanning, Crystal Hollows utilities
      helper/          # Config (save/load), WalkbackBridge
      loader/          # AddonLoader — discovers and loads addon JARs
      mining/          # MiningModule, MiningMacroModule, RoutesModule, etc.
      pathfinding/     # DuskPathfinder, HeadRotationModule, path profiles
      pig/             # PigMacroModule
      qol/             # QolModule
      rotation/        # RotationsModule
      spotify/         # SpotifyModule
      ui/              # All NanoVG UI — panels, components, HUD editor, themes
      visual/          # FullBright, DarkMode, BlockOverlay, Freecam, etc.
```

## Core Systems

### Module System
All features are `Module` subclasses. Modules are registered in `Cobalt.onInitializeClient()`. Settings use Kotlin property delegation:
```kotlin
class MyModule : Module("My Module") {
  val speed by SliderSetting("Speed", "Speed multiplier", 1.0, 0.1, 5.0)
  val enabled by CheckboxSetting("Enabled", "", false)
  val mode by ModeSetting("Mode", "", 0, arrayOf("Fast", "Slow"))  // .value is the index
}
```
Singletons use `object MyModule : Module("Name")`.

Available setting types: `SliderSetting` (Double, optional `step` param), `CheckboxSetting` (Boolean), `ModeSetting` (Int index into `options` array), `ColorSetting`, `TextSetting`, `KeyBindSetting`, `RangeSetting`, `ActionSetting` (button), `CommandHotkeySetting`, `InfoSetting` (read-only label).

### Event System
`EventBus` uses `@SubscribeEvent` annotation. Any class must call `EventBus.register(this)` before events fire. Handlers must have exactly one `Event` parameter:
```kotlin
@SubscribeEvent
fun onTick(event: TickEvent.Start) { ... }
```
Available events: `TickEvent.Start/End`, `WorldRenderEvent.Last`, `GuiRenderEvent`, `GuiPostRenderEvent`, `NvgEvent`, `ChatEvent`, `PacketEvent`, `MouseEvent`, `BlockChangeEvent`.

### HUD Elements
Created via the `hudElement()` DSL on a `Module`. Rendered with `NVGRenderer` (NanoVG). Settings inside HUD builders use `setting()` + `.value` (not `by` delegation):
```kotlin
val myHud = hudElement("my-hud", "My HUD") {
  anchor = HudAnchor.TOP_LEFT
  val show = setting(CheckboxSetting("Show", "", true))
  width { 100f }
  height { 20f }
  render { x, y, scale -> if (show.value) NVGRenderer.rect(...) }
  postRender { x, y, scale -> /* non-NVG rendering via GuiGraphics */ }
}
```
`width` and `height` lambdas are called every frame, so they can reference setting values. `minScale`/`maxScale` can also be set in the builder.

### Command System
Commands extend `Command(name, aliases)`. Methods annotated with `@DefaultHandler` handle the base command; `@SubCommand("name")` handles subcommands. Register via `CommandManager.register(...)`.

### Addon System
External addons are JARs placed in `config/cobalt/addons/`. Each JAR must contain `cobalt.addon.json` specifying `id`, `name`, `version`, `entrypoints` (classes implementing `Addon`), and optional `mixins`. In development mode, Fabric entrypoints named `"cobalt"` are also discovered. Implement `Addon.onLoad()`, `onUnload()`, and override `getModules()` to register modules.

### Config Persistence
Settings and HUD positions are serialized automatically to `config/cobalt/addons.json`. Themes save to `config/cobalt/themes.json`. `Config.loadModulesConfig()` is called at startup; `Config.saveModulesConfig()` must be called explicitly to persist changes (triggered by the UI).

### Rotation System
`RotationExecutor` applies GCD-aware rotation smoothing each render frame (`WorldRenderEvent.Last`). Call `RotationExecutor.rotateTo(endRot, strategy)` with an `IRotationStrategy`. Built-in strategies: `BezierTrackingRotationStrategy`, `TimedEaseStrategy`, `TrackingRotationStrategy`, `HeadRotationStrategy`.

### Pathfinding
`DuskPathfinder` (internal) wraps the `AStarPathfinder` from the API. `PathfindingModule` is the user-facing control surface. `PathPlanProfiles` defines preconfigured `PathfinderConfiguration` instances for different movement profiles.

### Theme System
`ThemeManager.currentTheme` provides color tokens used throughout NVG rendering (e.g., `theme.accent`, `theme.panel`, `theme.overlay`). Built-in: `DarkTheme`, `LightTheme`. Custom themes are serialized as `CustomTheme`.
