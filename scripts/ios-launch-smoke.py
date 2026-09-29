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


def capture_ready(name, process, attempts=3):
    screenshot = output / f'{name}.png'
    for attempt in range(attempts):
        if attempt:
            time.sleep(10)
        if process.poll() is not None:
            raise SystemExit(f'FAIL: app exited before {name} was rendered')
        run('xcrun', 'simctl', 'io', device, 'screenshot', str(screenshot))
        probe = output / f'{name}-probe.png'
        try:
            subprocess.run(['sips', '-Z', '160', str(screenshot), '--out', str(probe)],
                           check=True, capture_output=True, timeout=30)
            checked = subprocess.run(
                [sys.executable, str(pathlib.Path(__file__).with_name('ios-screen-ready.py')),
                 str(probe), *(['--ask'] if name == 'ask' else [])],
                text=True, capture_output=True, timeout=30)
        finally:
            probe.unlink(missing_ok=True)
        print(f'{name} capture {attempt + 1}: {checked.stdout.strip()}', flush=True)
        if checked.returncode == 0:
            print(f'PASS: {name} app screen rendered', flush=True)
            return
    raise SystemExit(f'FAIL: {name} remained blank or showed the simulator home screen')


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
for attempt in range(24):
    boot_image = output / 'boot.png'
    boot_image.unlink(missing_ok=True)
    try:
        run('xcrun', 'simctl', 'io', device, 'screenshot', str(boot_image))
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
        print(f'boot capture {attempt + 1}: screenshot command failed: {error}', flush=True)
        # CoreSimulator sometimes writes the image and then hangs while
        # returning from simctl. The file is still useful for checking whether
        # SpringBoard is ready; only retry immediately when no image exists.
        if not boot_image.is_file():
            time.sleep(10)
            continue
    probe = output / 'boot-probe.png'
    try:
        subprocess.run(['sips', '-Z', '160', str(boot_image), '--out', str(probe)],
                       check=True, capture_output=True, timeout=30)
        checked = subprocess.run(
            [sys.executable, str(pathlib.Path(__file__).with_name('ios-screen-ready.py')),
             str(probe), '--boot'], text=True, capture_output=True, timeout=30)
        print(f'boot capture {attempt + 1}: {checked.stdout.strip()}', flush=True)
        if checked.returncode == 0:
            print('PASS: simulator home screen is ready', flush=True)
            break
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
        print(f'boot capture {attempt + 1}: {error}', flush=True)
    finally:
        probe.unlink(missing_ok=True)
    time.sleep(10)
else:
    raise SystemExit('FAIL: simulator never left Apple boot screen; app was not launched')
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
    capture_ready('launch', process)
    if os.environ.get('MOV_CAPTURE_SCREENS') == '1':
        process.terminate()
        process.wait(timeout=5)
        for name, argument, seconds in [
            ('setup', '--mov-capture-setup', 8),
            ('setup-2016', '--mov-capture-setup-2016', 8),
            ('campaign', '--mov-capture-game', 20),
            ('plan', '--mov-capture-plan', 20),
            ('ask', '--mov-capture-ask', 20),
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
                    capture_ready(name, preview)
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
