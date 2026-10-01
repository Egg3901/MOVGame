#!/usr/bin/env python3
"""Verify startup configuration in the manifest packaged inside the APK."""
import pathlib
import sys
import xml.etree.ElementTree as ET

android = '{http://schemas.android.com/apk/res/android}'
root = ET.parse(pathlib.Path(sys.argv[1])).getroot()
application = root.find('application')
if application is None:
    raise SystemExit('FAIL: packaged Android manifest has no application')
if application.get(android + 'name') != 'com.lakesidegames.electioneer.MovApp':
    raise SystemExit('FAIL: packaged Android app bypasses MovApp startup')
metadata = {node.get(android + 'name'): node.get(android + 'value')
            for node in application.findall('meta-data')}
# Content providers run before Application.onCreate and its optional DSN guard.
if metadata.get('io.sentry.auto-init') != 'false':
    raise SystemExit('FAIL: Sentry can initialize before MovApp supplies a DSN')
print('PASS: packaged Android startup leaves Sentry initialization to MovApp')
