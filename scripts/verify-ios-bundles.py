#!/usr/bin/env python3
"""Ensure the iOS app contains every content bundle consumed by the native engine."""
import hashlib
import pathlib
import sys

app = pathlib.Path(sys.argv[1])
source = pathlib.Path(__file__).resolve().parents[1] / 'apps/native/shared/src/commonMain/resources/bundles'
destination = app / 'bundles'
source_files = sorted(source.rglob('*.json'))
if not source_files:
    raise SystemExit(f'No source content bundles found at {source}')
errors = []
for content in source_files:
    relative = content.relative_to(source)
    shipped = destination / relative
    if not shipped.is_file():
        errors.append(f'Missing {shipped}')
    elif hashlib.sha256(content.read_bytes()).digest() != hashlib.sha256(shipped.read_bytes()).digest():
        errors.append(f'Content differs: {shipped}')
if errors:
    raise SystemExit('\n'.join(errors))
print(f'PASS: {len(source_files)} content bundles present and byte-identical in {app}')
