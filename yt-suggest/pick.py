#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter
from datetime import datetime, timedelta, timezone
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent
DEFAULT_RAW_DIR = BASE_DIR / "raw"
DEFAULT_STATE = BASE_DIR / "state"
DEFAULT_LOG = BASE_DIR / "logs" / "pick.log"
DEFAULT_ENV = BASE_DIR / ".env"

SYSTEM_PROMPT = (
    "Du bist ein YouTube-Kurator. Du bekommst den Seh- und Like-Geschmack einer Person sowie "
    "eine Liste erlaubter Kandidatenvideos. Waehle genau EIN Kandidatenvideo, das mit grosser "
    "Wahrscheinlichkeit gefaellt. Likes wiegen deutlich schwerer als normales Sehen. Waehle nur "
    "IDs aus der Kandidatenliste und niemals eine gesperrte ID. Antworte ausschliesslich mit "
    'einem JSON-Objekt der Form {"video_id":"...","reason":"kurze deutsche Begruendung"}. '
    "Keine Markdown-Fences, kein zusaetzlicher Text."
)


def log(message: str, logfile: Path | None = None) -> None:
    line = f"{datetime.now().astimezone().isoformat(timespec='seconds')} {message}"
    print(line, flush=True)
    if logfile:
        logfile.parent.mkdir(parents=True, exist_ok=True)
        with logfile.open("a", encoding="utf-8") as handle:
            handle.write(line + "\n")


