#!/usr/bin/env python3
"""Publish one verified DDS Manager Velocity JAR to one platform.

Each platform runs independently. The GitHub Release supplies the immutable JAR.
No API request is made during --dry-run.
"""
import argparse
import hashlib
import json
import mimetypes
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

USER_AGENT = "DDS-Manager/official-release"
MODRINTH = "https://api.modrinth.com/v2"
CURSEFORGE = "https://minecraft.curseforge.com"
HANGAR = "https://hangar.papermc.io/api/v1/"


def api(url, *, method="GET", token=None, headers=None, data=None):
    req_headers = {"User-Agent": USER_AGENT, **(headers or {})}
    if token:
        req_headers["Authorization"] = token
    request = urllib.request.Request(url, data=data, headers=req_headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as exc:
        raw = exc.read(2048).decode("utf-8", "replace")
        try:
            payload = json.loads(raw)
        except ValueError:
            payload = {"description": raw}
        return exc.code, payload
    except urllib.error.URLError:
        # Never include an authentication URL in error logs.
        raise RuntimeError('Platform network request failed') from None


def require_ok(status, data, expected, context):
    if status not in expected:
        raise RuntimeError(f"{context}: HTTP {status}: {str(data)[:500]}")
    return data


def multipart(field, metadata, jar):
    boundary = "----DDSManager" + uuid.uuid4().hex
    content_type = mimetypes.guess_type(jar.name)[0] or "application/java-archive"
    parts = [
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"{field}\"\r\n"
        "Content-Type: application/json\r\n\r\n".encode()
        + json.dumps(metadata, ensure_ascii=False).encode("utf-8") + b"\r\n",
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{jar.name}\"\r\n"
        f"Content-Type: {content_type}\r\n\r\n".encode() + jar.read_bytes() + b"\r\n",
        f"--{boundary}--\r\n".encode(),
    ]
    return b"".join(p if isinstance(p, bytes) else p.encode() for p in parts), (
        f"multipart/form-data; boundary={boundary}"
    )


def load_release(args):
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", args.version):
        raise ValueError("Only reviewed stable semver releases are supported")
    config = json.loads(args.config.read_text("utf-8"))
    jar = args.jar.resolve(strict=True)
    if jar.name != f"DDS-Manager-{args.version}.jar":
        raise ValueError(f"Unexpected release JAR name: {jar.name}")
    expected_hash = args.sha256.read_text("ascii").strip().split()
    if len(expected_hash) != 2 or expected_hash[1] != jar.name:
        raise ValueError("Invalid SHA256 manifest filename")
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    if digest.lower() != expected_hash[0].lower():
        raise ValueError("JAR SHA256 does not match GitHub Release")
    notes = args.changelog.read_text("utf-8").strip()
    if not notes:
        raise ValueError("Release notes must not be empty")
    return config, jar, notes, digest


def validate_target(platform, config):
    key = {"hangar": "velocity_versions",
           "modrinth": "minecraft_versions",
           "curseforge": "curseforge_version_ids"}[platform]
    versions = config.get(key)
    if not isinstance(versions, list) or not versions:
        raise ValueError(f"{platform} requires a reviewed, non-empty {key} list")
    if platform == "curseforge":
        if any(type(v) is not int or v <= 0 for v in versions):
            raise ValueError("CurseForge requires positive numeric game-version IDs")
    elif any(not isinstance(v, str) or not v.strip() for v in versions):
        raise ValueError(f"{platform} requires non-empty string versions")
    if platform == "modrinth":
        policy = config.get("minecraft_support_policy", {})
        low = policy.get("minimum", "")
        high = policy.get("current_ceiling", "")
        def numeric_version(value):
            if not re.fullmatch(r"[0-9]+(?:\.[0-9]+){1,2}", value):
                raise ValueError(f"Modrinth version {value!r} is not an exact released Minecraft version")
            parts = [int(x) for x in value.split(".")]
            return tuple((parts + [0] * 3)[:3])
        if not low or not high or numeric_version(low) > numeric_version(high):
            raise ValueError("Minecraft intended version range is not configured")
        for version in versions:
            if not numeric_version(low) <= numeric_version(version) <= numeric_version(high):
                raise ValueError(f"Minecraft {version} lies outside the reviewed {low}–{high} range")
    if len(versions) != len(set(versions)):
        raise ValueError(f"{platform} has duplicate version metadata")


def publish_modrinth(args, config, jar, notes, digest):
    token = os.environ["MODRINTH_TOKEN"]
    project = os.environ["MODRINTH_PROJECT_SLUG"]
    slug = urllib.parse.quote(project, safe="")
    status, existing = api(f"{MODRINTH}/project/{slug}/version/{args.version}", token=token)
    if status == 200:
        files = existing.get("files", [])
        matched = [
            f for f in files
            if f.get("filename") == jar.name
            and f.get("hashes", {}).get("sha256", "").lower() == digest.lower()
            and f.get("primary") is True
        ]
        if not matched:
            raise RuntimeError("Modrinth version exists with a different JAR; manual review required")
        print(f"[already published] Modrinth {args.version} (matching SHA256)")
        return
    if status != 404:
        require_ok(status, existing, {404}, "Modrinth version lookup")
    status, project_data = api(f"{MODRINTH}/project/{slug}", token=token)
    require_ok(status, project_data, {200}, "Modrinth project lookup")
    if not isinstance(project_data, dict) or not project_data.get("id"):
        raise RuntimeError("Modrinth project lookup returned no canonical ID")
    # The project UI groups releases (e.g. 1.21.x), but the API requires
    # concrete game_version tags. Never upload unsupported or snapshot IDs.
    status, catalog = api(f"{MODRINTH}/tag/game_version")
    require_ok(status, catalog, {200}, "Modrinth game-version catalog")
    releases = {
        item["version"] for item in catalog
        if isinstance(item, dict) and item.get("version_type") == "release"
        and isinstance(item.get("version"), str)
    }
    unknown = sorted(set(config["minecraft_versions"]) - releases)
    if unknown:
        raise RuntimeError(f"Unknown/non-release Modrinth Minecraft versions: {unknown}")
    data = {
        "name": f"DDS Manager {args.version}",
        "version_number": args.version,
        "changelog": notes,
        "dependencies": [],
        "game_versions": config["minecraft_versions"],
        "version_type": "release",
        "loaders": ["velocity"],
        "environment": "dedicated_server_only",
        "featured": True,
        "status": "listed",
        "project_id": project_data["id"],
        "file_parts": ["file"],
        "primary_file": "file",
    }
    payload, content_type = multipart("data", data, jar)
    status, result = api(f"{MODRINTH}/version", method="POST", token=token,
                         headers={"Content-Type": content_type}, data=payload)
    require_ok(status, result, {200}, "Modrinth upload")
    print(f"[published] Modrinth: {result.get('id')}")


def publish_curseforge(args, config, jar, notes, digest):
    token = os.environ["CURSEFORGE_TOKEN"]
    project = os.environ["CURSEFORGE_PROJECT_ID"]
    if not project.isdigit():
        raise ValueError("CURSEFORGE_PROJECT_ID must be numeric")
    status, versions = api(f"{CURSEFORGE}/api/game/versions",
                           headers={"X-Api-Token": token})
    require_ok(status, versions, {200}, "CurseForge game version lookup")
    ids = config["curseforge_version_ids"]
    available = {item["id"] for item in versions if isinstance(item, dict) and isinstance(item.get("id"), int)}
    missing = sorted(set(ids) - available)
    if missing:
        raise RuntimeError(f"Unknown CurseForge game-version IDs: {missing}")
    data = {
        "displayName": f"DDS Manager {args.version} (Velocity)",
        "changelog": notes,
        "changelogType": "markdown",
        "gameVersions": ids,
        "releaseType": "release",
        "isMarkedForManualRelease": False,
    }
    payload, content_type = multipart("metadata", data, jar)
    # CurseForge has no preflight by version number in its upload API.
    status, result = api(f"{CURSEFORGE}/api/projects/{project}/upload-file",
                         method="POST", headers={"X-Api-Token": token,
                         "Content-Type": content_type}, data=payload)
    if status in (400, 409):
        message = str(result).lower()
        if any(phrase in message for phrase in ("duplicate", "already exists", "same file")):
            raise RuntimeError(
                "CurseForge reports a duplicate file but does not prove remote SHA256; "
                "review the existing file manually"
            )
    require_ok(status, result, {200, 201}, "CurseForge upload")
    print(f"[published] CurseForge: {result.get('id')}")


def publish_hangar(args, config, jar, notes, digest):
    token = os.environ["HANGAR_API_TOKEN"]
    project = os.environ["HANGAR_PROJECT"]
    path = urllib.parse.quote(project, safe="")
    # Preflight uses the public version endpoint; 404 means the tag is available.
    status, existing = api(f"{HANGAR}projects/{path}/versions/{args.version}")
    if status == 200:
        raise RuntimeError(
            f"Hangar version {args.version} exists: remote artifact hash is unverified; "
            "review this platform manually instead of assuming success"
        )
    if status != 404:
        require_ok(status, existing, {404}, "Hangar version lookup")
    # Equivalent to the official Hangar Gradle plugin's authenticate / upload calls.
    auth_url = HANGAR + "authenticate?apiKey=" + urllib.parse.quote(token, safe="")
    status, auth = api(auth_url, method="POST")
    if status != 200 or not isinstance(auth, dict) or not auth.get("token"):
        raise RuntimeError(f"Hangar authentication failed: HTTP {status}")
    meta = {
        "version": args.version,
        "pluginDependencies": {"VELOCITY": []},
        "platformDependencies": {"VELOCITY": config["velocity_versions"]},
        "description": notes,
        "files": [{"platforms": ["VELOCITY"], "externalUrl": None}],
        "channel": "Release",
    }
    # Hangar's multipart field for the binary is 'files' (plural).
    payload, content_type = multipart("versionUpload", meta, jar)
    payload = payload.replace(b'name="file";', b'name="files";', 1)
    status, result = api(f"{HANGAR}projects/{path}/upload", method="POST",
                         headers={"Authorization": auth["token"],
                                  "Content-Type": content_type}, data=payload)
    require_ok(status, result, {200}, "Hangar upload")
    print(f"[published] Hangar: {result.get('url')}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--platform", choices=["modrinth", "curseforge", "hangar"], required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--sha256", type=Path, required=True)
    parser.add_argument("--changelog", type=Path, required=True)
    parser.add_argument("--config", type=Path, default=Path("scripts/release-platforms.json"))
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    config, jar, notes, digest = load_release(args)
    validate_target(args.platform, config)
    print(f"[checked] {args.platform}: {jar.name} SHA256={digest}")
    if args.dry_run:
        print("[dry-run] Validation only; nothing uploaded")
        return
    handlers = {"modrinth": publish_modrinth,
                "curseforge": publish_curseforge,
                "hangar": publish_hangar}
    handlers[args.platform](args, config, jar, notes, digest)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError, KeyError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        sys.exit(1)
