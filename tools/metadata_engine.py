#!/usr/bin/env python3
"""Read tags and embedded cover art from local audio files. Never uses the network."""
from __future__ import annotations

import argparse
import base64
import json
import mimetypes
from pathlib import Path
from typing import Any

try:
    from mutagen import File as MutagenFile
except ImportError as exc:  # A friendly error instead of a traceback for first-time users.
    raise SystemExit("Install local metadata support with: python -m pip install -r tools/requirements.txt") from exc


def first_tag(tags: Any, *names: str) -> str:
    if not tags:
        return ""
    for name in names:
        value = tags.get(name)
        if value is None:
            continue
        if hasattr(value, "text"):
            value = value.text
        if isinstance(value, (list, tuple)):
            value = value[0] if value else ""
        if value is not None and str(value).strip():
            return str(value).strip()
    return ""


def read_track(path: Path) -> dict[str, Any]:
    audio = MutagenFile(path, easy=False)
    tags = audio.tags if audio else None
    title = first_tag(tags, "title", "TIT2", "©nam") or path.stem
    artist = first_tag(tags, "artist", "TPE1", "©ART", "aART") or "Unknown artist"
    album = first_tag(tags, "album", "TALB", "©alb") or "Local files"
    genre = first_tag(tags, "genre", "TCON", "©gen") or "Unknown"

    pictures: list[tuple[bytes, str]] = []
    if tags:
        for key in ("APIC:", "covr"):
            try:
                values = tags.getall(key) if hasattr(tags, "getall") else [tags[key]]
            except (KeyError, TypeError):
                values = []
            for picture in values:
                data = getattr(picture, "data", picture)
                mime = getattr(picture, "mime", "") or mimetypes.guess_type("cover.jpg")[0] or "image/jpeg"
                if isinstance(data, bytes) and data:
                    pictures.append((data, mime))
        # FLAC and Ogg Vorbis artwork is exposed through the pictures property.
        for picture in getattr(audio, "pictures", []) or []:
            data = getattr(picture, "data", b"")
            if data:
                pictures.append((data, getattr(picture, "mime", "image/jpeg")))

    cover = ""
    if pictures:
        data, mime = max(pictures, key=lambda item: len(item[0]))
        if len(data) <= 64 * 1024:
            cover = f"data:{mime};base64," + base64.b64encode(data).decode("ascii")

    return {
        "path": str(path.resolve()),
        "title": title,
        "artist": artist,
        "album": album,
        "genre": genre,
        "duration": round(float(audio.info.length), 3) if audio and audio.info else 0,
        "bitrate": int(getattr(audio.info, "bitrate", 0) or 0) if audio and audio.info else 0,
        "artwork": cover,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Read audio tags locally; audio files are never uploaded.")
    parser.add_argument("files", nargs="+", type=Path, help="Audio file paths")
    args = parser.parse_args()
    results = []
    for path in args.files:
        try:
            results.append(read_track(path))
        except Exception as error:  # Report individual unsupported/corrupt files without losing the rest.
            results.append({"path": str(path), "error": str(error)})
    print(json.dumps(results, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
