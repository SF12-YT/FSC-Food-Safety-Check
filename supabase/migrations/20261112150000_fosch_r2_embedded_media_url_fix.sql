-- Repair embedded legacy media URLs in submission JSON before the guarded R2 purge.
-- Only exact Supabase URLs for paths supplied by the verified migration are rewritten.

begin;

create or replace function public.fosch_rewrite_media_json(p_value jsonb, p_mapping jsonb)
returns jsonb
language plpgsql
immutable
set search_path = ''
as $function$
declare
  result jsonb;
  result_text text;
  mapping_row record;
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
    result_text := p_value #>> '{}';

    -- Replace mapped URLs wherever they occur inside this string, not only
    -- when the string consists solely of the URL.
    for mapping_row in
      select m.key as old_url, m.value #>> '{}' as new_url
      from jsonb_each(p_mapping) as m
    loop
      result_text := replace(result_text, mapping_row.old_url, mapping_row.new_url);
    end loop;

    return to_jsonb(result_text);
  end if;

  return p_value;
end;
$function$;

revoke all on function public.fosch_rewrite_media_json(jsonb, jsonb) from public, anon, authenticated;

commit;
