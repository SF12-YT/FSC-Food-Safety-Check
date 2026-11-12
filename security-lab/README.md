# FOSCH Security Lab

A standalone Android companion app for authorised testing of the FOSCH Android app and its own Supabase backend.

- Android package: `com.fosch.securitylab`
- Target app: `com.fosch.org`
- Install FOSCH separately; Security Lab does not replace it.
- No FOSCH private app data, WebView storage, login session, or private submission photos are accessed.
- The app uses Android's package manager to inspect public package metadata and the installed APK.
- A known-good APK can be imported through Android's document picker as an integrity baseline.
- API checks use the public publishable key extracted from the installed FOSCH APK and perform read-only GET requests only.
- Reports are stored locally and can be exported as JSON. Credentials and response bodies are not written to reports.

## Safety limits

- The default audit is local and passive.
- Backend probes are deliberately read-only and capped.
- The optional rate-limit probe is a five-request, sequential, low-impact check; it is not a DDoS simulator.
- The rate-limit probe rejects the known FOSCH production hostname and requires an explicit staging authorisation phrase.
- No automatic writes, deletions, account lockouts, privilege changes, data extraction, or high-volume load tests.
- Admin RPC and upload mutation tests are not included until they can be exercised with isolated disposable test fixtures.

## Build

From this directory:

```sh
gradle assembleDebug
```

The APK is produced at `app/build/outputs/apk/debug/app-debug.apk`.
