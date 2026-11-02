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
