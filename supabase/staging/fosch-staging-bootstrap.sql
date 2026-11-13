-- FOSCH STAGING BOOTSTRAP
-- Generated from the repository's timestamped Supabase migrations.
-- TARGET: qeqdvssrhzllatdojjte.supabase.co ONLY.
-- Run in Supabase Dashboard > SQL Editor after confirming the project ref.
-- Do NOT run this file against production (mqgvagftvuybietazbdc.supabase.co).
-- This installs schema, functions, grants, RLS and storage bucket policies only.
-- It does not copy production records, users, storage objects or secrets.
-- Review the accompanying STAGING-SETUP.md before execution.



-- ============================================================================
-- BEGIN SOURCE MIGRATION: supabase/migrations/20261102000000_fosch_submissions.sql
-- ============================================================================

-- FOSCH shared submission backend
-- Apply this migration in Supabase Dashboard -> SQL Editor.
-- Authentication users are managed by Supabase Auth; do not create a password table.

begin;

create table if not exists public.product_submissions (
  id uuid primary key default gen_random_uuid(),
  client_record_id text not null,
  submission_type text not null check (submission_type in ('product', 'product_correction', 'correction')),
  brand text,
  product_name text,
  barcode text,
  package_size text,
  country_code text,
  ingredients text,
  target text,
  message text,
  source text,
  payload jsonb not null default '{}'::jsonb check (jsonb_typeof(payload) = 'object'),
  photo_paths text[] not null default '{}',
  status text not null default 'pending' check (status in ('pending', 'approved', 'rejected')),
  submitted_by uuid references auth.users(id) on delete set null,
  submitted_at timestamptz not null default now(),
  reviewed_by uuid references auth.users(id) on delete set null,
  reviewed_at timestamptz,
  admin_note text,
  rejection_reason text,
  updated_at timestamptz not null default now(),
  constraint product_submissions_client_reference_unique unique (submission_type, client_record_id),
  constraint product_submissions_country_code_check check (country_code is null or country_code ~ '^[A-Z]{2}$'),
  constraint product_submissions_client_record_id_check check (length(client_record_id) between 1 and 160)
);

create index if not exists product_submissions_status_date_idx
  on public.product_submissions (status, submitted_at desc);
create index if not exists product_submissions_barcode_country_idx
  on public.product_submissions (country_code, barcode);
create unique index if not exists product_submissions_product_barcode_unique
  on public.product_submissions (country_code, barcode, coalesce(package_size, ''))
  where submission_type = 'product' and barcode is not null and btrim(barcode) <> '';

create table if not exists public.admin_audit_log (
  id bigint generated always as identity primary key,
  actor_id uuid references auth.users(id) on delete set null,
  actor_email text,
  action text not null,
  submission_id uuid references public.product_submissions(id) on delete set null,
  client_record_id text,
  reason text,
  details jsonb not null default '{}'::jsonb,
  old_record jsonb,
  new_record jsonb,
  created_at timestamptz not null default now()
);

create index if not exists admin_audit_log_created_at_idx
  on public.admin_audit_log (created_at desc);
create index if not exists admin_audit_log_submission_id_idx
  on public.admin_audit_log (submission_id);

create or replace function public.is_fosch_admin()
returns boolean
language sql
stable
security invoker
set search_path = ''
as $$
  select coalesce((auth.jwt() -> 'app_metadata' ->> 'role') = 'admin', false);
$$;

revoke all on function public.is_fosch_admin() from public;
grant execute on function public.is_fosch_admin() to authenticated;

alter table public.product_submissions enable row level security;
alter table public.admin_audit_log enable row level security;

revoke all on table public.product_submissions from anon, authenticated;
revoke all on table public.admin_audit_log from anon, authenticated;

grant insert on table public.product_submissions to anon, authenticated;
grant select on table public.product_submissions to authenticated;
grant select on table public.admin_audit_log to authenticated;

drop policy if exists "FOSCH public pending submission insert" on public.product_submissions;
create policy "FOSCH public pending submission insert"
  on public.product_submissions
  for insert
  to anon, authenticated
  with check (
    status = 'pending'
    and submitted_by is null
    and reviewed_by is null
    and reviewed_at is null
    and admin_note is null
    and rejection_reason is null
    and jsonb_typeof(payload) = 'object'
  );

drop policy if exists "FOSCH admins read submissions" on public.product_submissions;
create policy "FOSCH admins read submissions"
  on public.product_submissions
  for select
  to authenticated
  using ((select public.is_fosch_admin()));

drop policy if exists "FOSCH admins read audit log" on public.admin_audit_log;
create policy "FOSCH admins read audit log"
  on public.admin_audit_log
  for select
  to authenticated
  using ((select public.is_fosch_admin()));

-- All changes to shared submissions go through these RPCs. They enforce admin
-- claims and write an immutable audit snapshot in the same database transaction.
create or replace function public.admin_update_product_submission(
  p_submission_id uuid,
  p_changes jsonb,
  p_reason text
)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  old_row public.product_submissions%rowtype;
  new_row public.product_submissions%rowtype;
begin
  if not public.is_fosch_admin() then
    raise exception 'Administrator access required' using errcode = '42501';
  end if;
  if length(btrim(coalesce(p_reason, ''))) < 10 then
    raise exception 'A meaningful reason of at least 10 characters is required';
  end if;
  if p_changes is null or jsonb_typeof(p_changes) <> 'object' then
    raise exception 'Changes must be supplied as a JSON object';
  end if;

  select * into old_row
  from public.product_submissions
  where id = p_submission_id
  for update;

  if not found then
    raise exception 'Submission not found';
  end if;

  update public.product_submissions
  set
    brand = case when p_changes ? 'brand' then nullif(p_changes->>'brand', '') else brand end,
    product_name = case when p_changes ? 'product_name' then nullif(p_changes->>'product_name', '') else product_name end,
    barcode = case when p_changes ? 'barcode' then nullif(p_changes->>'barcode', '') else barcode end,
    package_size = case when p_changes ? 'package_size' then nullif(p_changes->>'package_size', '') else package_size end,
    ingredients = case when p_changes ? 'ingredients' then nullif(p_changes->>'ingredients', '') else ingredients end,
    target = case when p_changes ? 'target' then nullif(p_changes->>'target', '') else target end,
    message = case when p_changes ? 'message' then nullif(p_changes->>'message', '') else message end,
    source = case when p_changes ? 'source' then nullif(p_changes->>'source', '') else source end,
    payload = case when p_changes ? 'payload' then p_changes->'payload' else payload end,
    photo_paths = case
      when p_changes ? 'photo_paths' then array(select jsonb_array_elements_text(p_changes->'photo_paths'))
      else photo_paths
    end,
    status = case when p_changes ? 'status' then p_changes->>'status' else status end,
    admin_note = case when p_changes ? 'admin_note' then nullif(p_changes->>'admin_note', '') else admin_note end,
    rejection_reason = case when p_changes ? 'rejection_reason' then nullif(p_changes->>'rejection_reason', '') else rejection_reason end,
    reviewed_by = auth.uid(),
    reviewed_at = now(),
    updated_at = now()
  where id = p_submission_id
  returning * into new_row;

  insert into public.admin_audit_log (
    actor_id, actor_email, action, submission_id, client_record_id,
    reason, details, old_record, new_record
  ) values (
    auth.uid(),
    coalesce(auth.jwt()->>'email', ''),
    coalesce(p_changes->>'audit_action', 'submission_updated'),
    new_row.id,
    new_row.client_record_id,
    btrim(p_reason),
    coalesce(p_changes->'audit_details', '{}'::jsonb),
    to_jsonb(old_row),
    to_jsonb(new_row)
  );

  return to_jsonb(new_row);
end;
$$;

create or replace function public.admin_delete_product_submission(
  p_submission_id uuid,
  p_reason text
)
returns boolean
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  old_row public.product_submissions%rowtype;
begin
  if not public.is_fosch_admin() then
    raise exception 'Administrator access required' using errcode = '42501';
  end if;
  if length(btrim(coalesce(p_reason, ''))) < 10 then
    raise exception 'A meaningful reason of at least 10 characters is required';
  end if;

  select * into old_row
  from public.product_submissions
  where id = p_submission_id
  for update;

  if not found then
    raise exception 'Submission not found';
  end if;

  insert into public.admin_audit_log (
    actor_id, actor_email, action, submission_id, client_record_id,
    reason, details, old_record
  ) values (
    auth.uid(),
    coalesce(auth.jwt()->>'email', ''),
    'submission_deleted',
    old_row.id,
    old_row.client_record_id,
    btrim(p_reason),
    jsonb_build_object('deleted_at', now()),
    to_jsonb(old_row)
  );

  delete from public.product_submissions where id = p_submission_id;
  return true;
