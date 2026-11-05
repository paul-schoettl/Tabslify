#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import sqlite3
import sys
import tempfile
import time
from datetime import datetime
from pathlib import Path

from playwright.sync_api import TimeoutError as PlaywrightTimeoutError
from playwright.sync_api import sync_playwright

BASE_DIR = Path(__file__).resolve().parent
DEFAULT_PROFILE = BASE_DIR / "profile"
DEFAULT_RAW_DIR = BASE_DIR / "raw"
DEFAULT_LOG = BASE_DIR / "logs" / "scrape.log"

HISTORY_URL = "https://www.youtube.com/feed/history"
LIKES_URL = "https://www.youtube.com/playlist?list=LL"
HOME_URL = "https://www.youtube.com/"

DOM_JS = r"""
() => {
  const cards = document.querySelectorAll(
    'ytd-rich-item-renderer, ytd-video-renderer, ytd-grid-video-renderer, ' +
    'ytd-playlist-video-renderer, ytd-compact-video-renderer, ytd-reel-item-renderer'
  );
  const out = [];
  const seen = new Set();
  for (const card of cards) {
    if (card.querySelector('ytd-ad-slot-renderer, ytd-promoted-sparkles-web-renderer, ytd-display-ad-renderer, ytd-promoted-video-renderer')) {
      continue;
    }
    const links = card.querySelectorAll('a[href*="/watch?v="], a[href*="/shorts/"]');
    let href = '';
    let videoId = '';
    for (const link of links) {
      try {
        const u = new URL(link.href, location.origin);
        const short = u.pathname.match(/\/shorts\/([\w-]+)/);
        const vid = u.searchParams.get('v');
        if (short) {
          href = u.href;
          videoId = short[1];
          break;
        }
        if (vid) {
          href = u.href;
          videoId = vid;
          break;
        }
      } catch (e) {
      }
    }
    if (!videoId || seen.has(videoId)) continue;
    seen.add(videoId);
    const titleEl = card.querySelector('#video-title, #video-title-link, a#video-title-link, h3 a, h3');
    const channelEl = card.querySelector('ytd-channel-name a, #channel-name a, #channel-name, .ytd-channel-name');
    const metaParts = Array.from(card.querySelectorAll('#metadata-line span, .inline-metadata-item'))
      .map((e) => (e.textContent || '').replace(/\s+/g, ' ').trim())
      .filter(Boolean);
    const durationEl = card.querySelector(
      'ytd-thumbnail-overlay-time-status-renderer span, #time-status span, ' +
      '.badge-shape-wiz__text, ytd-thumbnail-overlay-time-status-renderer'
    );
    const img = card.querySelector('img');
    const progressEl = card.querySelector(
      '#progress, ytd-thumbnail-overlay-resume-playback-renderer #progress, ' +
      'ytd-thumbnail-overlay-resume-playback-renderer, #progress-bar'
    );
    let progressStyle = '';
    let progressPercent = null;
    if (progressEl) {
      progressStyle = progressEl.getAttribute('style') || '';
      const match = progressStyle.match(/width\s*:\s*([\d.]+)%/);
      if (match) progressPercent = parseFloat(match[1]);
      if (progressPercent === null) {
        const bar = progressEl.querySelector('#progress, #progress-bar');
        if (bar) {
          progressStyle = bar.getAttribute('style') || progressStyle;
          const inner = progressStyle.match(/width\s*:\s*([\d.]+)%/);
          if (inner) progressPercent = parseFloat(inner[1]);
        }
      }
    }
    const cardText = (card.textContent || '').replace(/\s+/g, ' ').trim();
    out.push({
      video_id: videoId,
      title: (titleEl ? titleEl.textContent : '').replace(/\s+/g, ' ').trim(),
      channel: (channelEl ? channelEl.textContent : '').replace(/\s+/g, ' ').trim(),
      metadata: metaParts,
      duration_text: (durationEl ? durationEl.textContent : '').replace(/\s+/g, ' ').trim(),
      thumbnail_url: img ? (img.currentSrc || img.src || '') : '',
      url: href,
      progress_style: progressStyle,
      progress_percent: progressPercent,
      has_resume_overlay: !!card.querySelector('ytd-thumbnail-overlay-resume-playback-renderer'),
      is_live: /\bLIVE\b|Premiere|Live jetzt|Jetzt live/.test(cardText),
      is_short: href.includes('/shorts/'),
    });
  }
  return out;
}
"""


