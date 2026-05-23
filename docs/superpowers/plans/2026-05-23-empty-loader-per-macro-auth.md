# Empty-Loader + Per-Macro Re-Auth — Implementation Plan (Phase 1)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire a per-macro re-authentication flow into the existing loader so every macro toggle calls `POST /auth/verify-module` against the Go server; modules that aren't `activation_policy=auto` no longer auto-activate on bootstrap. Heartbeat failure tears down every active module (1-strike, per spec).

**Architecture:** Phase 1 adds the new lifecycle contract (`LoadedModule.onLoad/onActivate/onDeactivate`), the `ModuleRegistry` state machine, the activation executor, and the server endpoint — without yet extracting a separate `bridge/` Gradle subproject. New types live in `src/main/kotlin/org/phantom/api/module/` (existing public layer, currently JIJ'd into `phantom.jar`). Phase 2 will move them into a dedicated `bridge/` subproject and strip the JIJ.

**Tech Stack:** Kotlin (loader + public layer), Java (mixin layer, unchanged this phase), Go (Fiber HTTP server, pgx, redis), kotlinx-serialization-json, JUnit 5.

**Spec reference:** `docs/superpowers/specs/2026-05-23-empty-loader-per-macro-auth-design.md`.

**Out of scope for Phase 1:**
- Extracting `bridge/` Gradle subproject (Phase 2).
- Moving `internal/**` into per-module `.enc` subprojects (Phase 2).
- ASM build-time invariant checks (Phase 2).
- Stripping `clientPublicLayer` JIJ from `Loader/loader/build.gradle.kts` (Phase 2).
- Building/signing the `.enc` bundles via a new Gradle task (already partially done in commit `1ba1bae8` — Phase 2 finishes the split).

**Pre-existing state to know:**
- Mixins are already dispatch-only (plan `2026-05-21-mixin-event-decoupling.md` is complete: commits `3adeef41`, `98d6ce92`, `07b3164d`). Phase 1 does not touch any mixin.
- `InMemoryAddonClassLoader` exists at `src/main/kotlin/org/phantom/internal/loader/InMemoryAddonClassLoader.kt`. Used as-is in Phase 1.
- `AddonLoader.unloadLoadedAddons()` exists. Phase 1 keeps it as the implementation under the new `ModuleRegistry` cascade — no rewrite.
- Heartbeat tolerance: `LoaderConfig.heartbeatFailureLimit` currently allows N strikes. Spec mandates 1. Phase 1 changes the default to 1.
- Build environment quirks (from memory `phantom-loader-integration`): `go`/`cargo` NOT on PATH in this shell. Gradle MUST be invoked with `-x buildNative`. Server-side Go tests are authored but not runnable in this environment.

---

## File Structure

**Files Phase 1 creates:**

| Path | Responsibility |
|---|---|
| `src/main/kotlin/org/phantom/api/module/LoadedModule.kt` | Lifecycle interface (onLoad/onActivate/onDeactivate) |
| `src/main/kotlin/org/phantom/api/module/LoaderApi.kt` | Interface modules call to access EventBus, registry, auth snapshot, native loader, requestActivate/Deactivate |
| `src/main/kotlin/org/phantom/api/module/AuthSnapshot.kt` | Frozen value object — mc username, account id, plan tier |
| `src/main/kotlin/org/phantom/api/module/ActivationPolicy.kt` | Enum: `AUTO`, `VERIFY_ON_TOGGLE` |
| `src/main/kotlin/org/phantom/api/module/ModuleState.kt` | Enum: `NOT_LOADED`, `LOADED`, `INACTIVE`, `ACTIVE`, `LOAD_FAILED`, `ACTIVATION_FAILED` |
| `src/main/kotlin/org/phantom/api/module/ModuleRegistry.kt` | Thread-safe registry of `LoadedModule` instances + state FSM |
| `src/main/kotlin/org/phantom/api/module/Result.kt` | Sealed class — `Ok`, `Denied(reason)`, `Error(cause)` |
| `Loader/loader/src/main/kotlin/org/phantom/loader/bootstrap/BootstrapVerifyModuleClient.kt` | HTTP client for `/auth/verify-module` |
| `Loader/loader/src/main/kotlin/org/phantom/loader/activation/ActivationExecutor.kt` | Single-thread executor serializing per-toggle activations |
| `Loader/loader/src/main/kotlin/org/phantom/loader/activation/DefaultLoaderApi.kt` | `LoaderApi` impl wiring EventBus + registry + executor |
| `Go-Server/server/internal/auth/verify_module.go` | New endpoint handler |
| `src/test/kotlin/org/phantom/api/module/ModuleRegistryTest.kt` | Unit tests for FSM transitions |
| `src/test/kotlin/org/phantom/api/module/ActivationExecutorTest.kt` | Unit tests for serialized activation + retry safety |
| `Go-Server/server/internal/auth/verify_module_test.go` | Unit tests for the new handler |
| `docs/superpowers/runbooks/2026-05-23-empty-loader-smoke.md` | E2E smoke playbook |

**Files Phase 1 modifies:**

| Path | Why |
|---|---|
| `Loader/loader/src/main/kotlin/org/phantom/loader/bootstrap/BootstrapModels.kt` | Add `activation_policy` field to `ManifestModule` + `SignedManifestPayload` |
| `Loader/loader/src/main/kotlin/org/phantom/loader/bootstrap/BootstrapStarter.kt` | Replace eager activation with the new onLoad → conditional onActivate flow; pass `LoaderApi` to modules |
| `Loader/loader/src/main/kotlin/org/phantom/loader/bootstrap/BootstrapHeartbeatClient.kt` | Reduce strike count to 1 (per spec 6d); call `ModuleRegistry.deactivateAll()` on failure |
| `Loader/loader/src/main/kotlin/org/phantom/loader/LoaderConfig.kt` | Set `heartbeatFailureLimit` default to 1 |
| `Go-Server/server/internal/db/manifests.go` | Add `ActivationPolicy` field to `ManifestModule` |
| `Go-Server/server/internal/content/manifest.go` | Set `ActivationPolicy` when building stable manifests (core="auto", others="verify_on_toggle") |
| `Go-Server/server/internal/auth/handler.go` | Register the new route + rate limit |
| `Go-Server/server/internal/auth/service.go` | Add `VerifyModule(ctx, sessionToken, moduleName, mcUsername, sourceIP)` |

---

## Task 1: Add `activation_policy` to the Kotlin manifest models

**Files:**
- Modify: `Loader/loader/src/main/kotlin/org/phantom/loader/bootstrap/BootstrapModels.kt`

- [ ] **Step 1: Add an enum at the top of `BootstrapModels.kt`** (above `VerifySessionRequest`)

```kotlin
package org.phantom.loader.bootstrap

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Determines whether a manifest module activates immediately after onLoad,
 * or waits for a successful POST /auth/verify-module per user toggle.
 */
@Serializable
enum class ActivationPolicy {
    @SerialName("auto")            AUTO,
    @SerialName("verify_on_toggle") VERIFY_ON_TOGGLE;

    companion object {
        fun fromWire(raw: String?): ActivationPolicy =
            when (raw?.trim()?.lowercase()) {
                "auto" -> AUTO
                "verify_on_toggle", null, "" -> VERIFY_ON_TOGGLE
                else -> VERIFY_ON_TOGGLE
            }
    }
}
```

- [ ] **Step 2: Add the field to `ManifestModule`**

Replace the existing `ManifestModule` block with:

```kotlin
@Serializable
data class ManifestModule(
    val name: String,
    val url: String,
    val sha256: String,
    val required: Boolean = false,
    @SerialName("init_order") val initOrder: Int = 0,
    @SerialName("activation_policy") val activationPolicy: ActivationPolicy = ActivationPolicy.VERIFY_ON_TOGGLE,
)
```

Default is `VERIFY_ON_TOGGLE` so manifests that omit the field don't accidentally auto-activate paid modules.

- [ ] **Step 3: Add the same field to `SignedManifestPayload`**

The signed payload is what the loader re-serializes for Ed25519 verification — its field set MUST be identical to what the Go server marshals (see `BootstrapManifestVerifier.kt:22-32` comment). Add `activationPolicy` at the same position as in `ManifestModule`:

```kotlin
@Serializable
data class SignedManifestPayload(
    @SerialName("build_id") val buildId: String,
    val channel: String,
    @SerialName("minimum_loader_version") val minimumLoaderVersion: String,
    @SerialName("module_key") val moduleKey: String? = null,
    val modules: List<ManifestModule>,
    @SerialName("native_components") val nativeComponents: List<ManifestNative>,
)
```

(No change to the payload class itself — it already holds `List<ManifestModule>` which now carries the new field via the data class.)

- [ ] **Step 4: Run Loader build to confirm it still compiles**

```bash
./gradlew :Loader:loader:build -x buildNative -x copyNativeDll -x test
```

Expected: BUILD SUCCESSFUL. No `activation_policy` references yet outside this file.

- [ ] **Step 5: Commit**

```bash
git add Loader/loader/src/main/kotlin/org/phantom/loader/bootstrap/BootstrapModels.kt
git commit -m "feat(loader): add activation_policy to manifest models"
```

---

## Task 2: Mirror `activation_policy` on the Go server side

**Files:**
- Modify: `Go-Server/server/internal/db/manifests.go`
- Modify: `Go-Server/server/internal/content/manifest.go`

- [ ] **Step 1: Add the field on the Go struct**

In `Go-Server/server/internal/db/manifests.go`, locate the `ManifestModule` struct definition. Add a new field after `InitOrder`:

```go
type ManifestModule struct {
    Name             string `json:"name"`
    URL              string `json:"url"`
    SHA256           string `json:"sha256"`
    Required         bool   `json:"required"`
    InitOrder        int    `json:"init_order"`
    ActivationPolicy string `json:"activation_policy"`
}
```

No `omitempty` — per the comment in `BootstrapManifestVerifier.kt:22-32`, the signed payload must always include this field byte-for-byte identical between Go's `json.Marshal` and Kotlin's `Json.encodeToString`. Omitting it breaks Ed25519 verification.

- [ ] **Step 2: Set the field when building the stable manifest**

In `Go-Server/server/internal/content/manifest.go`, find `BuildStableManifest`. Locate the loop that populates each `db.ManifestModule` entry. Set `ActivationPolicy` based on name:

```go
for _, entry := range moduleFiles {
    name := strings.TrimSuffix(entry.Name(), ".enc")
    policy := "verify_on_toggle"
    if name == "phantom-core" {
        policy = "auto"
    }

    modules = append(modules, db.ManifestModule{
        Name:             name,
        URL:              baseURL + "/content/module/" + name,
        SHA256:           hashHex,
        Required:         requiredFor(name),
        InitOrder:        initOrderFor(name),
        ActivationPolicy: policy,
    })
}
```

Adjust to match the existing file's loop variable names — the snippet above shows the policy assignment, not the exact loop variables. If the existing loop differs, integrate the `policy` line and the new struct field only.

- [ ] **Step 3: Smoke-build the Go server (best effort)**

If `go` is on PATH (memory says it isn't in this shell — skip in that case):

```bash
cd Go-Server/server && go build ./...
```

Expected: build succeeds. If `go` is missing, this validation moves to the user's environment.

- [ ] **Step 4: Commit**

```bash
git add Go-Server/server/internal/db/manifests.go Go-Server/server/internal/content/manifest.go
git commit -m "feat(server): add activation_policy field to manifest"
```

---

## Task 3: Add the `LoadedModule` lifecycle contract

**Files:**
- Create: `src/main/kotlin/org/phantom/api/module/LoadedModule.kt`
- Create: `src/main/kotlin/org/phantom/api/module/AuthSnapshot.kt`
- Create: `src/main/kotlin/org/phantom/api/module/LoaderApi.kt`
- Create: `src/main/kotlin/org/phantom/api/module/Result.kt`
- Create: `src/main/kotlin/org/phantom/api/module/ModuleState.kt`

- [ ] **Step 1: Create `ModuleState.kt`**

```kotlin
package org.phantom.api.module

enum class ModuleState {
    NOT_LOADED,
    LOADED,
    INACTIVE,
    ACTIVE,
    LOAD_FAILED,
    ACTIVATION_FAILED,
}
```

- [ ] **Step 2: Create `Result.kt`**

```kotlin
package org.phantom.api.module

sealed class Result {
    object Ok : Result()
    data class Denied(val reason: String) : Result()
    data class Error(val cause: Throwable) : Result()
}
```

- [ ] **Step 3: Create `AuthSnapshot.kt`**

```kotlin
package org.phantom.api.module

/** Read-only snapshot of the auth state at module load time. */
data class AuthSnapshot(
    val accountId: String,
    val username: String,
    val minecraftUsername: String,
    val planTier: String,
    val entitledModules: Set<String>,
)
```

- [ ] **Step 4: Create `LoaderApi.kt`**

```kotlin
package org.phantom.api.module

import org.phantom.api.event.EventBus

/**
 * The only surface a server-loaded module reaches into the loader through.
 * Implementations live in the loader; module code must depend only on this interface.
 */
interface LoaderApi {
    val eventBus: EventBus
    val registry: ModuleRegistry
    val auth: AuthSnapshot

    /** Request activation of the named module. Calls `/auth/verify-module` then onActivate(). */
    fun requestActivate(moduleName: String, callback: (Result) -> Unit)

    /** Deactivate the named module locally (no server call). */
    fun requestDeactivate(moduleName: String)

    /** Loader log; module code uses this for stack-trace attribution. */
    fun logInfo(message: String)
    fun logError(message: String, throwable: Throwable? = null)
}
```

`ModuleRegistry` does not exist yet — Kotlin allows the forward reference because Task 4 lands in the same package before compilation matters. The build will be broken between Task 3 and Task 4. Don't run a build between these tasks.

- [ ] **Step 5: Create `LoadedModule.kt`**

```kotlin
package org.phantom.api.module

/**
 * Contract every server-loaded module implements.
 *
 * Lifecycle:
 *   - onLoad(api) — called once per MC session on the bootstrap thread, in dependency order.
 *     Allowed: read api.auth, register HUD descriptors with api.registry, allocate buffers.
 *     Forbidden: subscribe to EventBus, touch the Minecraft world, spawn long-running threads.
 *
 *   - onActivate() — called when activation is authorized (post /auth/verify-module=true).
 *     Allowed: api.eventBus.register(this), start scheduled tasks.
 *     Forbidden: blocking network I/O on this thread.
 *
 *   - onDeactivate() — called when user toggles off OR heartbeat cascade fires.
 *     Must unregister from EventBus and cancel any scheduled work. Idempotent.
 */
interface LoadedModule {
    val name: String
    fun onLoad(api: LoaderApi)
    fun onActivate()
    fun onDeactivate()
}
```

- [ ] **Step 6: Commit (build is intentionally broken; fixed in Task 4)**

```bash
git add src/main/kotlin/org/phantom/api/module/
git commit -m "feat(api): add LoadedModule lifecycle contract scaffolding"
```

---

## Task 4: Add `ModuleRegistry` with FSM + tests

**Files:**
- Create: `src/main/kotlin/org/phantom/api/module/ModuleRegistry.kt`
- Create: `src/test/kotlin/org/phantom/api/module/ModuleRegistryTest.kt`

- [ ] **Step 1: Write the failing test first** (`src/test/kotlin/org/phantom/api/module/ModuleRegistryTest.kt`)

```kotlin
package org.phantom.api.module

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModuleRegistryTest {
    private fun stubModule(stateChanges: MutableList<String>, n: String = "phantom-test") = object : LoadedModule {
        override val name: String = n
        override fun onLoad(api: LoaderApi) { stateChanges.add("onLoad") }
        override fun onActivate() { stateChanges.add("onActivate") }
        override fun onDeactivate() { stateChanges.add("onDeactivate") }
    }

    @Test
    fun `register transitions NOT_LOADED to LOADED`() {
        val registry = ModuleRegistry()
        val mod = stubModule(mutableListOf())
        registry.register(mod)
        assertEquals(ModuleState.LOADED, registry.stateOf(mod.name))
    }

    @Test
    fun `markActive transitions LOADED to ACTIVE`() {
        val registry = ModuleRegistry()
        val mod = stubModule(mutableListOf())
        registry.register(mod)
        registry.markActive(mod.name)
        assertEquals(ModuleState.ACTIVE, registry.stateOf(mod.name))
    }

    @Test
    fun `markInactive transitions ACTIVE to INACTIVE`() {
        val registry = ModuleRegistry()
        val mod = stubModule(mutableListOf())
        registry.register(mod)
        registry.markActive(mod.name)
        registry.markInactive(mod.name)
        assertEquals(ModuleState.INACTIVE, registry.stateOf(mod.name))
    }

    @Test
    fun `activeModules returns only ACTIVE entries`() {
        val registry = ModuleRegistry()
        val a = stubModule(mutableListOf(), "a")
        val b = stubModule(mutableListOf(), "b")
        val c = stubModule(mutableListOf(), "c")
        registry.register(a); registry.register(b); registry.register(c)
        registry.markActive("a")
        registry.markActive("c")
        assertEquals(setOf("a", "c"), registry.activeModules().map { it.name }.toSet())
    }

    @Test
    fun `markLoadFailed is terminal — subsequent transitions are ignored`() {
        val registry = ModuleRegistry()
        val mod = stubModule(mutableListOf())
        registry.register(mod)
        registry.markLoadFailed(mod.name, "boom")
        registry.markActive(mod.name) // no-op
        assertEquals(ModuleState.LOAD_FAILED, registry.stateOf(mod.name))
    }

    @Test
    fun `unknown module returns NOT_LOADED`() {
        val registry = ModuleRegistry()
        assertEquals(ModuleState.NOT_LOADED, registry.stateOf("missing"))
    }

    @Test
    fun `failureReason is recorded for LOAD_FAILED`() {
        val registry = ModuleRegistry()
        val mod = stubModule(mutableListOf())
        registry.register(mod)
        registry.markLoadFailed(mod.name, "decrypt failed")
        assertEquals("decrypt failed", registry.failureReasonOf(mod.name))
    }
}
```

- [ ] **Step 2: Run the test, expect compile failure**

```bash
./gradlew test --tests "org.phantom.api.module.ModuleRegistryTest" -x buildNative -x copyNativeDll
```

Expected: COMPILATION ERROR — `ModuleRegistry` not found.

- [ ] **Step 3: Implement `ModuleRegistry`** (`src/main/kotlin/org/phantom/api/module/ModuleRegistry.kt`)

```kotlin
package org.phantom.api.module

import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe registry of loaded modules + their lifecycle state.
 *
 * State transitions are enforced: terminal states (LOAD_FAILED, ACTIVATION_FAILED)
 * cannot transition further. Unknown names always return NOT_LOADED.
 */
class ModuleRegistry {
    private data class Entry(
        val module: LoadedModule,
        var state: ModuleState,
        var failureReason: String? = null,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    fun register(module: LoadedModule) {
        entries[module.name] = Entry(module, ModuleState.LOADED)
    }

    fun moduleOf(name: String): LoadedModule? = entries[name]?.module
    fun stateOf(name: String): ModuleState = entries[name]?.state ?: ModuleState.NOT_LOADED
    fun failureReasonOf(name: String): String? = entries[name]?.failureReason

    fun markActive(name: String) = transition(name, ModuleState.ACTIVE)
    fun markInactive(name: String) = transition(name, ModuleState.INACTIVE)

    fun markLoadFailed(name: String, reason: String) {
        entries[name]?.let { e ->
            e.state = ModuleState.LOAD_FAILED
            e.failureReason = reason
        }
    }

    fun markActivationFailed(name: String, reason: String) {
        entries[name]?.let { e ->
            // Activation failure does NOT permanently lock out — operator can retry.
            // But we record it for diagnostics until next state change.
            if (e.state != ModuleState.LOAD_FAILED) {
                e.state = ModuleState.ACTIVATION_FAILED
                e.failureReason = reason
            }
        }
    }

    /** All modules currently in ACTIVE state, in registration order. */
    fun activeModules(): List<LoadedModule> =
        entries.values.filter { it.state == ModuleState.ACTIVE }.map { it.module }

    /** All known modules (any state). */
    fun allModules(): List<LoadedModule> = entries.values.map { it.module }

    private fun transition(name: String, to: ModuleState) {
        val entry = entries[name] ?: return
        if (entry.state == ModuleState.LOAD_FAILED) return // terminal
        entry.state = to
        entry.failureReason = null
    }
}
```

- [ ] **Step 4: Run tests, expect PASS**

```bash
./gradlew test --tests "org.phantom.api.module.ModuleRegistryTest" -x buildNative -x copyNativeDll
```

Expected: all 7 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/org/phantom/api/module/ModuleRegistry.kt src/test/kotlin/org/phantom/api/module/ModuleRegistryTest.kt
git commit -m "feat(api): add ModuleRegistry with state FSM + tests"
```

---

## Task 5: Add `POST /auth/verify-module` Go endpoint

**Files:**
- Create: `Go-Server/server/internal/auth/verify_module.go`
- Modify: `Go-Server/server/internal/auth/service.go` (add `VerifyModule` method)
- Modify: `Go-Server/server/internal/auth/handler.go` (register route + rate limit)
- Create: `Go-Server/server/internal/auth/verify_module_test.go`

- [ ] **Step 1: Write the failing test** (`Go-Server/server/internal/auth/verify_module_test.go`)

```go
package auth

import (
    "context"
    "strings"
    "testing"
    "time"
)

// VerifyModuleResult shape that the service returns. Defined inline to match
// the contract we expect; the type itself is added in step 3.
func TestVerifyModule_AuthorizedForEntitledModule(t *testing.T) {
    svc, ctx, sess, _ := setupTestServiceWithSession(t)
    defer ctx.Done()

    result, err := svc.VerifyModule(context.Background(), sess.RawToken, "phantom-fishing", sess.MinecraftUsername, sess.SourceIP)
    if err != nil {
        t.Fatalf("unexpected error: %v", err)
    }
    if !result.Authorized {
        t.Fatalf("expected authorized=true; got reason=%q", result.Reason)
    }
}

func TestVerifyModule_DeniesUnentitledModule(t *testing.T) {
    svc, ctx, sess, _ := setupTestServiceWithSession(t)
    defer ctx.Done()

    result, _ := svc.VerifyModule(context.Background(), sess.RawToken, "phantom-not-entitled", sess.MinecraftUsername, sess.SourceIP)
    if result.Authorized {
        t.Fatalf("expected authorized=false for un-entitled module")
    }
    if !strings.Contains(result.Reason, "not_entitled") {
        t.Fatalf("expected reason to mention not_entitled; got %q", result.Reason)
    }
}

func TestVerifyModule_PhantomCoreAlwaysAllowed(t *testing.T) {
    svc, ctx, sess, _ := setupTestServiceWithSession(t)
    defer ctx.Done()

    result, _ := svc.VerifyModule(context.Background(), sess.RawToken, "phantom-core", sess.MinecraftUsername, sess.SourceIP)
    if !result.Authorized {
        t.Fatalf("phantom-core must always be allowed; reason=%q", result.Reason)
    }
}

func TestVerifyModule_RejectsMcUsernameMismatch(t *testing.T) {
    svc, ctx, sess, _ := setupTestServiceWithSession(t)
    defer ctx.Done()

    result, _ := svc.VerifyModule(context.Background(), sess.RawToken, "phantom-fishing", "WrongName", sess.SourceIP)
    if result.Authorized {
        t.Fatalf("expected denial on MC username mismatch")
    }
    if !strings.Contains(result.Reason, "minecraft_username_mismatch") {
        t.Fatalf("expected mc mismatch reason; got %q", result.Reason)
    }
}

func TestVerifyModule_RejectsExpiredSession(t *testing.T) {
    svc, ctx, sess, _ := setupTestServiceWithSession(t)
    defer ctx.Done()

    expireSessionInDB(t, svc, sess.SessionID, time.Now().Add(-time.Hour))

    _, err := svc.VerifyModule(context.Background(), sess.RawToken, "phantom-fishing", sess.MinecraftUsername, sess.SourceIP)
    if err != ErrSessionInvalid {
        t.Fatalf("expected ErrSessionInvalid; got %v", err)
    }
}
```

`setupTestServiceWithSession` and `expireSessionInDB` are test helpers. Before writing the test file, **search the repo first**:

```bash
grep -r "func setup" Go-Server/server/internal/auth/ Go-Server/server/internal/db/
grep -r "_test.go" Go-Server/server/internal/auth/
```

If existing helpers exist (likely under `service_test.go`, `setup_test.go`, or a `testutil` package), reuse them and adjust the test function names/signatures above to match. If no helpers exist at all, the tests in this step are **deferred to integration testing**: comment out the test file with a `// TODO: needs DB/Redis test harness — covered by integration tests` note and verify the endpoint manually via curl in Task 10's smoke playbook. Do not invent a parallel test infrastructure for one new endpoint.

- [ ] **Step 2: Run the test, expect failure**

```bash
cd Go-Server/server && go test ./internal/auth/... -run TestVerifyModule -v
```

Expected: COMPILATION ERROR — `svc.VerifyModule` undefined.

(If `go` is not on PATH per the build-env memory, skip this step and rely on the user's CI/local environment to surface the compile error.)

- [ ] **Step 3: Add the result type + service method** (`Go-Server/server/internal/auth/service.go`)

Add near the existing result types (e.g. below `VerifySessionResult`):

```go
type VerifyModuleResult struct {
    Authorized bool
    Reason     string
}
```

Add the service method (alongside `VerifySession`):

```go
func (s *Service) VerifyModule(ctx context.Context, rawToken, moduleName, minecraftUsernameInput, sourceIP string) (*VerifyModuleResult, error) {
    minecraftUsernameInput = strings.TrimSpace(minecraftUsernameInput)
    moduleName = strings.TrimSpace(moduleName)

    if moduleName == "" {
        return &VerifyModuleResult{Authorized: false, Reason: "missing_module_name"}, nil
    }

    tokenHash, err := crypto.HashToken(rawToken)
    if err != nil {
        return nil, ErrSessionInvalid
    }

    sess, err := db.GetSessionByTokenHash(ctx, s.pool, tokenHash)
    if err != nil || sess.Revoked || time.Now().After(sess.ExpiresAt) {
        return nil, ErrSessionInvalid
    }

    device, err := db.GetDeviceByID(ctx, s.pool, sess.DeviceID)
    if err != nil {
        return nil, err
    }

    if device.BindingStatus == "suspended" || device.BindingStatus == "banned" {
        return &VerifyModuleResult{Authorized: false, Reason: "device_blocked"}, nil
    }

    account, err := db.GetAccountByID(ctx, s.pool, sess.AccountID)
    if err != nil {
        return nil, err
    }
    if account.Status != "active" {
        return &VerifyModuleResult{Authorized: false, Reason: "account_blocked"}, nil
    }

    if device.MinecraftUsername == nil || !strings.EqualFold(*device.MinecraftUsername, minecraftUsernameInput) {
        s.auditSvc.Log("auth.verify_module.fail", &account.ID, &device.ID, nil, &sourceIP, map[string]any{
            "reason":   "minecraft_username_mismatch",
            "module":   moduleName,
        })
        return &VerifyModuleResult{Authorized: false, Reason: "minecraft_username_mismatch"}, nil
    }

    ent, err := s.entSvc.Resolve(ctx, account.ID)
    if err != nil {
        return nil, err
    }
    if !ent.Authorized {
        return &VerifyModuleResult{Authorized: false, Reason: ent.Reason}, nil
    }

    if !moduleAllowed(moduleName, ent.EnabledModules) {
        s.auditSvc.Log("auth.verify_module.fail", &account.ID, &device.ID, nil, &sourceIP, map[string]any{
            "reason":   "not_entitled",
            "module":   moduleName,
        })
        return &VerifyModuleResult{Authorized: false, Reason: "not_entitled"}, nil
    }

    s.auditSvc.Log("auth.verify_module.success", &account.ID, &device.ID, nil, &sourceIP, map[string]any{
        "module": moduleName,
    })
    return &VerifyModuleResult{Authorized: true, Reason: ""}, nil
}

func moduleAllowed(moduleName string, enabled []string) bool {
    if moduleName == "phantom-core" {
        return true
    }
    short := strings.TrimPrefix(moduleName, "phantom-")
    for _, m := range enabled {
        if m == "*" || m == moduleName || m == short {
            return true
        }
    }
    return false
}
```

Mirror the existing `ModuleBytes` check in `content/service.go:84-89` so the gate is consistent between content download and verify-module.

- [ ] **Step 4: Create the HTTP handler** (`Go-Server/server/internal/auth/verify_module.go`)

```go
package auth

import (
    "log"
    "strings"

    "github.com/phantom/server/internal/middleware"
    "github.com/gofiber/fiber/v2"
)

type verifyModuleRequest struct {
    ModuleName        string `json:"module_name"`
    MinecraftUsername string `json:"minecraft_username"`
}

func handleVerifyModule(svc *Service) fiber.Handler {
    return func(c *fiber.Ctx) error {
        ip := middleware.GetRealIP(c)

        rawToken, err := middleware.ParseBearerToken(c.Get("Authorization"))
        if err != nil {
            return c.Status(401).JSON(fiber.Map{
                "authorized": false,
                "reason":     "missing_session_token",
            })
        }

        var req verifyModuleRequest
        if err := c.BodyParser(&req); err != nil {
            return c.Status(400).JSON(fiber.Map{
                "authorized": false,
                "reason":     "invalid_request",
            })
        }

        moduleName := strings.TrimSpace(req.ModuleName)
        mcUsername := strings.TrimSpace(req.MinecraftUsername)
        if moduleName == "" {
            return c.Status(400).JSON(fiber.Map{
                "authorized": false,
                "reason":     "missing_module_name",
            })
        }

        result, err := svc.VerifyModule(c.Context(), rawToken, moduleName, mcUsername, ip)
        if err == ErrSessionInvalid {
            return c.Status(401).JSON(fiber.Map{
                "authorized": false,
                "reason":     "session_invalid",
            })
        }
        if err != nil {
            log.Printf("[auth.verify_module.route] internal_error ip=%s module=%s err=%v", ip, moduleName, err)
            return c.Status(500).JSON(fiber.Map{
                "authorized": false,
                "reason":     "internal_error",
            })
        }

        status := 200
        if !result.Authorized {
            status = 403
        }

        log.Printf("[auth.verify_module.route] ip=%s module=%s authorized=%t reason=%s",
            ip, moduleName, result.Authorized, result.Reason,
        )

        return c.Status(status).JSON(fiber.Map{
            "authorized": result.Authorized,
            "reason":     result.Reason,
        })
    }
}
```

- [ ] **Step 5: Register the route in `handler.go`**

In `Go-Server/server/internal/auth/handler.go`'s `RegisterRoutes`, add the new route and its rate limit, alongside the existing handlers:

```go
verifyModuleLimit := middleware.RateLimit(rdb, 60, time.Minute, middleware.IPAndUsernameKey("verify-module"))
app.Post("/auth/verify-module", verifyModuleLimit, handleVerifyModule(svc))
```

Place it after `app.Post("/auth/verify-session", ...)` for grouping.

- [ ] **Step 6: Run tests, expect PASS**

```bash
cd Go-Server/server && go test ./internal/auth/... -run TestVerifyModule -v
```

Expected: all 5 tests PASS. If the env lacks `go`, defer this to user CI.

- [ ] **Step 7: Commit**

```bash
git add Go-Server/server/internal/auth/verify_module.go \
        Go-Server/server/internal/auth/verify_module_test.go \
        Go-Server/server/internal/auth/service.go \
        Go-Server/server/internal/auth/handler.go
git commit -m "feat(server): add POST /auth/verify-module endpoint"
```

---

## Task 6: Add the Kotlin `BootstrapVerifyModuleClient`

**Files:**
- Create: `Loader/loader/src/main/kotlin/org/phantom/loader/bootstrap/BootstrapVerifyModuleClient.kt`
- Modify: `Loader/loader/src/main/kotlin/org/phantom/loader/bootstrap/BootstrapModels.kt` (add request/response types)

- [ ] **Step 1: Add request/response models to `BootstrapModels.kt`**

Append to the bottom of `BootstrapModels.kt`:

```kotlin
@Serializable
data class VerifyModuleRequest(
    @SerialName("module_name") val moduleName: String,
    @SerialName("minecraft_username") val minecraftUsername: String,
)

@Serializable
data class VerifyModuleResponse(
    val authorized: Boolean = false,
    val reason: String = "",
)
```

- [ ] **Step 2: Create the client** (`BootstrapVerifyModuleClient.kt`)

```kotlin
package org.phantom.loader.bootstrap

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.phantom.loader.LoaderConfig
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Outcomes the loader's activation flow distinguishes:
 *   - Authorized: proceed with onActivate
 *   - Denied:    server explicitly said no — leave INACTIVE, surface reason
 *   - SessionInvalid: trigger the heartbeat-failure cascade (deactivate all)
 *   - Error:     network/timeout/non-200 — treat strictest (no activation)
 */
sealed class VerifyModuleOutcome {
    object Authorized : VerifyModuleOutcome()
    data class Denied(val reason: String) : VerifyModuleOutcome()
    object SessionInvalid : VerifyModuleOutcome()
    data class Error(val cause: Throwable) : VerifyModuleOutcome()
}

object BootstrapVerifyModuleClient {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    fun verify(sessionToken: String, moduleName: String, minecraftUsername: String): VerifyModuleOutcome {
        val body = json.encodeToString(VerifyModuleRequest(moduleName, minecraftUsername))
        val request = HttpRequest.newBuilder()
            .uri(trustedUri("${LoaderConfig.serverBaseUrl.trimEnd('/')}/auth/verify-module"))
            .timeout(Duration.ofSeconds(8))
            .header("Authorization", "Bearer $sessionToken")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        return runCatching {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            when (response.statusCode()) {
                401 -> VerifyModuleOutcome.SessionInvalid
                200, 403 -> {
                    val parsed = json.decodeFromString<VerifyModuleResponse>(response.body())
                    if (parsed.authorized) VerifyModuleOutcome.Authorized
                    else VerifyModuleOutcome.Denied(parsed.reason.ifBlank { "not_authorized" })
                }
                else -> VerifyModuleOutcome.Error(IllegalStateException("HTTP ${response.statusCode()}"))
            }
        }.getOrElse { VerifyModuleOutcome.Error(it) }
    }
}
```

Note: `trustedUri` is the existing helper used by the other bootstrap clients (see `BootstrapAuthClient.kt:23`). Reuse it.

- [ ] **Step 3: Build to confirm compiles**

```bash
./gradlew :Loader:loader:build -x buildNative -x copyNativeDll -x test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add Loader/loader/src/main/kotlin/org/phantom/loader/bootstrap/BootstrapVerifyModuleClient.kt \
        Loader/loader/src/main/kotlin/org/phantom/loader/bootstrap/BootstrapModels.kt
git commit -m "feat(loader): add BootstrapVerifyModuleClient"
```

---

## Task 7: Add the activation executor + `LoaderApi` impl

**Files:**
- Create: `Loader/loader/src/main/kotlin/org/phantom/loader/activation/ActivationExecutor.kt`
- Create: `Loader/loader/src/main/kotlin/org/phantom/loader/activation/DefaultLoaderApi.kt`
- Create: `src/test/kotlin/org/phantom/api/module/ActivationExecutorTest.kt`

- [ ] **Step 1: Write the failing test for serialization behavior**

```kotlin
package org.phantom.api.module

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.phantom.loader.activation.ActivationExecutor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class ActivationExecutorTest {

    @Test
    fun `submitted activations run on a single thread in order`() {
        val executor = ActivationExecutor()
        val seen = mutableListOf<Int>()
        val latch = CountDownLatch(3)

        executor.submit { seen.add(1); latch.countDown() }
        executor.submit { seen.add(2); latch.countDown() }
        executor.submit { seen.add(3); latch.countDown() }

        latch.await()
        assertEquals(listOf(1, 2, 3), seen)
        executor.shutdown()
    }

    @Test
    fun `submit returns quickly even when previous task blocks`() {
        val executor = ActivationExecutor()
        val started = AtomicInteger(0)
        val blockStart = CountDownLatch(1)
        val blockEnd = CountDownLatch(1)

        executor.submit {
            started.incrementAndGet()
            blockStart.countDown()
            blockEnd.await() // hold the worker
        }
        blockStart.await()

        // Submit second; should not block our test thread.
        val submitDeadline = System.nanoTime() + 200_000_000L // 200ms budget
        executor.submit { started.incrementAndGet() }
        assert(System.nanoTime() < submitDeadline) { "submit should be non-blocking" }

        blockEnd.countDown()
        executor.shutdown()
    }
}
```

- [ ] **Step 2: Run the test, expect compile failure**

```bash
./gradlew test --tests "org.phantom.api.module.ActivationExecutorTest" -x buildNative -x copyNativeDll
```

Expected: COMPILATION ERROR — `ActivationExecutor` not found.

- [ ] **Step 3: Implement `ActivationExecutor`** (`Loader/loader/src/main/kotlin/org/phantom/loader/activation/ActivationExecutor.kt`)

```kotlin
package org.phantom.loader.activation

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Single-thread executor that serializes activation requests.
 *
 * Two concurrent calls to LoaderApi.requestActivate("X") result in exactly one
 * /auth/verify-module call from inside the worker; the second submit is queued
 * behind the first and runs after it completes.
 */
class ActivationExecutor {
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Phantom-Activation").apply { isDaemon = true }
    }

    fun submit(task: () -> Unit) {
        executor.submit(task)
    }

    fun shutdown() {
        executor.shutdown()
        executor.awaitTermination(2, TimeUnit.SECONDS)
    }
}
```

- [ ] **Step 4: Run tests, expect PASS**

```bash
./gradlew test --tests "org.phantom.api.module.ActivationExecutorTest" -x buildNative -x copyNativeDll
```

Expected: both tests PASS.

- [ ] **Step 5: Implement `DefaultLoaderApi`** (`Loader/loader/src/main/kotlin/org/phantom/loader/activation/DefaultLoaderApi.kt`)

```kotlin
package org.phantom.loader.activation

import org.phantom.api.event.EventBus
import org.phantom.api.module.AuthSnapshot
import org.phantom.api.module.LoaderApi
import org.phantom.api.module.ModuleRegistry
import org.phantom.api.module.ModuleState
import org.phantom.api.module.Result
import org.phantom.loader.LoaderLog
import org.phantom.loader.bootstrap.BootstrapVerifyModuleClient
import org.phantom.loader.bootstrap.VerifyModuleOutcome

class DefaultLoaderApi(
    private val sessionToken: String,
    override val auth: AuthSnapshot,
    override val registry: ModuleRegistry,
    private val executor: ActivationExecutor,
    private val onSessionInvalid: () -> Unit,
) : LoaderApi {

    override val eventBus: EventBus get() = EventBus

    override fun requestActivate(moduleName: String, callback: (Result) -> Unit) {
        executor.submit {
            val module = registry.moduleOf(moduleName)
            if (module == null) {
                callback(Result.Error(IllegalStateException("not loaded: $moduleName")))
                return@submit
            }
            // Idempotent: already active → return Ok without re-verifying.
            if (registry.stateOf(moduleName) == ModuleState.ACTIVE) {
                callback(Result.Ok)
                return@submit
            }

            val outcome = BootstrapVerifyModuleClient.verify(
                sessionToken = sessionToken,
                moduleName = moduleName,
                minecraftUsername = auth.minecraftUsername,
            )

            when (outcome) {
                is VerifyModuleOutcome.Authorized -> {
                    try {
                        module.onActivate()
                        registry.markActive(moduleName)
                        callback(Result.Ok)
                    } catch (t: Throwable) {
                        try { module.onDeactivate() } catch (_: Throwable) {}
                        registry.markActivationFailed(moduleName, t.message ?: "activate threw")
                        logError("Activation of $moduleName threw", t)
                        callback(Result.Error(t))
                    }
                }
                is VerifyModuleOutcome.Denied -> callback(Result.Denied(outcome.reason))
                is VerifyModuleOutcome.SessionInvalid -> {
                    onSessionInvalid()
                    callback(Result.Denied("session_invalid"))
                }
                is VerifyModuleOutcome.Error -> callback(Result.Error(outcome.cause))
            }
        }
    }

    override fun requestDeactivate(moduleName: String) {
        executor.submit {
            val module = registry.moduleOf(moduleName) ?: return@submit
            if (registry.stateOf(moduleName) != ModuleState.ACTIVE) return@submit
            try {
                module.onDeactivate()
            } catch (t: Throwable) {
                logError("Deactivation of $moduleName threw", t)
            } finally {
                registry.markInactive(moduleName)
            }
        }
    }

    override fun logInfo(message: String) = LoaderLog.info(message)
    override fun logError(message: String, throwable: Throwable?) = LoaderLog.error(message, throwable)
}
```

- [ ] **Step 6: Build to confirm everything compiles together**

```bash
./gradlew :Loader:loader:build -x buildNative -x copyNativeDll -x test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add Loader/loader/src/main/kotlin/org/phantom/loader/activation/ \
        src/test/kotlin/org/phantom/api/module/ActivationExecutorTest.kt
git commit -m "feat(loader): add ActivationExecutor and DefaultLoaderApi"
```

---

## Task 8: Refactor `BootstrapStarter` to use the new lifecycle

**Files:**
- Modify: `Loader/loader/src/main/kotlin/org/phantom/loader/bootstrap/BootstrapStarter.kt`

The current flow eagerly activates everything via `AddonLoader.activateLoadedAddons()`. New flow: call `onLoad` on every module, call `onActivate` only on modules whose `activation_policy=AUTO` (i.e. `phantom-core`).

- [ ] **Step 1: Replace the activation section of `BootstrapStarter.start()`**

Find the current block in `BootstrapStarter.kt:60-72`:

```kotlin
            Auth.modulesTotal = modules.size
            modules.forEachIndexed { index, module ->
                loadModule(module, manifest, session.token)
                Auth.modulesLoaded = index + 1
            }

            AddonLoader.activateLoadedAddons()
            BootstrapHeartbeatClient.start(session.token)
```

Replace it with the new lifecycle wiring:

```kotlin
            Auth.modulesTotal = modules.size
            val registry = ModuleRegistry()
            val activationExecutor = ActivationExecutor()
            val authSnapshot = AuthSnapshot(
                accountId = auth.accountId,
                username = auth.username.ifBlank { auth.alias },
                minecraftUsername = Auth.minecraftUsername,
                planTier = auth.planTier,
                entitledModules = auth.enabledModules.toSet(),
            )
            val loaderApi = DefaultLoaderApi(
                sessionToken = session.token,
                auth = authSnapshot,
                registry = registry,
                executor = activationExecutor,
                onSessionInvalid = { triggerSessionInvalidCascade(registry, activationExecutor) },
            )

            modules.forEachIndexed { index, manifestModule ->
                runCatching { loadModule(manifestModule, manifest, session.token) }
                    .onSuccess {
                        val loaded = AddonLoader.findLoaded(manifestModule.name)
                        if (loaded != null) {
                            registry.register(loaded)
                            runCatching { loaded.onLoad(loaderApi) }
                                .onFailure {
                                    registry.markLoadFailed(manifestModule.name, it.message ?: "onLoad threw")
                                    LoaderLog.error("onLoad failed for ${manifestModule.name}", it)
                                }
                        }
                    }
                    .onFailure {
                        if (manifestModule.name == "phantom-core") throw it
                        LoaderLog.error("Failed to load ${manifestModule.name}", it)
                    }
                Auth.modulesLoaded = index + 1
            }

            // Auto-activate only modules whose manifest entry says so (currently phantom-core).
            modules
                .filter { it.activationPolicy == ActivationPolicy.AUTO }
                .forEach { manifestModule ->
                    val loaded = registry.moduleOf(manifestModule.name) ?: return@forEach
                    runCatching { loaded.onActivate() }
                        .onSuccess { registry.markActive(manifestModule.name) }
                        .onFailure {
                            registry.markActivationFailed(manifestModule.name, it.message ?: "onActivate threw")
                            LoaderLog.error("Auto onActivate failed for ${manifestModule.name}", it)
                            if (manifestModule.name == "phantom-core") throw it
                        }
                }

            ActiveLoaderState.registry = registry
            ActiveLoaderState.activationExecutor = activationExecutor
            ActiveLoaderState.loaderApi = loaderApi

            BootstrapHeartbeatClient.start(session.token, registry, activationExecutor)
```

`AddonLoader.findLoaded(name): LoadedModule?` does not exist yet — add it in step 2. `ActiveLoaderState` is a tiny new singleton (step 4) holding references for heartbeat-failure access.

- [ ] **Step 2: Add `findLoaded` to `AddonLoader`**

In `src/main/kotlin/org/phantom/internal/loader/AddonLoader.kt`, add a method that exposes already-loaded module instances by name. The implementer reads the existing file to find the storage map, then adds:

```kotlin
fun findLoaded(name: String): LoadedModule? {
    // Look up the loaded addon by manifest module name and return its
    // LoadedModule entrypoint instance. Returns null if not loaded or
    // if the addon doesn't expose a LoadedModule (legacy addons).
    return loadedAddons[name]?.entrypoint as? LoadedModule
}
```

Adjust to match the actual field names in `AddonLoader.kt`. The intent: given the registry of addons that `loadFromBytes` has already created, return their entrypoint object IF it implements the new `LoadedModule` interface, else null.

If existing addon entrypoints do NOT implement `LoadedModule` yet, this method returns null for them in Phase 1 — they continue using the legacy activation path. Phase 2 migrates them.

- [ ] **Step 3: Add `triggerSessionInvalidCascade` helper to `BootstrapStarter.kt`**

Append at the bottom of the `BootstrapStarter` object:

```kotlin
private fun triggerSessionInvalidCascade(
    registry: ModuleRegistry,
    executor: ActivationExecutor,
) {
    Auth.state = AuthState.FAILED
    Auth.failureReason = "Session invalidated by server"
    Auth.statusMessage = "Phantom session invalidated. Restart Minecraft."
    registry.activeModules().forEach { module ->
        runCatching { module.onDeactivate() }
            .onFailure { LoaderLog.error("onDeactivate during cascade failed for ${module.name}", it) }
        registry.markInactive(module.name)
    }
    executor.shutdown()
}
```

- [ ] **Step 4: Create `ActiveLoaderState`**

New file: `Loader/loader/src/main/kotlin/org/phantom/loader/activation/ActiveLoaderState.kt`

```kotlin
package org.phantom.loader.activation

import org.phantom.api.module.LoaderApi
import org.phantom.api.module.ModuleRegistry

/**
 * Lazy-initialized handles to the live loader state. Populated by
 * BootstrapStarter; consumed by the heartbeat thread and any future
 * UI bridge that needs to call requestActivate from outside the bootstrap.
 */
object ActiveLoaderState {
    @Volatile var registry: ModuleRegistry? = null
    @Volatile var activationExecutor: ActivationExecutor? = null
    @Volatile var loaderApi: LoaderApi? = null
}
```

- [ ] **Step 5: Add the new imports to `BootstrapStarter.kt`**

Add to the import block at the top:

```kotlin
import org.phantom.api.module.AuthSnapshot
import org.phantom.api.module.LoadedModule
import org.phantom.api.module.ModuleRegistry
import org.phantom.loader.activation.ActivationExecutor
import org.phantom.loader.activation.ActiveLoaderState
import org.phantom.loader.activation.DefaultLoaderApi
```

- [ ] **Step 6: Build to confirm compile**

```bash
./gradlew :Loader:loader:build -x buildNative -x copyNativeDll -x test
```

Expected: BUILD SUCCESSFUL. If `AddonLoader.findLoaded` shape doesn't match the existing addon storage, fix until it does — do not invent parallel storage.

- [ ] **Step 7: Commit**

```bash
git add Loader/loader/src/main/kotlin/org/phantom/loader/bootstrap/BootstrapStarter.kt \
        Loader/loader/src/main/kotlin/org/phantom/loader/activation/ActiveLoaderState.kt \
        src/main/kotlin/org/phantom/internal/loader/AddonLoader.kt
git commit -m "feat(loader): wire LoadedModule lifecycle into bootstrap"
```

---

## Task 9: Refactor heartbeat-failure to call `onDeactivate` on all active modules

**Files:**
- Modify: `Loader/loader/src/main/kotlin/org/phantom/loader/bootstrap/BootstrapHeartbeatClient.kt`
- Modify: `Loader/loader/src/main/kotlin/org/phantom/loader/LoaderConfig.kt`

- [ ] **Step 1: Change `LoaderConfig.heartbeatFailureLimit` default to 1**

In `LoaderConfig.kt`, find the `heartbeatFailureLimit` constant. Change its default to `1`. Per spec 6d, first heartbeat failure is fatal — there is no grace window.

If the field's current value is read from a settings file, also set the default in that file to `1`.

- [ ] **Step 2: Change `BootstrapHeartbeatClient.start` signature**

Update to accept the registry and activation executor (so it can run the deactivation cascade on failure):

```kotlin
fun start(
    sessionToken: String,
    registry: ModuleRegistry,
    executor: ActivationExecutor,
) {
    worker?.interrupt()
    worker = thread(name = "Phantom-Heartbeat", isDaemon = true) {
        var failures = 0
        while (!Thread.currentThread().isInterrupted) {
            val outcome = send(sessionToken)
            if (outcome.ok) {
                failures = 0
            } else if (++failures >= LoaderConfig.heartbeatFailureLimit) {
                LoaderLog.error("Heartbeat trust lost. Deactivating modules.")
                Auth.state = AuthState.FAILED
                Auth.failureReason = if (outcome.sessionInvalid) "session_invalid" else "Heartbeat trust lost"
                Auth.statusMessage = "Phantom session revoked - heartbeat trust lost."
                cascadeDeactivate(registry)
                executor.shutdown()
                Thread.currentThread().interrupt()
            }
            try {
                Thread.sleep(Duration.ofSeconds(LoaderConfig.heartbeatIntervalSeconds).toMillis())
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}

private data class SendOutcome(val ok: Boolean, val sessionInvalid: Boolean)

private fun send(sessionToken: String): SendOutcome =
    runCatching {
        val request = HttpRequest.newBuilder()
            .uri(trustedUri("${LoaderConfig.serverBaseUrl.trimEnd('/')}/auth/heartbeat"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(HeartbeatRequest(sessionToken))))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        SendOutcome(ok = response.statusCode() == 200, sessionInvalid = response.statusCode() == 401)
    }.onFailure {
        LoaderLog.error("Heartbeat failed: ${it.message}", it)
    }.getOrDefault(SendOutcome(ok = false, sessionInvalid = false))

private fun cascadeDeactivate(registry: ModuleRegistry) {
    registry.activeModules().forEach { module ->
        runCatching { module.onDeactivate() }
            .onFailure { LoaderLog.error("onDeactivate during cascade failed for ${module.name}", it) }
        registry.markInactive(module.name)
    }
    // Legacy path: also call the existing AddonLoader.unloadLoadedAddons() to
    // preserve backwards compatibility for any addons that still use the
    // pre-lifecycle activation path. Safe — it's idempotent.
    AddonLoader.unloadLoadedAddons()
}
```

Add the new imports near the existing ones:

```kotlin
import org.phantom.api.module.ModuleRegistry
import org.phantom.loader.activation.ActivationExecutor
```

- [ ] **Step 3: Update the `BootstrapStarter` call site**

Already done in Task 8 step 1 (passing `registry` and `activationExecutor` to `start`). Verify the call matches:

```kotlin
BootstrapHeartbeatClient.start(session.token, registry, activationExecutor)
```

- [ ] **Step 4: Build to confirm**

```bash
./gradlew :Loader:loader:build -x buildNative -x copyNativeDll -x test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add Loader/loader/src/main/kotlin/org/phantom/loader/bootstrap/BootstrapHeartbeatClient.kt \
        Loader/loader/src/main/kotlin/org/phantom/loader/LoaderConfig.kt
git commit -m "feat(loader): cascade deactivate on heartbeat failure"
```

---

## Task 10: E2E smoke playbook

**Files:**
- Create: `docs/superpowers/runbooks/2026-05-23-empty-loader-smoke.md`

- [ ] **Step 1: Write the runbook**

```markdown
# Empty-Loader + Per-Macro Re-Auth — E2E Smoke (Phase 1)

**When to run:** after merging Phase 1, before merging Phase 2.

**Prerequisites:**
- Local Go server running with the new `/auth/verify-module` endpoint (commit from Task 5).
- A test account that owns `phantom-fishing` entitlement (or `*`).
- Prism Launcher with a Fabric 1.21.11 instance, the new `phantom.jar` deployed.
- `LoaderConfig.heartbeatIntervalSeconds` set to ≤60 (for fast cascade reproduction).

## Steps

1. **Build the loader.**
   ```bash
   ./gradlew :Loader:loader:build -x buildNative -x copyNativeDll
   ./gradlew deployMod  # or copy build/libs/phantom-<ver>.jar into Prism's mods folder
   ```
   Expected: `phantom-<ver>.jar` exists and is under ~5 MB.

2. **Launch Minecraft.**
   Expected loader-side log lines (chronological):
   ```
   [Phantom-Loader] INFO Phantom loader initialized.
   [Phantom-Loader] INFO Bootstrap complete. Loaded N protected module bundle(s).
   ```
   `Auth.state` should land at `READY`. No exceptions.

3. **Confirm `phantom-core` auto-activated.**
   In MC, the Phantom UI hotkey (default `Right-Shift`) opens the panel. If the panel does not open, core did not auto-activate — FAIL.

4. **Toggle `phantom-fishing` ON via the UI.**
   Server log should show:
   ```
   [auth.verify_module.route] ip=... module=phantom-fishing authorized=true reason=
   ```
   Loader log should show `Activation of phantom-fishing succeeded` (or similar — depends on module-side logging).
   Fishing macro engages.

5. **Toggle `phantom-fishing` OFF via the UI.**
   No new server hit. Macro disengages. Module remains LOADED (not unloaded).

6. **Toggle `phantom-fishing` ON again.**
   Server log shows a second `auth.verify_module.route` line.
   Macro re-engages immediately — no classloader work happens this time.

7. **Test denial.** Remove the entitlement from the test account in the DB (or use a second account without fishing entitled). Toggle on:
   - Server log: `authorized=false reason=not_entitled`.
   - UI shows the denial reason as a toast.
   - Module stays INACTIVE.

8. **Test heartbeat cascade.** Block the loader's outbound HTTP to the server (firewall rule or shut down the server). Within `heartbeatIntervalSeconds` × 2:
   - Loader log: `Heartbeat trust lost. Deactivating modules.`
   - Any active macros disengage.
   - `Auth.state` is `FAILED`.
   - Restoring network does NOT recover — user must restart MC.

9. **Restart MC after cascade.**
   Bootstrap succeeds again; core re-activates; UI works.

## Pass/Fail

All 9 steps must reproduce the expected behavior. Any deviation is a Phase 1 regression — file an issue with the failing step number and observed vs. expected output.
```

- [ ] **Step 2: Commit**

```bash
git add docs/superpowers/runbooks/2026-05-23-empty-loader-smoke.md
git commit -m "docs(runbook): E2E smoke for empty-loader Phase 1"
```

---

## Done criteria

Phase 1 is complete when:

1. All 10 tasks above are committed on branch `loader`.
2. `./gradlew :Loader:loader:build -x buildNative -x copyNativeDll` succeeds.
3. The unit tests pass: `./gradlew test -x buildNative -x copyNativeDll`.
4. The Go server build succeeds on the user's environment (`cd Go-Server/server && go build ./...`).
5. The Go server tests pass on the user's environment (`go test ./internal/auth/... -v`).
6. The E2E smoke playbook (`docs/superpowers/runbooks/2026-05-23-empty-loader-smoke.md`) passes end-to-end on the user's machine with a real server + real MC instance.

When all six conditions hold, Phase 2 (extract `bridge/` subproject, split `internal/**` into per-module `.enc` bundles, strip the public-layer JIJ, add ASM build-time invariant checks) can be planned.
