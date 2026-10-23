#!/usr/bin/env python3
"""Verify bundled assets or an optional model deployment; does not download files."""

import argparse
import hashlib
import json
from pathlib import Path


def verify(manifest_path: Path, root: Path) -> int:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    failures = []
    entries = manifest["files"]
    if not isinstance(entries, list) or not entries:
        raise ValueError("Manifest must contain a nonempty files array")
    entries = list(entries)
    if "deploymentConfig" in manifest:
        entries.append(manifest["deploymentConfig"])
    base = root.resolve()
    for entry in entries:
        path = (base / entry["path"]).resolve()
        if not path.is_relative_to(base) or not path.is_file():
            failures.append(f"missing or invalid path: {entry['path']}")
            continue
        if path.stat().st_size != entry["bytes"]:
            failures.append(f"size mismatch: {entry['path']}")
            continue
        digest = hashlib.sha256()
        with path.open("rb") as stream:
            for block in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(block)
        if digest.hexdigest() != entry["sha256"]:
            failures.append(f"SHA-256 mismatch: {entry['path']}")
    for failure in failures:
        print(failure)
    print(f"Verified {len(entries) - len(failures)}/{len(entries)} files")
    return 1 if failures else 0


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=root / "third_party/assets.json")
    parser.add_argument("--root", type=Path, default=root)
    args = parser.parse_args()
    try:
        return verify(args.manifest, args.root)
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(1, f"Cannot verify assets: {error}\n")


if __name__ == "__main__":
    raise SystemExit(main())
