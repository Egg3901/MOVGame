import pathlib
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch
from ios_simulator_control import launch, screenshot


class SimulatorControlTest(unittest.TestCase):
    def test_acknowledged_launch_returns_running_process(self):
        with tempfile.TemporaryDirectory() as folder:
            path = pathlib.Path(folder) / 'console.log'
            process = Mock()
            process.poll.return_value = None
            def begin(*args, **kwargs):
                kwargs['stdout'].write('com.example.app: 123\n')
                kwargs['stdout'].flush()
                return process
            with patch('ios_simulator_control.subprocess.Popen', side_effect=begin):
                result, console = launch('device', 'com.example.app', path, acknowledgement_seconds=2)
                self.assertIs(result, process)
                console.close()

    def test_pending_launch_retries_once_and_fails_with_no_pid(self):
        with tempfile.TemporaryDirectory() as folder:
            process = Mock()
            process.poll.return_value = None
            with patch('ios_simulator_control.subprocess.Popen', return_value=process) as start, patch('ios_simulator_control.time.sleep'):
                with self.assertRaisesRegex(RuntimeError, 'two bounded attempts'):
                    launch('device', 'com.example.app', pathlib.Path(folder) / 'console.log', acknowledgement_seconds=2)
                self.assertEqual(2, start.call_count)
                self.assertEqual(2, process.terminate.call_count)

    def test_exited_app_is_not_retried(self):
        with tempfile.TemporaryDirectory() as folder:
            process = Mock()
            process.poll.return_value = 1
            with patch('ios_simulator_control.subprocess.Popen', return_value=process) as start:
                with self.assertRaisesRegex(RuntimeError, 'exited before acknowledgement'):
                    launch('device', 'com.example.app', pathlib.Path(folder) / 'console.log')
                self.assertEqual(1, start.call_count)

    def test_screenshot_timeout_cannot_accept_an_old_image(self):
        with tempfile.TemporaryDirectory() as folder:
            path = pathlib.Path(folder) / 'screen.png'
            path.write_bytes(b'old image')
            timeout = subprocess.TimeoutExpired('simctl', 120)
            with patch('ios_simulator_control.subprocess.check_output', side_effect=timeout):
                with self.assertRaises(subprocess.TimeoutExpired):
                    screenshot('device', path)
            self.assertFalse(path.exists())

    def test_fresh_screenshot_is_available_for_validation_after_response_timeout(self):
        with tempfile.TemporaryDirectory() as folder:
            path = pathlib.Path(folder) / 'screen.png'
            def capture(*args, **kwargs):
                path.write_bytes(b'fresh image to be checked by rendered-screen probe')
                raise subprocess.TimeoutExpired('simctl', 120)
            with patch('ios_simulator_control.subprocess.check_output', side_effect=capture):
                screenshot('device', path)
            self.assertTrue(path.exists())


if __name__ == '__main__':
    unittest.main()
