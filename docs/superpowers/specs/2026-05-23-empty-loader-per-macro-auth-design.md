# Empty-loader + per-macro re-auth — design

**Date:** 2026-05-23
**Branch:** `loader`
**Supersedes (in part):** `2026-05-20-client-loader-integration-design.md` — that spec defined the original auth/manifest/encrypted-module flow on a JIJ'd public-layer model. This spec extends it: the shipped loader contains only auth + dispatch mixins, every macro is fetched server-side, and the server re-verifies the account on every macro toggle.

## Goals

1. The shipped `phantom.jar` contains **no macro/feature code** — only auth code, mixin "dispatch thunks", and a small bridge layer (EventBus, lifecycle interfaces, native loader).
2. Every macro module is delivered from the Go server as a signed, encrypted `.enc` bundle, gated by the existing IP + Minecraft username + HWID checks.
3. Every time a user toggles a macro on, the loader posts to the server to re-verify that the account is still authorized for that module. Toggling off is local. Heartbeat failure disables all active macros immediately.

## Non-goals

- Loader auto-update. Pubkey/schema bumps mean a manual reinstall.
- Module hot-reload during a live session. Customer restarts MC.
- Offline mode. No cached auth, no grace window — strictest path.
- Plugin/addon SDK for third parties. The disk-based `config/cobalt/addons/` discovery is dropped from customer builds (dev-mode opt-in preserved).
- Telemetry / crash uploads.

## Trust model after this change

A customer who reverse-engineers `phantom.jar` gets:
- The auth protocol (HWID enrollment, session token format).
- The manifest Ed25519 public key.
- A list of mixin injection points.

They get **zero** macro behavior — no fishing logic, no routes, no UI strings, no command names. All of that lives in encrypted `.enc` bundles that won't ship to them without a valid entitlement.

---

## Architecture overview

```
                          ┌─────────────────────┐
                          │  Minecraft starts   │
                          └──────────┬──────────┘
                                     │
                          ┌──────────▼──────────┐
                          │ Fabric loads        │
                          │ phantom.jar         │
                          │ • mixins register   │
                          │   (NOT firing yet — │
                          │    EventBus has     │
                          │    zero subscribers)│
                          └──────────┬──────────┘
                                     │
                          ┌──────────▼──────────┐
                          │ Bootstrap thread    │
                          │ /auth/verify-session│
                          │ /content/manifest   │──── Ed25519 verify
                          │ download all .enc   │──── AES-GCM decrypt
                          │ classload each      │──── per-module ClassLoader
                          │ call onLoad() on    │     (registers w/ Registry,
                          │ every module        │      does NOT subscribe yet)
                          └──────────┬──────────┘
                                     │
                          ┌──────────▼──────────┐
                          │ Activation cascade  │
                          │ for each module     │
                          │ with policy=auto:   │
                          │   onActivate()      │──── only phantom-core
                          │   → subscribes to   │     auto-activates
                          │   EventBus          │
                          └──────────┬──────────┘
                                     │
                          ┌──────────▼──────────┐
                          │ Heartbeat thread    │
                          │ starts (60s)        │
                          └──────────┬──────────┘
                                     │
                                  ┌──┴──┐
                                  │READY│
                                  └──┬──┘
                                     │
                ┌────────────────────┼────────────────────┐
                │                    │                    │
   ┌────────────▼────────┐  ┌────────▼─────────┐  ┌──────▼─────────┐
   │ User toggles macro  │  │ Heartbeat fails  │  │ User toggles   │
   │ ON (from core UI)   │  │ (network, 401,   │  │ macro OFF      │
   │                     │  │  account revoke) │  │                │
   │ /auth/verify-module │  │                  │  │ module.        │
   │  → authorized=true  │  │ for each active  │  │  onDeactivate()│
   │ → module.onActivate │  │   module:        │  │ → unsubscribes │
   │   → subscribes      │  │   onDeactivate() │  │                │
   │                     │  │   → unsubscribes │  │ bytes stay     │
   │  OR                 │  │ Auth.state=FAILED│  │  loaded        │
   │  authorized=false   │  │                  │  │                │
   │ → toast reason,     │  └──────────────────┘  └────────────────┘
   │   toggle reverts    │
   └─────────────────────┘
```

---

## Section 1 — Loader jar contents

The `phantom.jar` that ships to customers contains only these packages.

**Stays in the loader:**