def load_env(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if path.exists():
        for raw in path.read_text(encoding="utf-8").splitlines():
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            if line.startswith("export "):
                line = line[7:].strip()
            key, separator, value = line.partition("=")
            if not separator:
                continue
            value = value.strip()
            if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
                value = value[1:-1]
            values[key.strip()] = value
    for key, value in os.environ.items():
        if key in (
            "SUPABASE_URL",
            "SUPABASE_SERVICE_ROLE_KEY",
            "SUPABASE_KEY",
            "COMMANDCODE_API_KEY",
            "COMMANDCODE_BASE_URL",
            "COMMANDCODE_MODEL",
        ):
            values[key] = value
    return values


def latest_raw(raw_dir: Path, explicit: Path | None) -> Path:
    if explicit:
        return explicit
    files = sorted(raw_dir.glob("*.json"))
    if not files:
        raise FileNotFoundError(f"no raw json in {raw_dir}")
    return files[-1]


def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def supabase_config(env: dict[str, str]) -> tuple[str, str] | None:
    url = env.get("SUPABASE_URL", "").rstrip("/")
    key = env.get("SUPABASE_SERVICE_ROLE_KEY") or env.get("SUPABASE_KEY")
    if not url or not key:
        return None
    return url, key


def excluded_from_supabase(env: dict[str, str], days: int, logfile: Path) -> list[str]:
    config = supabase_config(env)
    if not config:
        log("supabase credentials missing, skipping 14-day exclusion from server", logfile)
        return []
    url, key = config
    since = (datetime.now(timezone.utc) - timedelta(days=days)).isoformat()
    query = urllib.parse.urlencode(
        {
            "select": "video_id,valid_from",
            "valid_from": f"gte.{since}",
            "order": "valid_from.desc",
        }
    )
    request = urllib.request.Request(
        f"{url}/rest/v1/daily_suggestion?{query}",
        headers={
            "apikey": key,
            "Authorization": f"Bearer {key}",
            "Accept": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=25) as response:
            rows = json.loads(response.read().decode("utf-8"))
        return [row["video_id"] for row in rows if row.get("video_id")]
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8", errors="replace")[:300]
        log(f"supabase exclusion failed HTTP {error.code}: {body}", logfile)
    except Exception as error:
        log(f"supabase exclusion failed {type(error).__name__}: {error}", logfile)
    return []


def excluded_from_local(state_dir: Path, days: int, logfile: Path) -> list[str]:
    path = state_dir / "recent.json"
    if not path.exists():
        return []
    try:
        rows = load_json(path)
    except Exception as error:
        log(f"local exclusion unreadable: {error}", logfile)
        return []
    cutoff = datetime.now().astimezone() - timedelta(days=days)
    result = []
    for row in rows if isinstance(rows, list) else []:
        try:
            when = datetime.fromisoformat(row.get("pushed_at", ""))
            if when.tzinfo is None:
                when = when.astimezone()
            if when >= cutoff and row.get("video_id"):
                result.append(row["video_id"])
        except Exception:
            continue
    return result


def parse_duration_seconds(text: str) -> int | None:
    value = (text or "").strip()
    if not re.fullmatch(r"\d+(?::\d+){1,2}", value):
        return None
    seconds = 0
    for part in value.split(":"):
        seconds = seconds * 60 + int(part)
    return seconds


def parse_views(text: str) -> int | None:
    match = re.search(r"([\d.,]+)\s*(Mio\.?|Millionen|Tsd\.?|Tausend|K|M)?", text or "", re.I)
    if not match:
        return None
    raw = match.group(1).replace(".", "").replace(",", ".")
    try:
        number = float(raw)
    except ValueError:
        return None
    unit = (match.group(2) or "").lower()
    if unit in ("mio.", "mio", "millionen", "m"):
        number *= 1_000_000
    elif unit in ("tsd.", "tsd", "tausend", "k"):
        number *= 1_000
    return int(number)


def channel_key(value: str) -> str:
    value = re.sub(r"\s+", " ", (value or "").strip().lower())
    value = value.replace("✓", "").strip()
    return value


def compact_history(rows: list[dict], limit: int) -> str:
    lines = []
    for row in rows[:limit]:
        title = (row.get("title") or "").strip()
        channel = (row.get("channel") or "").strip()
        if not title:
            continue
        status = row.get("status") or ""
        suffix = f" [{status}]" if status else ""
        lines.append(f"- {title} — {channel}{suffix}")
    return "\n".join(lines) if lines else "- (keine Daten)"


def compact_likes(rows: list[dict], limit: int) -> str:
    lines = []
    for row in rows[:limit]:
        title = (row.get("title") or "").strip()
        channel = (row.get("channel") or "").strip()
        if not title:
            continue
        lines.append(f"- {title} — {channel}")
    return "\n".join(lines) if lines else "- (keine Daten)"


def compact_candidates(rows: list[dict]) -> str:
    lines = []
    for row in rows:
        duration = row.get("duration_text") or "?"
        views = row.get("views_text") or "?"
        lines.append(
            f"- {row.get('video_id')} | {row.get('title')} | {row.get('channel')} | {duration} | {views}"
        )
    return "\n".join(lines)


def build_prompt(raw: dict, candidates: list[dict], excluded: list[str]) -> str:
    history = raw.get("history") or []
    likes = raw.get("likes") or []
    parts = [
        "GESCHEN (Gewicht 1, letzte ~50):",
        compact_history(history, 50),
        "",
        "GELIKT (Gewicht 4, starke Vorliebe):",
        compact_likes(likes, 30),
        "",
        "GESPERRT (in den letzten 14 Tagen schon vorgeschlagen, nicht waehlen):",
        ", ".join(excluded) if excluded else "(keine)",
        "",
        "KANDIDATEN (nur aus dieser Liste waehlen):",
        compact_candidates(candidates),
        "",
        "Antworte nur mit JSON: {\"video_id\":\"...\",\"reason\":\"...\"}",
    ]
    return "\n".join(parts)


def call_commandcode(prompt: str, env: dict[str, str], logfile: Path) -> str:
    key = env.get("COMMANDCODE_API_KEY", "").strip()
    if not key:
        raise RuntimeError("COMMANDCODE_API_KEY missing")
    base = env.get("COMMANDCODE_BASE_URL", "https://api.commandcode.ai/provider/v1").rstrip("/")
    model = env.get("COMMANDCODE_MODEL", "deepseek/deepseek-v4.1-flash")
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": prompt},
        ],
        "temperature": 0.3,
        "max_tokens": 500,
    }
    request = urllib.request.Request(
        f"{base}/chat/completions",
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {key}",
            "Accept": "application/json",
            "User-Agent": "opencode/1.1.0",
        },
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=120) as response:
        data = json.loads(response.read().decode("utf-8"))
    choices = data.get("choices") or []
    if not choices:
        raise RuntimeError("empty choices from CommandCode")
    message = choices[0].get("message") or {}
    content = message.get("content") or ""
    if not content:
        raise RuntimeError("empty content from CommandCode")
    return content


def parse_reply(content: str) -> dict:
    text = content.strip()
    text = re.sub(r"^```(?:json)?", "", text, flags=re.I).strip()
    text = re.sub(r"```$", "", text).strip()
    try:
        return json.loads(text)
    except Exception:
        pass
    start = text.find("{")
    end = text.rfind("}")
    if start >= 0 and end > start:
        return json.loads(text[start : end + 1])
    raise ValueError("no JSON object in model reply")


