# Per-addon `.enc` bundles — design

**Date:** 2026-05-25
**Branch:** `loader`
**Relationship to existing specs:** This spec is a narrower, build-pipeline-focused alternative to `2026-05-23-empty-loader-per-macro-auth-design.md`. The 05-23 spec is the long-term architecture (per-module Gradle subprojects, dispatch-only mixins, per-toggle re-auth). This spec instead defines a single-project, package-filtered `.enc` build pipeline that can be brought up against the current source tree without first standing up a `bridge/` subproject or refactoring mixins. The two specs **disagree on source layout** — see "Divergence from 05-23" below. Pick one to execute; do not interleave them.

## Goal

Produce one AES-GCM-encrypted `.enc` file per feature *category* (combat, mining, slayer, dungeons, etc.) from the existing monolithic main mod, plus a manifest describing them. The Phantom loader subproject (already shipping with full AES-GCM + signed-manifest infrastructure) fetches and loads these `.enc` files after a successful auth handshake. The shipped main mod JAR no longer contains the feature modules.

## Non-goals

- Loader-side changes. The loader already supports per-module encrypted bundles, signed manifests, and in-memory addon classloading via `BootstrapStarter` + `BootstrapManifestVerifier` + `ModuleCrypto` + `AddonLoader.loadFromBytes`. This spec only adds the build pipeline that produces what the loader already expects.
- Per-toggle re-auth (`/auth/verify-module`), per-toggle activation policy, or the `LoadedModule` lifecycle. Bundles ship as legacy `Addon` entrypoints and go through `AddonLoader.activateLoadedAddons()` after the boot-time fetch.
- Mixin refactor. Mixins stay in the main JAR; per the existing `loader_mixin_eventbus_constraint` memory they already do not reference `internal/**`.
- Per-module Gradle subprojects, a `bridge/` subproject, or moving any source file. The source tree stays exactly where it is today; the bundler is purely a build-time task that filters compiled `.class` files by package.
- Manifest signing. Signing happens server-side with the pinned Ed25519 key when the manifest is published.
- Key rotation. Same `moduleKey` for all bundles in a given build.

## Divergence from `2026-05-23-empty-loader-per-macro-auth-design.md`

| Topic | 05-23 spec | This spec |
|---|---|---|
| Source layout | `modules/phantom-<area>/` Gradle subprojects, one per `.enc` | Single project, package-filtered bundler |
| Bundle granularity | One per `internal/<area>/` (slayer separate from combat, fishing separate from farming, etc.) | Coarser — combat + slayer in one bundle, farming + fishing + pig in one, visual + grotto in one (see §1) |
| Mixin model | Dispatch-only, in a separate `bridge/` subproject | Untouched, stays in main JAR |
| Module lifecycle | `LoadedModule` (onLoad / onActivate / onDeactivate) via the new activation executor | Legacy `Addon` via `AddonLoader.activateLoadedAddons()` |
| Per-toggle re-auth | Yes, new `/auth/verify-module` endpoint | No — boot-time entitlement check only, same as today's `BootstrapStarter` filter |
| Loader jar size target | ≤ 1.5 MB | Not a goal |
| Customer-visible feature gating | Per-module | Per-category |

If the long-term direction is the 05-23 architecture, this spec is a stepping stone that lands per-category `.enc` first and lets per-module + per-toggle re-auth come in a later wave. If the long-term direction is this spec, the 05-23 spec is shelved.

## Section 1 — Categories & bundle inventory

Twelve bundles, derived from `BuiltinModules.all()` and grouped by source package. `phantom-core` is always `required: true` and has `initOrder: 0`; the rest are entitled and have `initOrder: 20`.