| Package | Purpose | Source today |
|---|---|---|
| `org.phantom.loader.*` | Session storage, bootstrap thread, runtime guard | `Loader/loader/src/main/kotlin/org/phantom/loader/**` |
| `org.phantom.loader.bootstrap.*` | Auth client, manifest verifier, content client, AES-GCM, heartbeat | Same path, already exists |
| `org.phantom.bridge.mixin.*` | All `@Mixin` classes — pure dispatch only | NEW — refactored from current `src/main/java/org/phantom/mixin/**` |
| `org.phantom.bridge.event.*` | `EventBus`, `Event` base, event record types | NEW — minimal subset of today's `api/event/**` |
| `org.phantom.bridge.module.*` | `LoadedModule` interface (onLoad/onActivate/onDeactivate), `ModuleRegistry`, `LoaderApi`, `AuthSnapshot` | NEW |
| `org.phantom.bridge.native.NativeLoader` | JNI DLL extraction/installation | Already exists |
| `org.phantom.PhantomEntrypoint` | Fabric `ClientModInitializer` that kicks off the bootstrap thread | Today's `PhantomLoaderClient` |
| Pinned Ed25519 public key (constant) | Manifest signature verification | `LoaderConfig.kt` |
| `fabric.mod.json`, mixin config JSON(s), shaded `kotlinx-serialization-json` | Fabric registration + manifest parsing | Already JIJ'd |

**Removed from the loader (moves to `.enc`):**
- The entire `org.phantom:phantom-client-public` artifact — everything currently JIJ'd by `Loader/loader/build.gradle.kts:43-56`.
- All of `src/main/kotlin/org/phantom/api/**` except the small event/module bridge types.
- All of `src/main/kotlin/org/phantom/internal/**`.
- All Java renderers in `src/main/java/org/phantom/render/**`.
- `internal/loader/AddonLoader.kt` and `InMemoryAddonClassLoader.kt` — the in-memory classloader piece moves to the loader (renamed `EncryptedJarClassLoader`), the addon-discovery piece moves to `phantom-core.enc`.

**Size target:** loader jar ≤ 1.5 MB. Enforced by a Gradle `verifyJarSize` task.

---

## Section 2 — Mixin refactor pattern

Every mixin in the shipped loader follows one rule: **no feature logic, only event publish**.

```kotlin
// org.phantom.bridge.mixin.render.LevelRendererMixin
@Mixin(LevelRenderer::class)
abstract class LevelRendererMixin {
    @Inject(method = "renderLevel", at = @At("RETURN"))
    private fun phantom$onRenderLast(ctx: ..., ci: CallbackInfo) {
        EventBus.publish(WorldRenderEvent.Last(ctx))
    }
}
```

That is the entire body. No `if (module.enabled)`, no rendering calls, no math, no lookups.

The event side (also loader-resident):

```kotlin
// org.phantom.bridge.event.EventBus
object EventBus {
    private val subscribers = ConcurrentHashMap<Class<*>, CopyOnWriteArrayList<(Event) -> Unit>>()
    fun <T : Event> publish(event: T)
    fun <T : Event> on(type: Class<T>, handler: (T) -> Unit)
    fun unregister(subscriber: Any)
}
```

Event types are POJO records:

```kotlin
sealed class Event
data class TickStart(val mc: Minecraft) : Event()
data class WorldRenderLast(val tickDelta: Float, val poseStack: PoseStack, ...) : Event()
data class ChatReceived(val message: Component, val cancel: AtomicBoolean) : Event()
data class PacketReceived(val packet: Packet<*>, val cancel: AtomicBoolean) : Event()
// ~10-15 total event types covering everything the current mixins do
```

**Migration patterns for mixins that today contain logic:**

| Today's mixin shape | New shape |
|---|---|
| `@Inject` body reads state, transforms it, calls into `internal/**` | Publish event with all needed data; subscriber in core/macro module does the work |
| `@Redirect` that conditionally calls original vs. custom | Publish event with `cancel: AtomicBoolean` — original call proceeds unless subscriber sets it true |
| `@ModifyVariable` to tweak a value | Publish a `MutableXxxEvent` carrying the value in an `AtomicReference`; subscriber mutates it; mixin reads it back |

**Hard constraint:** a loader-side mixin must never `import org.phantom.internal.*`. Those classes are not on the loader classpath at compile time. Enforced by a build-time ASM scanner (see Section 7b). This matches the existing `loader_mixin_eventbus_constraint` memory.

---

## Section 3 — Server-side changes

### 3a. Manifest schema changes (additive)

```json
{
  "build_id": "...",
  "channel": "stable",
  "minimum_loader_version": "1.0.0",
  "module_key": "<AES-256 key, base64>",
  "modules": [
    {
      "name": "phantom-core",
      "url": ".../content/module/phantom-core",
      "sha256": "...",
      "required": true,
      "init_order": 0,
      "activation_policy": "auto"
    },
    {
      "name": "phantom-fishing",
      "url": ".../content/module/phantom-fishing",
      "sha256": "...",
      "required": false,
      "init_order": 100,
      "activation_policy": "verify_on_toggle"
    }
  ],
  "native_components": [...],
  "signature": "<ed25519 over canonical JSON>"
}
```

