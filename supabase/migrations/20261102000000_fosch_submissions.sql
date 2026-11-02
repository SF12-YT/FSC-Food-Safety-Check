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