| Bundle | Source packages | Notable contents |
|---|---|---|
| `phantom-core` | `internal/{loader,helper,command,auth,remote}` + `api/hud/modules/**` (moved to `internal/hud/`) | Bootstrap glue, command system, Watermark / InventoryHud / MiningHud. No user-facing macros. |
| `phantom-combat` | `internal/combat/**` (incl. `combat/slayer/`) | CombatMacro, CombatHud, CombatPatrol, all 7 slayer macros, cocoon / miniboss alerts |
| `phantom-mining` | `internal/mining/**` | MiningMacro, MiningCoinPopup, Routes, Vein*, Powder, Scatha, Excavator, Ore, Pingless, AutoForge, AutoLantern, Failsafe, LobbyHopper |
| `phantom-dungeons` | `internal/dungeons/**` | Dungeons, DungeonMap, DungeonRoutes, AutoRoutes, BloodCamp, AutoDojo |
| `phantom-farming` | `internal/{farming,fishing,pig}/**` | FarmingMacro, FishingMacro, FishingHotspot, FishingQol, PigMacro |
| `phantom-diana` | `internal/diana/**` | DianaMacro, DianaHelper |
| `phantom-rift` | `internal/{rift,seal,crimson}/**` | RiftModule, GunthersRace, ShyCrux, YearOfTheSeal, AutoDojo (source under `internal/crimson/`) |
| `phantom-etherwarp` | `internal/etherwarp/**` | EtherwarpHelper, LeftClickEtherwarp, SmoothAotv |
| `phantom-pathfinding` | `internal/pathfinding/**` + `internal/rotation/**` | PathfindingModule, PathPreview, RotationsModule, HeadRotation |
| `phantom-qol` | `internal/{qol,chat,spotify}/**` | Qol, AutoStash, ItemLocking, PriceTooltip, ColoredEnchants, MissingEnchants, LagDetector, Timer, RsaAutoGfs, Spotify, ChatFilter |
| `phantom-visual` | `internal/visual/**` + `internal/grotto/**` | FullBright, Freecam, OrbitFreecam, BlockOverlay, BlockOutline, ArrowHitboxes, CustomScoreboard, HotbarOverlay, PetDisplay, DeadEntityCleaner, DeployableHud, RsaEffects, RsaPresetWaypoints, WitherImpactOverlay, FairyGrotto |

`AutoDojo` is grouped under "Dungeons / helper modules" in `BuiltinModules.all()` but its source file lives in `internal/crimson/`. Since bundling is package-driven, it ends up in `phantom-rift`. Verify no other code path expects it under dungeons before shipping.

`internal/ui/**` (panel rendering, NVG, HUD editor, theme manager) is **infrastructure**, not a bundle. It stays in the main JAR.

## Section 2 — Build pipeline

A new set of Gradle tasks declared in the root `build.gradle.kts`, driven by a top-level `phantomBundles { ... }` DSL:

```kotlin
phantomBundles {
    bundle("phantom-core") {
        packages = listOf(
            "org.phantom.internal.loader",
            "org.phantom.internal.helper",
            "org.phantom.internal.command",
            "org.phantom.internal.auth",
            "org.phantom.internal.remote",
            "org.phantom.internal.hud",
        )
        modules = listOf(
            "org.phantom.internal.hud.WatermarkModule",
            "org.phantom.internal.hud.InventoryHudModule",
            "org.phantom.internal.hud.MiningHudModule",
        )
        initOrder = 0
        required = true
    }
    bundle("phantom-combat") {
        packages = listOf("org.phantom.internal.combat")
        modules = listOf(/* explicit Module class names; see §2.3 */)
        initOrder = 20
    }
    // ... etc. for each bundle in §1
}
```

Per bundle, the task chain:

### 2.1 `assemblePhantom<Name>Jar`

Copies `.class` files from `build/classes/{kotlin,java}/main/<pkg>/**` (for every package listed in the bundle) into a new JAR at `build/phantom-bundles/<name>.jar`. Adds at the JAR root:

- `phantom.addon.json` (matching the existing `AddonMetadata` schema consumed by `AddonLoader.loadAddon(sourceName, jarBytes)`):
  ```json
  {
    "id": "phantom-<name>",
    "name": "Phantom <Name>",
    "version": "<root project version>",
    "entrypoints": ["org.phantom.bundles.<Name>Addon"],
    "mixins": []
  }
  ```
- The generated entrypoint class (see §2.2).

`mixins` is empty by design — `AddonLoader.loadAddon(sourceName, jarBytes)` already rejects remote in-memory addons that declare mixins. Bundles are pure code; mixins live in the main JAR.

### 2.2 Entrypoint codegen

For each bundle, generate a single Kotlin file at `build/generated/phantom-bundles/<name>/org/phantom/bundles/<Name>Addon.kt`:

```kotlin
package org.phantom.bundles

import org.phantom.api.addon.Addon
import org.phantom.api.module.Module

class <Name>Addon : Addon {
    override fun onLoad() {}
    override fun onUnload() {}
    override fun getModules(): List<Module> = listOf(
        org.phantom.internal.combat.CombatMacroModule(),
        org.phantom.internal.combat.CombatHudModule,
        // ... explicit list from the bundle's `modules` DSL entry
    )
}
```

