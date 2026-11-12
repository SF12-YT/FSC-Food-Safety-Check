# FOSCH Security Lab

A standalone Android companion app for authorised inspection and security testing of FOSCH and a separate Supabase staging project.

- Android package: `com.fosch.securitylab`
- Target app: `com.fosch.org`
- Version: `0.2.0`
- Install FOSCH separately; Security Lab does not replace it.
- The local audit inspects public package metadata, signing certificate, APK hash, exported components and basic runtime indicators.
- A known-good APK can be imported through Android's document picker as a modding/tamper comparison baseline.
- Reports are stored locally and can be exported as JSON. Staging API keys and response bodies are not saved or exported.

## Staging target

Use a separate Supabase project, not the live FOSCH project. The generated Supabase project URL normally has the form:

```
https://<20-character-project-ref>.supabase.co
```

The previous build incorrectly required the words `staging`, `stage`, `dev`, `test` or `preview` in the hostname. Supabase-generated hostnames do not include the project name. Version 0.2.0 accepts a standard Supabase project-ref hostname, while hard-blocking the known FOSCH production hostname.

The suite requires the project's public `anon` / publishable key. Never enter a `service_role` or secret key. The key is used in memory for requests and is not stored or included in reports.

## Included tests

- Anonymous read checks against `product_submissions` and `admin_audit_log`. A 401/403 is marked inconclusive, not automatically passed; a 200 with actual rows is a high-severity finding.
- A random SQL-looking PostgREST filter canary. This is deliberately described as a narrow parameter-handling check, not a complete SQL-injection proof.
- Malformed submission RPC input (invalid UUID) to check validation before insert.
- Anonymous invocation checks for admin RPCs, using invalid arguments and a random absent UUID so the probe should not change a real row.
- Anonymous listing attempt against the private `fosch-submission-photos` bucket, using a random non-existent prefix. No object is uploaded, fetched or deleted.
- A bounded API burst of at most 24 read-only GET requests, with no more than four concurrent workers. It stops on the first 429, 5xx or transport failure. This is not a DDoS simulator or a capacity certification.

## Boundaries

- Never accesses FOSCH private app storage, WebView local storage, login sessions or private submission-photo contents.
- No production stress tests; the known live FOSCH Supabase hostname is rejected.
- No file uploads/deletions, account lockouts, credentials harvesting, destructive database calls, or unbounded traffic.
- Missing tables/functions and invalid API keys produce inconclusive results rather than false passes.
- Run against a staging project populated only with fake test data and the same migrations/policies as production.

## Build

From this directory:

```sh
gradle assembleDebug
```

The APK is produced at `app/build/outputs/apk/debug/app-debug.apk`.
