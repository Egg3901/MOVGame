"""Bounded simulator launch and screenshot commands, shared by smoke captures."""
import pathlib
import os
import pty
import re
import subprocess
import threading
import time


def screenshot(device, destination):
    destination = pathlib.Path(destination)
    command = ['xcrun', 'simctl', 'io', device, 'screenshot', str(destination)]
    for attempt in range(2):
        destination.unlink(missing_ok=True)
        print('Running:', ' '.join(command), flush=True)
        try:
            subprocess.check_output(command, text=True, timeout=120)
            return
        except subprocess.TimeoutExpired:
            # A fresh image still needs the existing rendered-screen probe.
            if destination.is_file() and destination.stat().st_size > 0:
                print('Screenshot written; simctl response timed out', flush=True)
                return
            if attempt == 1:
                raise
            print('Screenshot command stalled without an image; retrying once', flush=True)
            time.sleep(10)


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
        master, terminal = pty.openpty()
        try:
            process = subprocess.Popen(['xcrun', 'simctl', 'launch', '--console', device, bundle, *arguments],
                                       stdout=terminal, stderr=terminal)
        except Exception:
            os.close(master)
            console.close()
            raise
        finally:
            os.close(terminal)
        # simctl buffers its PID acknowledgement when stdout is a file. A PTY
        # keeps the console interactive; continuously tee it into the artifact.
        def tee(descriptor=master, destination=console):
            try:
                while True:
                    data = os.read(descriptor, 4096)
                    if not data:
                        break
                    destination.write(data.decode(errors='replace'))
                    destination.flush()
            except (OSError, ValueError):
                pass
            finally:
                os.close(descriptor)
        threading.Thread(target=tee, daemon=True).start()
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