end;
$$;

create or replace function public.admin_log_event(
  p_action text,
  p_details jsonb default '{}'::jsonb
)
returns bigint
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  new_id bigint;
begin
  if not public.is_fosch_admin() then
    raise exception 'Administrator access required' using errcode = '42501';
  end if;
  if length(btrim(coalesce(p_action, ''))) = 0 then
    raise exception 'Action is required';
  end if;

  insert into public.admin_audit_log (actor_id, actor_email, action, details)
  values (
    auth.uid(),
    coalesce(auth.jwt()->>'email', ''),
    left(btrim(p_action), 120),
    coalesce(p_details, '{}'::jsonb)
  )
  returning id into new_id;

  return new_id;
end;
$$;

revoke all on function public.admin_update_product_submission(uuid, jsonb, text) from public, anon;
revoke all on function public.admin_delete_product_submission(uuid, text) from public, anon;
revoke all on function public.admin_log_event(text, jsonb) from public, anon;
grant execute on function public.admin_update_product_submission(uuid, jsonb, text) to authenticated;
grant execute on function public.admin_delete_product_submission(uuid, text) to authenticated;
grant execute on function public.admin_log_event(text, jsonb) to authenticated;

-- Private product evidence bucket. The public client can upload only beneath
-- submissions/<UUID>/... and cannot list, read, replace or delete evidence.
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
  'fosch-submission-photos',
  'fosch-submission-photos',
  false,
  5242880,
  array['image/jpeg', 'image/png', 'image/webp']
)
on conflict (id) do update
set public = false,
    file_size_limit = 5242880,
    allowed_mime_types = array['image/jpeg', 'image/png', 'image/webp'];

drop policy if exists "FOSCH allow submission evidence uploads" on storage.objects;
create policy "FOSCH allow submission evidence uploads"
  on storage.objects
  for insert
  to anon, authenticated
  with check (
    bucket_id = 'fosch-submission-photos'
    and (storage.foldername(name))[1] = 'submissions'
    and (storage.foldername(name))[2] ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
  );

drop policy if exists "FOSCH admins read submission evidence" on storage.objects;
create policy "FOSCH admins read submission evidence"
  on storage.objects
  for select
  to authenticated
  using (bucket_id = 'fosch-submission-photos' and (select public.is_fosch_admin()));

drop policy if exists "FOSCH admins delete submission evidence" on storage.objects;
create policy "FOSCH admins delete submission evidence"
  on storage.objects
  for delete
  to authenticated
  using (bucket_id = 'fosch-submission-photos' and (select public.is_fosch_admin()));

commit;

-- After creating your admin user in Authentication -> Users, grant the admin
-- role once from SQL Editor, replacing the email with the exact account email:
--
-- update auth.users
-- set raw_app_meta_data =
--   coalesce(raw_app_meta_data, '{}'::jsonb) || jsonb_build_object('role', 'admin')
-- where lower(email) = lower('YOUR_ADMIN_EMAIL_HERE');
--
-- Sign out and sign back in after running that statement so the JWT is refreshed.

-- END SOURCE MIGRATION: supabase/migrations/20261102000000_fosch_submissions.sql


-- ============================================================================
-- BEGIN SOURCE MIGRATION: supabase/migrations/20261102010000_fosch_submission_status.sql
-- ============================================================================

-- FOSCH submitter status receipts and removal-from-queue semantics.
-- Apply after 20261102000000_fosch_submissions.sql.
begin;
create extension if not exists pgcrypto with schema extensions;
alter table public.product_submissions
  add column if not exists status_token text,
  add column if not exists status_token_hash text;

create or replace function public.capture_fosch_submission_status_token()
returns trigger language plpgsql security definer set search_path = ''
as $function$
begin
  if new.status_token is null then
    new.status_token_hash := null;
    return new;
  end if;
  if new.status_token !~ '^[0-9a-fA-F]{64}$' then
    raise exception 'The private submission status receipt is invalid';
  end if;
  new.status_token_hash := pg_catalog.encode(
    extensions.digest(pg_catalog.decode(lower(new.status_token), 'hex'), 'sha256'), 'hex'
  );
  new.status_token := null;
  return new;
end;
$function$;

drop trigger if exists fosch_capture_submission_status_token on public.product_submissions;
create trigger fosch_capture_submission_status_token
  before insert on public.product_submissions
  for each row execute function public.capture_fosch_submission_status_token();

create index if not exists product_submissions_status_receipt_idx
  on public.product_submissions (client_record_id, submission_type, status_token_hash);

create or replace function public.get_fosch_submission_statuses(p_lookups jsonb)
returns jsonb language plpgsql security definer set search_path = ''
as $function$
declare
  result jsonb;
begin
  if p_lookups is null or pg_catalog.jsonb_typeof(p_lookups) <> 'array' then
    raise exception 'Submission status lookups must be a JSON array';
  end if;
  if pg_catalog.jsonb_array_length(p_lookups) > 100 then
    raise exception 'A maximum of 100 submission statuses can be checked per request';
  end if;

  select coalesce(
    pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object(
      'client_record_id', s.client_record_id,
      'submission_type', s.submission_type,
      'status', s.status,
      'rejection_reason', s.rejection_reason,
      'reviewed_at', s.reviewed_at,
      'updated_at', s.updated_at
    )),
    '[]'::jsonb
  )
  into result
  from pg_catalog.jsonb_array_elements(p_lookups) as request(value)
  join public.product_submissions as s
    on s.client_record_id = request.value ->> 'client_record_id'
   and s.submission_type = request.value ->> 'submission_type'
   and case
     when s.status_token_hash is not null then
       case
         when (request.value ->> 'status_token') ~ '^[0-9a-fA-F]{64}$' then
           s.status_token_hash = pg_catalog.encode(
             extensions.digest(
               pg_catalog.decode(lower(request.value ->> 'status_token'), 'hex'),
               'sha256'
             ),
             'hex'
           )
         else false
       end
     when s.status_token_hash is null then
       s.id::text = lower(coalesce(request.value ->> 'submission_id', ''))
     else false
   end;

  return result;
end;
$function$;

revoke all on function public.capture_fosch_submission_status_token() from public, anon, authenticated;
revoke all on function public.get_fosch_submission_statuses(jsonb) from public;
grant execute on function public.get_fosch_submission_statuses(jsonb) to anon, authenticated;

create or replace function public.admin_delete_product_submission(p_submission_id uuid, p_reason text)
returns boolean language plpgsql security definer set search_path = public, pg_temp
as $function$
declare
  old_row public.product_submissions%rowtype;
  new_row public.product_submissions%rowtype;
begin
  if not public.is_fosch_admin() then
    raise exception 'Administrator access required' using errcode = '42501';
  end if;
  if length(btrim(coalesce(p_reason, ''))) < 10 then
    raise exception 'A meaningful reason of at least 10 characters is required';
  end if;
  select * into old_row from public.product_submissions
  where id = p_submission_id for update;
  if not found then raise exception 'Submission not found'; end if;

  update public.product_submissions
  set status = 'rejected',
      rejection_reason = btrim(p_reason),
      admin_note = 'Removed from the pending queue: ' || btrim(p_reason),
      reviewed_by = auth.uid(), reviewed_at = now(), updated_at = now()
  where id = p_submission_id returning * into new_row;

  insert into public.admin_audit_log (
    actor_id, actor_email, action, submission_id, client_record_id,
    reason, details, old_record, new_record
  ) values (
    auth.uid(), coalesce(auth.jwt() ->> 'email', ''),
    'submission_removed_from_queue', new_row.id, new_row.client_record_id,
    btrim(p_reason), jsonb_build_object('soft_deleted', true, 'removed_at', now()),
    to_jsonb(old_row), to_jsonb(new_row)
  );
  return true;
end;
$function$;

revoke all on function public.admin_delete_product_submission(uuid, text) from public, anon;
grant execute on function public.admin_delete_product_submission(uuid, text) to authenticated;
commit;

-- END SOURCE MIGRATION: supabase/migrations/20261102010000_fosch_submission_status.sql