def firefox_cookies(db_path: Path) -> list[dict]:
    handle, temp_name = tempfile.mkstemp(suffix=".sqlite")
    os.close(handle)
    shutil.copy(db_path, temp_name)
    try:
        connection = sqlite3.connect(temp_name)
        rows = connection.execute(
            "select name,value,host,path,expiry,isSecure,isHttpOnly,sameSite,originAttributes "
            "from moz_cookies where host like '%youtube.com' or host like '%google.com'"
        ).fetchall()
        connection.close()
    finally:
        try:
            os.unlink(temp_name)
        except OSError:
            pass
    cookies = []
    for name, value, host, path, expiry, secure, http_only, same_site, origin in rows:
        if not value or (origin or "").strip():
            continue
        expires = int(expiry) if expiry else -1
        if expires > 10**11:
            expires //= 1000
        cookies.append(
            {
                "name": name,
                "value": value,
                "domain": host,
                "path": path or "/",
                "expires": expires,
                "secure": bool(secure),
                "httpOnly": bool(http_only),
                "sameSite": {0: "None", 1: "Lax", 2: "Strict"}.get(int(same_site or 0), "Lax"),
            }
        )
    return cookies


def log(message: str, logfile: Path | None = None) -> None:
    line = f"{datetime.now().astimezone().isoformat(timespec='seconds')} {message}"
    print(line, flush=True)
    if logfile:
        logfile.parent.mkdir(parents=True, exist_ok=True)
        with logfile.open("a", encoding="utf-8") as handle:
            handle.write(line + "\n")


def text_from(value) -> str:
    if value is None:
        return ""
    if isinstance(value, str):
        return value.strip()
    if isinstance(value, dict):
        if isinstance(value.get("simpleText"), str):
            return value["simpleText"].strip()
        if isinstance(value.get("content"), str):
            return value["content"].strip()
        runs = value.get("runs")
        if isinstance(runs, list):
            return "".join(str(run.get("text", "")) for run in runs if isinstance(run, dict)).strip()
    return ""


def best_thumbnail(renderer: dict) -> str:
    candidates = []

    def collect(node):
        if isinstance(node, dict):
            if isinstance(node.get("thumbnails"), list):
                for thumb in node["thumbnails"]:
                    if isinstance(thumb, dict) and thumb.get("url"):
                        candidates.append((thumb.get("width") or 0, thumb["url"]))
            for key in ("sources", "image", "thumbnail"):
                if key in node:
                    collect(node[key])
            for value in node.values():
                if isinstance(value, (dict, list)):
                    collect(value)
        elif isinstance(node, list):
            for value in node:
                collect(value)

    collect(renderer)
    if not candidates:
        return ""
    url = sorted(candidates, key=lambda item: item[0])[-1][1]
    if url.startswith("//"):
        url = "https:" + url
    return url


def find_percent(node) -> float | None:
    if isinstance(node, dict):
        for key, value in node.items():
            if key in ("percentDurationWatched", "percentWatched") and value is not None:
                try:
                    return float(value)
                except (TypeError, ValueError):
                    pass
            found = find_percent(value)
            if found is not None:
                return found
    elif isinstance(node, list):
        for value in node:
            found = find_percent(value)
            if found is not None:
                return found
    return None


def has_live_marker(renderer: dict) -> bool:
    if renderer.get("isLiveNow") or renderer.get("isLive"):
        return True
    if renderer.get("upcomingEventData"):
        return True
    text = json.dumps(renderer, ensure_ascii=False).upper()
    return any(marker in text for marker in ('"LIVE"', 'LIVE NOW', 'STYLE_LIVE', 'BADGE_STYLE_TYPE_LIVE_NOW'))


def has_ad_marker(renderer: dict) -> bool:
    return any(key in renderer for key in ("adBadge", "isAd", "promotedSparklesText", "adSlotRenderer"))


