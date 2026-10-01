#!/usr/bin/env python3
"""Reuse the reviewed simulator binary on a small phone and an iPad."""
import json
import os
import pathlib
import subprocess
import sys

app = pathlib.Path(sys.argv[1]).resolve()
output = pathlib.Path(sys.argv[2]).resolve()
types = json.loads(subprocess.check_output(['xcrun', 'simctl', 'list', 'devicetypes', '--json'], text=True))['devicetypes']
runtimes = json.loads(subprocess.check_output(['xcrun', 'simctl', 'list', 'runtimes', '--json'], text=True))['runtimes']
runtime = next(r for r in runtimes if r['isAvailable'] and r['identifier'].startswith('com.apple.CoreSimulator.SimRuntime.iOS-26'))['identifier']
phone = next((t for t in types if t['name'] == 'iPhone SE (3rd generation)'), None)
if not phone:
    raise SystemExit('Required small iPhone SE simulator type is missing')
tablet = next((t for t in types if t['name'] in ('iPad (A16)', 'iPad (10th generation)', 'iPad Air 11-inch (M3)')), None)
if not tablet:
    raise SystemExit('Required iPad simulator type is missing')
for label, device_type in [('small-phone', phone), ('tablet', tablet)]:
    device = subprocess.check_output(['xcrun', 'simctl', 'create', f'MOV {label}', device_type['identifier'], runtime], text=True).strip()
    try:
        env = dict(os.environ, SIMULATOR_UDID=device, MOV_CAPTURE_SCREENS='1',
                   MOV_CAPTURE_NAMES='setup,library,saves,settings,editor,world-de')
        subprocess.run([sys.executable, str(pathlib.Path(__file__).with_name('ios-launch-smoke.py')), str(app), str(output / label)],
                       env=env, check=True, timeout=600)
    finally:
        subprocess.run(['xcrun', 'simctl', 'shutdown', device], timeout=30, check=False)
        subprocess.run(['xcrun', 'simctl', 'delete', device], timeout=30, check=False)
