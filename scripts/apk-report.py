#!/usr/bin/env python3
"""Measure shipped bytes and fail if a compact APK loses its packaging guarantees."""
import argparse
import hashlib
import json
import struct
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
        for entry in native:
            elf = archive.read(entry)
            assert elf[:6] == b"\x7fELF\x02\x01", "Expected little-endian ELF64"
            offset = struct.unpack_from("<Q", elf, 32)[0]
            stride, count = struct.unpack_from("<HH", elf, 54)
            for index in range(count):
                header = offset + index * stride
                if struct.unpack_from("<I", elf, header)[0] == 1:  # PT_LOAD
                    file_offset, address = struct.unpack_from("<QQ", elf, header + 8)
                    alignment = struct.unpack_from("<Q", elf, header + 48)[0]
                    assert alignment >= 16384 and file_offset % 16384 == address % 16384, f"Not 16 KiB aligned: {entry.filename}"
    report = {"apk": args.apk.name, "bytes": args.apk.stat().st_size,
              "sha256": hashlib.sha256(args.apk.read_bytes()).hexdigest()}
    for label, group in (("native", native), ("dex", dex)):
        report[label] = {"raw_bytes": sum(e.file_size for e in group),
                         "packed_bytes": sum(e.compress_size for e in group),
                         "entries": [{"name": e.filename, "raw_bytes": e.file_size,
                                      "packed_bytes": e.compress_size, "method": e.compress_type} for e in group]}
    print(json.dumps(report, indent=2))
