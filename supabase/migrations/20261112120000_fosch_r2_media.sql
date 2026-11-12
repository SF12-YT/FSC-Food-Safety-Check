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
  'officialPhoto', photo_list.first_photo,
  'officialPhotos', photo_list.photos
)
from photo_list
where exists (select 1 from matched);
$function$;

revoke all on function public.get_fosch_public_product_media(text, text) from public;
grant execute on function public.get_fosch_public_product_media(text, text) to anon, authenticated;

commit;