def fallback_pick(candidates: list[dict], likes: list[dict]) -> tuple[dict, str]:
    counts: Counter[str] = Counter(channel_key(row.get("channel", "")) for row in likes if row.get("channel"))
    for channel, count in counts.most_common():
        for candidate in candidates:
            if channel_key(candidate.get("channel", "")) == channel:
                label = candidate.get("channel") or channel
                return candidate, f"Meistgeliketer Kanal in den Kandidaten: {label} ({count} Likes)"
    candidate = candidates[0]
    return candidate, "Erster Kandidat (kein gelikter Kanal in der Auswahl)"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--raw", type=Path)
    parser.add_argument("--raw-dir", type=Path, default=DEFAULT_RAW_DIR)
    parser.add_argument("--state-dir", type=Path, default=DEFAULT_STATE)
    parser.add_argument("--env", type=Path, default=DEFAULT_ENV)
    parser.add_argument("--log", type=Path, default=DEFAULT_LOG)
    parser.add_argument("--exclude-days", type=int, default=14)
    parser.add_argument("--no-ai", action="store_true")
    args = parser.parse_args()
    try:
        raw_path = latest_raw(args.raw_dir, args.raw)
        raw = load_json(raw_path)
        env = load_env(args.env)
        excluded = set(excluded_from_supabase(env, args.exclude_days, args.log))
        excluded.update(excluded_from_local(args.state_dir, args.exclude_days, args.log))
        candidates = [
            row
            for row in (raw.get("candidates") or [])
            if row.get("video_id") and row["video_id"] not in excluded
        ]
        if not candidates:
            raise RuntimeError("no candidates left after exclusions")
        log(f"raw={raw_path.name} candidates={len(candidates)} excluded={len(excluded)}", args.log)

        method = "fallback"
        model = ""
        candidate = None
        reason = ""
        if not args.no_ai:
            prompt = build_prompt(raw, candidates, sorted(excluded))
            model = env.get("COMMANDCODE_MODEL", "deepseek/deepseek-v4.1-flash")
            last_error = None
            for attempt in range(1, 3):
                try:
                    content = call_commandcode(prompt, env, args.log)
                    selection = parse_reply(content)
                    video_id = str(selection.get("video_id", "")).strip()
                    reason = str(selection.get("reason", "")).strip()
                    candidate = next((row for row in candidates if row.get("video_id") == video_id), None)
                    if candidate and reason:
                        method = "ai"
                        break
                    last_error = f"invalid video_id or reason: {selection}"
                    log(f"attempt {attempt}: {last_error}", args.log)
                except Exception as error:
                    last_error = f"{type(error).__name__}: {error}"
                    log(f"attempt {attempt}: commandcode failed: {last_error}", args.log)
                candidate = None
            if candidate is None:
                log(f"AI selection failed, using fallback: {last_error}", args.log)

        if candidate is None:
            candidate, reason = fallback_pick(candidates, raw.get("likes") or [])

        now = datetime.now().astimezone()
        result = {
            "picked_at": now.isoformat(timespec="seconds"),
            "date": now.strftime("%Y-%m-%d"),
            "method": method,
            "model": model,
            "video_id": candidate.get("video_id"),
            "title": candidate.get("title"),
            "channel": candidate.get("channel"),
            "duration": candidate.get("duration") or parse_duration_seconds(candidate.get("duration_text", "")),
            "duration_text": candidate.get("duration_text"),
            "views": candidate.get("views") or parse_views(candidate.get("views_text", "")),
            "views_text": candidate.get("views_text"),
            "thumbnail_url": candidate.get("thumbnail_url"),
            "url": candidate.get("url") or f"https://youtu.be/{candidate.get('video_id')}",
            "reason": reason[:240],
            "excluded_ids": sorted(excluded),
            "candidate_count": len(candidates),
        }
        state = args.state_dir
        state.mkdir(parents=True, exist_ok=True)
        target = state / "pick.json"
        temp = target.with_suffix(".json.tmp")
        temp.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        temp.replace(target)
        log(
            f"picked {result['video_id']} via {method}: {result['title']} — {result['reason']}",
            args.log,
        )
        return 0
    except Exception as error:
        log(f"ERROR {type(error).__name__}: {error}", args.log)
        return 1


if __name__ == "__main__":
    sys.exit(main())
