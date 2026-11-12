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