-- ============================================================================
-- BEGIN SOURCE MIGRATION: supabase/migrations/20261102020000_fosch_status_legacy_lookup.sql
-- ============================================================================

-- FOSCH backward-compatible submitter status retrieval.
-- Apply after 20261102000000_fosch_submissions.sql.
-- This migration supersedes the status RPC from 20261102010000 and supports
-- older rows that were created before a receipt token was available.
begin;

create extension if not exists pgcrypto with schema extensions;

alter table public.product_submissions
  add column if not exists status_token text,
  add column if not exists status_token_hash text;

create or replace function public.capture_fosch_submission_status_token()
returns trigger language plpgsql security definer set search_path = ''
as $function$
begin
  if new.status_token is null then
    new.status_token_hash := null;
    return new;
  end if;
  if new.status_token !~ '^[0-9a-fA-F]{64}$' then
    raise exception 'The private submission status receipt is invalid';
  end if;
  new.status_token_hash := pg_catalog.encode(
    extensions.digest(pg_catalog.decode(lower(new.status_token), 'hex'), 'sha256'), 'hex'
  );
  new.status_token := null;
  return new;
end;
$function$;

drop trigger if exists fosch_capture_submission_status_token on public.product_submissions;
create trigger fosch_capture_submission_status_token
  before insert on public.product_submissions
  for each row execute function public.capture_fosch_submission_status_token();

create index if not exists product_submissions_status_receipt_idx
  on public.product_submissions (client_record_id, submission_type, status_token_hash);

create or replace function public.get_fosch_submission_statuses(p_lookups jsonb)
returns jsonb language plpgsql security definer set search_path = ''
as $function$
declare
  result jsonb;
begin
  if p_lookups is null or pg_catalog.jsonb_typeof(p_lookups) <> 'array' then
    raise exception 'Submission status lookups must be a JSON array';
  end if;
  if pg_catalog.jsonb_array_length(p_lookups) > 100 then
    raise exception 'A maximum of 100 submission statuses can be checked per request';
  end if;

  select coalesce(
    pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object(
      'client_record_id', s.client_record_id,
      'submission_type', s.submission_type,
      'status', s.status,
      'rejection_reason', s.rejection_reason,
      'reviewed_at', s.reviewed_at,
      'updated_at', s.updated_at
    )),
    '[]'::jsonb
  )
  into result
  from pg_catalog.jsonb_array_elements(p_lookups) as request(value)
  join public.product_submissions as s
    on s.client_record_id = request.value ->> 'client_record_id'
   and s.submission_type = request.value ->> 'submission_type'
   and case
     when s.status_token_hash is not null then
       case
         when (request.value ->> 'status_token') ~ '^[0-9a-fA-F]{64}$' then
           s.status_token_hash = pg_catalog.encode(
             extensions.digest(
               pg_catalog.decode(lower(request.value ->> 'status_token'), 'hex'),
               'sha256'
             ),
             'hex'
           )
         else false
       end
     when s.status_token_hash is null then
       s.id::text = lower(coalesce(request.value ->> 'submission_id', ''))
     else false
   end;

  return result;
end;
$function$;

revoke all on function public.capture_fosch_submission_status_token() from public, anon, authenticated;
revoke all on function public.get_fosch_submission_statuses(jsonb) from public;
grant execute on function public.get_fosch_submission_statuses(jsonb) to anon, authenticated;

create or replace function public.admin_delete_product_submission(p_submission_id uuid, p_reason text)
returns boolean language plpgsql security definer set search_path = public, pg_temp
as $function$
declare
  old_row public.product_submissions%rowtype;
  new_row public.product_submissions%rowtype;
begin
  if not public.is_fosch_admin() then
    raise exception 'Administrator access required' using errcode = '42501';
  end if;
  if length(btrim(coalesce(p_reason, ''))) < 10 then
    raise exception 'A meaningful reason of at least 10 characters is required';
  end if;
  select * into old_row from public.product_submissions
  where id = p_submission_id for update;
  if not found then raise exception 'Submission not found'; end if;

  update public.product_submissions
  set status = 'rejected',
      rejection_reason = btrim(p_reason),
      admin_note = 'Removed from the pending queue: ' || btrim(p_reason),
      reviewed_by = auth.uid(), reviewed_at = now(), updated_at = now()
  where id = p_submission_id returning * into new_row;

  insert into public.admin_audit_log (
    actor_id, actor_email, action, submission_id, client_record_id,
    reason, details, old_record, new_record
  ) values (
    auth.uid(), coalesce(auth.jwt() ->> 'email', ''),
    'submission_removed_from_queue', new_row.id, new_row.client_record_id,
    btrim(p_reason), jsonb_build_object('soft_deleted', true, 'removed_at', now()),
    to_jsonb(old_row), to_jsonb(new_row)
  );
  return true;
end;
$function$;

revoke all on function public.admin_delete_product_submission(uuid, text) from public, anon;
grant execute on function public.admin_delete_product_submission(uuid, text) to authenticated;

commit;

-- END SOURCE MIGRATION: supabase/migrations/20261102020000_fosch_status_legacy_lookup.sql


-- ============================================================================
-- BEGIN SOURCE MIGRATION: supabase/migrations/20261102030000_fosch_submission_insert_rpc.sql
-- ============================================================================

-- FOSCH retry-safe submission RPC and explicit API-role grants.
-- Apply after the three existing FOSCH submission/status migrations.
begin;

-- Reassert the minimum table privileges expected by the public submission
-- flow. No public UPDATE or DELETE access is granted.
grant usage on schema public to anon, authenticated;
grant insert on table public.product_submissions to anon, authenticated;
grant select on table public.product_submissions to authenticated;

create or replace function public.submit_fosch_product_submission(p_submission jsonb)
returns boolean
language plpgsql
security definer
set search_path = pg_catalog, public
as $function$
declare
  inserted_count integer;
  photo_values text[];
  payload_value jsonb;
begin
  if p_submission is null or jsonb_typeof(p_submission) <> 'object' then
    raise exception 'A submission object is required';
  end if;

  if coalesce(p_submission->>'id', '') !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then
    raise exception 'A valid submission ID is required';
  end if;
  if length(coalesce(p_submission->>'client_record_id', '')) not between 1 and 160 then
    raise exception 'A valid local draft reference is required';
  end if;
  if coalesce(p_submission->>'submission_type', '') not in ('product', 'product_correction', 'correction') then
    raise exception 'Unsupported submission type';
  end if;

  payload_value := coalesce(p_submission->'payload', '{}'::jsonb);
  if jsonb_typeof(payload_value) <> 'object' then
    raise exception 'Submission payload must be an object';
  end if;

  select coalesce(array_agg(value), '{}'::text[])
  into photo_values
  from jsonb_array_elements_text(
    case
      when jsonb_typeof(p_submission->'photo_paths') = 'array' then p_submission->'photo_paths'
      else '[]'::jsonb
    end
  ) as paths(value);

  insert into public.product_submissions (
    id, client_record_id, submission_type, brand, product_name, barcode,
    package_size, country_code, ingredients, target, message, source,
    payload, photo_paths, status_token, status, submitted_by, submitted_at
  ) values (
    (p_submission->>'id')::uuid,
    p_submission->>'client_record_id',
    p_submission->>'submission_type',
    nullif(p_submission->>'brand', ''),
    nullif(p_submission->>'product_name', ''),
    nullif(p_submission->>'barcode', ''),
    nullif(p_submission->>'package_size', ''),
    nullif(p_submission->>'country_code', ''),
    nullif(p_submission->>'ingredients', ''),
    nullif(p_submission->>'target', ''),
    nullif(p_submission->>'message', ''),
    nullif(p_submission->>'source', ''),
    payload_value,
    photo_values,
    nullif(p_submission->>'status_token', ''),
    'pending',
    null,
    coalesce(nullif(p_submission->>'submitted_at', '')::timestamptz, now())
  )
  on conflict (submission_type, client_record_id) do nothing;

  get diagnostics inserted_count = row_count;
  return inserted_count = 1;
end;
$function$;

revoke all on function public.submit_fosch_product_submission(jsonb) from public;
grant execute on function public.submit_fosch_product_submission(jsonb) to anon, authenticated;

commit;

-- END SOURCE MIGRATION: supabase/migrations/20261102030000_fosch_submission_insert_rpc.sql


-- ============================================================================
-- BEGIN SOURCE MIGRATION: supabase/migrations/20261104100000_fosch_submitter_pending_edits.sql
-- ============================================================================

