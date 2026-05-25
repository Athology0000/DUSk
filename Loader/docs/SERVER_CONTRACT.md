# Server contract

## POST `/api/auth/verify`

Request:

```json
{
  "username": "site-login-name",
  "minecraftUsername": "MinecraftName",
  "hwid": "client-hwid-hash",
  "clientVersion": "0.1.0"
}
```

Success response:

```json
{
  "ok": true,
  "sessionToken": "short-lived-bearer-token",
  "manifest": {
    "payloadBase64": "base64-json-payload",
    "signatureBase64": "ed25519-signature-over-raw-payload-bytes"
  }
}
```

Failure response:

```json
{
  "ok": false,
  "reason": "license expired"
}
```

## Manifest payload

The manifest is JSON encoded, then signed using Ed25519. The loader verifies the signature before reading module download URLs.

```json
{
  "issuedAt": 1777920000,
  "expiresAt": 1777920900,
  "userId": "user_123",
  "minecraftUsername": "PlayerName",
  "modules": [
    {
      "id": "mining",
      "version": "0.1.0",
      "delivery": "jar",
      "url": "mining-0.1.0.jar",
      "sha256": "lowercase_sha256_hex",
      "entrypoint": "org.cobalt.modules.mining.MiningRuntimeModule",
      "requiredRole": "pro"
    }
  ]
}
```

## Module download endpoint

The loader downloads module jars with:

```text
Authorization: Bearer <sessionToken>
Accept: application/java-archive, application/octet-stream
```

By default, relative module URLs are resolved from:

```text
https://valiant-cooperation-production.up.railway.app/modules/
```

So a manifest module can use `"url": "mining-0.1.0.jar"` or omit `url` entirely, in which case the loader requests `{id}-{version}.jar`.

The loader rejects module URLs with query strings/fragments and rejects URLs outside the configured module base URL unless explicitly configured otherwise. Keep module files under `/modules/`.

For a single-class raw bytecode module, set `"delivery": "class"` or `"delivery": "bytecode"`. If `url` is omitted, the loader requests `{id}-{version}.class`, verifies SHA-256 against the raw class bytes, and defines the entrypoint class in memory. Use jar delivery for multi-class modules or modules that need resources/dependencies.

Server rules:

- Token must be short lived.
- Token must be tied to the user/session/manifest.
- Reject expired or replayed tokens.
- Only serve modules included in the signed manifest.
- Return exact bytes that match the signed SHA-256.

## POST `/api/auth/heartbeat`

The loader sends this after successful auth and repeats it while the client is running.

Headers:

```text
Authorization: Bearer <sessionToken>
Content-Type: application/json
Accept: application/json
```

Request:

```json
{
  "username": "site-login-name",
  "minecraftUsername": "MinecraftName",
  "hwid": "client-hwid-hash",
  "clientVersion": "0.1.0"
}
```

Response:

```json
{
  "ok": true
}
```

If the Minecraft account is not the one bound to the Cobalt account, return `ok: false` from auth with a reason that mentions the bound Minecraft account. The client will show a bind-account prompt instead of loading protected modules.

## /auth/verify-session — hwid field (loader v0.2+)

Request body adds `hwid` (lowercase hex SHA-256, 64 chars).
Server contract:
- No pinned HWID on the account → store this one (TOFU), respond `authorized: true`.
- Pinned HWID matches → respond `authorized: true`.
- Pinned HWID differs → respond `{ authorized: false, reason: "hwid_mismatch" }`.

The loader surfaces `hwid_mismatch` as "Hardware mismatch — visit dashboard to reset binding". Unbinding is a server-side dashboard action; the loader has no rebind path.