- `activation_policy: "auto"` → loader calls `onActivate()` right after `onLoad()` at boot. Used by `phantom-core` only.
- `activation_policy: "verify_on_toggle"` → loader calls `onLoad()` at boot but not `onActivate()`. Activation waits for a successful `/auth/verify-module` response per toggle.

This field is inside the signed payload, so a tampered manifest that downgrades a paid macro to `auto` will not verify.

### 3b. New endpoint: `POST /auth/verify-module`

```
POST /auth/verify-module
Authorization: Bearer <session_token>
Content-Type: application/json

{
  "module_name": "phantom-fishing",
  "minecraft_username": "Steve"
}
```

**Go handler logic** (sits next to `handleVerifySession` in `Go-Server/server/internal/auth/handler.go`):

1. Resolve session by token hash (same as `verify-session`).
2. Reject if `session.revoked` or `time.Now().After(session.expires_at)`.
3. Load device by `session.device_id`. Reject if `binding_status` is `suspended`/`banned`.
4. Compare `request.SourceIP` against the session's bound IP — reject on mismatch.
5. Compare `request.minecraft_username` to `device.MinecraftUsername` (case-insensitive) — reject on mismatch.
6. Resolve entitlement (`entSvc.Resolve(accountID)`). Check `module_name` is in `enabledModules` (with `"*"` and `phantom-` prefix handling, identical to the existing `ModuleBytes` check in `content/service.go:84-89`). `phantom-core` is always allowed.
7. Return `{authorized: true, reason: ""}` or `{authorized: false, reason: "..."}` with HTTP 200/403.

**Rate limit:** 60 requests/minute per IP — new middleware key `"verify-module"`.

**Audit log:** `auth.verify_module.success` / `auth.verify_module.fail` with `{account_id, module, reason}`.

### 3c. Unchanged endpoints

`/auth/verify-session`, `/auth/heartbeat`, `/content/manifest/:id`, `/content/module/:name`, `/content/native/:name` — semantically unchanged. Manifest entries gain `activation_policy` (additive).

---

## Section 4 — Lifecycle

### 4a. Boot phase (Phantom-Bootstrap thread)

1. `PhantomSession.read()` — fail fast if no session.
2. `BootstrapAuthClient.verifySession(token, mcUsername)` — fail fast if not authorized.
3. `BootstrapManifestVerifier.fetchAndVerify(...)` — Ed25519 check, fail fast on signature mismatch.
4. Download all manifest `.enc` modules + required natives (parallelized).
5. For each manifest module (sorted by `init_order`, respecting `depends_on`):
   - AES-GCM decrypt
   - SHA-256 against manifest entry
   - `EncryptedJarClassLoader.load(bytes)` → returns a `LoadedModule` handle
   - Call `loadedModule.onLoad(loaderApi)` — module registers HUD descriptors, commands, settings. **Does not subscribe to EventBus.**
6. Activation cascade — modules with `activation_policy == "auto"` proceed to `onActivate()`. Only `phantom-core`.
7. `BootstrapHeartbeatClient.start(token)`.
8. `Auth.state = READY`.

Per-module failures during steps 4-5 are tolerated except for `phantom-core` (best-effort loading, Section 6).

### 4b. Per-toggle activation

Invoked from `phantom-core`'s UI when user flips a toggle. Core calls `LoaderApi.requestActivate(name, callback)` (the interface defined in Section 5b — there is no separate `LoaderBridge` type).

```kotlin
sealed class Result {
    object Ok : Result()
    data class Denied(val reason: String) : Result()
    data class Error(val cause: Throwable) : Result()
}
```

Loader-side implementation:

1. Look up `LoadedModule` by name. If not present → `Result.Error("not loaded")`.
2. Run on the dedicated single-thread activation executor (serializes toggles).
3. POST `/auth/verify-module` with `{module_name, minecraft_username = Auth.minecraftUsername}`. Timeout 8s.
4. HTTP 200 + `authorized=true`: call `module.onActivate()` → subscribes to EventBus. Registry → ACTIVE. Return `Result.Ok`.
5. HTTP 200 or 403 + `authorized=false`: return `Result.Denied(reason)`. No state change. (Server returns 200 on positive auth, 403 on negative — same shape as the existing `handleVerifySession`. Loader reads the `authorized` field regardless of status code.)
6. HTTP 401 (session invalid): trigger heartbeat-failure cascade (4d). Return `Result.Denied("session_invalid")`.
7. Network/timeout/other error: `Result.Error(...)`. Strictest path — toggle does not turn on.

Callback runs on the executor thread; core dispatches back to render thread for UI updates.

### 4c. Per-toggle deactivation

