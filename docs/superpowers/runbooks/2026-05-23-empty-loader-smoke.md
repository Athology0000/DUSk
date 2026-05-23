# Empty-Loader + Per-Macro Re-Auth — E2E Smoke (Phase 1)

**When to run:** after merging Phase 1, before merging Phase 2.

**Prerequisites:**
- Local Go server running with the new `/auth/verify-module` endpoint (commit `9ecb3573` or later).
- A test account that owns at least one entitled macro (e.g. `phantom-fishing`) or has `"*"` in `enabled_modules`.
- Prism Launcher with a Fabric 1.21.11 instance, the new `phantom.jar` deployed.
- For step 8, set `PHANTOM_HEARTBEAT_INTERVAL_SECONDS=15` (env var) before launching MC so the cascade reproduces in under a minute. Default is 30s.

**Note on Phase 1 reach:** No bundled module currently implements `LoadedModule`. Until a module's `Addon` entrypoint implements `LoadedModule` and the manifest entry sets `activation_policy=verify_on_toggle`, the per-toggle verify-module call will not fire from the UI — that wiring lands when Phase 2 starts migrating modules. What Phase 1 *can* verify in MC:
- `verify-session` + manifest load + heartbeat cascade still work.
- The `/auth/verify-module` endpoint responds correctly when hit by `curl`.
- The legacy addon flow continues to function unchanged.

The "toggle Fishing" steps (4-7 below) are written for the Phase 2 acceptance criteria — they should be retained but skipped until at least one module implements `LoadedModule`.

## Steps

### 1. Build the loader.

From the repo root:

```bash
./gradlew publishToMavenLocal -x buildNative -x copyNativeDll
# Bump Loader/gradle.properties:phantom_client_version to the new mavenLocal version
Loader/gradlew --project-dir Loader :loader:build
```

Expected: a `Loader/loader/build/libs/phantom-*.jar` is produced and is under ~5 MB.

### 2. Deploy + launch.

Copy `phantom-*.jar` into the Prism instance's `mods/` folder. Launch MC.

Expected log lines (in `logs/latest.log`):
```
[Phantom-Loader] INFO Phantom loader initialized.
[Phantom-Loader] INFO Bootstrap complete. Loaded N protected module bundle(s).
```

`Auth.state` should land at `READY`. No exceptions.

### 3. Confirm `phantom-core` auto-activated.

Open the Phantom UI hotkey (default `Right-Shift`). If the panel opens, core is up. If not, capture `logs/latest.log` and stop — core failed to load.

### 4. Hit `/auth/verify-module` directly with curl.

Replace `$TOKEN` with the session token from `phantom_session.json` and `$MC` with your MC username:

```bash
curl -X POST https://<server>/auth/verify-module \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"module_name":"phantom-fishing","minecraft_username":"'$MC'"}'
```

Expected (entitled): `200 OK`, body `{"authorized":true,"reason":""}`.
Expected (not entitled): `403`, body `{"authorized":false,"reason":"not_entitled"}`.
Expected (wrong MC name): `403`, body `{"authorized":false,"reason":"minecraft_username_mismatch"}`.

Server log should show `[auth.verify_module.route] ip=... module=phantom-fishing authorized=true reason=`.

### 5. Toggle a macro ON via the UI (Phase 2 only).

Skip until a bundled module implements `LoadedModule`. Once one does:
- Click Enable on the module in the Phantom UI.
- Server log: `[auth.verify_module.route] ... module=<name> authorized=true`.
- Loader log: module-side `onActivate` should fire.

### 6. Toggle the same macro OFF (Phase 2 only).

- No new server hit.
- Module remains LOADED (registry) but `onDeactivate` was called.

### 7. Toggle the same macro ON again (Phase 2 only).

- Server log shows a second `verify-module` line.
- Module re-engages immediately — no re-download, no classloader work.

### 8. Test the heartbeat cascade.

Block the loader's outbound HTTP to the server (firewall rule, or stop the server). Within `heartbeatIntervalSeconds × 2`:

Expected:
- Loader log: `[Phantom-Loader] ERROR Heartbeat trust lost. Deactivating modules.`
- Any active LoadedModule's `onDeactivate` is called.
- `AddonLoader.unloadLoadedAddons` runs for legacy addons.
- `Auth.state = FAILED`.
- `Auth.failureReason` is `session_invalid` if heartbeat got 401, else `Heartbeat trust lost`.

Restoring network does NOT auto-recover — the user must restart MC. Verify by un-blocking and confirming no new heartbeats fire (the worker has interrupted itself).

### 9. Restart MC after the cascade.

Bootstrap succeeds again; core re-activates; UI opens.

## Pass/Fail

For Phase 1, steps 1-4, 8, 9 must reproduce exactly. Steps 5-7 are deferred to Phase 2 acceptance (when at least one module implements `LoadedModule`). Any deviation in the required steps is a Phase 1 regression — file an issue with the failing step number and observed vs. expected output.
