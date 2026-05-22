# Mixin → Protected-Module Decoupling (Event Bus) — Implementation Plan

**Goal:** Make `phantom.jar` (the loader) launch without crashing. Public-layer
mixins currently call protected `internal/**` module singletons directly
(`DungeonChestGamblingModule.INSTANCE.x()`, etc.). Those classes are stripped
from the loader jar and, even after auth, load into an isolated in-memory
classloader the mixins cannot see — so the reference is an unconditional
`NoClassDefFoundError`.

**Fix:** invert every coupling. Mixins post events on the public `EventBus`
(`org.phantom.api.event`, shipped in the loader jar). Protected modules
`EventBus.register(this)` and handle the events with `@SubscribeEvent`. The
mixin never names a protected class; before modules load, the event simply has
no subscriber and the mixin no-ops.

**Verification:** `./gradlew clientPublicApiJar -x buildNative -x copyNativeDll`
then `cd Loader && ./gradlew build`, then launch `phantom.jar` in a Fabric
1.21.11 instance — no `NoClassDefFoundError`.

## Affected files

12 mixins (all `src/main/java/org/phantom/mixin/`): `client/MinecraftMixin`,
`render/ScreenMixin`, `client/AbstractContainerScreenMixin`,
`render/FishingHookRendererMixin`, `render/CameraMixin`,
`client/ChatComponentMixin`, `client/FishingHookMixin`,
`client/KeyboardInputMixin`, `client/GuiEventListenerMixin`,
`client/WardrobeScreenMixin`, `client/LocalPlayerMixin`,
`client/MouseHandlerMixin`.

8 modules: `dungeons/gambling/DungeonChestGamblingModule`,
`dungeons/DungeonsModule`, `fishing/FishingQolModule`,
`etherwarp/SmoothAotvModule`, `qol/AutoStashModule`, `qol/ItemLockingModule`,
`garden/managers/PestCleaningSequencer`, `wardrobe/WardrobeModule`.

## Task 1: New event types (public layer)

Create under `src/main/kotlin/org/phantom/api/event/impl/`. All carry the MC
context the subscriber needs; cancellable ones extend `Event(true)`. Query
events expose a mutable result field defaulting to the no-op value.

- [ ] `client/ScreenOpenEvent(screen: Screen?)` — non-cancellable notify.
- [ ] `render/ScreenDrawEvent(screen: Screen, graphics: GuiGraphics)` — cancellable.
- [ ] `client/ScreenKeyEvent(keyCode: Int, released: Boolean)` — cancellable.
- [ ] `client/ContainerMouseEvent(kind: Kind)` where `Kind` ∈ {CLICK, RELEASE, DRAG, SCROLL} — cancellable.
- [ ] `client/ContainerSlotClickEvent(menuTitle: String, slotId: Int, button: Int, clickType: ClickType)` — cancellable.
- [ ] `client/ContainerScreenInitEvent(screen: AbstractContainerScreen<*>)` — non-cancellable notify (modules add their own widgets).
- [ ] `render/ContainerSlotRenderEvent(graphics, slot, x: Int, y: Int)` — non-cancellable notify.
- [ ] `render/ContainerDrawEvent(screen: AbstractContainerScreen<*>, graphics, mouseX, mouseY, partialTick)` — cancellable.
- [ ] `client/ChatMessageEvent(message: String)` — non-cancellable notify.
- [ ] `render/CameraSetupEvent` with `var positionOverride: Vec3? = null` — query.
- [ ] `client/FishingBobberRenderEvent(isLocalPlayerOwned: Boolean)` with `var hide: Boolean = false` — query.
- [ ] `client/FishingBobberFixEvent` with `var lavaFix: Boolean = false` — query (replaces `FishingQolModule.shouldFixBobber()`; fired once per use site).
- [ ] `client/ForcedKeyQueryEvent(key: Key)` where `Key` ∈ {BACKWARD, …} with `var pressed: Boolean = false` — query (for `DungeonsModule.shouldPressBackward`).
- [ ] `client/PlayerVelocityCancelEvent` with `var cancel: Boolean = false` — query.
- [ ] `client/ItemDropQueryEvent` with `var cancel: Boolean = false` — query.

Keep names/packages consistent with existing events. Register nothing here —
events are POJOs.

## Task 2: Rewrite the 12 mixins

Each `import org.phantom.internal.*` line is deleted; replace the call:

- [ ] **MinecraftMixin** `phantom$showWelcomeBeforeTitle`: replace
  `DungeonChestGamblingModule.INSTANCE.onScreenChanged(screen)` with
  `new ScreenOpenEvent(screen).post();`. (Keep the welcome-screen redirect — it
  uses `internal/visual`, which is public.)
- [ ] **ScreenMixin**: `renderWithTooltipAndSubtitles` → post `ScreenDrawEvent`,
  cancel on `isCancelled()`. `keyPressed` → post `ScreenKeyEvent(key,false)`,
  `cir.setReturnValue(true)` on cancel.