Local-only, no server call:
1. `module.onDeactivate()` → `EventBus.unregister(this)`.
2. Registry → INACTIVE.
3. Bytes stay loaded. Next activation only needs a re-verify.

### 4d. Heartbeat-failure cascade

Triggered by: heartbeat non-200/network failure, or any endpoint returning 401 `session_invalid`.

1. Heartbeat thread sets `Auth.state = FAILED`, `Auth.failureReason`.
2. Iterates `ModuleRegistry.activeModules()`, calls `onDeactivate()` on each. They unregister.
3. **No retry.** User restarts Minecraft.
4. Posts a chat line via `Minecraft.getInstance().player?.sendSystemMessage(...)` — direct call, not EventBus (because subscribers may have unregistered).

### 4e. Threading model

| Thread | Responsibility |
|---|---|
| Phantom-Bootstrap (daemon, one-shot) | Auth + downloads + classloading + `onLoad` |
| Phantom-Activation (single-thread executor, daemon) | Per-toggle `/auth/verify-module` + `onActivate`/`onDeactivate`. Serialized. |
| Phantom-Heartbeat (daemon, ScheduledExecutor) | 60s heartbeat + failure cascade |
| MC client thread | Mixin `publish()`, EventBus dispatch (synchronous), module handlers |

EventBus dispatch is synchronous on the client thread. Module handlers must not block.

---

## Section 5 — Module package shape

### 5a. JAR layout

```
phantom-fishing.jar  (the decrypted bytes)
├── META-INF/
│   └── MANIFEST.MF
├── phantom-module.json
├── org/phantom/fishing/
│   ├── FishingMacroModule.class
│   ├── ...
└── assets/phantom/fishing/  (optional)
```

`phantom-module.json` schema v1:

```json
{
  "schema": 1,
  "name": "phantom-fishing",
  "version": "1.0.3",
  "entrypoint": "org.phantom.fishing.FishingMacroModule",
  "depends_on": ["phantom-core"]
}
```

Loader reads this first to find the entrypoint class. `depends_on` enforces `onLoad` ordering. Missing `depends_on` defaults to `["phantom-core"]`.

### 5b. The `LoadedModule` contract

```kotlin
// org.phantom.bridge.module (loader-resident)
interface LoadedModule {
    /**
     * Called once per MC session, on the bootstrap thread, in dependency order.
     * Allowed: read api.minecraftUsername, register HUD descriptors with
     * api.registry, register chat commands, allocate buffers.
     * Forbidden: subscribe to EventBus, touch the Minecraft world,
     *            spawn long-running threads.
     */
    fun onLoad(api: LoaderApi)

    /**
     * Called when activation is authorized (post verify-module=true).
     * Allowed: EventBus.register(this), start scheduled tasks.
     * Forbidden: blocking network I/O on this thread.
     */
    fun onActivate()

    /**
     * Called when user toggles off OR heartbeat cascade fires.
     * Must unregister from EventBus and cancel any scheduled work.
     * Must be idempotent.
     */
    fun onDeactivate()
}

interface LoaderApi {
    val eventBus: EventBus
    val registry: ModuleRegistry
    val auth: AuthSnapshot
    val nativeLoader: NativeLoader
    val log: LoaderLog
    fun requestActivate(name: String, cb: (Result) -> Unit)
    fun requestDeactivate(name: String)
}
```

`AuthSnapshot` is a frozen value object (mc username, account id, plan tier) — the loader does not leak mutable state to module code.

### 5c. Classloader topology

```
┌─────────────────────────────────────────────────────┐
│ Application classloader (Fabric)                    │
│ ├── Minecraft                                       │
│ ├── Fabric Loader                                   │
│ ├── Kotlin stdlib                                   │
│ └── phantom.jar  (loader + mixins + bridge)         │
└──────────────────────┬──────────────────────────────┘
                       │  parent
            ┌──────────┴──────────┐
            │                     │
   ┌────────▼────────┐   ┌────────▼────────┐
   │ phantom-core    │   │ phantom-fishing │   ... one per module
   │ classloader     │   │ classloader     │
   │ (in-memory JAR) │   │ (in-memory JAR) │
   └─────────────────┘   └─────────────────┘
```

- Each module gets its own `EncryptedJarClassLoader`, parent = loader's classloader.
- Modules see loader/bridge/Minecraft. They cannot see each other's internals directly.
- Cross-module communication: `EventBus` events or `LoaderApi.registry` lookups (which return interface types defined in the bridge).
- Per-module classloaders enable clean unload when an entitlement is removed mid-session (GC reclaims the classes).

### 5d. Build-side: producing `.enc` bundles

