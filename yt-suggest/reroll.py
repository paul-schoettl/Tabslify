#!/usr/bin/env python3
from __future__ import annotations

import argparse
import fcntl
import json
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent
DEFAULT_STATE = BASE_DIR / "state"
DEFAULT_RAW_DIR = BASE_DIR / "raw"
DEFAULT_LOG = BASE_DIR / "logs" / "reroll.log"
DEFAULT_ENV = BASE_DIR / ".env"
POLL_SECONDS = 3
MAX_ATTEMPTS = 3
RESULT_CAP = 500
PIPELINE_TIMEOUT = 300
LOCK_TIMEOUT = 900


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
    return values


def supabase_config(env: dict[str, str]) -> tuple[str, str] | None:
    url = env.get("SUPABASE_URL", "").rstrip("/")
    key = env.get("SUPABASE_SERVICE_ROLE_KEY") or env.get("SUPABASE_KEY")
    if not url or not key:
        return None
    return url, key


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def _rest(
    settings: tuple[str, str],
    method: str,
    path: str,
    params: dict[str, str] | None = None,
    body: dict | None = None,
    prefer: str | None = None,
) -> tuple[int, object]:
    url, key = settings
    query = urllib.parse.urlencode(params or {})
    target = f"{url}/rest/v1/{path}" + (f"?{query}" if query else "")
    headers = {
        "apikey": key,
        "Authorization": f"Bearer {key}",
        "Accept": "application/json",
    }
    if body is not None:
        headers["Content-Type"] = "application/json"
    if prefer:
        headers["Prefer"] = prefer
    data = json.dumps(body).encode("utf-8") if body is not None else None
    request = urllib.request.Request(target, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=25) as response:
            text = response.read().decode("utf-8", errors="replace")
            if not text.strip():
                return response.status, []
            return response.status, json.loads(text)
    except urllib.error.HTTPError as error:
        text = error.read().decode("utf-8", errors="replace")[:RESULT_CAP]
        return error.code, text
    except Exception as error:
        return 0, f"{type(error).__name__}: {error}"


def pending_job(settings: tuple[str, str]) -> dict | None:
    status, data = _rest(
        settings,
        "GET",
        "suggestion_jobs",
        params={
            "select": "id,attempts,video_id",
            "status": "eq.pending",
            "attempts": f"lt.{MAX_ATTEMPTS}",
            "order": "id.asc",
            "limit": "1",
        },
    )
    if status != 200 or not isinstance(data, list) or not data:
        return None
    return data[0]


def claim_job(settings: tuple[str, str], job: dict) -> int | None:
    attempts = int(job.get("attempts") or 0) + 1
    status, data = _rest(
        settings,
        "PATCH",
        "suggestion_jobs",
        params={"id": f"eq.{job['id']}", "status": "eq.pending"},
        body={
            "status": "running",
            "attempts": attempts,
            "handled_at": _now_iso(),
        },
        prefer="return=representation",
    )
    if status != 200 or not isinstance(data, list) or not data:
        return None
    return attempts


def recover_stale(settings: tuple[str, str]) -> None:
    threshold = (datetime.now(timezone.utc) - timedelta(minutes=10)).isoformat()
    _rest(
        settings,
        "PATCH",
        "suggestion_jobs",
        params={"status": "eq.running", "handled_at": f"lt.{threshold}"},
        body={"status": "pending", "handled_at": None},
        prefer="return=minimal",
    )


def finish_job(settings: tuple[str, str], job_id: int, attempts: int, ok: bool, detail: str) -> None:
    status_value = "done" if ok else ("failed" if attempts >= MAX_ATTEMPTS else "pending")
    _rest(
        settings,
        "PATCH",
        "suggestion_jobs",
        params={"id": f"eq.{job_id}"},
        body={
            "status": status_value,
            "attempts": attempts,
            "result": detail[:RESULT_CAP],
            "handled_at": _now_iso(),
        },
        prefer="return=minimal",
    )


def pipeline_lock(timeout: int = LOCK_TIMEOUT):
    path = DEFAULT_STATE / "pipeline.lock"
    path.parent.mkdir(parents=True, exist_ok=True)
    handle = path.open("w")
    deadline = time.monotonic() + timeout
    while True:
        try:
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
            return handle
        except BlockingIOError:
            if time.monotonic() >= deadline:
                handle.close()
                return None
            time.sleep(1)


def latest_raw() -> Path | None:
    files = sorted(DEFAULT_RAW_DIR.glob("*.json"))
    return files[-1] if files else None


def needs_scrape() -> bool:
    latest = latest_raw()
    return latest is None or latest.stem != datetime.now().astimezone().strftime("%Y-%m-%d")


def run_pipeline(logfile: Path) -> tuple[bool, str]:
    lock = pipeline_lock()
    if lock is None:
        return False, "pipeline lock timeout"
    try:
        python = sys.executable
        steps = []
        if needs_scrape():
            log("scrape start: no raw for today", logfile)
            steps.append([python, str(BASE_DIR / "yt_scrape.py"), "--login-timeout", "0"])
        steps.extend(
            [
                [python, str(BASE_DIR / "pick.py")],
                [python, str(BASE_DIR / "push.py"), "--valid-from", "now"],
            ]
        )
        output = []
        for step in steps:
            try:
                result = subprocess.run(
                    step,
                    cwd=BASE_DIR,
                    capture_output=True,
                    text=True,
                    timeout=PIPELINE_TIMEOUT,
                )
            except subprocess.TimeoutExpired:
                return False, f"timeout: {' '.join(step[1:])}"
            output.append((result.stdout or "") + (result.stderr or ""))
            if result.returncode != 0:
                tail = output[-1].strip().splitlines()[-4:]
                return False, f"{Path(step[1]).name} rc={result.returncode}: {' | '.join(tail)}"
        video_id = ""
        last_push = DEFAULT_STATE / "last_push.json"
        if last_push.exists():
            try:
                pushed = json.loads(last_push.read_text(encoding="utf-8"))
                video_id = str((pushed.get("local") or {}).get("video_id") or "")
            except Exception:
                video_id = ""
        return True, f"pushed {video_id}".strip()
    finally:
        fcntl.flock(lock, fcntl.LOCK_UN)
        lock.close()


def process_once(settings: tuple[str, str], logfile: Path) -> bool:
    job = pending_job(settings)
    if job is None:
        return False
    job_id = int(job["id"])
    attempts = claim_job(settings, job)
    if attempts is None:
        return False
    log(f"reroll #{job_id} started attempt={attempts} video={job.get('video_id')}", logfile)
    ok, detail = run_pipeline(logfile)
    finish_job(settings, job_id, attempts, ok, detail)
    log(f"reroll #{job_id} {'done' if ok else 'failed'}: {detail}", logfile)
    return True


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--env", type=Path, default=DEFAULT_ENV)
    parser.add_argument("--log", type=Path, default=DEFAULT_LOG)
    parser.add_argument("--watch", action="store_true")
    args = parser.parse_args()
    env = load_env(args.env)
    recovered = False
    while True:
        settings = supabase_config(env)
        if settings is None:
            log("supabase credentials missing", args.log)
            if not args.watch:
                return 1
            time.sleep(POLL_SECONDS)
            continue
        if not recovered:
            recover_stale(settings)
            recovered = True
        processed = process_once(settings, args.log)
        if not args.watch:
            return 0
        if not processed:
            time.sleep(POLL_SECONDS)


if __name__ == "__main__":
    sys.exit(main())