- [ ] **AbstractContainerScreenMixin**: `init` → `new ContainerScreenInitEvent((AbstractContainerScreen)(Object)this).post()`
  (drop the button code — moves into `AutoStashModule`). `containerTick` → drop
  the button-sync (module owns it). `keyPressed` → `ScreenKeyEvent` +
  per-slot via `ContainerSlotClickEvent`/`ScreenKeyEvent`. `mouseClicked/Released/Dragged/Scrolled`
  → `ContainerMouseEvent(kind)`. `slotClicked` → `ContainerSlotClickEvent`.
  `renderSlots` → per active slot post `ContainerSlotRenderEvent`.
- [ ] **FishingHookRendererMixin** `shouldRender`: post
  `FishingBobberRenderEvent(ownerIsLocalPlayer)`; if `event.hide` →
  `cir.setReturnValue(false)`.
- [ ] **CameraMixin** `setup`: post `CameraSetupEvent`; if `positionOverride != null`
  → `setPosition(...)`.
- [ ] **ChatComponentMixin** `forwardToGarden`: `new ChatMessageEvent(msg).post()`.
- [ ] **FishingHookMixin**: both `shouldFixBobber()` sites → post
  `FishingBobberFixEvent`, read `lavaFix`.
- [ ] **KeyboardInputMixin**: `DungeonsModule.INSTANCE.shouldPressBackward()` →
  post `ForcedKeyQueryEvent(BACKWARD)`, read `pressed`.
- [ ] **GuiEventListenerMixin** `keyReleased`: post `ScreenKeyEvent(key,true)`.
- [ ] **WardrobeScreenMixin** `render`: post `ContainerDrawEvent`, cancel on
  `isCancelled()`.
- [ ] **LocalPlayerMixin**: `aiStep` → `PlayerVelocityCancelEvent`; `drop` →
  `ItemDropQueryEvent`.
- [ ] **MouseHandlerMixin** `onButton`: `DungeonsModule.onLeftClick()` → reuse the
  existing `MouseEvent.LeftClick` path (DungeonsModule already can subscribe to
  `MouseEvent`); delete the direct call.

## Task 3: Module subscribers

For each module: add `@SubscribeEvent` handlers wrapping the existing logic,
keep the old public methods (call them from the handler), and ensure
`EventBus.register(this)` runs in the module's `onLoad()`/init. Modules are
`object`s; verify each registers.

- [ ] **DungeonChestGamblingModule** — `ScreenOpenEvent`, `ScreenDrawEvent`
  (cancel if `renderScreen`), `ScreenKeyEvent` (cancel if `onKeyPressed`),
  `ContainerMouseEvent`/`ContainerSlotClickEvent` (cancel if `isRendering()`).
- [ ] **DungeonsModule** — `ForcedKeyQueryEvent` (`pressed = shouldPressBackward()`),
  `PlayerVelocityCancelEvent`, `MouseEvent.LeftClick` (`onLeftClick`).
- [ ] **FishingQolModule** — `FishingBobberRenderEvent`, `FishingBobberFixEvent`.
- [ ] **SmoothAotvModule** — `CameraSetupEvent` (`positionOverride = interpolatedCameraPos()`).
- [ ] **AutoStashModule** — `ContainerScreenInitEvent` (own the button: add the
  widget, sync its label on tick — subscribe a tick event or store the button).
- [ ] **ItemLockingModule** — `ScreenKeyEvent`, `ContainerSlotClickEvent`,
  `ContainerSlotRenderEvent`, `ItemDropQueryEvent`.
- [ ] **PestCleaningSequencer** — `ChatMessageEvent`.
- [ ] **WardrobeModule** — `ContainerDrawEvent` (cancel if `shouldSuppressVanillaRender()`).

## Task 4: Build + verify

- [ ] `./gradlew clientPublicApiJar publishToMavenLocal -x buildNative -x copyNativeDll`
- [ ] Confirm the published artifact has **no** `org/phantom/internal/{dungeons,fishing,etherwarp,qol,garden,wardrobe}/` classes referenced by mixin bytecode (grep the mixin `.class` constant pools).
- [ ] `cd Loader && ./gradlew build`
- [ ] Launch `phantom.jar` in a Fabric 1.21.11 instance — game reaches the title screen, no `NoClassDefFoundError`.
- [ ] Re-encrypt the protected bundles (`encryptPhantomModules` with `MODULE_ENCRYPTION_KEY`) — module bytecode changed.

## Notes / risk

- `AbstractContainerScreenMixin` also references `internal/qol/ItemLockingModule`
  and `internal/dungeons` — it is the densest mixin; do it last and carefully.
- Query events fired every frame/tick (`FishingBobberFixEvent`, `CameraSetupEvent`)
  are cheap (no listener pre-auth) but confirm no per-frame allocation hot path
  regresses; reuse instances if needed.
- After this, audit `org/phantom/{render,api,bridge}/**` for any *non-mixin*
  references into protected packages — same crash class, separate sweep.
