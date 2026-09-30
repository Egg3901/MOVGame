"""Bounded simulator launch and screenshot commands, shared by smoke captures."""
import pathlib
import re
import subprocess
import time


def screenshot(device, destination):
    destination = pathlib.Path(destination)
    destination.unlink(missing_ok=True)
    command = ['xcrun', 'simctl', 'io', device, 'screenshot', str(destination)]
    print('Running:', ' '.join(command), flush=True)
    try:
        subprocess.check_output(command, text=True, timeout=120)
    except subprocess.TimeoutExpired:
        # simctl can finish writing a fresh image but stall returning to the
        # caller. The existing rendered-screen probe must still validate it.
        if not destination.is_file() or destination.stat().st_size == 0:
            raise
        print('Screenshot written; simctl response timed out', flush=True)


def stop(process):
    if process.poll() is None:
        process.terminate()
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)


def launch(device, bundle, console_path, arguments=(), acknowledgement_seconds=60):
    console_path = pathlib.Path(console_path)
    for attempt in range(2):
        console = console_path.open('w')
        process = subprocess.Popen(['xcrun', 'simctl', 'launch', '--console', device, bundle, *arguments],
                                   stdout=console, stderr=subprocess.STDOUT)
        for second in range(acknowledgement_seconds):
            if process.poll() is not None:
                console.close()
                raise RuntimeError(f'App launch exited before acknowledgement: {console_path.read_text()}')
            if re.search(rf'{re.escape(bundle)}: [1-9][0-9]*', console_path.read_text()):
                print(f'PASS: simulator acknowledged launch on attempt {attempt + 1}', flush=True)
                return process, console
            time.sleep(1)
        stop(process)
        console.close()
        console_path.with_name(f'{console_path.stem}-attempt-{attempt + 1}.log').write_text(console_path.read_text())
        print(f'Launch attempt {attempt + 1} received no app PID; cancelling stalled simctl', flush=True)
    raise RuntimeError('Simulator did not acknowledge app launch after two bounded attempts')
