-- FOSCH public, cross-device administrator product media.
-- Apply after the existing FOSCH submission migrations.

insert into storage.buckets (id,name,public,file_size_limit,allowed_mime_types)
values ('fosch-public-product-media','fosch-public-product-media',true,5242880,array['image/jpeg','image/png','image/webp'])
on conflict (id) do update set public=excluded.public,file_size_limit=excluded.file_size_limit,allowed_mime_types=excluded.allowed_mime_types;

drop policy if exists "FOSCH public product media read" on storage.objects;
create policy "FOSCH public product media read" on storage.objects for select to anon,authenticated
using (bucket_id='fosch-public-product-media');

drop policy if exists "FOSCH admins upload public product media" on storage.objects;
create policy "FOSCH admins upload public product media" on storage.objects for insert to authenticated
with check (bucket_id='fosch-public-product-media' and (select public.is_fosch_admin()));

drop policy if exists "FOSCH admins update public product media" on storage.objects;
create policy "FOSCH admins update public product media" on storage.objects for update to authenticated
using (bucket_id='fosch-public-product-media' and (select public.is_fosch_admin()))
with check (bucket_id='fosch-public-product-media' and (select public.is_fosch_admin()));

drop policy if exists "FOSCH admins delete public product media" on storage.objects;
create policy "FOSCH admins delete public product media" on storage.objects for delete to authenticated
using (bucket_id='fosch-public-product-media' and (select public.is_fosch_admin()));

create or replace function public.get_fosch_public_product_media(p_barcode text,p_country text)
returns jsonb language sql stable security definer set search_path=''
as $$
with candidates as (
 select s.updated_at,s.submitted_at,s.brand as submission_brand,
  case when jsonb_typeof(s.payload->'suggestedProduct')='object' then s.payload->'suggestedProduct' else s.payload end as product_payload,
  coalesce(nullif(s.barcode,''),nullif(s.payload->>'barcode',''),nullif(s.payload->'suggestedProduct'->>'barcode','')) as product_barcode,
  upper(coalesce(nullif(s.country_code,''),nullif(s.payload->>'country',''),nullif(s.payload->'suggestedProduct'->>'country',''))) as product_country
 from public.product_submissions s
 where s.status='approved' and s.submission_type in ('product','product_correction','correction')
)
select jsonb_build_object(
 'brand',coalesce(nullif(c.product_payload->>'brand',''),nullif(c.product_payload->>'brands',''),c.submission_brand,''),
 'officialPhoto',case when c.product_payload->>'officialPhoto' like 'https://mqgvagftvuybietazbdc.supabase.co/storage/v1/object/public/fosch-public-product-media/%' then c.product_payload->>'officialPhoto' else '' end,
 'officialPhotos',coalesce((
  select jsonb_agg(to_jsonb(m.url) order by m.ordinal)
  from (
   select src.url,min(src.ordinal) ordinal
   from (
    select photo.value url,photo.ordinality ordinal
    from jsonb_array_elements_text(case when jsonb_typeof(c.product_payload->'officialPhotos')='array' then c.product_payload->'officialPhotos' else '[]'::jsonb end) with ordinality as photo(value,ordinality)
    union all
    select c.product_payload->>'officialPhoto',0
    where c.product_payload->>'officialPhoto' like 'https://mqgvagftvuybietazbdc.supabase.co/storage/v1/object/public/fosch-public-product-media/%'
   ) src
   where src.url like 'https://mqgvagftvuybietazbdc.supabase.co/storage/v1/object/public/fosch-public-product-media/%'
   group by src.url
  ) m
 ),'[]'::jsonb)
)
from candidates c
where c.product_barcode=nullif(btrim(p_barcode),'') and c.product_country=upper(nullif(btrim(p_country),''))
order by c.updated_at desc nulls last,c.submitted_at desc limit 1;
$$;
revoke all on function public.get_fosch_public_product_media(text,text) from public;
grant execute on function public.get_fosch_public_product_media(text,text) to anon,authenticated;