1. Gradle multi-project: one subproject per `.enc` bundle (`modules/phantom-core`, `modules/phantom-fishing`, …).
2. Each subproject produces a plain JAR with `phantom-module.json` at root.
3. Custom Gradle task `encryptModules` (in `buildSrc/`) AES-GCM encrypts each JAR with the module key, produces `phantom-<name>.enc`, computes SHA-256.
4. Go server's manifest builder (`content/manifest.go`) picks up every `.enc` from `contentDir`, reads the matching sidecar metadata, emits the signed manifest.
5. `Loader/loader/build.gradle.kts` removes the `clientPublicLayer` JIJ block and the `phantom-client-public` dependency.

### 5e. AddonLoader migration

- The in-memory classloader piece → loader as `EncryptedJarClassLoader`.
- The disk-based addon discovery (`config/cobalt/addons/`) → `phantom-core.enc`, dev-mode opt-in only. Customer builds drop it entirely.

---

## Section 6 — Error handling

### 6a. Failure matrix

| Phase | Failure | Loader action | User sees | Recoverable? |
|---|---|---|---|---|
| Boot | No `phantom_session.json` | Auth.state=FAILED | "No Phantom session. Run the launcher." | Restart launcher |
| Boot | `verify-session` HTTP non-200 | Auth.state=FAILED | "Phantom auth failed: HTTP N" | Restart MC |
| Boot | `verify-session` authorized=false | Auth.state=FAILED | "Phantom: {reason}" | Fix profile / support |
| Boot | Manifest fetch non-200 | Auth.state=FAILED | "Manifest unavailable: HTTP N" | Retry MC |
| Boot | Manifest Ed25519 verify fail | Hard abort — no `.enc` downloaded | "Phantom: signature invalid. Update the loader." | New install |
| Boot | Manifest schema unknown | Auth.state=FAILED | "Manifest schema unsupported. Update Phantom." | Update loader |
| Boot | `.enc` download non-200 | Skip module, mark NOT_AVAILABLE | UI greys it out | Restart MC |
| Boot | `.enc` SHA-256 mismatch | Skip, mark NOT_AVAILABLE | UI greys it out, "integrity check failed" | Restart MC / support |
| Boot | AES-GCM decrypt fail | Skip, mark NOT_AVAILABLE | UI greys it out, "decrypt failed" | Restart MC / support |
| Boot | JAR parse / no `phantom-module.json` | Skip, mark NOT_AVAILABLE | "{name}: invalid bundle" | Server build issue |
| Boot | Entrypoint class not found / wrong type | Skip, mark NOT_AVAILABLE | "{name}: bad entrypoint" | Server build issue |
| Boot | `onLoad()` throws | Skip, mark NOT_AVAILABLE, log stack | "{name}: load error" | Module bug |
| Boot | `phantom-core` fails any of the above | Hard abort — no UI possible | Chat line "Phantom core failed: {reason}" | Restart MC |
| Activate | verify-module HTTP non-200 | `Result.Error`, INACTIVE | "Could not verify ({name}): HTTP N" | Retry |
| Activate | verify-module 401 session_invalid | Heartbeat-failure cascade | "Phantom session expired — all macros disabled" | Restart MC |
| Activate | verify-module authorized=false | `Result.Denied`, INACTIVE | "{name}: {reason}" | Depends on reason |
| Activate | verify-module timeout | `Result.Error` | "Verify timed out — try again" | Retry |
| Activate | `onActivate()` throws | Try `onDeactivate()`, INACTIVE, log stack | "{name}: activation error" | Module bug |
| Runtime | EventBus subscriber throws | Catch, log stack, continue dispatching | Rate-limited "{name} error in {event}" | Module bug |
| Heartbeat | non-200 / network failure | Cascade: deactivate all, FAILED, no retry | "Phantom: connection lost — macros disabled" | Restart MC |
| Heartbeat | 401 session_invalid | Same cascade | "Phantom session revoked" | Restart MC |
| Deactivate | `onDeactivate()` throws | Log stack, still mark INACTIVE | Nothing user-visible | Module bug |

### 6b. Logging policy

- Loader-side: `LoaderLog`, prefix `[Phantom-Loader]`, into MC's log file.
- Module-side: `LoaderApi.log` (same `LoaderLog`) for attribution.
- Server-side: existing audit table + new `auth.verify_module.*` events.
- No PII beyond MC username + account ID. Session tokens use `shortToken` helper.

### 6c. Best-effort module loading

Today's bootstrap is all-or-nothing. New design: per-module degradation. One bad bundle does not lock the user out of unrelated macros. `phantom-core` is the lone hard-abort case (no UI = no recovery path).

Registry per-module state: `NOT_LOADED | LOADED | INACTIVE | ACTIVE | LOAD_FAILED | ACTIVATION_FAILED`. Core's UI shows state + failure reason.

### 6d. Heartbeat cascade is one-way

