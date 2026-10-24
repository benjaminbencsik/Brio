-- Run once in the SQL editor of Brio's Supabase project.
-- This table contains catalog identities, watchlists, and progress only.
create table if not exists public.library_entries (
  user_id uuid not null references auth.users(id) on delete cascade,
  record_id text not null check (length(record_id) between 1 and 250),
  record jsonb not null check (jsonb_typeof(record) = 'object' and length(record::text) <= 16384),
  updated_at bigint not null check (updated_at >= 0),
  primary key (user_id, record_id)
);

alter table public.library_entries enable row level security;
revoke all on public.library_entries from anon;
grant select, insert, update on public.library_entries to authenticated;

drop policy if exists "Own library" on public.library_entries;
create policy "Own library" on public.library_entries for all to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

-- Atomic last-write-wins merge: an older offline edit cannot replace a newer row.
create or replace function public.merge_library_record(
  p_record_id text, p_record jsonb, p_updated_at bigint
) returns jsonb language plpgsql security invoker set search_path = '' as $$
declare winner jsonb;
begin
  if (select auth.uid()) is null then raise exception 'Authentication required'; end if;
  insert into public.library_entries(user_id, record_id, record, updated_at)
  values ((select auth.uid()), p_record_id, p_record, p_updated_at)
  on conflict (user_id, record_id) do update
    set record = excluded.record, updated_at = excluded.updated_at
    where public.library_entries.updated_at <= excluded.updated_at
  returning record into winner;
  if winner is null then
    select record into winner from public.library_entries
      where user_id = (select auth.uid()) and record_id = p_record_id;
  end if;
  return winner;
end $$;

revoke all on function public.merge_library_record(text, jsonb, bigint) from public, anon;
grant execute on function public.merge_library_record(text, jsonb, bigint) to authenticated;
