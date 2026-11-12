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
