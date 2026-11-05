#!/usr/bin/env bash
set -u

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR" || exit 1
mkdir -p logs

PYTHON="$DIR/.venv/bin/python"
if [ ! -x "$PYTHON" ]; then
    echo "$(date --iso-8601=seconds) ERROR missing venv: $PYTHON" >> logs/run.log
    exit 1
fi

export YT_LOGIN_TIMEOUT="${YT_LOGIN_TIMEOUT:-0}"

echo "$(date --iso-8601=seconds) run start" >> logs/run.log

if ! "$PYTHON" yt_scrape.py >> logs/run.log 2>&1; then
    echo "$(date --iso-8601=seconds) ERROR scrape failed, keeping previous suggestion" >> logs/run.log
    exit 1
fi

if ! "$PYTHON" pick.py >> logs/run.log 2>&1; then
    echo "$(date --iso-8601=seconds) ERROR pick failed, keeping previous suggestion" >> logs/run.log
    exit 1
fi

if ! "$PYTHON" push.py >> logs/run.log 2>&1; then
    echo "$(date --iso-8601=seconds) ERROR push failed, keeping previous suggestion" >> logs/run.log
    exit 1
fi

echo "$(date --iso-8601=seconds) run done" >> logs/run.log