def parse_legacy(renderer: dict, kind: str) -> dict | None:
    video_id = renderer.get("videoId")
    if not video_id:
        return None
    url = ""
    nav = renderer.get("navigationEndpoint") or {}
    try:
        url = nav["commandMetadata"]["webCommandMetadata"]["url"]
    except (KeyError, TypeError):
        url = ""
    if not url:
        for key in ("watchEndpoint", "reelWatchEndpoint"):
            endpoint = renderer.get(key)
            if isinstance(endpoint, dict) and endpoint.get("videoId"):
                video_id = endpoint["videoId"]
                url = f"/watch?v={video_id}"
                break
    is_short = "/shorts/" in (url or "") or "reelWatchEndpoint" in renderer
    title = text_from(renderer.get("title"))
    channel = (
        text_from(renderer.get("ownerText"))
        or text_from(renderer.get("longBylineText"))
        or text_from(renderer.get("shortBylineText"))
        or text_from((renderer.get("videoOwnerRenderer") or {}).get("title"))
    )
    duration_text = text_from(renderer.get("lengthText"))
    if not duration_text:
        overlays = renderer.get("thumbnailOverlays") or []
        for overlay in overlays:
            if isinstance(overlay, dict):
                status = overlay.get("thumbnailOverlayTimeStatusRenderer")
                if isinstance(status, dict):
                    duration_text = text_from(status.get("text"))
                    if duration_text:
                        break
    views_text = text_from(renderer.get("viewCountText")) or text_from(renderer.get("shortViewCountText"))
    if not views_text:
        for part in (renderer.get("metadataParts") or []):
            if isinstance(part, dict):
                candidate = text_from(part.get("text"))
                if candidate and re.search(r"Aufrufe|views|Mal", candidate, re.I):
                    views_text = candidate
                    break
    progress = find_percent(renderer)
    return {
        "video_id": video_id,
        "title": title,
        "channel": channel,
        "duration_text": duration_text,
        "views_text": views_text,
        "thumbnail_url": best_thumbnail(renderer.get("thumbnail") or renderer),
        "url": url,
        "progress_percent": progress,
        "is_live": has_live_marker(renderer),
        "is_short": is_short,
        "is_ad": has_ad_marker(renderer),
        "source_renderer": kind,
    }


def parse_lockup(lockup: dict) -> dict | None:
    content_type = lockup.get("contentType") or ""
    if content_type and content_type != "LOCKUP_CONTENT_TYPE_VIDEO":
        return None
    video_id = lockup.get("contentId")
    if not video_id:
        return None
    meta = ((lockup.get("metadata") or {}).get("lockupMetadataViewModel") or {})
    title = text_from(meta.get("title"))
    parts = []
    for row in (((meta.get("metadata") or {}).get("contentMetadataViewModel") or {}).get("metadataRows") or []):
        if isinstance(row, dict):
            for part in row.get("metadataParts") or []:
                if isinstance(part, dict):
                    value = text_from(part.get("text"))
                    if value:
                        parts.append(value)
    channel = parts[0] if parts else ""
    views_text = next((part for part in parts[1:] if re.search(r"Aufrufe|views|Mal", part, re.I)), "")
    duration_text = ""
    for overlay in (((lockup.get("contentImage") or {}).get("thumbnailViewModel") or {}).get("overlays") or []):
        if isinstance(overlay, dict):
            for badge in (((overlay.get("thumbnailOverlayBadgeViewModel") or {}).get("thumbnailBadges") or [])):
                if isinstance(badge, dict):
                    value = text_from(((badge.get("thumbnailBadgeViewModel") or {}).get("text")))
                    if re.fullmatch(r"\d+(?::\d+){1,2}", value.strip()):
                        duration_text = value.strip()
                        break
                    if value.strip().upper() in ("LIVE", "PREMIERE"):
                        duration_text = value.strip()
    url = ""
    renderer_context = lockup.get("rendererContext") or {}
    command = ((renderer_context.get("commandContext") or {}).get("onTap") or {}).get("innertubeCommand") or {}
    try:
        url = command["commandMetadata"]["webCommandMetadata"]["url"]
    except (KeyError, TypeError):
        url = ""
    if not url:
        try:
            url = command["urlEndpoint"]["url"]
        except (KeyError, TypeError):
            url = ""
    is_short = "/shorts/" in (url or "") or "reelWatchEndpoint" in json.dumps(command, ensure_ascii=False)
    progress = find_percent(lockup)
    return {
        "video_id": video_id,
        "title": title,
        "channel": channel,
        "duration_text": duration_text,
        "views_text": views_text,
        "thumbnail_url": best_thumbnail(((lockup.get("contentImage") or {}).get("thumbnailViewModel") or {}).get("image") or lockup),
        "url": url,
        "progress_percent": progress,
        "is_live": has_live_marker(lockup),
        "is_short": is_short,
        "is_ad": has_ad_marker(lockup),
        "source_renderer": "lockupViewModel",
    }


