#!/usr/bin/env python3
"""Ensure the APK ships the exact resources read by the shared classloader."""
import hashlib
import pathlib
import sys
import zipfile

source = pathlib.Path(__file__).resolve().parents[1] / 'apps/native/shared/src/commonMain/resources/bundles'
files = sorted(source.rglob('*.json'))
if not files:
    raise SystemExit('FAIL: no source campaign bundles found')
errors = []
with zipfile.ZipFile(sys.argv[1]) as apk:
    for content in files:
        name = 'bundles/' + content.relative_to(source).as_posix()
        try:
            shipped = apk.read(name)
        except KeyError:
            errors.append(f'Missing {name}')
            continue
        if hashlib.sha256(content.read_bytes()).digest() != hashlib.sha256(shipped).digest():
            errors.append(f'Content differs: {name}')
if errors:
    raise SystemExit('\n'.join(errors))
print(f'PASS: all {len(files)} campaign resources are present and byte-identical in the APK')
