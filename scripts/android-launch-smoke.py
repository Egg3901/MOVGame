#!/usr/bin/env python3
"""Capture real native Android flows, semantic markers, keyboard and restart."""
import importlib.util
import pathlib
import re
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
semantics_spec = importlib.util.spec_from_file_location('ui_semantics', pathlib.Path(__file__).with_name('android-ui-semantics.py'))
ui_semantics = importlib.util.module_from_spec(semantics_spec)
semantics_spec.loader.exec_module(ui_semantics)


def adb(*args, binary=False):
    return subprocess.check_output(['adb', *args], timeout=25, text=not binary)


def semantic_text(name):
    path = output / f'{name}.xml'
    path.unlink(missing_ok=True)
    try:
        # A null-root dump exits successfully but leaves the old device file.
        # Remove it first so every assertion uses the current window's tree.
        adb('shell', 'rm', '-f', '/sdcard/mov-ui.xml')
        adb('shell', 'uiautomator', 'dump', '/sdcard/mov-ui.xml')
        xml = adb('shell', 'cat', '/sdcard/mov-ui.xml')
        nodes = ET.fromstring(xml).iter('node')
        text = ' '.join(node.get('text', '') + ' ' + node.get('content-desc', '') for node in nodes).upper()
        path.write_text(xml)
        return text
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired, ET.ParseError):
        print(f'Accessibility tree not ready for {name}; waiting for a fresh dump', flush=True)
        return ''


def capture(name, marker, mode='game'):
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        time.sleep(1)
        alive = subprocess.run(['adb', 'shell', 'pidof', PACKAGE], capture_output=True, text=True, timeout=25)
        if alive.returncode != 0 or not alive.stdout.strip():
            raise RuntimeError(f'App exited during {name}')
        if marker.upper() not in semantic_text(name):
            continue
        path = output / f'{name}.png'
        path.write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
        ready, coverage = probe.check_screen(path, mode)
        if ready:
            print(f'PASS {name}: {marker}; {coverage}', flush=True)
            return
    raise RuntimeError(f'{name} did not render its expected content: {marker}')


def launch(flow):
    adb('shell', 'am', 'force-stop', PACKAGE)
    adb('shell', 'am', 'start', '-W', '-n', f'{PACKAGE}/.MainActivity', '--es', 'mov_capture', flow)


def wait_ask(marker, name):
    deadline = time.monotonic() + 70
    while time.monotonic() < deadline:
        log = adb('logcat', '-d', '-s', 'MOVAsk:I', '*:S')
        if marker in log:
            (output / f'{name}-console.log').write_text(log)
            return log
        if not adb('shell', 'pidof', PACKAGE).strip():
            raise RuntimeError(f'App exited during {name}')
        time.sleep(1)
    (output / f'{name}-console.log').write_text(log)
    raise RuntimeError(f'{name} never reached {marker}')


