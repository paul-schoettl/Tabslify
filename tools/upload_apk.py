#!/usr/bin/env python3
import argparse
import hashlib
import json
import logging
import os
import re
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import quote

import requests
from dotenv import load_dotenv

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_APK = ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
GRADLE_FILE = ROOT / "app" / "build.gradle.kts"
BUCKET = "tabslify-apks"
PREFIX = "tabslify-"
METADATA_NAME = "tabslify-latest.json"
DEBUG_DIR = "debug/"
DEBUG_PREFIX = "tabslify-debug-"
DEBUG_METADATA_NAME = "debug/tabslify-debug-latest.json"
DEFAULT_PART_MB = 10
MAX_PART_MB = 45

log = logging.getLogger("tabslify-apk")


def setup_logging(verbose: bool) -> None:
    logging.basicConfig(
        level=logging.DEBUG if verbose else logging.INFO,
        format="%(asctime)s %(levelname)-7s %(message)s",
        datefmt="%H:%M:%S",
    )


def read_gradle_versions(path: Path) -> tuple[int, str]:
    text = path.read_text(encoding="utf-8")
    code = re.search(r"versionCode\s*=?\s*(\d+)", text)
    name = re.search(r'versionName\s*=?\s*"([^"]+)"', text)
    if not code or not name:
        raise ValueError(f"versionCode/versionName nicht gefunden in {path}")
    return int(code.group(1)), name.group(1)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def part_name(prefix: str, version_name: str, version_code: int, index: int) -> str:
    return f"{prefix}v{version_name}-vc{version_code}-part{index:02d}.bin"


class StorageClient:
    def __init__(self, url: str, key: str) -> None:
        self.base = url.rstrip("/")
        self.session = requests.Session()
        self.session.headers.update({
            "apikey": key,
            "Authorization": f"Bearer {key}",
        })

    def _url(self, path: str) -> str:
        return f"{self.base}/storage/v1/object/{path}"

    def list_objects(self, bucket: str, prefix: str = "") -> list[dict]:
        collected: list[dict] = []
        offset = 0
        limit = 100
        while True:
            response = self.session.post(
                f"{self.base}/storage/v1/object/list/{bucket}",
                json={
                    "prefix": prefix,
                    "limit": limit,
                    "offset": offset,
                    "sortBy": {"column": "name", "order": "asc"},
                },
                timeout=60,
            )
            response.raise_for_status()
            page = response.json()
            if not page:
                break
            for obj in page:
                if prefix and obj.get("name"):
                    obj = dict(obj)
                    obj["name"] = prefix + obj["name"]
                collected.append(obj)
            if len(page) < limit:
                break
            offset += limit
        return collected

    def remove(self, bucket: str, names: list[str]) -> None:
        response = self.session.delete(
            self._url(bucket),
            json={"prefixes": names},
            timeout=120,
        )
        response.raise_for_status()

    def upload(self, bucket: str, name: str, data: bytes, content_type: str) -> None:
        response = self.session.post(
            self._url(f"{bucket}/{quote(name)}"),
            data=data,
            headers={
                "content-type": content_type,
                "x-upsert": "true",
                "cache-control": "no-store",
            },
            timeout=300,
        )
        if response.status_code >= 400:
            raise RuntimeError(
                f"Upload {name} fehlgeschlagen: HTTP {response.status_code} {response.text[:300]}"
            )

    def download(self, bucket: str, name: str) -> bytes:
        response = self.session.get(
            self._url(f"{bucket}/{quote(name)}"),
            timeout=300,
        )
        response.raise_for_status()
        return response.content


def make_client() -> StorageClient:
    load_dotenv(Path.home() / ".env")
    url = os.environ.get("SUPABASE_URL")
    key = os.environ.get("SUPABASE_SERVICE_ROLE_KEY")
    if not url or not key:
        raise RuntimeError("SUPABASE_URL oder SUPABASE_SERVICE_ROLE_KEY fehlt in ~/.env")
    log.info("Supabase-Client bereit (Projekt %s)", url)
    return StorageClient(url, key)


def bucket_file_size_limit(client: StorageClient, bucket: str) -> int | None:
    response = client.session.get(
        f"{client.base}/storage/v1/bucket/{bucket}",
        timeout=60,
    )
    response.raise_for_status()
    value = response.json().get("file_size_limit")
    return int(value) if value is not None else None