No automatic retry, no grace window. First failure → terminal session. Intentional aggression — any failure could be a revocation, and we do not want to risk macros running for a banned/refunded account.

### 6e. Deliberately not handled

- No on-disk error log persistence beyond MC's own log file.
- No automatic loader updates. Manifest's `minimum_loader_version` is the only compatibility lever — exceeding it fails with "Update Phantom".
- No partial-state recovery when a dependency's `onLoad` succeeds and a dependent's fails. Dependency stays LOADED-but-inactive, dependent is LOAD_FAILED.

---

## Section 7 — Testing strategy

### 7a. Layered pyramid

```
                       ┌─────────────────────────┐
                       │ E2E (manual)            │  ← real MC + real server
                       │ Single smoke playbook   │
                       └─────────────────────────┘
                  ┌────────────────────────────────────┐
                  │ Loader integration (JVM, no MC)    │  ← MockWebServer
                  │ Full bootstrap against fake server │
                  └────────────────────────────────────┘
            ┌──────────────────────────────────────────────┐
            │ Unit tests (JVM)                             │
            │ Crypto, EventBus, classloader, lifecycle FSM │
            └──────────────────────────────────────────────┘
      ┌──────────────────────────────────────────────────────────┐
      │ Static / build-time invariants                            │
      │ Loader source can't import internal/**, mixin shape lint  │
      └──────────────────────────────────────────────────────────┘
```

### 7b. Build-time invariants (Gradle tasks, fail the build)

| Check | Implementation | Failure mode |
|---|---|---|
| Loader bytecode contains no `org.phantom.internal.*` references | ASM scanner | Build fails with offending class + reference |
| Loader bytecode contains no `org.phantom.api.*` references except the bridge subset | ASM scanner | Build fails |
| Every mixin's `@Inject`/`@Redirect` method body matches dispatch-only pattern | ASM walker: method must contain ≤1 `EventBus.publish` call + return; no branches except null-guards | Build fails with offending mixin |
| `phantom.jar` total size ≤ 1.5 MB | `verifyJarSize` task | Build fails if loader bloats |
| Pinned Ed25519 pubkey is not the placeholder | String check in `LoaderConfig.kt` against `REPLACE_WITH_` prefix | Build fails (already exists) |

### 7c. Unit tests (JUnit 5, `Loader/loader/src/test`)

| Component | Cases |
|---|---|
| `BootstrapManifestVerifier` | Known-payload positive; tampered negative; missing pubkey; both `moduleKey=null` and present (dual-attempt) |
| `ModuleCrypto.decryptAesGcm` | Roundtrip; wrong key fails; truncated ciphertext fails; tampered tag fails |
| `EventBus` | Subscribe/unsubscribe; unsubscribe during dispatch; multiple subscribers; throwing subscriber doesn't stop others; concurrent publish |
| `EncryptedJarClassLoader` | Loads from bytes; class isolation between modules; parent visible; resource lookup |
| `ModuleRegistry` lifecycle FSM | Valid transitions; invalid noop/log; LOAD_FAILED terminal |
| Activation executor | Concurrent activates → one server hit, others wait |
| Heartbeat cascade | 3 active modules → all see `onDeactivate` exactly once |
| `phantom-module.json` parsing | v1 happy path; missing field error; unknown schema error |

### 7d. Integration tests (OkHttp MockWebServer)

| Scenario | Assertion |
|---|---|
| Happy path | 3 modules LOADED, core ACTIVE, others INACTIVE |
| Manifest tampering | Hard-abort, no `.enc` downloaded |
| One module SHA-256 fail | That module NOT_AVAILABLE; siblings load |
| Core decrypt fail | Hard-abort with "core failed" |
| verify-module denied | Module stays INACTIVE; no `onActivate` |
| Heartbeat 401 | All active see `onDeactivate` |
| Mid-bootstrap network drop | Per-module NOT_AVAILABLE; not hard-abort (unless core) |

### 7e. Go server tests

(Authored, not runnable in this shell per `phantom_loader_integration` memory — `go` not on PATH.)

| Component | Cases |
|---|---|
| `handleVerifyModule` | Valid → 200 authorized=true; expired → 401; not entitled → 200 false; IP mismatch → false; MC mismatch → false; banned device → false |
| Manifest builder | `activation_policy="auto"` for core; `"verify_on_toggle"` for others |
| Rate limit on verify-module | 61st in 60s → 429 |

### 7f. E2E smoke (manual playbook in `docs/superpowers/runbooks/`)

1. Build loader with `./gradlew :Loader:loader:build -x buildNative`.
2. Drop `phantom.jar` into Prism. Build `.enc`s. Deploy server.
3. Launch MC → loader auths → core auto-activates → UI panel opens.
4. Toggle Fishing on → verify-module roundtrip → macro engages.
5. Toggle Fishing off → it stops.
6. Toggle Fishing on again → second verify-module → engages (classes reused).
7. Cut server's network → heartbeat fails → macros auto-disable, chat line appears.
8. Restart MC → bootstrap succeeds again.