def extract_initial(data) -> list[dict]:
    found: list[dict] = []

    def walk(node):
        if isinstance(node, dict):
            for key, value in node.items():
                if key in ("videoRenderer", "compactVideoRenderer", "gridVideoRenderer", "playlistVideoRenderer", "reelItemRenderer") and isinstance(value, dict):
                    item = parse_legacy(value, key)
                    if item:
                        found.append(item)
                elif key == "lockupViewModel" and isinstance(value, dict):
                    item = parse_lockup(value)
                    if item:
                        found.append(item)
                if isinstance(value, (dict, list)):
                    walk(value)
        elif isinstance(node, list):
            for value in node:
                walk(value)

    walk(data)
    return found


def parse_duration_seconds(text: str) -> int | None:
    value = (text or "").strip()
    if not value or not re.fullmatch(r"\d+(?::\d+){1,2}", value):
        return None
    parts = [int(part) for part in value.split(":")]
    seconds = 0
    for part in parts:
        seconds = seconds * 60 + part
    return seconds


def parse_views(text: str) -> int | None:
    value = (text or "").strip()
    if not value:
        return None
    match = re.search(r"([\d.,]+)\s*(Mio\.?|Millionen|Tsd\.?|Tausend|K|M)?", value, re.I)
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


def normalize_item(item: dict) -> dict:
    metadata = item.get("metadata") or []
    views_text = item.get("views_text") or ""
    if not views_text:
        views_text = next((part for part in metadata if re.search(r"Aufrufe|views|Mal", part, re.I)), "")
    duration_text = (item.get("duration_text") or "").strip()
    if not duration_text:
        duration_text = next((part for part in metadata if re.fullmatch(r"\d+(?::\d+){1,2}", part.strip())), "")
    url = item.get("url") or ""
    if not url and item.get("video_id"):
        url = f"/watch?v={item['video_id']}"
    if url.startswith("/"):
        url = "https://www.youtube.com" + url
    result = dict(item)
    result["duration_text"] = duration_text
    result["duration"] = parse_duration_seconds(duration_text)
    result["views_text"] = views_text
    result["views"] = parse_views(views_text)
    result["url"] = url
    result["thumbnail_url"] = item.get("thumbnail_url") or ""
    result.pop("metadata", None)
    result.pop("progress_style", None)
    return result


def merge_items(*groups: list[dict]) -> list[dict]:
    merged: dict[str, dict] = {}
    order: list[str] = []
    for group in groups:
        for raw in group:
            item = normalize_item(raw)
            video_id = item.get("video_id")
            if not video_id:
                continue
            if video_id not in merged:
                merged[video_id] = item
                order.append(video_id)
                continue
            current = merged[video_id]
            for key, value in item.items():
                if value not in (None, "", 0, False) and current.get(key) in (None, "", 0, False):
                    current[key] = value
            if item.get("progress_percent") is not None and current.get("progress_percent") is None:
                current["progress_percent"] = item["progress_percent"]
    return [merged[video_id] for video_id in order]


def is_relevant(item: dict) -> bool:
    return not item.get("is_short") and not item.get("is_live") and not item.get("is_ad")


