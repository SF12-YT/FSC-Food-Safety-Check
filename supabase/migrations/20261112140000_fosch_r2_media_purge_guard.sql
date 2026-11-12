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