### 7g. Out of scope for testing

- Native pathfinder DLL changes (none in this design).
- TDD for the migration itself (mechanical moves; tests live with the moved code).
- Cross-version compatibility. Loader and server ship together. `minimum_loader_version` is the only lever.

---

## Section 8 — Scope and migration

### 8a. Streams of work

| Stream | Effort | Blocks |
|---|---|---|
| Bridge layer + loader (the new "empty" `phantom.jar`) | M | Everything |
| Mixin refactor to pure-dispatch | M-L | Module activation; build-time invariants |
| Build split into per-module subprojects + `encryptModules` | M | Server-side manifest builder |
| Server: `/auth/verify-module` + manifest `activation_policy` | S | E2E |

### 8b. Target repo layout

```
Cobalt/                                   (this repo)
├── bridge/                               NEW subproject
│   └── src/main/{java,kotlin}/org/phantom/bridge/
│       ├── mixin/                        every @Mixin (dispatch-only)
│       ├── event/                        EventBus, Event, ~10-15 event records
│       └── module/                       LoadedModule, LoaderApi, ModuleRegistry, AuthSnapshot
├── Loader/loader/                        depends on bridge/; produces phantom.jar
├── modules/
│   ├── phantom-core/                     UI + command + module + HUD + theme + notification + util + rotation engine
│   ├── phantom-fishing/
│   ├── phantom-farming/
│   ├── phantom-mining/
│   ├── phantom-dungeons/
│   ├── phantom-grotto/
│   ├── phantom-pig/
│   ├── phantom-combat/
│   ├── phantom-slayer/
│   ├── phantom-diana/
│   ├── phantom-etherwarp/
│   ├── phantom-pathfinding/              user-facing UI; engine lives in core
│   ├── phantom-qol/
│   ├── phantom-rotation/                 user-toggleable rotation module
│   ├── phantom-spotify/
│   └── phantom-visual/                   FullBright, DarkMode, etc.
├── buildSrc/                             NEW — encryptModules Gradle plugin
├── natives/                              UNCHANGED
└── src/main/                             DELETED — content moves into modules/ + bridge/

Go-Server/                                (go-server branch, unchanged layout)
├── server/internal/auth/                 + handleVerifyModule, + service.VerifyModule
├── server/internal/content/manifest.go   + activation_policy field
├── server/internal/db/manifests.go       (no schema change — JSON blob)
└── bootstrapper/                         UNCHANGED
```

### 8c. File-by-file migration

**Bridge (loader-resident):**

| From | To |
|---|---|
| `src/main/java/org/phantom/mixin/**/*.java` | `bridge/src/main/java/org/phantom/bridge/mixin/**` — rewritten dispatch-only |
| `src/main/kotlin/org/phantom/api/event/EventBus.kt` | `bridge/src/main/kotlin/org/phantom/bridge/event/EventBus.kt` |
| `src/main/kotlin/org/phantom/api/event/Event.kt` | `bridge/src/main/kotlin/org/phantom/bridge/event/Event.kt` |
| Selected event records from `api/event/impl/**` | `bridge/.../bridge/event/` — only ones mixins publish |
| `src/main/kotlin/org/phantom/api/pathfinder/jni/NativeLoader.kt` | `bridge/.../bridge/native/NativeLoader.kt` |

**phantom-core (server-loaded):**

| From | To |
|---|---|
| `src/main/kotlin/org/phantom/internal/ui/**` | `modules/phantom-core/.../core/ui/**` |
| `src/main/kotlin/org/phantom/internal/loader/AddonLoader.kt` | `modules/phantom-core/.../core/loader/AddonLoader.kt` (in-memory only) |
| `src/main/kotlin/org/phantom/api/module/**` | `modules/phantom-core/.../core/module/**` |
| `src/main/kotlin/org/phantom/api/command/**` | `modules/phantom-core/.../core/command/**` |
| `src/main/kotlin/org/phantom/api/hud/**` | `modules/phantom-core/.../core/hud/**` |
| `src/main/kotlin/org/phantom/api/notification/**` | `modules/phantom-core/.../core/notification/**` |
| `src/main/kotlin/org/phantom/api/rotation/**` | `modules/phantom-core/.../core/rotation/**` |
| `src/main/kotlin/org/phantom/api/util/**` | `modules/phantom-core/.../core/util/**` |
| `src/main/kotlin/org/phantom/api/ui/theme/**` | `modules/phantom-core/.../core/theme/**` |
| `src/main/kotlin/org/phantom/internal/chat/**` | `modules/phantom-core/.../core/chat/**` (chat events are foundational) |
| `src/main/kotlin/org/phantom/internal/helper/**` | `modules/phantom-core/.../core/helper/**` |