def list_prefix_for(remote_prefix: str) -> str:
    return remote_prefix.rsplit("/", 1)[0] + "/" if "/" in remote_prefix else ""


def delete_existing(client: StorageClient, remote_prefix: str) -> None:
    objects = [
        obj["name"]
        for obj in client.list_objects(BUCKET, list_prefix_for(remote_prefix))
        if obj["name"].startswith(remote_prefix)
    ]
    if not objects:
        log.info("Keine alten Objekte mit Prefix %s im Bucket %s", remote_prefix, BUCKET)
        return
    log.info("Loesche %d alte Objekt(e) mit Prefix %s aus %s", len(objects), remote_prefix, BUCKET)
    for start in range(0, len(objects), 50):
        batch = objects[start:start + 50]
        client.remove(BUCKET, batch)
        log.info("Geloescht: %s", ", ".join(batch))


def cleanup_local(apk: Path) -> None:
    log.info("Loesche lokalen Build-Output: %s", apk)
    apk.unlink(missing_ok=True)
    for stale in apk.parent.glob(f"{PREFIX}*-part*.bin"):
        log.info("Loesche lokalen Part: %s", stale)
        stale.unlink(missing_ok=True)
    log.info("Lokaler Build-Output entfernt")


def build_plan(apk: Path, prefix: str, version_name: str, version_code: int, part_size: int) -> list[dict]:
    total = apk.stat().st_size
    parts: list[dict] = []
    index = 0
    with apk.open("rb") as handle:
        while True:
            chunk = handle.read(part_size)
            if not chunk:
                break
            parts.append({
                "index": index,
                "name": part_name(prefix, version_name, version_code, index),
                "size": len(chunk),
                "sha256": sha256_bytes(chunk),
            })
            index += 1
    if not parts:
        raise RuntimeError("APK ist leer")
    if sum(part["size"] for part in parts) != total:
        raise RuntimeError("Part-Groessen summieren sich nicht zur APK-Groesse")
    return parts


def upload(client: StorageClient, apk: Path, parts: list[dict], metadata: dict, part_size: int, metadata_name: str) -> None:
    with apk.open("rb") as handle:
        for part in parts:
            log.info(
                "Upload %s (%d/%d, %d Bytes)",
                part["name"], part["index"] + 1, len(parts), part["size"],
            )
            data = handle.read(part_size)
            if len(data) != part["size"]:
                raise RuntimeError(
                    f"{part['name']}: gelesen {len(data)} statt {part['size']} Bytes"
                )
            if sha256_bytes(data) != part["sha256"]:
                raise RuntimeError(f"{part['name']}: SHA-256 weicht beim Upload ab")
            client.upload(BUCKET, part["name"], data, "application/octet-stream")
    payload = json.dumps(metadata, indent=2, ensure_ascii=False).encode("utf-8")
    log.info("Upload Metadaten %s (%d Bytes)", metadata_name, len(payload))
    client.upload(BUCKET, metadata_name, payload, "application/json")


