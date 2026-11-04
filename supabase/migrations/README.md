# FOSCH Supabase migrations

Apply the SQL migration files in timestamp order in Supabase Dashboard → SQL Editor.

For v0.10.47, apply `20261104100000_fosch_submitter_pending_edits.sql` after the existing submission and status migrations. It installs the receipt-token-protected `edit_fosch_pending_submission` RPC. It does not grant public table UPDATE privileges and permits changes only while the matching submission remains pending.

