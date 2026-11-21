#!/usr/bin/env python3
"""Measure shipped bytes and fail if a compact APK loses its packaging guarantees."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument("apk", type=Path)
parser.add_argument("--compact", action="store_true")
parser.add_argument("--arm64", action="store_true")
args = parser.parse_args()
with zipfile.ZipFile(args.apk) as archive:
    entries = archive.infolist()
    native = [e for e in entries if e.filename.startswith("lib/") and e.filename.endswith(".so")]
    dex = [e for e in entries if e.filename.endswith(".dex")]
    if args.compact:
        assert native and dex, "Missing native libraries or DEX"
        assert all(e.compress_type == zipfile.ZIP_DEFLATED for e in native + dex), "Native libraries and DEX must be compressed"
        assert args.apk.stat().st_size < 15_000_000, "Compact APK exceeds 15 MB size budget"
    if args.arm64:
        assert {e.filename.split('/')[1] for e in native} == {"arm64-v8a"}, "Expected ARM64 only"
    report = {"apk": args.apk.name, "bytes": args.apk.stat().st_size,
              "sha256": hashlib.file_digest(args.apk.open("rb"), "sha256").hexdigest() if hasattr(hashlib, "file_digest") else hashlib.sha256(args.apk.read_bytes()).hexdigest()}
    for label, group in (("native", native), ("dex", dex)):
        report[label] = {"raw_bytes": sum(e.file_size for e in group),
                         "packed_bytes": sum(e.compress_size for e in group),
                         "entries": [{"name": e.filename, "raw_bytes": e.file_size,
                                      "packed_bytes": e.compress_size, "method": e.compress_type} for e in group]}
    print(json.dumps(report, indent=2))
