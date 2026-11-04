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