def verify(client: StorageClient, parts: list[dict], expected_sha: str, expected_size: int) -> None:
    digest = hashlib.sha256()
    written = 0
    with tempfile.NamedTemporaryFile(prefix="tabslify-verify-", suffix=".apk", delete=False) as tmp:
        tmp_path = Path(tmp.name)
        try:
            for part in parts:
                log.info("Verify Download %s", part["name"])
                data = client.download(BUCKET, part["name"])
                if len(data) != part["size"]:
                    raise RuntimeError(
                        f"{part['name']}: Groesse {len(data)} != erwartet {part['size']}"
                    )
                actual = sha256_bytes(data)
                if actual != part["sha256"]:
                    raise RuntimeError(
                        f"{part['name']}: SHA-256 {actual} != erwartet {part['sha256']}"
                    )
                tmp.write(data)
                digest.update(data)
                written += len(data)
            tmp.flush()
            os.fsync(tmp.fileno())
        finally:
            tmp.close()
        try:
            if written != expected_size:
                raise RuntimeError(f"Gesamtgroesse {written} != erwartet {expected_size}")
            actual_sha = digest.hexdigest()
            if actual_sha != expected_sha:
                raise RuntimeError(f"Gesamt-SHA-256 {actual_sha} != erwartet {expected_sha}")
            local_sha = sha256_file(tmp_path)
            if local_sha != expected_sha:
                raise RuntimeError(f"Reassemblierte Datei {local_sha} != erwartet {expected_sha}")
            log.info("Verify OK: %d Bytes, SHA-256 %s", written, actual_sha)
        finally:
            tmp_path.unlink(missing_ok=True)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Splittet die Tabslify-APK, laedt sie nach Supabase Storage, verifiziert sie und loescht danach den lokalen Build-Output."
    )
    parser.add_argument("--apk", type=Path, default=DEFAULT_APK)
    parser.add_argument("--variant", choices=("release", "debug"), default="release")
    parser.add_argument("--part-size-mb", type=int, default=DEFAULT_PART_MB)
    parser.add_argument("--version-code", type=int)
    parser.add_argument("--version-name")
    parser.add_argument("--no-verify", action="store_true")
    parser.add_argument("--keep-local", action="store_true")
    parser.add_argument("--delete-remote", action="store_true")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()

    setup_logging(args.verbose)

    if args.variant == "debug":
        remote_prefix = DEBUG_DIR + DEBUG_PREFIX
        metadata_name = DEBUG_METADATA_NAME
    else:
        remote_prefix = PREFIX
        metadata_name = METADATA_NAME

    if args.delete_remote:
        delete_existing(make_client(), remote_prefix)
        log.info("Remote-Bereinigung abgeschlossen")
        return 0

    apk = args.apk.resolve()
    if not apk.is_file():
        log.error("APK nicht gefunden: %s", apk)
        return 1

    if not 1 <= args.part_size_mb <= MAX_PART_MB:
        parser.error(f"--part-size-mb muss zwischen 1 und {MAX_PART_MB} liegen")

    gradle_code, gradle_name = read_gradle_versions(GRADLE_FILE)
    version_code = args.version_code if args.version_code is not None else gradle_code
    version_name = args.version_name if args.version_name is not None else gradle_name
    part_size = args.part_size_mb * 1024 * 1024

    total_size = apk.stat().st_size
    apk_sha = sha256_file(apk)
    log.info("APK: %s", apk)
    log.info("Variante: %s", args.variant)
    log.info("Version: %s (versionCode %d)", version_name, version_code)
    log.info("Groesse: %d Bytes (%.1f MB)", total_size, total_size / (1024 * 1024))
    log.info("SHA-256: %s", apk_sha)
    log.info("Part-Groesse: %d Bytes (%.1f MB)", part_size, part_size / (1024 * 1024))

    parts = build_plan(apk, remote_prefix, version_name, version_code, part_size)
    log.info("Part-Anzahl: %d", len(parts))

    metadata = {
        "app": "tabslify",
        "bucket": BUCKET,
        "variant": args.variant,
        "versionName": version_name,
        "versionCode": version_code,
        "appFileName": apk.name,
        "totalSize": total_size,
        "partCount": len(parts),
        "partSizeLimit": part_size,
        "sha256": apk_sha,
        "timestamp": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "parts": [
            {"name": part["name"], "size": part["size"], "sha256": part["sha256"]}
            for part in parts
        ],
    }

    if args.dry_run:
        for part in parts:
            log.info("Plan %s (%d Bytes, %s)", part["name"], part["size"], part["sha256"])
        log.info("Dry-Run beendet, kein Upload")
        return 0

    client = make_client()
    bucket_limit = bucket_file_size_limit(client, BUCKET)
    if bucket_limit is not None and part_size > bucket_limit:
        log.error(
            "Part-Groesse %d Bytes ueberschreitet das Bucket-Limit %d Bytes von %s",
            part_size, bucket_limit, BUCKET,
        )
        return 1
    log.info(
        "Bucket-Limit %s: %s Bytes",
        BUCKET,
        bucket_limit if bucket_limit is not None else "global/unbekannt",
    )
    delete_existing(client, remote_prefix)
    upload(client, apk, parts, metadata, part_size, metadata_name)

    if not args.no_verify:
        verify(client, parts, apk_sha, total_size)

    if args.keep_local:
        log.info("Lokaler Build-Output bleibt erhalten: %s", apk)
    else:
        cleanup_local(apk)

    log.info(
        "Fertig: %d Parts, %d Bytes, Bucket %s, Metadaten %s",
        len(parts), total_size, BUCKET, metadata_name,
    )
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:
        log.error("Abbruch: %s", exc, exc_info=True)
        sys.exit(1)
