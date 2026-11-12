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
