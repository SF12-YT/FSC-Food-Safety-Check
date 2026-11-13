# FOSCH Security Lab

A standalone Android companion app for authorised inspection and security testing of FOSCH and a separate Supabase staging project.

- Android package: `com.fosch.securitylab`
- Target app: `com.fosch.org`
- Version: `0.5.0`
- Install FOSCH separately; Security Lab does not replace it.
- The local audit inspects public package metadata, signing certificate, APK hash, exported components and basic runtime indicators.
- A known-good APK can be imported through Android's document picker as a modding/tamper comparison baseline.
- Reports are stored locally and can be exported as JSON. Staging API keys and raw response bodies are not saved or exported.

## Staging target

Use a separate Supabase project, not the live FOSCH project. Enter only its HTTPS project URL and public publishable key. Never enter a `service_role` or secret key. The key is used in memory and is not saved or included in reports.

The advanced staging suite validates the exact host, blocks the known FOSCH production host, and uses only the explicitly entered staging URL for its requests.

## Included checks

- Anonymous reads against `product_submissions` and `admin_audit_log`.
- A random SQL-looking PostgREST filter canary. This is a narrow parameter-handling check, not proof of complete SQL-injection resistance.
- Malformed submission RPC input (invalid UUID) to check validation before insert.
- Anonymous invocation checks for admin RPCs, using invalid arguments and a random absent UUID.
- Anonymous listing attempt against the private `fosch-submission-photos` bucket using a manually uploaded canary.
- A private-object download probe. A non-success response is classified only when the server's error explicitly identifies an access denial; otherwise it remains inconclusive.
- A bounded API burst of at most 24 read-only GET requests, with no more than four concurrent workers. It now stops early on authentication rejection as well as rate limiting, server errors or transport failures. This is not a DDoS simulator or capacity certification.

## Diagnostics and privacy

For HTTP errors, results include the HTTP status, content type, response byte count and a short sanitised summary made only from structured error code/message fields. API keys, bearer tokens and JWTs are redacted. Successful response bodies and returned object contents are never written to the report.

Each history entry is tagged with its scope:
- `EXPLICIT_STAGING:<host>`: requests sent to the URL entered in the staging suite.
- `EMBEDDED_BACKEND_READ_ONLY`: separate, confirmation-gated checks against the Supabase host embedded in the installed FOSCH APK.
- `LOCAL_APP` / `LOCAL_BASELINE`: on-device checks.
- `LEGACY_UNCLASSIFIED`: old v0.4.0-or-earlier history that did not record its scope.

The staging suite does not call the separate embedded-backend audit. The latter has a confirmation dialog and clearly identifies the host before sending any requests.

## Boundaries

- Never accesses FOSCH private app storage, WebView local storage, login sessions or private submission-photo contents.
- No writes, file uploads/deletions, account lockouts, credentials harvesting, destructive database calls or unbounded traffic.
- Only run against a staging project populated with fake test data and the same migrations/policies as production.
- A PASS is limited to the described test and is not proof of complete security.

## Build

From this directory:

```sh
gradle assembleDebug
```

The APK is produced at `app/build/outputs/apk/debug/app-debug.apk`.
