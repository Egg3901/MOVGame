#!/usr/bin/env python3
"""Capture real native Android flows, semantic markers, keyboard and restart."""
import importlib.util
import pathlib
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PACKAGE = 'com.lakesidegames.electioneer'
output = pathlib.Path(sys.argv[2])
output.mkdir(parents=True, exist_ok=True)
spec = importlib.util.spec_from_file_location('screen_ready', pathlib.Path(__file__).with_name('ios-screen-ready.py'))
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


def adb(*args, binary=False):
    return subprocess.check_output(['adb', *args], timeout=25, text=not binary)


def semantic_text(name):
    adb('shell', 'uiautomator', 'dump', '/sdcard/mov-ui.xml')
    xml = adb('shell', 'cat', '/sdcard/mov-ui.xml')
    (output / f'{name}.xml').write_text(xml)
    return ' '.join(node.get('text', '') + ' ' + node.get('content-desc', '') for node in ET.fromstring(xml).iter('node')).upper()


def capture(name, marker, mode='game'):
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        time.sleep(1)
        if not adb('shell', 'pidof', PACKAGE).strip():
            raise RuntimeError(f'App exited during {name}')
        path = output / f'{name}.png'
        path.write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
        ready, coverage = probe.check_screen(path, mode)
        if ready and marker.upper() in semantic_text(name):
            print(f'PASS {name}: {marker}; {coverage}', flush=True)
            return
    raise RuntimeError(f'{name} did not render its expected content: {marker}')


def launch(flow):
    adb('shell', 'am', 'force-stop', PACKAGE)
    adb('shell', 'am', 'start', '-W', '-n', f'{PACKAGE}/.MainActivity', '--es', 'mov_capture', flow)


try:
    adb('install', '-r', sys.argv[1])
    adb('shell', 'pm', 'clear', PACKAGE)
    adb('shell', 'settings', 'put', 'global', 'window_animation_scale', '0')
    adb('shell', 'settings', 'put', 'global', 'transition_animation_scale', '0')
    adb('shell', 'settings', 'put', 'global', 'animator_duration_scale', '0')
    routes = [('account', 'YOUR ACCOUNT'), ('settings', 'Sound effects'), ('guide', 'HOW TO PLAY'),
              ('editor', 'SCENARIO EDITOR'), ('saves', 'SAVED CAMPAIGNS'), ('analysis', 'Back to campaign'), ('replay', 'Campaign replay')]
    for flow, marker in routes:
        launch(flow)
        capture(flow, marker, 'ask')
    for country in ('US', 'UK', 'CA', 'DE', 'FR', 'AU'):
        for kind in ('game', 'custom', 'results'):
            name = f'{kind}-{country}'
            launch(name)
            marker = 'CAMPAIGN DESK' if country == 'US' and kind != 'results' else 'ELECTION RESULT' if kind == 'results' and country != 'US' else 'CAMPAIGN SCORE' if kind == 'results' else 'WEEK'
            capture(name, marker)
    # Use explicit dp widths for narrow phones and tablet navigation.
    for layout, size, density in [('small-phone', '640x1136', '320'), ('tablet', '1920x1200', '240')]:
        adb('shell', 'wm', 'size', size)
        adb('shell', 'wm', 'density', density)
        time.sleep(2)
        for flow, marker in [('settings', 'Sound effects'), ('editor', 'SCENARIO EDITOR'), ('saves', 'SAVED CAMPAIGNS'), ('game-DE', 'WEEK')]:
            launch(flow)
            capture(f'{layout}-{flow}', marker, 'ask')
    adb('shell', 'wm', 'size', 'reset')
    adb('shell', 'wm', 'density', 'reset')
    # Verify a blank week's keyboard command asks before consuming the week.
    launch('game-US')
    capture('keyboard-before', 'WEEK 1/9')
    adb('shell', 'input', 'keyevent', '66')
    capture('keyboard-empty-week', 'Unspent slots win nothing', 'ask')
    adb('shell', 'input', 'keyevent', '4')
    capture('keyboard-cancelled', 'WEEK 1/9')
    # Restart restores a real custom country campaign from persisted storage.
    launch('custom-DE')
    capture('custom-before-restart', 'WEEK 1 OF')
    launch('resume')
    capture('custom-after-restart', 'WEEK 1 OF')
finally:
    (output / 'logcat.txt').write_text(adb('logcat', '-d', '-t', '1200'))
    adb('shell', 'am', 'force-stop', PACKAGE)
