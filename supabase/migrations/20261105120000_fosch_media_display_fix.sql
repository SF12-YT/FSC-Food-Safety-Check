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
