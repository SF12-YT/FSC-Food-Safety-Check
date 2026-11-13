# FOSCH staging security setup

This setup is for the separate Supabase staging project:

- **Staging:** `qeqdvssrhzllatdojjte.supabase.co`
- **Production (never use for this setup):** `mqgvagftvuybietazbdc.supabase.co`

## 1. Confirm the target before running SQL

Open Supabase Dashboard and select the project whose Reference ID is exactly:

`qeqdvssrhzllatdojjte`

Do not proceed if the project reference differs. The root `supabase/config.toml` is still configured with the production project ID; do not use an already-linked CLI session or run `supabase db push` without first explicitly verifying/re-linking the target.

The bootstrap is intended for a fresh, isolated staging project. It creates FOSCH tables, functions, grants, RLS policies, and the two FOSCH storage buckets. It does **not** copy production rows, users, authentication data, storage files, service keys, or R2 secrets. It also does not configure an R2 public origin or deploy the `fosch-media` Edge Function.

## 2. Apply the schema

Open **SQL Editor** in the staging project and run:

`fosch-staging-bootstrap.sql`

The bundle is an ordered copy of all 14 timestamped FOSCH migrations in `supabase/migrations/` at the time it was generated. It includes the submission/status RPCs, admin-only RPCs, public-media schema, private evidence bucket, R2 metadata/functions, and the existing storage policies.

If this staging project already contains partial FOSCH migrations or unrelated application tables, stop and inspect its current schema before running the bundle. Do not reset or drop the project just to make the script run.

After execution, confirm there were no SQL errors. The expected private bucket is `fosch-submission-photos` and it must be marked **Private**. The bootstrap also sends a PostgREST schema-cache reload notification so newly created tables and RPCs should become visible to the API without a manual refresh.

## 3. Create a harmless storage canary

The existing private-bucket policies intentionally do not permit anonymous users to upload, list, download, replace, or delete evidence. Create one test object through the **Supabase Dashboard** as the project owner (dashboard upload uses privileged project access):

1. Open **Storage → fosch-submission-photos** in the staging project.
2. Create a folder named `security-lab-canary`.
3. Inside it create a folder containing a fresh UUID, for example `7c59b3e1-a8dd-42e4-bc2e-0c4e9e1d4a61`.
4. Upload a plain-text file named `canary.txt` at that location.
5. Its exact content should be:

   `FOSCH-SECURITY-LAB-CANARY-v1`

The complete object path will look like:

`security-lab-canary/7c59b3e1-a8dd-42e4-bc2e-0c4e9e1d4a61/canary.txt`

Use your own newly generated UUID, not the example. Confirm the object is visible in the Dashboard before running the test. Do not put real submission photos or personal data in the canary.

## 4. Run Security Lab

In FOSCH Security Lab, select **Advanced staging security suite** and enter:

- Staging URL: `https://qeqdvssrhzllatdojjte.supabase.co`
- The staging project's **publishable key** (or legacy anon key), never a secret/service-role key.
- The exact canary object path you created.
- The existing authorisation phrase requested by the app.

The app uses the canary path only for two read-only anonymous probes:

- List the containing canary folder. A returned entry demonstrates metadata listing access. An empty list is only a passing result because you manually confirmed that exact canary exists in the Dashboard.
- Retrieve the exact canary object through the private authenticated-object endpoint. HTTP 200 means the private object is anonymously readable and is a critical finding. An explicit authorization denial is a pass. Missing-object/unknown-path responses remain inconclusive.

The app does not upload, delete, modify or export the object contents. It only records HTTP status, content type and response byte count; it never saves object names or file bytes in the report. The canary folder/path is user-supplied and restricted to the `security-lab-canary/<UUID>/canary.txt` format.

The database/RPC probes will become meaningful after the schema is installed. HTTP 404s for tables or RPCs usually mean the endpoint/schema is not present or exposed; they are not passes. HTTP 401/403 responses must still be examined to distinguish an authorization denial from an invalid API key.

## 5. Remove the canary

After testing, delete the canary object and its UUID folder from **Storage → fosch-submission-photos** in the staging Dashboard. Do not run cleanup against production.

## 6. Production separation

This bundle is not a deployment to production. Security Lab blocks the known production host in its advanced suite. Continue to use only fake fixtures in staging. Do not paste a service-role key into Security Lab, and do not upload live user evidence to staging.
