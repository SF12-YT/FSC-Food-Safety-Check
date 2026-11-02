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