def scroll_page(page, target: int, max_scrolls: int) -> None:
    last = 0
    for index in range(max_scrolls):
        page.mouse.wheel(0, 1600)
        page.wait_for_timeout(1100)
        count = page.evaluate(
            "() => document.querySelectorAll('ytd-rich-item-renderer, ytd-video-renderer, "
            "ytd-grid-video-renderer, ytd-playlist-video-renderer, ytd-compact-video-renderer').length"
        )
        if count >= target:
            break
        if count == last and index >= 1:
            break
        last = count


def dom_items(page) -> list[dict]:
    try:
        return page.evaluate(DOM_JS)
    except Exception:
        return []


def page_debug(page) -> dict:
    try:
        initial = page.evaluate("() => window.ytInitialData || null")
    except Exception:
        initial = None
    try:
        html = page.evaluate("() => (document.querySelector('ytd-app') || document.body).innerHTML")
    except Exception:
        html = ""
    return {"url": page.url, "initial_data": initial, "html": html}


def is_logged_in(page) -> bool:
    try:
        return bool(
            page.evaluate(
                """() => {
                  const data = window.ytInitialData || {};
                  const context = data.responseContext || {};
                  const main = context.mainAppWebResponseContext || {};
                  if (main.loggedIn === true || main.loggedOut === false) return true;
                  if (main.loggedOut === true) return false;
                  const params = (context.serviceTrackingParams || []).flatMap((entry) => entry.params || []);
                  if (params.some((entry) => entry.key === 'logged_in' && entry.value !== '0')) return true;
                  if (params.some((entry) => entry.key === 'logged_in' && entry.value === '0')) return false;
                  return !!document.querySelector('#avatar-btn, button#avatar-btn, ytd-topbar-menu-button-renderer #avatar-btn');
                }"""
            )
        )
    except Exception:
        return False


def accept_consent(page) -> None:
    selectors = [
        'button[aria-label*="Alle akzeptieren"]',
        'button[aria-label*="Accept all"]',
        'button:has-text("Alle akzeptieren")',
        'button:has-text("Accept all")',
        'button:has-text("Ich stimme zu")',
    ]
    for selector in selectors:
        try:
            locator = page.locator(selector).first
            if locator.count() and locator.is_visible(timeout=500):
                locator.click(timeout=1500)
                page.wait_for_timeout(800)
                return
        except Exception:
            continue


def wait_for_login(page, timeout: int, logfile: Path) -> None:
    if is_logged_in(page):
        return
    if timeout <= 0:
        raise RuntimeError("login required: profile is not logged in")
    log(f"login required: log in to YouTube in the opened browser (waiting up to {timeout}s)", logfile)
    deadline = time.time() + timeout
    while time.time() < deadline:
        page.wait_for_timeout(2000)
        accept_consent(page)
        if is_logged_in(page):
            page.wait_for_timeout(1500)
            log("login detected", logfile)
            return
    raise RuntimeError("login timeout: still not logged in")


def load_page(page, url: str, target: int, logfile: Path, scrolls: int, navigate: bool = True) -> dict:
    if navigate:
        page.goto(url, wait_until="domcontentloaded", timeout=90000)
    page.wait_for_timeout(2500)
    try:
        page.wait_for_load_state("networkidle", timeout=12000)
    except PlaywrightTimeoutError:
        pass
    accept_consent(page)
    scroll_page(page, target, scrolls)
    page.wait_for_timeout(1200)
    debug = page_debug(page)
    initial = extract_initial(debug.get("initial_data"))
    dom = dom_items(page)
    merged = merge_items(initial, dom)
    log(f"{url} -> initial={len(initial)} dom={len(dom)} merged={len(merged)}", logfile)
    return {"items": merged, "debug": debug}


