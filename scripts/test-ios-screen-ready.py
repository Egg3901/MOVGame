#!/usr/bin/env python3
"""Regression checks for the simulator screenshot readiness probe."""
import pathlib
import runpy
import struct
import tempfile
import unittest
import zlib

probe = runpy.run_path(str(pathlib.Path(__file__).with_name('ios-screen-ready.py')))
coverage = probe['screen_coverage']
check_screen = probe['check_screen']


def png(background, marker=None):
    width, height = 74, 160
    pixels = bytearray(background * (width * height))
    if marker:
        for y in range(40, 43):
            for x in range(7, 10):
                offset = (y * width + x) * 3
                pixels[offset:offset + 3] = bytes(marker)
    raw = b''.join(b'\0' + pixels[y * width * 3:(y + 1) * width * 3] for y in range(height))

    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))

    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(raw)) + chunk(b'IEND', b''))


class ScreenReadyTest(unittest.TestCase):
    def measure(self, image):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / 'screen.png'
            path.write_bytes(image)
            return coverage(path)

    def ready(self, image, mode='game'):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / 'screen.png'
            path.write_bytes(image)
            return check_screen(path, mode)[0]

    def test_small_party_marker_at_left_of_standings_is_counted(self):
        dark, amber, coral, blue, _ = self.measure(png([10, 15, 20], [0, 130, 210]))
        self.assertGreaterEqual(dark, 0.35)
        self.assertGreaterEqual(blue, 8)

    def test_rendered_analysis_with_thin_chart_marks_is_counted(self):
        # Actual simulator capture: 160-pixel resizing erased the title accent
        # and chart dots. The 320-pixel probe preserves both without loosening
        # the readiness thresholds.
        fixture = pathlib.Path(__file__).parent / 'test-fixtures' / 'ios-analysis.png'
        self.assertTrue(check_screen(fixture)[0])
        dark, amber, coral, blue, _ = coverage(fixture)
        self.assertGreaterEqual(dark, 0.35)
        self.assertTrue(amber >= 3 or coral >= 3 or blue >= 8)

    def test_blank_dark_screen_has_no_accent(self):
        self.assertFalse(self.ready(png([10, 15, 20])))
        self.assertFalse(self.ready(png([10, 15, 20]), 'ask'))
        dark, amber, coral, blue, _ = self.measure(png([10, 15, 20]))
        self.assertEqual(dark, 1)
        self.assertEqual((amber, coral, blue), (0, 0, 0))

    def test_blank_white_screen_is_rejected(self):
        self.assertFalse(self.ready(png([255, 255, 255])))
        self.assertFalse(self.ready(png([255, 255, 255]), 'ask'))
        dark, _, _, _, _ = self.measure(png([255, 255, 255]))
        self.assertEqual(dark, 0)


if __name__ == '__main__':
    unittest.main()
