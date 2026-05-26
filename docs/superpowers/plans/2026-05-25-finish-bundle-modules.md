# Finish bundle modules — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce a complete, correct set of `.enc` bundle files for the existing 5-bundle layout (`phantom-core`, `phantom-mining`, `phantom-slayer`, `phantom-diana`, `phantom-rift`). Verify each one decrypts to a valid addon JAR and contains every module its `RemoteModuleAddons.kt` entrypoint references.

**Architecture:** Scope is narrowly the build pipeline that already exists in `build.gradle.kts` plus the entrypoints in `src/main/kotlin/org/phantom/internal/remote/RemoteModuleAddons.kt`. No new bundles, no DSL refactor, no codegen, no spec restructuring. Audit work compares `RemoteModuleAddons.kt` against `BuiltinModules.all()` to find orphan modules (modules registered in builtins but absent from every bundle entrypoint) and assigns each orphan to its correct bundle. The runtime verifier decrypts each emitted `.enc` with the same code path the loader uses (`ModuleCrypto.decryptAesGcm`) and asserts the result is a JAR containing the expected `phantom.addon.json` + entrypoint class.

**Tech Stack:** Gradle Kotlin DSL, AES-256-GCM via `javax.crypto`, the existing JAR/ZIP handling in the root `build.gradle.kts`.

**Constraints / project memories applied:**
- `phantom_loader_integration` — `go` and `cargo` are not on PATH. Use `-x buildNative` for any Gradle build that would otherwise compile the C++ pathfinder.
- `feedback_no_git_push` — local commits only, never push.
- Encryption key must come from `MODULE_ENCRYPTION_KEY` or `MASTER_KEY` env var (existing `moduleEncryptionKey()` in `build.gradle.kts:599-609`). The build aborts with a clear error if neither is set; this plan does not invent a default.

---

### Task 1: Confirm encryption key is available in the dev environment

**Files:** none modified.

- [ ] **Step 1: Check for `MODULE_ENCRYPTION_KEY` or `MASTER_KEY` in the environment**

Run:
```powershell
if ($env:MODULE_ENCRYPTION_KEY) { "MODULE_ENCRYPTION_KEY set" } elseif ($env:MASTER_KEY) { "MASTER_KEY set" } else { "NEITHER set" }
```

Expected: prints one of "MODULE_ENCRYPTION_KEY set" / "MASTER_KEY set". If both are absent, stop and ask the user to export one (32 bytes base64) before proceeding — every subsequent `encryptPhantom*Module` task will fail without it.

- [ ] **Step 2: Verify the key decodes to 32 bytes**

Run:
```powershell
$raw = if ($env:MODULE_ENCRYPTION_KEY) { $env:MODULE_ENCRYPTION_KEY } else { $env:MASTER_KEY }
[Convert]::FromBase64String($raw).Length
```

Expected: `32`. If it prints anything else, the key is wrong shape and the build will fail at `build.gradle.kts:605-607`. Stop and have the user regenerate the key.

---

### Task 2: Snapshot the current `build/phantom-modules/` contents

**Files:** none modified.

- [ ] **Step 1: List existing artifacts**

Run:
```powershell
ls build/phantom-modules/encrypted/, build/phantom-modules/plain/ 2>$null
```

Expected (from the pre-task scan): `encrypted/` contains 4 `.enc` files (core, diana, mining, slayer) and a legacy `phantom-0.1.0.jar`; `plain/` contains 5 `.jar` files (core, diana, mining, rift, slayer). The missing artifact is `phantom-rift.enc`.

- [ ] **Step 2: Record the existing files' SHA-256 + size**

Run:
```powershell
Get-ChildItem build/phantom-modules/encrypted/, build/phantom-modules/plain/ | ForEach-Object { "{0}  {1}  {2}" -f $_.FullName, $_.Length, (Get-FileHash $_.FullName -Algorithm SHA256).Hash }
```

