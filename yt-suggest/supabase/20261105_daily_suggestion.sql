create table if not exists public.daily_suggestion (
  id bigserial primary key,
  valid_from timestamptz not null,
  valid_until timestamptz not null,
  video_id text not null,
  title text not null,
  channel text,
  thumbnail_url text,
  duration integer,
  reason text,
  created_at timestamptz not null default now(),
  constraint daily_suggestion_window_check check (valid_until > valid_from)
);

create unique index if not exists daily_suggestion_valid_from_key
  on public.daily_suggestion (valid_from);

create index if not exists daily_suggestion_valid_until_idx
  on public.daily_suggestion (valid_until desc, valid_from desc);

alter table public.daily_suggestion enable row level security;

drop policy if exists daily_suggestion_read on public.daily_suggestion;
create policy daily_suggestion_read
  on public.daily_suggestion
  for select
  to anon, authenticated
  using (true);

revoke insert, update, delete on public.daily_suggestion from anon, authenticated;
grant select on public.daily_suggestion to anon, authenticated;
grant all on public.daily_suggestion to service_role;
grant usage, select on sequence public.daily_suggestion_id_seq to service_role;