def scrape(
    profile: Path,
    raw_dir: Path,
    logfile: Path,
    login_timeout: int,
    scrolls: int,
    allow_logged_out: bool = False,
    seed_cookies: Path | None = None,
) -> Path:
    profile.mkdir(parents=True, exist_ok=True)
    raw_dir.mkdir(parents=True, exist_ok=True)
    with sync_playwright() as playwright:
        context = playwright.chromium.launch_persistent_context(
            str(profile),
            headless=False,
            locale="de-DE",
            viewport={"width": 1440, "height": 1000},
            args=["--disable-blink-features=AutomationControlled"],
        )
        try:
            if seed_cookies:
                cookies = firefox_cookies(seed_cookies)
                context.add_cookies(cookies)
                log(f"seeded {len(cookies)} cookies from {seed_cookies}", logfile)
            page = context.pages[0] if context.pages else context.new_page()
            page.goto(HISTORY_URL, wait_until="domcontentloaded", timeout=90000)
            page.wait_for_timeout(2500)
            accept_consent(page)
            if allow_logged_out and not is_logged_in(page):
                log("WARNING running without login (test mode)", logfile)
            else:
                wait_for_login(page, login_timeout, logfile)
            logged_in = is_logged_in(page)

            if "/feed/history" not in page.url:
                page.goto(HISTORY_URL, wait_until="domcontentloaded", timeout=90000)
                page.wait_for_timeout(2500)
            history_page = load_page(page, HISTORY_URL, 50, logfile, scrolls, navigate=False)
            page.wait_for_timeout(2200)
            likes_page = load_page(page, LIKES_URL, 30, logfile, scrolls)
            page.wait_for_timeout(2200)
            home_page = load_page(page, HOME_URL, 40, logfile, scrolls)

            history = [item for item in history_page["items"] if is_relevant(item)][:50]
            for item in history:
                percent = item.get("progress_percent")
                if percent is None:
                    item["status"] = "fertig"
                elif percent >= 90:
                    item["status"] = "fertig"
                else:
                    item["status"] = "angefangen"
            likes = [item for item in likes_page["items"] if is_relevant(item)][:30]
            watched = {item["video_id"] for item in history}
            candidates = [
                item for item in home_page["items"] if is_relevant(item) and item["video_id"] not in watched
            ][:40]

            now = datetime.now().astimezone()
            payload = {
                "scraped_at": now.isoformat(timespec="seconds"),
                "date": now.strftime("%Y-%m-%d"),
                "logged_in": logged_in,
                "history": history,
                "likes": likes,
                "candidates": candidates,
                "stats": {
                    "history": len(history),
                    "likes": len(likes),
                    "candidates": len(candidates),
                    "history_watched_ids": len(watched),
                    "filtered_short_live_ad": sum(
                        1
                        for page_data in (history_page, likes_page, home_page)
                        for item in page_data["items"]
                        if not is_relevant(item)
                    ),
                },
                "debug": {
                    "history": history_page["debug"],
                    "likes": likes_page["debug"],
                    "home": home_page["debug"],
                },
            }
            out = raw_dir / f"{now.strftime('%Y-%m-%d')}.json"
            out.write_text(json.dumps(payload, ensure_ascii=False, indent=1), encoding="utf-8")
            log(f"wrote {out} ({out.stat().st_size} bytes)", logfile)
            return out
        finally:
            context.close()


def prune_raw(raw_dir: Path, keep_days: int, logfile: Path) -> None:
    if keep_days <= 0:
        return
    cutoff = time.time() - keep_days * 86400
    for path in raw_dir.glob("*.json"):
        try:
            if path.stat().st_mtime < cutoff:
                path.unlink()
                log(f"pruned {path.name}", logfile)
        except OSError:
            continue


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--profile", type=Path, default=DEFAULT_PROFILE)
    parser.add_argument("--raw-dir", type=Path, default=DEFAULT_RAW_DIR)
    parser.add_argument("--log", type=Path, default=DEFAULT_LOG)
    parser.add_argument("--login-timeout", type=int, default=int(os.environ.get("YT_LOGIN_TIMEOUT", "300")))
    parser.add_argument("--scrolls", type=int, default=8)
    parser.add_argument("--retention-days", type=int, default=30)
    parser.add_argument("--allow-logged-out", action="store_true")
    parser.add_argument("--seed-cookies", type=Path)
    args = parser.parse_args()
    try:
        scrape(
            args.profile,
            args.raw_dir,
            args.log,
            args.login_timeout,
            args.scrolls,
            args.allow_logged_out,
            args.seed_cookies,
        )
        prune_raw(args.raw_dir, args.retention_days, args.log)
        return 0
    except Exception as error:
        log(f"ERROR {type(error).__name__}: {error}", args.log)
        return 1


if __name__ == "__main__":
    sys.exit(main())