def tap_label(label, direction='down', suffix=False, prefix=False):
    width, height = map(int, adb('shell', 'wm', 'size').strip().split()[-1].split('x'))
    for attempt in range(12):
        if not semantic_text(f'tap-{attempt}'):
            time.sleep(1)
            continue
        nodes = ET.fromstring((output / f'tap-{attempt}.xml').read_text()).iter('node')
        for node in nodes:
            text = node.get('text', '').strip()
            matches = text.endswith(label) if suffix else text.startswith(label) if prefix else text == label
            if not matches or node.get('enabled') != 'true':
                continue
            bounds = list(map(int, re.findall(r'\d+', node.get('bounds', ''))))
            if len(bounds) == 4 and bounds[2] > bounds[0] and bounds[3] > bounds[1]:
                # Leave space below the button for the updated queue count.
                if bounds[3] > height * 2 // 3:
                    adb('shell', 'input', 'swipe', str(width // 2), str(height * 3 // 4), str(width // 2), str(height // 2), '300')
                    break
                adb('shell', 'input', 'tap', str((bounds[0] + bounds[2]) // 2), str((bounds[1] + bounds[3]) // 2))
                time.sleep(0.5)
                return
        else:
            start, end = (height * 3 // 4, height // 3) if direction == 'down' else (height // 3, height * 3 // 4)
            adb('shell', 'input', 'swipe', str(width // 2), str(start), str(width // 2), str(end), '300')
    raise RuntimeError(f'Could not reach control: {label}')


try:
    adb('install', '-r', sys.argv[1])
    adb('shell', 'pm', 'clear', PACKAGE)
    adb('shell', 'settings', 'put', 'global', 'window_animation_scale', '0')
    adb('shell', 'settings', 'put', 'global', 'transition_animation_scale', '0')
    adb('shell', 'settings', 'put', 'global', 'animator_duration_scale', '0')
    # Ask is an embedded page with the same bounded campaign handoff as iOS.
    adb('logcat', '-c')
    launch('home')
    capture('ask-home-entry', 'Ask about', 'ask')
    launch('game-US')
    capture('ask-campaign-entry', 'CAMPAIGN DESK')
    tap_label('Ask about this campaign')
    wait_ask('MOV_ASK_DOCUMENT_READY', 'ask-live')
    wait_ask('MOV_ASK_SNAPSHOT_READY', 'ask-snapshot')
    capture('ask-live', 'Margin of Victory', 'ask')
    tap_label('Sign in', prefix=True)
    wait_ask('MOV_ASK_SIGNIN_REACHED_EMBEDDED_AUTH', 'ask-signin-form')
    capture('ask-signin', 'Password', 'ask')
    text = ' '.join(node.get('text', '') + ' ' + node.get('content-desc', '')
                    for node in ET.fromstring((output / 'ask-signin.xml').read_text()).iter('node')).upper()
    if 'PASSWORD' not in text or ('EMAIL' not in text and 'USERNAME' not in text):
        raise RuntimeError('Ask sign-in did not show the embedded identity form')
    adb('logcat', '-c')
    launch('ask-DE')
    wait_ask('MOV_ASK_SNAPSHOT_COUNTRY_Germany', 'ask-de-snapshot')
    capture('ask-de', 'Margin of Victory', 'ask')
    for flow in ('ask-offline', 'ask-blank', 'ask-retry'):
        adb('logcat', '-c')
        launch(flow)
        log = wait_ask('MOV_ASK_ERROR_SHOWN', flow)
        if log.count('MOV_ASK_AUTOMATIC_RETRY') != 1 or 'MOV_ASK_DOCUMENT_READY' in log:
            raise RuntimeError(f'{flow} did not show a bounded failure')
        capture(flow, 'Try again', 'ask')
        log = adb('logcat', '-d', '-s', 'MOVAsk:I', '*:S')
        if log.count('MOV_ASK_ERROR_SHOWN') != 1 or 'MOV_ASK_DOCUMENT_READY' in log:
            raise RuntimeError(f'{flow} did not preserve its terminal failure state')
        (output / f'{flow}-console.log').write_text(log)
        if flow == 'ask-retry':
            if log.count('MOV_ASK_FIXTURE_RESPONSE_503') != 2:
                raise RuntimeError('Ask retry did not preserve both failed HTTP responses')
            tap_label('Try again')
            retried = wait_ask('MOV_ASK_DOCUMENT_READY', 'ask-retried')
            if retried.count('MOV_ASK_FIXTURE_RESPONSE_200') != 1:
                raise RuntimeError('Try again did not load the successful third HTTP response')
            capture('ask-retried', 'Ask recovered', 'ask')
    routes = [('home', 'Margin of'), ('library', 'Choose your election'), ('setup', 'Choose your path'),
              ('store', 'History is yours to play'), ('account', 'YOUR ACCOUNT'), ('settings', 'Sound effects'), ('guide', 'HOW TO PLAY'),
              ('editor', 'SCENARIO EDITOR'), ('saves', 'SAVED CAMPAIGNS'), ('analysis', 'CAMPAIGN ANALYSIS'), ('replay', 'CAMPAIGN REPLAY AND REPORT')]
    for flow, marker in routes:
        launch(flow)
        capture(flow, marker, 'ask')
    for country in ('US', 'UK', 'CA', 'DE', 'FR', 'AU'):
        for kind in ('game', 'custom', 'results'):
            name = f'{kind}-{country}'
            launch(name)
            marker = 'CAMPAIGN DESK' if country == 'US' and kind != 'results' else 'ELECTION RESULT' if kind == 'results' and country != 'US' else 'CAMPAIGN SCORE' if kind == 'results' else 'WEEK'
            capture(name, marker)
    # Check the actual accessibility tree, including each switch's name.
    launch('settings')
    capture('settings-accessibility', 'Sound effects', 'ask')
    remaining = {'Sound effects', 'Reduce motion', 'Keyboard shortcuts'}
    width, height = map(int, adb('shell', 'wm', 'size').strip().split()[-1].split('x'))
    for attempt in range(4):
        name = f'settings-accessibility-{attempt}'
        if not semantic_text(name):
            continue
        remaining -= ui_semantics.named_toggles((output / f'{name}.xml').read_text())
        if not remaining:
            break
        adb('shell', 'input', 'swipe', str(width // 2), str(height * 3 // 4), str(width // 2), str(height // 3), '300')
    if remaining:
        raise RuntimeError(f'Settings switches have no accessible names: {sorted(remaining)}')
    # Use explicit dp widths for narrow phones and tablet navigation.
    adb('shell', 'settings', 'put', 'system', 'font_scale', '1.3')
    for layout, size, density in [('small-phone', '640x1136', '320'), ('tablet', '1920x1200', '240')]:
        adb('shell', 'wm', 'size', size)
        adb('shell', 'wm', 'density', density)
        time.sleep(2)
        for flow, marker in [('settings', 'Sound effects'), ('editor', 'SCENARIO EDITOR'), ('saves', 'SAVED CAMPAIGNS'), ('game-DE', 'WEEK')]:
            launch(flow)
            capture(f'{layout}-{flow}', marker, 'ask')
    adb('shell', 'settings', 'put', 'system', 'font_scale', '1.0')
    adb('shell', 'wm', 'size', 'reset')
    adb('shell', 'wm', 'density', 'reset')
    # A successful queue edit must repaint without another navigation or selection.
    launch('game-US')
    capture('queue-before', 'CAMPAIGN DESK')
    tap_label('Fundraise')
    tap_label('Add to day 1')
    capture('queue-added', '1 planned', 'ask')
    tap_label('Add to day 1')
    capture('queue-added-twice', '2 planned', 'ask')
    tap_label('Fundraise  ×', suffix=True)
    capture('queue-removed', '1 planned', 'ask')
    tap_label('Clear all', direction='up')
    capture('queue-cleared', '0 planned', 'ask')
    # Verify a blank week's keyboard command asks before consuming the week.
    launch('game-US')
    capture('keyboard-before', 'WEEK 1/9')
    # Android consumes the first navigation key when leaving touch mode.
    # Establish focus with Tab before exercising the campaign shortcut.
    adb('shell', 'input', 'keyevent', '61')
    capture('keyboard-navigation-focus', 'WEEK 1/9')
    focused = ET.fromstring((output / 'keyboard-navigation-focus.xml').read_text())
    if not any(node.get('focused') == 'true' for node in focused.iter('node')):
        raise RuntimeError('Keyboard navigation did not focus a campaign control')
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
