#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request
from datetime import datetime, timedelta
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent
DEFAULT_STATE = BASE_DIR / "state"
DEFAULT_LOG = BASE_DIR / "logs" / "push.log"
DEFAULT_ENV = BASE_DIR / ".env"


def log(message: str, logfile: Path | None = None) -> None:
    line = f"{datetime.now().astimezone().isoformat(timespec='seconds')} {message}"
    print(line, flush=True)
    if logfile:
        logfile.parent.mkdir(parents=True, exist_ok=True)
        with logfile.open("a", encoding="utf-8") as handle:
            handle.write(line + "\n")


def load_env(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if not path.exists():
        raise FileNotFoundError(f"env file missing: {path}")
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
    return values


def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def write_atomic(path: Path, value) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_suffix(path.suffix + ".tmp")
    temp.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")
    temp.replace(path)


def push(row: dict, env: dict[str, str], logfile: Path) -> dict:
    url = env.get("SUPABASE_URL", "").rstrip("/")
    key = env.get("SUPABASE_SERVICE_ROLE_KEY", "").strip()
    if not url or not key:
        raise RuntimeError("SUPABASE_URL or SUPABASE_SERVICE_ROLE_KEY missing in .env")
    request = urllib.request.Request(
        f"{url}/rest/v1/daily_suggestion?on_conflict=valid_from",
        data=json.dumps(row).encode("utf-8"),
        headers={
            "apikey": key,
            "Authorization": f"Bearer {key}",
            "Content-Type": "application/json",
            "Accept": "application/json",
            "Prefer": "resolution=merge-duplicates,return=representation",
        },
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        body = response.read().decode("utf-8", errors="replace")
    parsed = json.loads(body) if body.strip() else []
    return parsed[0] if isinstance(parsed, list) and parsed else {}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--state-dir", type=Path, default=DEFAULT_STATE)
    parser.add_argument("--env", type=Path, default=DEFAULT_ENV)
    parser.add_argument("--log", type=Path, default=DEFAULT_LOG)
    args = parser.parse_args()
    try:
        pick = load_json(args.state_dir / "pick.json")
        env = load_env(args.env)
        now = datetime.now().astimezone()
        valid_from = now.replace(hour=18, minute=0, second=0, microsecond=0)
        valid_until = valid_from + timedelta(days=1)
        row = {
            "valid_from": valid_from.isoformat(timespec="seconds"),
            "valid_until": valid_until.isoformat(timespec="seconds"),
            "video_id": pick["video_id"],
            "title": pick.get("title") or "",
            "channel": pick.get("channel") or "",
            "thumbnail_url": pick.get("thumbnail_url") or "",
            "duration": pick.get("duration"),
            "reason": pick.get("reason") or "",
        }
        saved = push(row, env, args.log)
        recent_path = args.state_dir / "recent.json"
        recent = []
        if recent_path.exists():
            try:
                recent = load_json(recent_path)
            except Exception:
                recent = []
        cutoff = now - timedelta(days=60)
        cleaned = []
        for entry in recent if isinstance(recent, list) else []:
            try:
                when = datetime.fromisoformat(entry.get("pushed_at", ""))
                if when.tzinfo is None:
                    when = when.astimezone()
                if when >= cutoff and entry.get("video_id") != row["video_id"]:
                    cleaned.append(entry)
            except Exception:
                continue
        cleaned.append(
            {
                "video_id": row["video_id"],
                "title": row["title"],
                "channel": row["channel"],
                "valid_from": row["valid_from"],
                "valid_until": row["valid_until"],
                "pushed_at": now.isoformat(timespec="seconds"),
                "row_id": saved.get("id"),
            }
        )
        write_atomic(recent_path, cleaned[-120:])
        write_atomic(args.state_dir / "last_push.json", {"row": saved, "local": row})
        log(
            f"pushed id={saved.get('id')} {row['video_id']} valid {row['valid_from']} -> {row['valid_until']}",
            args.log,
        )
        return 0
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8", errors="replace")[:500]
        log(f"ERROR HTTP {error.code}: {body}", args.log)
        return 1
    except Exception as error:
        log(f"ERROR {type(error).__name__}: {error}", args.log)
        return 1


if __name__ == "__main__":
    sys.exit(main())
