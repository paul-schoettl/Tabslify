create table if not exists public.suggestion_feedback (
  id bigserial primary key,
  video_id text not null,
  feedback_type text not null,
  reason text,
  suggestion_id bigint references public.daily_suggestion(id) on delete set null,
  created_at timestamptz not null default now(),
  constraint suggestion_feedback_type_check check (feedback_type in ('known', 'dislike'))
);

create unique index if not exists suggestion_feedback_video_id_key
  on public.suggestion_feedback (video_id);

alter table public.suggestion_feedback enable row level security;

revoke all on public.suggestion_feedback from anon, authenticated;
grant all on public.suggestion_feedback to service_role;
grant usage, select on sequence public.suggestion_feedback_id_seq to service_role;

create table if not exists public.suggestion_jobs (
  id bigserial primary key,
  video_id text not null,
  feedback_type text not null,
  reason text,
  suggestion_id bigint,
  status text not null default 'pending',
  attempts integer not null default 0,
  result text,
  created_at timestamptz not null default now(),
  handled_at timestamptz,
  constraint suggestion_jobs_status_check check (status in ('pending', 'running', 'done', 'failed'))
);

create index if not exists suggestion_jobs_pending_idx
  on public.suggestion_jobs (status, id);

alter table public.suggestion_jobs enable row level security;

revoke all on public.suggestion_jobs from anon, authenticated;
grant all on public.suggestion_jobs to service_role;
grant usage, select on sequence public.suggestion_jobs_id_seq to service_role;
