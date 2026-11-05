# YouTube daily suggestion pipeline

One suggestion per day, valid from 18:00 until 18:00 the next day. The PC pipeline scrapes the
logged-in YouTube account, lets Command Code pick a candidate, and writes the result to Supabase.
The Tabslify app reads it through the `get_suggestion` Edge Function.

## Components

| File | Purpose |
|---|---|
| `yt_scrape.py` | Playwright Chromium with the persistent profile in `profile/`. Reads history (~50), likes (~30) and the YouTube homepage (~40 candidates), filters Shorts/live/ads/already watched, writes `raw/YYYY-MM-DD.json`. |
| `pick.py` | Reads the newest raw file, removes IDs suggested in the last 14 days, asks Command Code for exactly one candidate, falls back to the most-liked channel. Writes `state/pick.json`. |
| `push.py` | Writes the pick into `public.daily_suggestion` with the service key from `.env`. Updates the local recent cache. |
| `run.sh` | Runs scrape, pick and push sequentially. A failed step leaves the previous suggestion untouched. |
| `systemd/` | User timer at 17:55 with `Persistent=true`. |
| `supabase/` | Migration SQL and the `get_suggestion` Edge Function source (also applied to Supabase project `vfctgombfsgirctobpca`). |

## Setup

```bash
cd ~/AndroidStudioProjects/Cloud1/yt-suggest
uv venv .venv
uv pip install --python .venv/bin/python playwright
.venv/bin/python -m playwright install chromium
cp .env.example .env
chmod 600 .env
```

Fill `.env`:

```
SUPABASE_URL=https://<project>.supabase.co
SUPABASE_SERVICE_ROLE_KEY=<service role key>
COMMANDCODE_API_KEY=<Command Code API key>
COMMANDCODE_BASE_URL=https://api.commandcode.ai/provider/v1
COMMANDCODE_MODEL=deepseek/deepseek-v4.1-flash
YT_LOGIN_TIMEOUT=0
```

The service key and the Command Code key live only in `.env` (gitignored) and never in the
repository or the Android app.

### First login

The first run must be interactive because YouTube requires a real login:

```bash
YT_LOGIN_TIMEOUT=600 .venv/bin/python yt_scrape.py
```

The script opens a visible Chromium window with the `profile/` directory. Log in once. Later runs
reuse the profile. For unattended runs `run.sh` sets `YT_LOGIN_TIMEOUT=0`, so an expired login is
logged and the old suggestion stays in place.

An existing Firefox login can be imported once as a shortcut:

```bash
.venv/bin/python yt_scrape.py --seed-cookies /path/to/profile/cookies.sqlite
```

Firefox stores the cookies unencrypted; the values are only copied into the local Chromium profile.

### Manual run

```bash
./run.sh
tail -n 30 logs/run.log
```

`run.sh` uses the scheduled window (valid from today 18:00). For an immediate manual
suggestion outside that window:

```bash
.venv/bin/python pick.py
.venv/bin/python push.py --valid-from now
```

The scraper can rebuild `history`/`likes`/`candidates` from the saved raw debug data without
opening YouTube again:

```bash
.venv/bin/python yt_scrape.py --reparse raw/YYYY-MM-DD.json
```

## systemd user timer

```bash
mkdir -p ~/.config/systemd/user
cp systemd/yt-suggest.service systemd/yt-suggest.timer ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now yt-suggest.timer
systemctl --user list-timers yt-suggest.timer
```

The units assume the repository is at `~/AndroidStudioProjects/Cloud1/yt-suggest`. Adjust
`WorkingDirectory` and `ExecStart` when the path differs. Logs:

```bash
journalctl --user -u yt-suggest.service -n 100
```

`Persistent=true` runs a missed execution after the PC was off. The timer fires at 17:55 and the
suggestion window starts at 18:00 local time.

## Supabase

Migration: `supabase/20261105_daily_suggestion.sql`

* `public.daily_suggestion` with `id`, `valid_from`, `valid_until`, `video_id`, `title`, `channel`,
  `thumbnail_url`, `duration` (seconds), `reason`, `created_at`.
* RLS: `anon` and `authenticated` may only `SELECT`. `INSERT`/`UPDATE`/`DELETE` are revoked; the
  service role bypasses RLS and is the only writer.
* A unique index on `valid_from` makes the daily push idempotent (upsert).

Edge Function: `supabase/functions/get_suggestion/index.ts`

* Returns the newest row with `valid_from <= now() < valid_until` as `{"suggestion": {...}}`.
* Deployed with `verify_jwt: false` because the app sends the publishable key, matching the other
  read-only functions of this project.
* Accepts `GET` and `POST` because `supabase-kt` `functions.invoke` uses POST.

## Assumptions

* Timezone is the PC's local timezone (`Europe/Berlin` here). The day anchor is always today at
  18:00; running shortly before 18:00 creates a row that becomes valid at 18:00.
* History entries without a progress overlay are treated as finished; entries with
  `percentDurationWatched >= 90` are finished, everything else is started.
* Shorts are filtered by `/shorts/` URL or reel endpoint. Live/premiere items are filtered by
  renderer markers and badge text. Ads are filtered by ad renderer markers.
* View counts are approximated from German/English text (`1,2 Mio.`, `120.000`, `1.2M`) and stored
  as integers; the original text stays in `views_text`.
* The AI default model is a cheap, fast Command Code text model. The prompt keeps only titles,
  channels and candidate IDs, so it stays compact. Channel frequency is summarized separately:
  one history entry counts as one point, a like as four.
* If Command Code fails or returns an invalid ID, the fallback picks a candidate from the channel
  with the most likes. If no liked channel appears in the candidates, the first candidate wins.
* `raw/` contains the extracted items plus the raw `ytInitialData` and DOM HTML for selector
  debugging. It may contain personal viewing data; it is gitignored and pruned after 30 days.
* A failed scrape/pick/push exits non-zero and never deletes the previous Supabase row.