The list of module class names is the explicit `modules = listOf(...)` from the bundle DSL — hand-curated, not derived by reflection. Trade-off: refactors that add a new module class require updating the bundle DSL; we accept this in exchange for a build that fails loudly when a module is forgotten, instead of silently shipping an `.enc` that's missing a module.

`object` modules (singletons) reference the class directly; `class` modules use no-arg constructors. The generator inspects the JVM metadata of the referenced classes to choose between `.INSTANCE` access and `()` instantiation, mirroring the dual path in `AddonLoader.loadAddon` at `:106-112`.

The generated `.kt` file is compiled as part of a dedicated source set per bundle (separate from main) so its classes don't pollute the main JAR.

### 2.3 `encryptPhantom<Name>`

Reads `build/phantom-bundles/<name>.jar`. Generates a fresh 12-byte cryptographically random nonce per build. AES-256-GCM encrypts under the shared `moduleKey` (32-byte base64, supplied per §5). Writes `nonce || ciphertext_and_tag` to `build/phantom-bundles/<name>.enc`. Format matches `ModuleCrypto.decryptAesGcm` exactly (no header, no version byte).

### 2.4 `assemblePhantomManifest`

Emits `build/phantom-bundles/manifest.json` matching the existing `ContentManifest` schema (`buildId`, `channel`, `minimumLoaderVersion`, `moduleKey` (null in build output — server fills in), `modules[]`, `nativeComponents[]`). For each bundle:

- `name`: `phantom-<name>`
- `url`: empty placeholder (server fills in)
- `sha256`: hex SHA-256 of the **plaintext** `.jar` bytes — matches what `BootstrapStarter.loadModule` checks at `:157-159`
- `required`: from DSL
- `initOrder`: from DSL
- `activationPolicy`: omitted / null (loader treats absent as legacy / auto-via-`activateLoadedAddons`)

Manifest signing is **not** performed by this task. Signing happens server-side at publish time with the pinned Ed25519 key. The build emits an unsigned manifest skeleton.

### 2.5 `assemblePhantomAll`

Aggregate task. Depends on every `encryptPhantom<Name>` + `assemblePhantomManifest`. Produces the deployable set: `build/phantom-bundles/*.enc` + `build/phantom-bundles/manifest.json`.

## Section 3 — Main JAR changes

The main mod JAR shrinks to API + UI infrastructure. Loader code already lives in `Loader/` as a separate subproject and is untouched.

### 3.1 Removed from main JAR (moved into bundles)

- All of `org.phantom.internal.{combat, mining, dungeons, farming, fishing, pig, diana, rift, seal, crimson, etherwarp, pathfinding, rotation, qol, chat, spotify, visual, grotto, helper}`.
- `org.phantom.api.hud.modules.{Watermark, InventoryHud, MiningHud}` — first moved to `org.phantom.internal.hud.*`, then bundled into `phantom-core`.
- `BuiltinModules.register()` body — emptied. `BuiltinModules.all()` — deleted.
- `BuiltinModules.applyDefaultHudVisibility()` — moved into the `phantom-core` bundle's entrypoint `onLoad()`, applied to whatever HUDs the bundle's modules contribute.

### 3.2 Stays in main JAR

- `org.phantom.api.**` — all of it. Module / ModuleManager / EventBus / hud DSL / settings / pathfinder / rotation / util / theme.
- `org.phantom.internal.ui.**` — panels, NVG renderer, HUD editor, theme manager. Used by every bundle's modules.
- Mixin classes (`src/main/java/org/phantom/mixin/**`). Already constrained per `loader_mixin_eventbus_constraint`.
- `Phantom.onInitializeClient()` — unchanged structurally, but the call to `BuiltinModules.register()` becomes a no-op (the method exists but does nothing).
- `PreLaunch` + mixin auto-discovery.

### 3.3 Build-time guard

A new `verifyPhantomMainJarClean` task scans `build/libs/<main-jar>.jar` and fails the build if any class under one of the bundle source packages (per the `phantomBundles { ... }` DSL) is present in the main JAR. Prevents accidental re-bundling when a refactor accidentally re-adds a feature class to a kept package.

### 3.4 Startup flow change