-- Allow the original submitter to edit their own pending row using the private receipt token.
-- Apply after 20261102030000_fosch_submission_insert_rpc.sql.
-- No table UPDATE privilege is granted, and this function cannot change review status.

begin;

create or replace function public.edit_fosch_pending_submission(
  p_submission_id uuid,
  p_client_record_id text,
  p_submission_type text,
  p_status_token text,
  p_changes jsonb
)
returns boolean
language plpgsql
security definer
set search_path = pg_catalog, public, extensions
as $function$
declare
  next_payload jsonb;
  next_photos text[];
begin
  if p_submission_id is null
     or length(coalesce(p_client_record_id, '')) not between 1 and 160
     or coalesce(p_submission_type, '') not in ('product', 'product_correction', 'correction') then
    raise exception 'A valid submission reference is required';
  end if;

  if coalesce(p_status_token, '') !~ '^[0-9a-fA-F]{64}$' then
    raise exception 'The private submission edit receipt is invalid';
  end if;

  if p_changes is null or jsonb_typeof(p_changes) <> 'object' then
    raise exception 'Submission changes must be a JSON object';
  end if;

  if p_changes ?| array['id', 'client_record_id', 'submission_type', 'status',
                         'submitted_by', 'submitted_at', 'reviewed_by', 'reviewed_at',
                         'admin_note', 'rejection_reason', 'status_token', 'status_token_hash'] then
    raise exception 'Protected submission fields cannot be edited by a submitter';
  end if;

  if p_changes ? 'payload' then
    if jsonb_typeof(p_changes->'payload') <> 'object' then
      raise exception 'Submission payload must be a JSON object';
    end if;
    next_payload := p_changes->'payload';
    if next_payload ?| array['cloudStatusToken', 'status_token', 'status_token_hash',
                             'reviewed_by', 'reviewed_at', 'admin_note', 'rejection_reason'] then
      raise exception 'Protected payload fields cannot be submitted';
    end if;
  end if;

  if p_changes ? 'photo_paths' then
    if jsonb_typeof(p_changes->'photo_paths') <> 'array' then
      raise exception 'Photo paths must be a JSON array';
    end if;
    select coalesce(array_agg(value), '{}'::text[])
      into next_photos
      from jsonb_array_elements_text(p_changes->'photo_paths') as item(value);
    if coalesce(array_length(next_photos, 1), 0) > 5 then
      raise exception 'A maximum of five product photos can be attached';
    end if;
  end if;

  if p_changes ? 'country_code'
     and nullif(p_changes->>'country_code', '') is not null
     and upper(p_changes->>'country_code') !~ '^[A-Z]{2}$' then
    raise exception 'The selected product market must be a two-letter country code';
  end if;

  perform 1
    from public.product_submissions
   where id = p_submission_id
     and client_record_id = p_client_record_id
     and submission_type = p_submission_type
     and status = 'pending'
     and reviewed_at is null
     and status_token_hash = pg_catalog.encode(
       extensions.digest(pg_catalog.decode(lower(p_status_token), 'hex'), 'sha256'),
       'hex'
     )
   for update;

  if not found then
    raise exception 'This submission cannot be edited: it may no longer be pending or the private receipt did not match';
  end if;

  update public.product_submissions
     set brand = case when p_changes ? 'brand' then nullif(p_changes->>'brand', '') else brand end,
         product_name = case when p_changes ? 'product_name' then nullif(p_changes->>'product_name', '') else product_name end,
         barcode = case when p_changes ? 'barcode' then nullif(p_changes->>'barcode', '') else barcode end,
         package_size = case when p_changes ? 'package_size' then nullif(p_changes->>'package_size', '') else package_size end,
         country_code = case when p_changes ? 'country_code' then nullif(upper(p_changes->>'country_code'), '') else country_code end,
         ingredients = case when p_changes ? 'ingredients' then nullif(p_changes->>'ingredients', '') else ingredients end,
         target = case when p_changes ? 'target' then nullif(p_changes->>'target', '') else target end,
         message = case when p_changes ? 'message' then nullif(p_changes->>'message', '') else message end,
         source = case when p_changes ? 'source' then nullif(p_changes->>'source', '') else source end,
         payload = case when p_changes ? 'payload' then next_payload else payload end,
         photo_paths = case when p_changes ? 'photo_paths' then coalesce(next_photos, '{}'::text[]) else photo_paths end,
         updated_at = now()
   where id = p_submission_id;

  return true;
end;
$function$;

revoke all on function public.edit_fosch_pending_submission(uuid, text, text, text, jsonb) from public;
grant execute on function public.edit_fosch_pending_submission(uuid, text, text, text, jsonb) to anon, authenticated;

commit;

-- END SOURCE MIGRATION: supabase/migrations/20261104100000_fosch_submitter_pending_edits.sql


-- ============================================================================
-- BEGIN SOURCE MIGRATION: supabase/migrations/20261104130000_fosch_public_product_media.sql
-- ============================================================================

-- FOSCH public, cross-device administrator product media.
-- Apply after the existing FOSCH submission migrations.

insert into storage.buckets (id,name,public,file_size_limit,allowed_mime_types)
values ('fosch-public-product-media','fosch-public-product-media',true,5242880,array['image/jpeg','image/png','image/webp'])
on conflict (id) do update set public=excluded.public,file_size_limit=excluded.file_size_limit,allowed_mime_types=excluded.allowed_mime_types;

drop policy if exists "FOSCH public product media read" on storage.objects;
create policy "FOSCH public product media read" on storage.objects for select to anon,authenticated
using (bucket_id='fosch-public-product-media');

drop policy if exists "FOSCH admins upload public product media" on storage.objects;
create policy "FOSCH admins upload public product media" on storage.objects for insert to authenticated
with check (bucket_id='fosch-public-product-media' and (select public.is_fosch_admin()));

drop policy if exists "FOSCH admins update public product media" on storage.objects;
create policy "FOSCH admins update public product media" on storage.objects for update to authenticated
using (bucket_id='fosch-public-product-media' and (select public.is_fosch_admin()))
with check (bucket_id='fosch-public-product-media' and (select public.is_fosch_admin()));

drop policy if exists "FOSCH admins delete public product media" on storage.objects;
create policy "FOSCH admins delete public product media" on storage.objects for delete to authenticated
using (bucket_id='fosch-public-product-media' and (select public.is_fosch_admin()));

create or replace function public.get_fosch_public_product_media(p_barcode text,p_country text)
returns jsonb language sql stable security definer set search_path=''
as $$
with candidates as (
 select s.updated_at,s.submitted_at,s.brand as submission_brand,
  case when jsonb_typeof(s.payload->'suggestedProduct')='object' then s.payload->'suggestedProduct' else s.payload end as product_payload,
  coalesce(nullif(s.barcode,''),nullif(s.payload->>'barcode',''),nullif(s.payload->'suggestedProduct'->>'barcode','')) as product_barcode,
  upper(coalesce(nullif(s.country_code,''),nullif(s.payload->>'country',''),nullif(s.payload->'suggestedProduct'->>'country',''))) as product_country
 from public.product_submissions s
 where s.status='approved' and s.submission_type in ('product','product_correction','correction')
)
select jsonb_build_object(
 'brand',coalesce(nullif(c.product_payload->>'brand',''),nullif(c.product_payload->>'brands',''),c.submission_brand,''),
 'officialPhoto',case when c.product_payload->>'officialPhoto' like 'https://mqgvagftvuybietazbdc.supabase.co/storage/v1/object/public/fosch-public-product-media/%' then c.product_payload->>'officialPhoto' else '' end,
 'officialPhotos',coalesce((
  select jsonb_agg(to_jsonb(m.url) order by m.ordinal)
  from (
   select src.url,min(src.ordinal) ordinal
   from (
    select photo.value url,photo.ordinality ordinal
    from jsonb_array_elements_text(case when jsonb_typeof(c.product_payload->'officialPhotos')='array' then c.product_payload->'officialPhotos' else '[]'::jsonb end) with ordinality as photo(value,ordinality)
    union all
    select c.product_payload->>'officialPhoto',0
    where c.product_payload->>'officialPhoto' like 'https://mqgvagftvuybietazbdc.supabase.co/storage/v1/object/public/fosch-public-product-media/%'
   ) src
   where src.url like 'https://mqgvagftvuybietazbdc.supabase.co/storage/v1/object/public/fosch-public-product-media/%'
   group by src.url
  ) m
 ),'[]'::jsonb)
)
from candidates c
where c.product_barcode=nullif(btrim(p_barcode),'') and c.product_country=upper(nullif(btrim(p_country),''))
order by c.updated_at desc nulls last,c.submitted_at desc limit 1;
$$;
revoke all on function public.get_fosch_public_product_media(text,text) from public;
grant execute on function public.get_fosch_public_product_media(text,text) to anon,authenticated;

