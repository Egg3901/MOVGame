#!/usr/bin/env python3
"""Install and launch the built app, failing if it exits during startup."""
import json
import os
import pathlib
import subprocess
import sys
import time

app = pathlib.Path(sys.argv[1]).resolve()
output = pathlib.Path(sys.argv[2]).resolve()
output.mkdir(parents=True, exist_ok=True)
bundle = 'com.lakesidegames.electioneer'


def run(*args, timeout=120):
    print("Running:", " ".join(args), flush=True)
    return subprocess.check_output(args, text=True, timeout=timeout).strip()


devices = json.loads(run('xcrun', 'simctl', 'list', 'devices', 'available', '--json'))
requested = os.environ.get('SIMULATOR_UDID')
phones = [dict(d, runtime=runtime)
          for runtime, group in devices['devices'].items() if 'iOS' in runtime
          for d in group if d['name'].startswith('iPhone')
          and (not requested or d['udid'] == requested)]
if not phones:
    raise SystemExit('No available iPhone simulator')
device = phones[0]['udid']
print(json.dumps(phones[0]), flush=True)
(output / 'simulator.json').write_text(json.dumps(phones[0], indent=2))
if phones[0]['state'] != 'Booted':
    run('xcrun', 'simctl', 'boot', device)
run('xcrun', 'simctl', 'bootstatus', device, '-b', timeout=300)
run('xcrun', 'simctl', 'install', device, str(app), timeout=300)
console = (output / 'launch-console.log').open('w')
process = subprocess.Popen(['xcrun', 'simctl', 'launch', '--console', device, bundle],
                           stdout=console, stderr=subprocess.STDOUT)
try:
    for second in range(30):
        time.sleep(1)
        if process.poll() is not None:
            console.flush()
            print((output / 'launch-console.log').read_text())
            raise SystemExit(f'FAIL: app exited during startup after {second + 1}s')
    listing = run('xcrun', 'simctl', 'spawn', device, 'launchctl', 'list')
    entries = [line for line in listing.splitlines() if bundle in line]
    if not any(line.split()[0].isdigit() for line in entries):
        raise SystemExit('FAIL: app has no running process after launch')
    run('xcrun', 'simctl', 'io', device, 'screenshot', str(output / 'launch.png'))
    print('PASS: app stayed running for 30 seconds; launch screenshot captured')
finally:
    process.terminate()
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()
    console.close()
    reports = pathlib.Path.home() / 'Library/Logs/DiagnosticReports'
    for report in reports.glob('MOVGameiOS*'):
        if report.is_file():
            (output / report.name).write_bytes(report.read_bytes())
            print(report.read_text(errors='replace')[:30000])