`Phantom.onInitializeClient()` runs at Fabric init and registers no feature modules. The loader subproject (`Loader/loader/`) is a separate Fabric `ClientModInitializer` that triggers `BootstrapStarter.start()` on a daemon thread. After auth, manifest fetch, decryption, SHA verification, and `AddonLoader.loadFromBytes()`, the existing `AddonLoader.activateLoadedAddons()` call at `BootstrapStarter.kt:135` walks the loaded `Addon` entrypoints, calls `addon.onLoad()`, and registers `addon.getModules()` with `ModuleManager`. That is the path that lands per-category modules in `ModuleManager` after auth.

### 3.5 Pre-auth UI behavior

Before `Auth.state == READY`, `ModuleManager` is empty. The Phantom command panel UI today opens unconditionally. Change: when the panel opens with `Auth.state != READY`, render the existing UI shell but replace the module list region with an authenticating placeholder (`Auth.statusMessage` already supplies a user-readable string).

## Section 4 — Cross-bundle coupling

The dominant implementation risk: a class in `internal/combat/` references a class in `internal/mining/`. When `phantom-combat.enc` loads but `phantom-mining.enc` does not (entitlement gating), the JVM throws `NoClassDefFoundError` the first time the cross-reference is touched.

### 4.1 Coupling analyzer

A new Gradle task `analyzePhantomBundleCoupling`. Uses `org.objectweb.asm` (already on the classpath via Fabric) to build the class graph. For each `.class` under any `org.phantom.internal.<category>/` package listed in the bundle DSL, lists its dependencies on classes in *other* `internal/<category>/` packages. Emits a report at `build/phantom-bundles/coupling-report.txt`, grouped by `(source-bundle, target-bundle)` pair, with the offending class + method name.

The task fails the build if any cross-bundle edge is not on a per-bundle allowlist. The allowlist starts empty; edges are added deliberately as they're resolved.

### 4.2 Resolution patterns

For each edge in the report, pick one of:

| Pattern | Resolution |
|---|---|
| Shared utility class living in the wrong category | Move it into `org.phantom.api.util.**`. Both bundles link against the main JAR's API, so the edge disappears. |
| One macro genuinely depends on another macro's runtime state | Refactor to talk through `EventBus` events or `ModuleManager.get<T>()` (returns null cleanly when the other bundle isn't loaded). |
| Genuine semantic dependency (bundle A is meaningless without bundle B) | Either merge A and B into one bundle, or declare a hard `requires` in `phantom.addon.json` and have `BootstrapStarter` skip A when B is absent. |

If `AddonMetadata` does not currently have a `requires` field, add it before relying on the third pattern. Today's schema: `id`, `name`, `version`, `entrypoints`, `mixins`, `icon`. Adding `requires: List<String>` is backwards-compatible (defaults to empty).

### 4.3 Runtime classloader behavior

`InMemoryAddonClassLoader` (used by `AddonLoader.loadFromBytes`) shares its parent classloader with the main JAR's classloader. A single instance accumulates JARs via `addJar()`. Consequence: once bundles A and B are both loaded, classes from A can see classes from B at runtime through the shared classloader. The risk reduces to: "what if B is not loaded at all due to entitlement gating?" — that's the case the allowlist + `requires` resolutions defend against.

### 4.4 Manifest entitlement defense

Extend the filter at `BootstrapStarter.kt:71-76` so that if a manifest module declares `requires: ["phantom-X"]` and `phantom-X` is being skipped (not entitled or filtered out), the dependent module is also skipped with a warning instead of being loaded and crashing later.

## Section 5 — Encryption & key handling

### 5.1 Format

AES-256-GCM. Wire layout per `.enc`: `nonce(12 bytes) || ciphertext_and_tag(N bytes)`. No header, no version byte. Dictated by `ModuleCrypto.decryptAesGcm` — `BootstrapStarter` already invokes it as `cipher.copyOfRange(0,12)` nonce + `copyOfRange(12, end)` ciphertext.

### 5.2 Key supply at build time

The bundler Gradle task sources the 32-byte base64 key in priority order:

1. `-Pphantom.moduleKey=<base64>` Gradle property
2. `PHANTOM_MODULE_KEY` environment variable
3. Build fails with a clear error if neither is set. No insecure default.

Add `phantom.moduleKey` to `.gitignore`'d files. Verify `gradle.properties` is not tracked, or add an exclude rule for the key entry specifically.

### 5.3 Nonce policy

Fresh 12-byte cryptographically random nonce per `.enc` per build. Two builds of the same source produce different bytes — that's fine; the manifest pins the plaintext SHA-256, and the server is the source of truth for which build to ship.

