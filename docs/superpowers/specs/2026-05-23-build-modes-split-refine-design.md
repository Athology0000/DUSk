# Build modes: `buildLocal` / `buildRemote` + protected bundle refine

Two-mode build for Phantom so the same source tree produces either a self-contained Fabric mod or the loader payload + per-tier `.enc` bundles consumed by the Go content server. Includes a refine pass on the bundle definitions so split mode actually runs every module at runtime instead of crashing or silently skipping features.

## Modes

### `./gradlew buildLocal` — standalone fat jar
- Pins `phantomSplitModules=false` and `phantomBuildChannel=release`
- Depends only on `build`
- No `MODULE_ENCRYPTION_KEY` required
- Output: `build/libs/phantom-<version>.jar` — a regular Fabric mod with every internal module baked in. Runs without the Loader project, without the Go server, without auth (auth gate can be bypassed in dev via `phantom.devMockAuth`; in this mode the embedded path is `Phantom.kt → BuiltinModules.register()`).

### `./gradlew buildRemote` — loader payload + encrypted server bundles
- Pins `phantomSplitModules=true` and `phantomBuildChannel=release`
- Depends on `build`, `publishToMavenLocal`, and `syncPhantomModulesToServer` (which transitively runs `encryptPhantomModules` and `syncPhantomNativeToServer`)
- Requires `MODULE_ENCRYPTION_KEY` (base64-encoded 32 bytes) — fails fast with the existing error if unset
- Outputs:
  - `build/libs/phantom-<version>.jar` — loader-style stripped jar
  - `~/.m2/.../phantom-client-public-<version>.jar` — for the separate `Loader/` project
  - `Go-Server/server/content/modules/{phantom-core,phantom-mining,phantom-slayer,phantom-diana}.enc`
  - `Go-Server/server/content/native/phantom_pathfinder.dll`

Neither mode replaces the existing `buildDev`/`buildRelease`/`deployDev`/`deploy` tasks. Those are untouched.

## Why a refine pass is needed

The existing `phantomProtectedIncludes` (strip list) and per-bundle includes have three classes of bug that would make `buildRemote` ship a broken loader:

1. **Stripped-but-orphaned classes.** `phantomProtectedIncludes` removes `BuiltinModules.class`, `chat/**`, `crimson/**`, `dungeons/**`, `farming/**`, `fishing/**`, `garden/**`, `pig/**`, `seal/**`, `spotify/**` from the loader jar, but none of the four per-tier bundle include lists pick them up. After split mode runs, those modules + `BuiltinModules` simply don't exist at runtime.
2. **Same class in two `.enc` bundles.** `combat/*.class` (top-level) is in Mining + Slayer; `etherwarp/**` is in Mining + Diana; `qol/**` is in Core + Diana. Each duplicate becomes a separate `Class` instance under a separate addon classloader at runtime — Kotlin `object` singletons end up with two copies of state, and `EventBus.register(this)` from both copies makes handlers fire twice.
3. **Wasted overlap between loader jar and core `.enc`.** 13 of 15 packages in `phantomCoreIncludes` (`account`, `debug`, `helper`, `pathfinding`, `performance`, `rotation`, `routes`, `scheduler`, `skyblock`, `stats`, `ui`, `visual`) are not in `phantomProtectedIncludes`, so they ship in the loader jar AND in `phantom-core.enc`. The addon classloader's parent (loader) wins delegation, so the `.enc` copies never load — just wasted bytes and a drift risk.

Additionally, `PhantomCoreAddon.getModules()` returns an empty list today, so even after the orphan classes ship correctly, no addon would *register* chat/crimson/dungeons/farming/fishing/garden/pig/seal/spotify/qol/combat-top-level/etherwarp/grotto/visual/watermark in split mode.

## Refined bundle layout

Every protected class lives in exactly one `.enc`. Every per-tier addon's cross-package deps (`pathfinding`, `rotation`, `routes`, `ui`, `skyblock`, `helper`, `visual`) resolve through the loader's parent classloader.

### Loader jar (not stripped)
- `api/**`, `Phantom.kt`, `PhantomPublicInit.kt`, `PreLaunch.kt`
- `internal/auth/**`, `internal/loader/**`, `internal/command/**`
- `internal/helper/**`, `internal/pathfinding/**`, `internal/rotation/**`
- `internal/routes/**`, `internal/ui/**`, `internal/skyblock/**`
- `internal/stats/**`, `internal/scheduler/**`, `internal/performance/**`
- `internal/debug/**`, `internal/account/**`, `internal/visual/**`

### `phantom-core.enc` (base tier, always served)
- `internal/BuiltinModules.class` (the dev-mode aggregate, still referenced by the legacy monolithic addon entry)
- `internal/remote/PhantomCoreAddon.class` (+ any Kt synthetic for the file)
- `internal/qol/**`, `internal/etherwarp/**`
- `internal/combat/*.class` (top-level only)
- `internal/chat/**`, `internal/crimson/**`, `internal/dungeons/**`
- `internal/farming/**`, `internal/fishing/**`, `internal/garden/**`
- `internal/pig/**`, `internal/seal/**`, `internal/spotify/**`

### `phantom-mining.enc`
- `internal/remote/PhantomMiningAddon.class`
- `internal/mining/**`
- `internal/grotto/**` (Crystal Hollows is mining content)

### `phantom-slayer.enc`
- `internal/remote/PhantomSlayerAddon.class`
- `internal/combat/slayer/**`

### `phantom-diana.enc`
- `internal/remote/PhantomDianaAddon.class`
- `internal/diana/**`

### `phantomProtectedIncludes` (strip list)
Mirrors the union of the four bundles: `BuiltinModules.class`, `remote/**`, `qol/**`, `etherwarp/**`, `combat/**`, `chat/**`, `crimson/**`, `dungeons/**`, `farming/**`, `fishing/**`, `garden/**`, `pig/**`, `seal/**`, `spotify/**`, `mining/**`, `grotto/**`, `diana/**`. (`wardrobe/**` was previously listed but the package is now empty after recent deletions — drop it.)

## `PhantomCoreAddon.getModules()` registration

Populated to register every module currently in `BuiltinModules.all()` that isn't already in `PhantomMiningAddon.getModules()`, `PhantomSlayerAddon.getModules()`, or `PhantomDianaAddon.getModules()`. `FairyGrottoModule` is also added to `PhantomMiningAddon.getModules()` to match its new bundle home.

## Risk / verification

After implementing:
1. `./gradlew clean buildLocal -x buildNative` succeeds.
2. `./gradlew clean buildRemote -x buildNative` succeeds with a test `MODULE_ENCRYPTION_KEY`.
3. Fat jar contains `internal/**` classes; loader jar from `buildRemote` does not contain any strip-list entry.
4. Each `.enc` decrypts to a jar whose entries exactly match its include list, no more, no less.
5. Union of (loader jar entries) ∪ (every `.enc` jar entries) covers every `.class` in the fat jar with no class appearing twice. This is the canonical correctness check for the split.

## Out of scope
- The obfuscation gate (`phantomObfuscate`) is left as-is.
- The native DLL build pipeline is untouched.
- The legacy monolithic `packagePhantomModuleJar` / `encryptPhantomModule` / `phantom.enc` path stays as-is; `buildRemote` does not wire it.
- `buildDev`, `buildRelease`, `deployDev`, `deploy` are untouched.