**Per-feature `.enc` modules:**

Each `src/main/kotlin/org/phantom/internal/<area>/` directory moves to `modules/phantom-<area>/src/main/kotlin/org/phantom/<area>/`. Mapping:

- `internal/combat/` → `modules/phantom-combat/`
- `internal/dungeons/` → `modules/phantom-dungeons/`
- `internal/etherwarp/` → `modules/phantom-etherwarp/`
- `internal/farming/` → `modules/phantom-farming/`
- `internal/fishing/` → `modules/phantom-fishing/`
- `internal/grotto/` → `modules/phantom-grotto/`
- `internal/mining/` → `modules/phantom-mining/`
- `internal/pathfinding/` → split: engine pieces to core, user-facing module to `modules/phantom-pathfinding/`
- `internal/pig/` → `modules/phantom-pig/`
- `internal/qol/` → `modules/phantom-qol/`
- `internal/rotation/` → `modules/phantom-rotation/`
- `internal/spotify/` → `modules/phantom-spotify/`
- `internal/visual/` → `modules/phantom-visual/` (FullBright, DarkMode, BlockOverlay, Freecam, etc.)
- `internal/garden/` → already dropped per commit `2a2bb98b`

Each move also rewrites imports: `org.phantom.api.event.*` → `org.phantom.bridge.event.*`; direct sibling-module calls become EventBus events or `LoaderApi.registry` lookups.

**Deleted:**

- All files in current git status as `D` (already removed by the user).
- `src/main/kotlin/org/phantom/PhantomPublicInit.kt` — replaced by per-module `onLoad()` from the loader.
- `Loader/loader/build.gradle.kts:43-56` — `clientPublicLayer` JIJ block.
- `Loader/loader/build.gradle.kts:36-37` — `phantom-client-public` dependency.

**Unchanged:**

- Rust bootstrapper (`Go-Server/bootstrapper/**`).
- Existing Go endpoints `/auth/verify-session`, `/auth/heartbeat`, `/content/*`.
- Native pathfinder C++ (`natives/**`).
- Database schema.

### 8d. Implementation ordering

1. **Stand up `bridge/` subproject** with EventBus, LoadedModule, LoaderApi interfaces — empty mixins still in the old place. Tests pass for EventBus + EncryptedJarClassLoader. No behavior change.
2. **Stand up `modules/phantom-core/`** as a Gradle subproject — empty stub that compiles against bridge/.
3. **Move one mixin** into `bridge/` and rewrite as dispatch-only. Move its consumers into `phantom-core`. E2E smoke one slice in MC.
4. **Add `/auth/verify-module`** to Go server + `activation_policy` field. Loader does not call it yet — endpoint dormant.
5. **Wire activation lifecycle** in loader: onLoad / onActivate / onDeactivate per Section 4 + 5. Heartbeat cascade. Activation executor.
6. **Bulk migrate** remaining mixins (dispatch-only) and `internal/**` directories into `modules/phantom-<area>/`. Per-area smoke.
7. **Build-time invariant checks** (Section 7b) wired into CI.
8. **Strip `clientPublicLayer` JIJ** from `Loader/loader/build.gradle.kts`.
9. **`encryptModules` task + manifest-generator** updates.
10. **E2E smoke playbook** authored + run.

Each numbered step is one PR-worth of work, ends in a working build. Step 6 fans out into ~10 sub-steps (one per module area).

### 8e. Out of scope (parking lot)

- Loader self-update.
- Module hot-reload during a live session.
- Offline mode / cached auth / grace window.
- Multi-account on one machine.
- Public addon SDK for third parties.
- Telemetry / crash reporting.

---

## Related memories

- `phantom-loader-integration` — build-env quirks (no `go`/`cargo` on PATH, `-x buildNative`), force-add for Go backend files.
- `loader_mixin_eventbus_constraint` — pre-existing rule that mixins must not reference protected `internal/**` modules. This design enforces it via build-time ASM scanning.
- `feedback_no_git_push` — local commits only.

## Open questions resolved during brainstorming

- "Auth on every macro startup" = per-toggle re-verify against `/auth/verify-module`; first toggle fetches+decrypts+classloads, subsequent toggles re-verify but reuse loaded classes (resolved: combined Section 4b lazy + Section 4c reuse).
- Boot fetches = core bundle + all entitled macros (not lazy-only).
- Auth-failure behavior = strictest (refuse to enable + kill running).
- Bridging mechanism = ~10-15 small event record types via EventBus (not a forwarding interface).
- Per-module classloaders (not one shared classloader).
- User-addon disk discovery = removed from customer builds, dev-mode opt-in preserved.