### 5.4 Plaintext SHA-256 (not ciphertext)

`BootstrapStarter.loadModule` decrypts first, then hashes, then compares to `module.sha256`. The manifest's `sha256` field must therefore be the SHA-256 of the plaintext JAR bytes. The `assemblePhantomManifest` task emits the plaintext hash.

### 5.5 Key rotation

Out of scope. Same key for all bundles in a given build.

## Section 6 — Verification & rollout

### 6.1 Build-side verification

| Task | Purpose | Failure mode |
|---|---|---|
| `analyzePhantomBundleCoupling` | List cross-bundle class references | Build fails if any edge isn't on the allowlist |
| `verifyPhantomMainJarClean` | Ensure main JAR no longer contains bundled packages | Build fails with offending class + bundle name |
| `verifyPhantomBundleRoundTrip` | Encrypt then decrypt each bundle with the same key; assert plaintext bytes match | Catches AES-GCM key/format mistakes locally without booting MC |

All three run as part of `assemblePhantomAll`.

### 6.2 Runtime verification (dev)

- Set `LoaderConfig.moduleKeyB64` to the dev key locally. Point the loader's manifest URL at a local fileserver hosting `manifest.json` + the `.enc` files.
- Add a dev-mode toggle (Gradle property → JVM system property) that forces `Auth.state = READY` without a real session, for iteration without a real auth server.

### 6.3 Rollout order

Each row is one PR / commit; each ends in a working build.

1. Coupling analyzer task (read-only, produces a report). No allowlist enforcement yet.
2. Resolve high-impact coupling edges based on the report. No bundles yet — just untangle internals (move shared utils to `api/util`, refactor cross-macro calls to `EventBus`).
3. Move `api/hud/modules/{Watermark, InventoryHud, MiningHud}` into `internal/hud/`. Update `BuiltinModules.all()` accordingly. Main JAR still ships modules.
4. `phantomBundles { ... }` config DSL + per-bundle `assemble<Name>Jar` task. Entrypoint codegen. Output is plaintext addon JARs in `build/phantom-bundles/<name>.jar`; do not encrypt yet. End-to-end test: drop them in `config/phantom/addons/`, confirm they load via the legacy local-addon path (`allowLocalAddons()` is true in dev).
5. AES-GCM encryptor task. `verifyPhantomBundleRoundTrip` task. Output `.enc` files alongside plaintext JARs (keep both during transition).
6. `assemblePhantomManifest` task — produces the manifest JSON the server will sign and serve.
7. Empty `BuiltinModules.register()` body; remove `BuiltinModules.all()`. Main JAR no longer ships feature modules. UI gate on `Auth.state != READY` (Section 3.5). `verifyPhantomMainJarClean` task enforced.
8. `analyzePhantomBundleCoupling` allowlist enforced (build fails on any new cross-bundle edge).
9. Remove plaintext JAR outputs once the server side is fully wired (`.enc` only).

## Section 7 — Open questions

- **`AddonMetadata.requires` field.** Adding this is straightforward and additive, but it touches the JSON contract that `phantom.addon.json` parsing relies on (`AddonLoader.kt:85-87`). Need to confirm no other consumer reads the JSON with a strict schema. Resolution can be deferred to Section 4 implementation.
- **`AutoDojo` ownership.** Source lives in `internal/crimson/` but it's grouped under "Dungeons / helper modules" in `BuiltinModules.all()`. Package-driven bundling lands it in `phantom-rift`. Verify no code path expects it under dungeons.
- **`internal/visual/` size.** 15+ modules in one bundle is workable but could be split (cosmetic vs. effects vs. overlays) if entitlement gating ever wants finer granularity. Not done in this spec.
- **Server-side manifest publisher.** The build emits an unsigned manifest skeleton. The Go server (currently deleted per git status, but referenced by the loader) must accept this skeleton, fill `moduleKey` + `url` + `signature`, and serve it. Out of scope here; tracked separately.

## Related memories

- `phantom_loader_integration` — build-env quirks (no `go`/`cargo` on PATH, `-x buildNative`).
- `loader_mixin_eventbus_constraint` — pre-existing rule that loader-side mixins must not reference protected `internal/**` modules. Compatible with this spec because mixins stay in the main JAR (which is allowed to reference `internal/**`).
- `feedback_no_git_push` — local commits only.
