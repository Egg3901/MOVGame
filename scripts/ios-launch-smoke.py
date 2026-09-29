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
    run('xcrun', 'simctl', 'io', device, 'screenshot', str(output / 'launch.png'))
    if process.poll() is not None:
        console.flush()
        print((output / 'launch-console.log').read_text())
        raise SystemExit('FAIL: app exited before the screenshot was captured')
    print('PASS: app stayed running for 30 seconds; launch screenshot captured')
    if os.environ.get('MOV_CAPTURE_SCREENS') == '1':
        process.terminate()
        process.wait(timeout=5)
        for name, argument, seconds in [
            ('setup', '--mov-capture-setup', 8),
            ('campaign', '--mov-capture-game', 20),
        ]:
            subprocess.run(['xcrun', 'simctl', 'terminate', device, bundle], check=False)
            with (output / f'{name}-console.log').open('w') as preview_console:
                preview = subprocess.Popen(
                    ['xcrun', 'simctl', 'launch', '--console', device, bundle, argument],
                    stdout=preview_console, stderr=subprocess.STDOUT)
                try:
                    for second in range(seconds):
                        time.sleep(1)
                        if preview.poll() is not None:
                            preview_console.flush()
                            print((output / f'{name}-console.log').read_text())
                            raise SystemExit(f'FAIL: app exited during {name} capture after {second + 1}s')
                    run('xcrun', 'simctl', 'io', device, 'screenshot', str(output / f'{name}.png'))
                    print(f'PASS: {name} screenshot captured')
                finally:
                    preview.terminate()
                    preview.wait(timeout=5)
finally:
    if process.poll() is None:
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