Expected: a list of file + size + SHA-256. Save this output for later comparison after the rebuild. (Just paste it into the assistant's reply for the record; nothing to commit.)

---

### Task 3: Audit `RemoteModuleAddons.kt` for orphan modules

**Files:**
- Read: `src/main/kotlin/org/phantom/internal/remote/RemoteModuleAddons.kt`
- Read: `src/main/kotlin/org/phantom/internal/BuiltinModules.kt`

- [ ] **Step 1: Enumerate every Module class referenced by `BuiltinModules.all()`**

The `BuiltinModules.all()` method at `src/main/kotlin/org/phantom/internal/BuiltinModules.kt:116` is the single source of truth for what modules ship. Extract every class name from the `listOf(...)` body (e.g., `CombatHudModule`, `PathfindingModule`, etc.). Capture as a set.

- [ ] **Step 2: Enumerate every Module class referenced by all 5 `Phantom*Addon` entrypoints**

`RemoteModuleAddons.kt` defines five addons:
- `PhantomCoreAddon.getModules()` (lines 99-165)
- `PhantomMiningAddon.getModules()` (lines 183-208)
- `PhantomDianaAddon.getModules()` (lines 216-218)
- `PhantomSlayerAddon.getModules()` (lines 227-236)
- `PhantomRiftAddon.getModules()` (lines 244-248)

Extract every class name from each `listOf(...)` body. Capture as a set (union across all 5 addons).

- [ ] **Step 3: Compute set difference (orphans)**

`orphans = builtins - addons_union`. Report each orphan's source package (e.g. `org.phantom.internal.mining.tunnels.TunnelMinerModule` → mining).

From a pre-scan I already know `PhantomMiningAddon` references `TunnelMinerModule` and `WorldVeinCacherModule` but `BuiltinModules.all()` may not list them — check both directions for completeness:
- `addons_union - builtins` → "dead" addon entries (won't crash at boot, but useless).
- `builtins - addons_union` → orphans (will ship in main JAR but never load through a bundle).

Report both sets back as findings; **do not modify code yet**.

- [ ] **Step 4: Commit the audit findings as a one-shot note (optional)**

If the audit produces useful output, save it to `docs/superpowers/notes/2026-05-25-bundle-audit.md` and commit. Otherwise skip — the next task lands the actual fixes.

```powershell
git status --short
```

Expected: either no changes (skipped) or just `docs/superpowers/notes/2026-05-25-bundle-audit.md` added.

---

### Task 4: Assign each orphan to its bundle

**Files:**
- Modify: `src/main/kotlin/org/phantom/internal/remote/RemoteModuleAddons.kt`

For each orphan from Task 3, decide its bundle by package:

| Orphan package | Target addon |
|---|---|
| `org.phantom.internal.mining.**` | `PhantomMiningAddon` |
| `org.phantom.internal.combat.slayer.**` | `PhantomSlayerAddon` |
| `org.phantom.internal.diana.**` | `PhantomDianaAddon` |
| `org.phantom.internal.rift.**`, `org.phantom.internal.seal.**`, `org.phantom.internal.crimson.**` | `PhantomRiftAddon` if it's a rift-y feature, else `PhantomCoreAddon` (current convention: SealOfYear → Core, AutoDojo → Core) |
| Anything else | `PhantomCoreAddon` |

Important constraint from `build.gradle.kts:233-249`: the bundle JARs filter by package globs. A module's class file must live under a package the target bundle *includes*, or it ends up in a different JAR than its entrypoint. For example, adding `TunnelMinerModule` (under `internal/mining/tunnels/**`) to `PhantomCoreAddon.getModules()` would work at compile time but at runtime `PhantomCoreAddon`'s JAR (`phantomCoreIncludes`, line 217-232) doesn't pull in `mining/**`, so the class lookup fails. Match orphans to bundles whose include globs already cover their package.

- [ ] **Step 1: For each orphan, add it to the matching addon's `listOf(...)`**

Show the exact diff per orphan. Example shape:

```kotlin
// In PhantomMiningAddon.getModules() — add at appropriate position in the list
TunnelMinerModule,
WorldVeinCacherModule,
```

Preserve the existing `.distinctBy { it.name.trim().lowercase() }` tail so duplicates between addons don't double-register at runtime.

- [ ] **Step 2: Compile to verify imports and references resolve**

Run:
```powershell
./gradlew compileKotlin -x buildNative
```

Expected: `BUILD SUCCESSFUL`. If a referenced class doesn't exist, the orphan list was wrong — fix and re-run.

- [ ] **Step 3: Commit**

```powershell
git add src/main/kotlin/org/phantom/internal/remote/RemoteModuleAddons.kt
git commit -m "$(cat <<'EOF'
fix(loader): assign orphan modules to their bundles

Modules registered in BuiltinModules.all() but missing from any
PhantomXxxAddon.getModules() would compile into the main JAR but
never appear in any encrypted bundle — so they would not be loaded
post-auth. Bind each orphan to the bundle whose package globs
include its source file.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: Rebuild all plaintext bundle JARs from a clean slate

**Files:** none modified directly.

- [ ] **Step 1: Delete existing bundle outputs**

Run:
```powershell
Remove-Item -Recurse -Force build/phantom-modules -ErrorAction SilentlyContinue
```

Expected: directory is gone or never existed.

- [ ] **Step 2: Rebuild plaintext JARs for all 5 bundles**

Run:
```powershell
./gradlew packagePhantomModules -x buildNative
```

Expected: `BUILD SUCCESSFUL`. New JARs appear at:
- `build/phantom-modules/plain/phantom-core.jar`
- `build/phantom-modules/plain/phantom-mining.jar`
- `build/phantom-modules/plain/phantom-slayer.jar`
- `build/phantom-modules/plain/phantom-diana.jar`
- `build/phantom-modules/plain/phantom-rift.jar`

- [ ] **Step 3: Confirm every JAR contains its `phantom.addon.json` and entrypoint class**

For each bundle, list the JAR contents and grep for the two required entries:

```powershell
foreach ($name in @("phantom-core","phantom-mining","phantom-slayer","phantom-diana","phantom-rift")) {
  $jar = "build/phantom-modules/plain/$name.jar"
  Write-Host "=== $name ==="
  $entries = (& jar tf $jar) -split "`r?`n"
  if ($entries -notcontains "phantom.addon.json") { Write-Host "MISSING phantom.addon.json" }
  $expectedEntry = "org/phantom/internal/remote/Phantom" + ($name -replace 'phantom-','' | ForEach-Object { $_.Substring(0,1).ToUpper() + $_.Substring(1) }) + "Addon.class"
  if ($entries -notcontains $expectedEntry) { Write-Host "MISSING $expectedEntry" }
}
```

Expected: no "MISSING" output. If `jar` is not on PATH, fall back to PowerShell's built-in zip handling: `[System.IO.Compression.ZipFile]::OpenRead($jar).Entries.Name`.

---

### Task 6: Encrypt all 5 bundles to `.enc`

**Files:** none modified.

- [ ] **Step 1: Run the aggregate encryption task**

Run:
```powershell
./gradlew encryptPhantomModules -x buildNative
```

Expected: `BUILD SUCCESSFUL`. The task is at `build.gradle.kts:776` and depends on the five per-bundle encryption tasks.

- [ ] **Step 2: Confirm all 5 `.enc` files exist**

Run:
```powershell
ls build/phantom-modules/encrypted/*.enc
```

Expected: 5 files — `phantom-core.enc`, `phantom-mining.enc`, `phantom-slayer.enc`, `phantom-diana.enc`, `phantom-rift.enc`. (Compare against the Task 2 snapshot — `phantom-rift.enc` should be NEW; the other four should be REGENERATED with different bytes because of the fresh AES-GCM nonce.)

- [ ] **Step 3: Record the new SHA-256s of plaintext jars + .enc files**

Run:
```powershell
Get-ChildItem build/phantom-modules/encrypted/*.enc, build/phantom-modules/plain/*.jar | ForEach-Object { "{0}  {1}  {2}" -f $_.Name, $_.Length, (Get-FileHash $_.FullName -Algorithm SHA256).Hash }
```

Expected: 10 lines. Save the plaintext SHAs — they are what would go into the loader manifest's `sha256` field.

---

### Task 7: Round-trip-verify each `.enc` using the loader's decrypt path

**Files:**
- Create: `tools/verify_bundles.main.kts` (Kotlin script using the same AES-GCM logic as `ModuleCrypto.decryptAesGcm`).

This step proves the build output is loader-compatible. We re-implement nothing — we use the exact algorithm + parameter shape `ModuleCrypto.decryptAesGcm` uses at `Loader/loader/src/main/kotlin/org/phantom/loader/bootstrap/ModuleCrypto.kt:9-31`.

- [ ] **Step 1: Create `tools/verify_bundles.main.kts`**

```kotlin
#!/usr/bin/env kotlin
// Decrypts each .enc in build/phantom-modules/encrypted/ using the same
// AES-GCM parameters as Loader/loader/.../ModuleCrypto.decryptAesGcm,
// then asserts the decrypted bytes are a valid ZIP containing
// phantom.addon.json + the expected entrypoint .class.
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64
import java.util.zip.ZipInputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

fun decryptGcm(key: ByteArray, blob: ByteArray): ByteArray {
    require(blob.size > 12) { "ciphertext too short" }
    val nonce = blob.copyOfRange(0, 12)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
    return cipher.doFinal(blob.copyOfRange(12, blob.size))
}

fun zipEntries(bytes: ByteArray): List<String> {
    val names = mutableListOf<String>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
        while (true) {
            val e = z.nextEntry ?: break
            names += e.name
            z.closeEntry()
        }
    }
    return names
}

val keyB64 = System.getenv("MODULE_ENCRYPTION_KEY") ?: System.getenv("MASTER_KEY")
    ?: error("Set MODULE_ENCRYPTION_KEY or MASTER_KEY (base64 32 bytes).")
val key = Base64.getDecoder().decode(keyB64)
require(key.size == 32) { "key must decode to 32 bytes, got ${key.size}" }

val encDir = File("build/phantom-modules/encrypted")
require(encDir.isDirectory) { "missing $encDir — run encryptPhantomModules first" }

val expectations = mapOf(
    "phantom-core"   to "PhantomCoreAddon",
    "phantom-mining" to "PhantomMiningAddon",
    "phantom-slayer" to "PhantomSlayerAddon",
    "phantom-diana"  to "PhantomDianaAddon",
    "phantom-rift"   to "PhantomRiftAddon",
)

var failures = 0
for ((bundle, addonClass) in expectations) {
    val encFile = encDir.resolve("$bundle.enc")
    if (!encFile.isFile) { println("FAIL $bundle: $encFile missing"); failures++; continue }
    val plain = try { decryptGcm(key, encFile.readBytes()) }
        catch (t: Throwable) { println("FAIL $bundle: decrypt error — ${t.message}"); failures++; continue }
    val entries = try { zipEntries(plain) }
        catch (t: Throwable) { println("FAIL $bundle: not a zip — ${t.message}"); failures++; continue }
    if ("phantom.addon.json" !in entries) { println("FAIL $bundle: missing phantom.addon.json"); failures++; continue }
    val classEntry = "org/phantom/internal/remote/$addonClass.class"
    if (classEntry !in entries) { println("FAIL $bundle: missing $classEntry"); failures++; continue }
    println("OK   $bundle: ${entries.size} entries, contains $classEntry")
}

if (failures > 0) {
    System.err.println("$failures bundle(s) failed verification.")
    System.exit(1)
}
println("All ${expectations.size} bundles verified.")
```

- [ ] **Step 2: Run the verifier**

Run:
```powershell
kotlin tools/verify_bundles.main.kts
```

Expected output:
```
OK   phantom-core: ... entries, contains org/phantom/internal/remote/PhantomCoreAddon.class
OK   phantom-mining: ... entries, contains org/phantom/internal/remote/PhantomMiningAddon.class
OK   phantom-slayer: ... entries, contains org/phantom/internal/remote/PhantomSlayerAddon.class
OK   phantom-diana: ... entries, contains org/phantom/internal/remote/PhantomDianaAddon.class
OK   phantom-rift: ... entries, contains org/phantom/internal/remote/PhantomRiftAddon.class
All 5 bundles verified.
```

If `kotlin` is not on PATH (Kotlin scripting CLI), fall back to running the script via Gradle: add a small `tasks.register<JavaExec>("verifyBundles")` block that points at the same logic. Decide at execution time.

If any FAIL line appears, stop and report. Common causes: wrong env-var key (Task 1), missing orphan fix (Task 4), or `.enc` not regenerated (Task 6).

- [ ] **Step 3: Commit the verifier script**

```powershell
git add tools/verify_bundles.main.kts
git commit -m "$(cat <<'EOF'
build: add round-trip verifier for encrypted bundles

Decrypts each .enc with the same AES-GCM parameters the loader uses
(ModuleCrypto.decryptAesGcm) and asserts the result is a JAR
containing phantom.addon.json + the expected entrypoint class.
Run after encryptPhantomModules to confirm bundles are
loader-compatible.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: Confirm the JSON inside each `.enc` matches loader expectations

**Files:** none modified.

- [ ] **Step 1: For each bundle, decrypt + extract `phantom.addon.json` and inspect**

Extend the verifier or run a quick PowerShell snippet:

```powershell
# After Task 7, decrypted bytes can be re-extracted ad-hoc; or:
foreach ($bundle in @("phantom-core","phantom-mining","phantom-slayer","phantom-diana","phantom-rift")) {
  Write-Host "=== $bundle ==="
  & jar xf build/phantom-modules/plain/$bundle.jar phantom.addon.json
  Get-Content phantom.addon.json
  Remove-Item phantom.addon.json
}
```

Expected: each `phantom.addon.json` parses as the schema `AddonLoader.loadAddon` reads (`AddonMetadata` at `src/main/kotlin/org/phantom/api/addon/AddonMetadata.kt`):
```json
{
  "id": "phantom-<name>",
  "name": "Phantom <Name>",
  "version": "<project version>",
  "entrypoints": ["org.phantom.internal.remote.Phantom<Name>Addon"],
  "mixins": []
}
```

`mixins` MUST be empty (`AddonLoader.loadAddon(sourceName, jarBytes)` at `src/main/kotlin/org/phantom/internal/loader/AddonLoader.kt:137-139` rejects remote in-memory addons that declare mixins). If any bundle has a non-empty `mixins` array, fix the corresponding `generatePhantom<Name>RemoteManifest` task in `build.gradle.kts:319-442` and rerun Tasks 5-7.

- [ ] **Step 2: Confirm `entrypoints[0]` matches the class name actually present in the JAR**

For each bundle, the entrypoint string from `phantom.addon.json` must equal `org.phantom.internal.remote.Phantom<Name>Addon`, and that class must exist at the corresponding path inside the JAR. Task 5 step 3 already covers this for the class file; this step adds a check that the JSON `entrypoints` field points at the right name.

Mismatches here are dead-ends at load time — `AddonLoader.loadAddon` throws `"Entrypoint class '<x>' does not exist inside <jar>"` at line 145.

---

### Task 9: Final state check + closing commit

**Files:** none modified (unless prior steps left untracked artifacts).

- [ ] **Step 1: List final artifacts**

```powershell
ls build/phantom-modules/encrypted/, build/phantom-modules/plain/
```

Expected: 5 `.enc`, 5 `.jar` (and possibly the legacy `phantom-0.1.0.jar` if it was preserved — that's OK, it's not referenced by any active code path).

- [ ] **Step 2: Confirm `git status` is clean**

```powershell
git status --short
```

Expected: untracked build outputs (`build/phantom-modules/**`) only, no tracked-but-uncommitted source files. If there are tracked changes, finish committing them in the appropriate prior task before declaring done.

- [ ] **Step 3: Report the deployable artifacts to the user**

Final report message (paste into chat, not committed):
```
5 bundle .enc files ready for the server to serve:
  build/phantom-modules/encrypted/phantom-core.enc   (sha256: <plain-sha>, <bytes>)
  build/phantom-modules/encrypted/phantom-mining.enc (sha256: <plain-sha>, <bytes>)
  build/phantom-modules/encrypted/phantom-slayer.enc (sha256: <plain-sha>, <bytes>)
  build/phantom-modules/encrypted/phantom-diana.enc  (sha256: <plain-sha>, <bytes>)
  build/phantom-modules/encrypted/phantom-rift.enc   (sha256: <plain-sha>, <bytes>)

Each file is nonce(12) || ciphertext+tag — directly consumable by
Loader/loader/.../ModuleCrypto.decryptAesGcm. The sha256 listed
above is over the *plaintext* JAR (what BootstrapStarter.loadModule
verifies post-decrypt at :157-159). These are the values the server
manifest's `modules[].sha256` field must carry.
```

---

## Out of scope

- Spec rewrite to match reality. The committed `2026-05-25-per-addon-enc-bundles-design.md` describes a 12-bundle world that does not exist in this repo. A separate cleanup pass should either rewrite it to document the 5-bundle layout or supersede it with the 05-23 spec. Not blocking the bundle work.
- Adding more bundles (combat-without-slayer, dungeons, farming, etherwarp, pathfinding, qol, visual). The current 5-bundle partition is the shipping design.
- DSL refactor (`phantomBundles { ... }`), entrypoint codegen, coupling analyzer task. Existing code uses hand-written entrypoints in `RemoteModuleAddons.kt`; no codegen is needed to ship the modules.
- Manifest assembler. The server publishes a signed manifest; the build emits per-bundle `phantom.addon.json` only. Manifest signing remains server-side.
- `BuiltinModules.register()` removal from the main JAR. That's a separate later step — the modules need to be *built* and *loadable through the loader* before we can sever the main-JAR shortcut. This plan stops at "modules built + loadable".