-- END SOURCE MIGRATION: supabase/migrations/20261104130000_fosch_public_product_media.sql


-- ============================================================================
-- BEGIN SOURCE MIGRATION: supabase/migrations/20261105120000_fosch_media_display_fix.sql
-- ============================================================================

-- FOSCH shared brand-logo references and resilient approved product-photo lookup.
-- Apply after 20261104130000_fosch_public_product_media.sql.

begin;

create table if not exists public.fosch_brand_media (
  brand_key text primary key check (brand_key ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
  brand_name text not null,
  object_path text not null unique check (object_path ~ '^brands/[a-z0-9-]+[.]jpeg$'),
  updated_at timestamptz not null default now()
);

alter table public.fosch_brand_media enable row level security;
revoke all on table public.fosch_brand_media from public, anon, authenticated;

insert into public.fosch_brand_media (brand_key, brand_name, object_path)
select
  regexp_replace(name, '^brands/|[.]jpeg$', '', 'g'),
  regexp_replace(name, '^brands/|[.]jpeg$', '', 'g'),
  name
from storage.objects
where bucket_id = 'fosch-public-product-media'
  and name ~ '^brands/[a-z0-9-]+[.]jpeg$'
on conflict (brand_key) do update
set object_path = excluded.object_path, updated_at = now();

create or replace function public.admin_set_fosch_brand_logo(
  p_brand_key text,
  p_brand_name text,
  p_object_path text
)
returns text
language plpgsql
security definer
set search_path = ''
as $function$
declare
  clean_key text := lower(btrim(coalesce(p_brand_key, '')));
  clean_path text := btrim(coalesce(p_object_path, ''));
begin
  if not public.is_fosch_admin() then
    raise exception 'Administrator access required' using errcode = '42501';
  end if;
  if clean_key !~ '^[a-z0-9]+(-[a-z0-9]+)*$' or clean_path <> 'brands/' || clean_key || '.jpeg' then
    raise exception 'Invalid brand-logo reference';
  end if;
  if not exists (
    select 1 from storage.objects
    where bucket_id = 'fosch-public-product-media' and name = clean_path
  ) then
    raise exception 'The uploaded brand-logo object could not be found';
  end if;

  insert into public.fosch_brand_media (brand_key, brand_name, object_path, updated_at)
  values (clean_key, coalesce(nullif(btrim(p_brand_name), ''), clean_key), clean_path, now())
  on conflict (brand_key) do update
    set brand_name = excluded.brand_name,
        object_path = excluded.object_path,
        updated_at = now();
  return clean_path;
end;
$function$;

create or replace function public.get_fosch_public_brand_logo(p_brand_key text)
returns text
language sql
stable
security definer
set search_path = ''
as $function$
  select bm.object_path
  from public.fosch_brand_media bm
  where bm.brand_key = lower(btrim(coalesce(p_brand_key, '')))
  limit 1;
$function$;

revoke all on function public.admin_set_fosch_brand_logo(text, text, text) from public, anon;
grant execute on function public.admin_set_fosch_brand_logo(text, text, text) to authenticated;
revoke all on function public.get_fosch_public_brand_logo(text) from public;
grant execute on function public.get_fosch_public_brand_logo(text) to anon, authenticated;

create or replace function public.get_fosch_public_product_media(p_barcode text, p_country text)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $function$
with candidates as (
  select
    s.updated_at,
    s.submitted_at,
    s.brand as submission_brand,
    case
      when jsonb_typeof(s.payload->'suggestedProduct') = 'object'
      then s.payload->'suggestedProduct'
      else s.payload
    end as product_payload,
    coalesce(
      nullif(s.barcode, ''),
      nullif(s.payload->>'barcode', ''),
      nullif(s.payload->'suggestedProduct'->>'barcode', '')
    ) as product_barcode,
    upper(coalesce(
      nullif(s.country_code, ''),
      nullif(s.payload->>'country', ''),
      nullif(s.payload->'suggestedProduct'->>'country', '')
    )) as product_country
  from public.product_submissions s
  where s.status = 'approved'
    and s.submission_type in ('product', 'product_correction', 'correction')
),
matched as (
  select *
  from candidates
  where product_barcode = nullif(btrim(p_barcode), '')
    and product_country = upper(nullif(btrim(p_country), ''))
),
brand_choice as (
  select coalesce(
    nullif(product_payload->>'brand', ''),
    nullif(product_payload->>'brands', ''),
    nullif(submission_brand, '')
  ) as brand
  from matched
  where coalesce(
    nullif(product_payload->>'brand', ''),
    nullif(product_payload->>'brands', ''),
    nullif(submission_brand, '')
  ) is not null
  order by updated_at desc nulls last, submitted_at desc
  limit 1
),
photo_rows as (
  select m.updated_at, photo.value as url, photo.ordinality as ordinal
  from matched m
  cross join lateral jsonb_array_elements_text(
    case when jsonb_typeof(m.product_payload->'officialPhotos') = 'array'
      then m.product_payload->'officialPhotos' else '[]'::jsonb end
  ) with ordinality as photo(value, ordinality)
  union all
  select m.updated_at, m.product_payload->>'officialPhoto' as url, 0::bigint as ordinal
  from matched m
  where coalesce(m.product_payload->>'officialPhoto', '') <> ''
),
unique_photos as (
  select url, max(updated_at) as latest_at, min(ordinal) as ordinal
  from photo_rows
  where url like 'https://mqgvagftvuybietazbdc.supabase.co/storage/v1/object/public/fosch-public-product-media/%'
  group by url
),
top_photos as (
  select url, latest_at, ordinal
  from unique_photos
  order by latest_at desc nulls last, ordinal
  limit 5
),
photo_list as (
  select
    coalesce(jsonb_agg(to_jsonb(url) order by latest_at desc nulls last, ordinal), '[]'::jsonb) as photos,
    coalesce((array_agg(url order by latest_at desc nulls last, ordinal))[1], '') as first_photo
  from top_photos
)
select jsonb_build_object(
  'brand', coalesce((select brand from brand_choice), ''),
  'officialPhoto', photo_list.first_photo,
  'officialPhotos', photo_list.photos
)
from photo_list
where exists (select 1 from matched);
$function$;

revoke all on function public.get_fosch_public_product_media(text, text) from public;
grant execute on function public.get_fosch_public_product_media(text, text) to anon, authenticated;

commit;

-- END SOURCE MIGRATION: supabase/migrations/20261105120000_fosch_media_display_fix.sql


-- ============================================================================
-- BEGIN SOURCE MIGRATION: supabase/migrations/20261112120000_fosch_r2_media.sql
-- ============================================================================

-- FOSCH Cloudflare R2 media storage.
-- Product metadata, approvals and submission evidence remain in Supabase.
-- Existing Supabase Storage images remain readable during the migration.

begin;

alter table public.fosch_brand_media
  add column if not exists storage_provider text not null default 'supabase';

alter table public.fosch_brand_media
  drop constraint if exists fosch_brand_media_storage_provider_check;

alter table public.fosch_brand_media
  add constraint fosch_brand_media_storage_provider_check
  check (storage_provider in ('supabase', 'r2'));

create table if not exists public.fosch_media_storage_config (
  singleton boolean primary key default true check (singleton),
  r2_public_url text not null,
  updated_at timestamptz not null default now()
);

alter table public.fosch_media_storage_config enable row level security;
revoke all on table public.fosch_media_storage_config from public, anon, authenticated;

create or replace function public.admin_set_fosch_r2_public_url(p_public_url text)
returns boolean
language plpgsql
security definer
set search_path = ''
as $function$
declare
  clean_url text := regexp_replace(btrim(coalesce(p_public_url, '')), '/+$', '');
begin
  if not public.is_fosch_admin() then
    raise exception 'Administrator access required' using errcode = '42501';
  end if;
  if clean_url !~ '^https://[A-Za-z0-9.-]+(:[0-9]{1,5})?$' then
    raise exception 'Invalid R2 public origin';
  end if;

  insert into public.fosch_media_storage_config (singleton, r2_public_url, updated_at)
  values (true, clean_url, now())
  on conflict (singleton) do update
    set r2_public_url = excluded.r2_public_url,
        updated_at = now();
  return true;
end;
$function$;

revoke all on function public.admin_set_fosch_r2_public_url(text) from public, anon;
grant execute on function public.admin_set_fosch_r2_public_url(text) to authenticated;

create or replace function public.admin_set_fosch_brand_logo(
  p_brand_key text,
  p_brand_name text,
  p_object_path text
)
returns text
language plpgsql
security definer
set search_path = ''
as $function$
declare
  clean_key text := lower(btrim(coalesce(p_brand_key, '')));
  clean_path text := btrim(coalesce(p_object_path, ''));
begin
  if not public.is_fosch_admin() then
    raise exception 'Administrator access required' using errcode = '42501';
  end if;
  if clean_key !~ '^[a-z0-9]+(-[a-z0-9]+)*$' or clean_path <> 'brands/' || clean_key || '.jpeg' then
    raise exception 'Invalid brand-logo reference';
  end if;

  insert into public.fosch_brand_media
    (brand_key, brand_name, object_path, storage_provider, updated_at)
  values
    (clean_key, coalesce(nullif(btrim(p_brand_name), ''), clean_key), clean_path, 'r2', now())
  on conflict (brand_key) do update
    set brand_name = excluded.brand_name,
        object_path = excluded.object_path,
        storage_provider = 'r2',
        updated_at = now();
  return clean_path;
end;
$function$;

revoke all on function public.admin_set_fosch_brand_logo(text, text, text) from public, anon;
grant execute on function public.admin_set_fosch_brand_logo(text, text, text) to authenticated;

create or replace function public.get_fosch_public_brand_logo(p_brand_key text)
returns text
language sql
stable
security definer
set search_path = ''
as $function$
  select case
    when bm.storage_provider = 'r2' and cfg.r2_public_url is not null
      then cfg.r2_public_url || '/' || bm.object_path
    else 'https://mqgvagftvuybietazbdc.supabase.co/storage/v1/object/public/fosch-public-product-media/' || bm.object_path
  end
  from public.fosch_brand_media bm
  left join public.fosch_media_storage_config cfg on cfg.singleton = true
  where bm.brand_key = lower(btrim(coalesce(p_brand_key, '')))
  limit 1;
$function$;

revoke all on function public.get_fosch_public_brand_logo(text) from public;
grant execute on function public.get_fosch_public_brand_logo(text) to anon, authenticated;

create or replace function public.get_fosch_public_product_media(p_barcode text, p_country text)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $function$
with candidates as (
  select
    s.updated_at,
    s.submitted_at,
    s.brand as submission_brand,
    case
      when jsonb_typeof(s.payload->'suggestedProduct') = 'object'
      then s.payload->'suggestedProduct'
      else s.payload
    end as product_payload,
    coalesce(
      nullif(s.barcode, ''),
      nullif(s.payload->>'barcode', ''),
      nullif(s.payload->'suggestedProduct'->>'barcode', '')
    ) as product_barcode,
    upper(coalesce(
      nullif(s.country_code, ''),
      nullif(s.payload->>'country', ''),
      nullif(s.payload->'suggestedProduct'->>'country', '')
    )) as product_country
  from public.product_submissions s
  where s.status = 'approved'
    and s.submission_type in ('product', 'product_correction', 'correction')
),
matched as (
  select *
  from candidates
  where product_barcode = nullif(btrim(p_barcode), '')
    and product_country = upper(nullif(btrim(p_country), ''))
),
brand_choice as (
  select coalesce(
    nullif(product_payload->>'brand', ''),
    nullif(product_payload->>'brands', ''),
    nullif(submission_brand, '')
  ) as brand
  from matched
  where coalesce(
    nullif(product_payload->>'brand', ''),
    nullif(product_payload->>'brands', ''),
    nullif(submission_brand, '')
  ) is not null
  order by updated_at desc nulls last, submitted_at desc
  limit 1
),
photo_rows as (
  select m.updated_at, photo.value as url, photo.ordinality as ordinal
  from matched m
  cross join lateral jsonb_array_elements_text(
    case when jsonb_typeof(m.product_payload->'officialPhotos') = 'array'
      then m.product_payload->'officialPhotos' else '[]'::jsonb end
  ) with ordinality as photo(value, ordinality)
  union all
  select m.updated_at, m.product_payload->>'officialPhoto' as url, 0::bigint as ordinal
  from matched m
  where coalesce(m.product_payload->>'officialPhoto', '') <> ''
),
unique_photos as (
  select photos.url, max(photos.updated_at) as latest_at, min(photos.ordinal) as ordinal
  from photo_rows photos
  where
    photos.url like 'https://mqgvagftvuybietazbdc.supabase.co/storage/v1/object/public/fosch-public-product-media/%'
    or exists (
      select 1
      from public.fosch_media_storage_config cfg
      where cfg.singleton = true
        and left(photos.url, length(cfg.r2_public_url) + 1) = cfg.r2_public_url || '/'
        and substring(photos.url from length(cfg.r2_public_url) + 2) like 'products/%'
    )
  group by photos.url
),
top_photos as (
  select url, latest_at, ordinal
  from unique_photos
  order by latest_at desc nulls last, ordinal
  limit 5
),
photo_list as (
  select
    coalesce(jsonb_agg(to_jsonb(url) order by latest_at desc nulls last, ordinal), '[]'::jsonb) as photos,
    coalesce((array_agg(url order by latest_at desc nulls last, ordinal))[1], '') as first_photo
  from top_photos
)
select jsonb_build_object(
  'brand', coalesce((select brand from brand_choice), ''),
  'r2PublicUrl', coalesce((select cfg.r2_public_url from public.fosch_media_storage_config cfg where cfg.singleton = true), ''),
  'officialPhoto', photo_list.first_photo,
  'officialPhotos', photo_list.photos
)
from photo_list
where exists (select 1 from matched);
$function$;

revoke all on function public.get_fosch_public_product_media(text, text) from public;
grant execute on function public.get_fosch_public_product_media(text, text) to anon, authenticated;

commit;

-- END SOURCE MIGRATION: supabase/migrations/20261112120000_fosch_r2_media.sql


-- ============================================================================
-- BEGIN SOURCE MIGRATION: supabase/migrations/20261112130000_fosch_r2_media_migration_finalize.sql
-- ============================================================================

-- Temporary FOSCH R2 migration finalizer.
-- Run after 20261112120000_fosch_r2_media.sql and before using fosch-media-migration.
-- It only changes references for paths already copied and SHA-256 verified by the migration function.

begin;

create or replace function public.fosch_rewrite_media_json(p_value jsonb, p_mapping jsonb)
returns jsonb
language plpgsql
immutable
set search_path = ''
as $function$
declare
  result jsonb;
begin
  if p_value is null then
    return null;
  elsif jsonb_typeof(p_value) = 'object' then
    select coalesce(jsonb_object_agg(e.key, public.fosch_rewrite_media_json(e.value, p_mapping)), '{}'::jsonb)
      into result
    from jsonb_each(p_value) as e;
    return result;
  elsif jsonb_typeof(p_value) = 'array' then
    select coalesce(jsonb_agg(public.fosch_rewrite_media_json(e.value, p_mapping) order by e.ordinality), '[]'::jsonb)
      into result
    from jsonb_array_elements(p_value) with ordinality as e(value, ordinality);
    return result;
  elsif jsonb_typeof(p_value) = 'string' then
    return coalesce(p_mapping -> (p_value #>> '{}'), p_value);
  end if;
  return p_value;
end;
$function$;

revoke all on function public.fosch_rewrite_media_json(jsonb, jsonb) from public, anon, authenticated;

create or replace function public.admin_finalize_fosch_r2_media_migration(p_object_paths text[])
returns jsonb
language plpgsql
security definer
set search_path = ''
as $function$
declare
  clean_paths text[];
  r2_base text;
  old_base constant text := 'https://mqgvagftvuybietazbdc.supabase.co/storage/v1/object/public/fosch-public-product-media/';
  mappings jsonb;
  product_rows integer := 0;
  brand_rows integer := 0;
begin
  if not public.is_fosch_admin() then
    raise exception 'Administrator access required' using errcode = '42501';
  end if;
  if p_object_paths is null or cardinality(p_object_paths) < 1 or cardinality(p_object_paths) > 100 then
    raise exception 'Supply between 1 and 100 verified media paths' using errcode = '22023';
  end if;

  select array_agg(distinct paths.path) into clean_paths
  from unnest(p_object_paths) as paths(path)
  where paths.path ~ '^products/(?:[A-Z]{2}|INT)/[A-Za-z0-9_-]{1,80}/[0-9a-f]{16}[.](?:jpeg|png|webp)$'
     or paths.path ~ '^brands/[a-z0-9]+(-[a-z0-9]+)*[.]jpeg$';
  if coalesce(cardinality(clean_paths), 0) <> cardinality(p_object_paths) then
    raise exception 'One or more supplied paths are not valid FOSCH media paths' using errcode = '22023';
  end if;

  select c.r2_public_url into r2_base
  from public.fosch_media_storage_config c
  where c.singleton = true;
  if r2_base is null then
    raise exception 'The R2 public URL has not been configured' using errcode = '55000';
  end if;

  select coalesce(jsonb_object_agg(old_base || paths.path, to_jsonb(r2_base || '/' || paths.path)), '{}'::jsonb)
    into mappings
  from unnest(clean_paths) as paths(path);

  update public.product_submissions s
  set payload = public.fosch_rewrite_media_json(s.payload, mappings)
  where s.payload is not null
    and exists (select 1 from jsonb_object_keys(mappings) as m(url) where position(m.url in s.payload::text) > 0);
  get diagnostics product_rows = row_count;

  update public.fosch_brand_media bm
  set storage_provider = 'r2', updated_at = now()
  where bm.object_path = any(clean_paths);
  get diagnostics brand_rows = row_count;

  return jsonb_build_object(
    'updatedSubmissionRows', product_rows,
    'updatedBrandRows', brand_rows,
    'pathsFinalized', cardinality(clean_paths)
  );
end;
$function$;

revoke all on function public.admin_finalize_fosch_r2_media_migration(text[]) from public, anon;
grant execute on function public.admin_finalize_fosch_r2_media_migration(text[]) to authenticated;

commit;

-- END SOURCE MIGRATION: supabase/migrations/20261112130000_fosch_r2_media_migration_finalize.sql


-- ============================================================================
-- BEGIN SOURCE MIGRATION: supabase/migrations/20261112140000_fosch_r2_media_purge_guard.sql
-- ============================================================================

-- Guarded deletion of migrated public-media originals after R2 cutover.
-- This function is called by the temporary migration Edge Function immediately before
-- deleting the source objects. It refuses deletion while any Supabase image URL remains.
begin;

create or replace function public.admin_verify_fosch_r2_media_purge(p_object_paths text[])
returns jsonb
language plpgsql
security definer
set search_path = ''
as $function$
declare
  old_base constant text := 'https://mqgvagftvuybietazbdc.supabase.co/storage/v1/object/public/fosch-public-product-media/';
  invalid_paths integer := 0;
  distinct_paths integer := 0;
  legacy_submission_rows integer := 0;
  legacy_brand_rows integer := 0;
begin
  if not public.is_fosch_admin() then
    raise exception 'Administrator access required' using errcode = '42501';
  end if;
  if p_object_paths is null or cardinality(p_object_paths) < 1 or cardinality(p_object_paths) > 1000 then
    raise exception 'Supply between 1 and 1000 migrated media paths' using errcode = '22023';
  end if;

  select count(*) into invalid_paths
  from unnest(p_object_paths) as paths(path)
  where paths.path !~ '^products/(?:[A-Z]{2}|INT)/[A-Za-z0-9_-]{1,80}/[0-9a-f]{16}[.](?:jpeg|png|webp)$'
    and paths.path !~ '^brands/[a-z0-9]+(-[a-z0-9]+)*[.]jpeg$';

  select count(distinct paths.path) into distinct_paths
  from unnest(p_object_paths) as paths(path);

  if invalid_paths <> 0 or distinct_paths <> cardinality(p_object_paths) then
    raise exception 'One or more paths are invalid or duplicated' using errcode = '22023';
  end if;

  select count(*) into legacy_submission_rows
  from public.product_submissions s
  where s.payload is not null
    and position(old_base in s.payload::text) > 0;

  select count(*) into legacy_brand_rows
  from public.fosch_brand_media bm
  where bm.storage_provider <> 'r2';

  return jsonb_build_object(
    'safeToDelete', legacy_submission_rows = 0 and legacy_brand_rows = 0,
    'legacySubmissionRows', legacy_submission_rows,
    'legacyBrandRows', legacy_brand_rows,
    'verifiedPathCount', distinct_paths
  );
end;
$function$;

revoke all on function public.admin_verify_fosch_r2_media_purge(text[]) from public, anon;
grant execute on function public.admin_verify_fosch_r2_media_purge(text[]) to authenticated;

commit;

-- END SOURCE MIGRATION: supabase/migrations/20261112140000_fosch_r2_media_purge_guard.sql


-- ============================================================================
-- BEGIN SOURCE MIGRATION: supabase/migrations/20261112150000_fosch_r2_embedded_media_url_fix.sql
-- ============================================================================

-- Repair embedded legacy media URLs in submission JSON before the guarded R2 purge.
-- Only exact Supabase URLs for paths supplied by the verified migration are rewritten.

begin;

create or replace function public.fosch_rewrite_media_json(p_value jsonb, p_mapping jsonb)
returns jsonb
language plpgsql
immutable
set search_path = ''
as $function$
declare
  result jsonb;
  result_text text;
  mapping_row record;
begin
  if p_value is null then
    return null;
  elsif jsonb_typeof(p_value) = 'object' then
    select coalesce(jsonb_object_agg(e.key, public.fosch_rewrite_media_json(e.value, p_mapping)), '{}'::jsonb)
      into result
    from jsonb_each(p_value) as e;
    return result;
  elsif jsonb_typeof(p_value) = 'array' then
    select coalesce(jsonb_agg(public.fosch_rewrite_media_json(e.value, p_mapping) order by e.ordinality), '[]'::jsonb)
      into result
    from jsonb_array_elements(p_value) with ordinality as e(value, ordinality);
    return result;
  elsif jsonb_typeof(p_value) = 'string' then
    result_text := p_value #>> '{}';

    -- Replace mapped URLs wherever they occur inside this string, not only
    -- when the string consists solely of the URL.
    for mapping_row in
      select m.key as old_url, m.value #>> '{}' as new_url
      from jsonb_each(p_mapping) as m
    loop
      result_text := replace(result_text, mapping_row.old_url, mapping_row.new_url);
    end loop;

    return to_jsonb(result_text);
  end if;

  return p_value;
end;
$function$;

revoke all on function public.fosch_rewrite_media_json(jsonb, jsonb) from public, anon, authenticated;

commit;

-- END SOURCE MIGRATION: supabase/migrations/20261112150000_fosch_r2_embedded_media_url_fix.sql


-- ============================================================================
-- BEGIN SOURCE MIGRATION: supabase/migrations/20261112160000_fosch_r2_orphan_reference_repair.sql
-- ============================================================================

-- Discover valid legacy public-media paths still referenced by submissions.
-- Some source objects may already be absent from Supabase while their verified
-- copies remain in R2. The migration Edge Function checks those R2 objects
-- before using this list to rewrite their database URLs.

begin;

create or replace function public.admin_list_fosch_r2_legacy_media_paths()
returns text[]
language plpgsql
security definer
set search_path = ''
as $function$
declare
  legacy_paths text[];
begin
  if not public.is_fosch_admin() then
    raise exception 'Administrator access required' using errcode = '42501';
  end if;

  select coalesce(
    array_agg(distinct matches.captures[1] order by matches.captures[1]),
    '{}'::text[]
  )
  into legacy_paths
  from public.product_submissions s
  cross join lateral regexp_matches(
    s.payload::text,
    'https://mqgvagftvuybietazbdc\.supabase\.co/storage/v1/object/public/fosch-public-product-media/((?:products/(?:[A-Z]{2}|INT)/[A-Za-z0-9_-]{1,80}/[0-9a-f]{16}\.(?:jpeg|png|webp)|brands/[a-z0-9]+(?:-[a-z0-9]+)*\.jpeg))',
    'g'
  ) as matches(captures);

  return coalesce(legacy_paths, '{}'::text[]);
end;
$function$;

revoke all on function public.admin_list_fosch_r2_legacy_media_paths() from public, anon;
grant execute on function public.admin_list_fosch_r2_legacy_media_paths() to authenticated;

commit;

-- END SOURCE MIGRATION: supabase/migrations/20261112160000_fosch_r2_orphan_reference_repair.sql


-- ============================================================================
-- BEGIN SOURCE MIGRATION: supabase/migrations/20261112170000_fosch_r2_media_hash_reconciliation.sql
-- ============================================================================

-- Permit the guarded R2 migration to repair a stale legacy filename
-- when content fingerprints prove the correct object within the same product folder.
-- The Edge Function must verify the destination object's FOSCH fingerprint before
-- calling this admin-only RPC.

begin;

create or replace function public.admin_finalize_fosch_r2_media_reference_mappings(
  p_path_mappings jsonb
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $function$
declare
  old_base constant text := 'https://mqgvagftvuybietazbdc.supabase.co/storage/v1/object/public/fosch-public-product-media/';
  r2_base text;
  mapping_row record;
  mappings jsonb;
  updated_rows integer := 0;
  mapping_count integer := 0;
  source_path text;
  destination_path text;
begin
  if not public.is_fosch_admin() then
    raise exception 'Administrator access required' using errcode = '42501';
  end if;

  if p_path_mappings is null or jsonb_typeof(p_path_mappings) <> 'object' then
    raise exception 'Supply a JSON object containing verified media path mappings' using errcode = '22023';
  end if;

  select count(*)::integer
  into mapping_count
  from jsonb_object_keys(p_path_mappings);

  if mapping_count < 1 or mapping_count > 100 then
    raise exception 'Supply between 1 and 100 verified media path mappings' using errcode = '22023';
  end if;

  for mapping_row in
    select entry.key as source_path, entry.value #>> '{}' as destination_path
    from jsonb_each(p_path_mappings) as entry
  loop
    if jsonb_typeof(p_path_mappings -> mapping_row.source_path) <> 'string'
       or mapping_row.destination_path is null then
      raise exception 'Every media path mapping must have a string destination path' using errcode = '22023';
    end if;
    source_path := mapping_row.source_path;
    destination_path := mapping_row.destination_path;

    if source_path !~ '^products/(?:[A-Z]{2}|INT)/[A-Za-z0-9_-]{1,80}/[0-9a-f]{16}[.](?:jpeg|png|webp)$'
       or destination_path !~ '^products/(?:[A-Z]{2}|INT)/[A-Za-z0-9_-]{1,80}/[0-9a-f]{16}[.](?:jpeg|png|webp)$'
       or split_part(source_path, '/', 1) <> split_part(destination_path, '/', 1)
       or split_part(source_path, '/', 2) <> split_part(destination_path, '/', 2)
       or split_part(source_path, '/', 3) <> split_part(destination_path, '/', 3) then
      raise exception 'Mappings must connect valid product-photo paths within the same market and barcode folder' using errcode = '22023';
    end if;

  end loop;

  select cfg.r2_public_url into r2_base
  from public.fosch_media_storage_config cfg
  where cfg.singleton = true;

  if r2_base is null then
    raise exception 'The R2 public URL has not been configured' using errcode = '55000';
  end if;

  select coalesce(
    jsonb_object_agg(
      old_base || entry.key,
      to_jsonb(r2_base || '/' || (entry.value #>> '{}'))
    ),
    '{}'::jsonb
  )
  into mappings
  from jsonb_each(p_path_mappings) as entry;

  update public.product_submissions submission
  set payload = public.fosch_rewrite_media_json(submission.payload, mappings),
      updated_at = now()
  where submission.payload is not null
    and exists (
      select 1
      from jsonb_object_keys(mappings) as mapping(url)
      where position(mapping.url in submission.payload::text) > 0
    );

  get diagnostics updated_rows = row_count;

  return jsonb_build_object(
    'updatedSubmissionRows', updated_rows,
    'verifiedPathMappings', mapping_count
  );
end;
$function$;

revoke all on function public.admin_finalize_fosch_r2_media_reference_mappings(jsonb) from public, anon;
grant execute on function public.admin_finalize_fosch_r2_media_reference_mappings(jsonb) to authenticated;

commit;

-- END SOURCE MIGRATION: supabase/migrations/20261112170000_fosch_r2_media_hash_reconciliation.sql


-- ============================================================================
-- BEGIN SOURCE MIGRATION: supabase/migrations/20261112180000_fosch_r2_media_hash_reconciliation_key_count.sql
-- ============================================================================

-- Repair the hash-reconciliation RPC after the original migration was applied.
-- PostgreSQL has jsonb_object_keys(), but no jsonb_object_length() function.

-- Permit the guarded R2 migration to repair a stale legacy filename
-- when content fingerprints prove the correct object within the same product folder.
-- The Edge Function must verify the destination object's FOSCH fingerprint before
-- calling this admin-only RPC.

begin;

create or replace function public.admin_finalize_fosch_r2_media_reference_mappings(
  p_path_mappings jsonb
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $function$
declare
  old_base constant text := 'https://mqgvagftvuybietazbdc.supabase.co/storage/v1/object/public/fosch-public-product-media/';
  r2_base text;
  mapping_row record;
  mappings jsonb;
  updated_rows integer := 0;
  mapping_count integer := 0;
  source_path text;
  destination_path text;
begin
  if not public.is_fosch_admin() then
    raise exception 'Administrator access required' using errcode = '42501';
  end if;

  if p_path_mappings is null or jsonb_typeof(p_path_mappings) <> 'object' then
    raise exception 'Supply a JSON object containing verified media path mappings' using errcode = '22023';
  end if;

  select count(*)::integer
  into mapping_count
  from jsonb_object_keys(p_path_mappings);

  if mapping_count < 1 or mapping_count > 100 then
    raise exception 'Supply between 1 and 100 verified media path mappings' using errcode = '22023';
  end if;

  for mapping_row in
    select entry.key as source_path, entry.value #>> '{}' as destination_path
    from jsonb_each(p_path_mappings) as entry
  loop
    if jsonb_typeof(p_path_mappings -> mapping_row.source_path) <> 'string'
       or mapping_row.destination_path is null then
      raise exception 'Every media path mapping must have a string destination path' using errcode = '22023';
    end if;
    source_path := mapping_row.source_path;
    destination_path := mapping_row.destination_path;

    if source_path !~ '^products/(?:[A-Z]{2}|INT)/[A-Za-z0-9_-]{1,80}/[0-9a-f]{16}[.](?:jpeg|png|webp)$'
       or destination_path !~ '^products/(?:[A-Z]{2}|INT)/[A-Za-z0-9_-]{1,80}/[0-9a-f]{16}[.](?:jpeg|png|webp)$'
       or split_part(source_path, '/', 1) <> split_part(destination_path, '/', 1)
       or split_part(source_path, '/', 2) <> split_part(destination_path, '/', 2)
       or split_part(source_path, '/', 3) <> split_part(destination_path, '/', 3) then
      raise exception 'Mappings must connect valid product-photo paths within the same market and barcode folder' using errcode = '22023';
    end if;

  end loop;

  select cfg.r2_public_url into r2_base
  from public.fosch_media_storage_config cfg
  where cfg.singleton = true;

  if r2_base is null then
    raise exception 'The R2 public URL has not been configured' using errcode = '55000';
  end if;

  select coalesce(
    jsonb_object_agg(
      old_base || entry.key,
      to_jsonb(r2_base || '/' || (entry.value #>> '{}'))
    ),
    '{}'::jsonb
  )
  into mappings
  from jsonb_each(p_path_mappings) as entry;

  update public.product_submissions submission
  set payload = public.fosch_rewrite_media_json(submission.payload, mappings),
      updated_at = now()
  where submission.payload is not null
    and exists (
      select 1
      from jsonb_object_keys(mappings) as mapping(url)
      where position(mapping.url in submission.payload::text) > 0
    );

  get diagnostics updated_rows = row_count;

  return jsonb_build_object(
    'updatedSubmissionRows', updated_rows,
    'verifiedPathMappings', mapping_count
  );
end;
$function$;

revoke all on function public.admin_finalize_fosch_r2_media_reference_mappings(jsonb) from public, anon;
grant execute on function public.admin_finalize_fosch_r2_media_reference_mappings(jsonb) to authenticated;

commit;

-- END SOURCE MIGRATION: supabase/migrations/20261112180000_fosch_r2_media_hash_reconciliation_key_count.sql
