#!/usr/bin/env python3
"""Install and launch the built app, failing if it exits during startup."""
import json
import os
import pathlib
import runpy
import subprocess
import sys
import time
from ios_simulator_control import launch, screenshot, stop

app = pathlib.Path(sys.argv[1]).resolve()
output = pathlib.Path(sys.argv[2]).resolve()
output.mkdir(parents=True, exist_ok=True)
bundle = 'com.lakesidegames.electioneer'
check_screen = runpy.run_path(str(pathlib.Path(__file__).with_name('ios-screen-ready.py')))['check_screen']


def run(*args, timeout=120):
    print("Running:", " ".join(args), flush=True)
    return subprocess.check_output(args, text=True, timeout=timeout).strip()


def capture_ready(name, process, attempts=3):
    capture = output / f'{name}.png'
    for attempt in range(attempts):
        if attempt:
            time.sleep(10)
        if process.poll() is not None:
            raise SystemExit(f'FAIL: app exited before {name} was rendered')
        screenshot(device, capture)
        if process.poll() is not None:
            raise SystemExit(f'FAIL: app exited during {name} capture')
        probe = output / f'{name}-probe.png'
        try:
            subprocess.run(['sips', '--resampleWidth', '320', str(capture), '--out', str(probe)],
                           check=True, capture_output=True, timeout=30)
            ready, summary = check_screen(probe, 'ask' if name == 'ask' else 'game')
        finally:
            probe.unlink(missing_ok=True)
        print(f'{name} capture {attempt + 1}: {summary}', flush=True)
        if ready:
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
if os.environ.get('MOV_FRESH_SIMULATOR') == '1' and not requested:
    template = phones[0]
    device = run('xcrun', 'simctl', 'create', 'MOV launch smoke', template['deviceTypeIdentifier'], template['runtime'])
    phones[0] = dict(udid=device, name='MOV launch smoke', state='Shutdown', runtime=template['runtime'],
                     deviceTypeIdentifier=template['deviceTypeIdentifier'])
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
        subprocess.run(['sips', '--resampleWidth', '320', str(boot_image), '--out', str(probe)],
                       check=True, capture_output=True, timeout=30)
        ready, summary = check_screen(probe, 'boot')
        print(f'boot capture {attempt + 1}: {summary}', flush=True)
        if ready:
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
process, console = launch(device, bundle, output / 'launch-console.log')
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
            ('library', '--mov-capture-library', 8),
            ('daily', '--mov-capture-daily', 8),
            ('analysis', '--mov-capture-analysis', 30),
            ('replay', '--mov-capture-replay', 30),
            ('saves', '--mov-capture-saves', 30),
            ('account', '--mov-capture-account', 8),
            ('settings', '--mov-capture-settings', 8),
            ('guide', '--mov-capture-guide', 8),
            ('champions', '--mov-capture-champions', 20),
            ('lakeside-login', '--mov-capture-lakeside-login', 30),
            ('world-uk', '--mov-capture-world-uk', 20),
            ('world-ca', '--mov-capture-world-ca', 20),
            ('world-de', '--mov-capture-world-de', 20),
            ('world-fr', '--mov-capture-world-fr', 20),
            ('world-au', '--mov-capture-world-au', 20),
            ('world-results', '--mov-capture-world-results', 20),
            ('campaign', '--mov-capture-game', 20),
            ('plan', '--mov-capture-plan', 20),
            ('results', '--mov-capture-results', 20),
            ('reveal', '--mov-capture-reveal', 20),
            ('ask', '--mov-capture-ask', 20),
            ('ask-login', '--mov-capture-ask-login', 20),
        ]:
            subprocess.run(['xcrun', 'simctl', 'terminate', device, bundle], check=False)
            preview, preview_console = launch(device, bundle, output / f'{name}-console.log', [argument])
            with preview_console:
                try:
                    for second in range(seconds):
                        time.sleep(1)
                        if preview.poll() is not None:
                            preview_console.flush()
                            print((output / f'{name}-console.log').read_text())
                            raise SystemExit(f'FAIL: app exited during {name} capture after {second + 1}s')
                    if name in ('ask-login', 'lakeside-login'):
                        marker = 'MOV_ASK_SIGNIN_REACHED_EMBEDDED_AUTH' if name == 'ask-login' else 'MOV_LAKESIDE_SIGNIN_REACHED_AUTH'
                        deadline = time.monotonic() + 90
                        while marker not in (output / f'{name}-console.log').read_text() and time.monotonic() < deadline:
                            if preview.poll() is not None:
                                raise SystemExit(f'FAIL: app exited while waiting for {name} authentication')
                            time.sleep(1)
                        if marker not in (output / f'{name}-console.log').read_text():
                            raise SystemExit(f'FAIL: {name} did not reach auth inside the app webview')
                        screenshot(device, output / f'{name}.png')
                        print(f'PASS: {name} reached auth inside the app webview', flush=True)
                    else:
                        capture_ready(name, preview)
                finally:
                    stop(preview)
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
