#!/usr/bin/env python3
"""Regression checks for the simulator screenshot readiness probe."""
import pathlib
import runpy
import struct
import tempfile
import unittest
import zlib

coverage = runpy.run_path(str(pathlib.Path(__file__).with_name('ios-screen-ready.py')))['screen_coverage']


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

    def test_small_party_marker_at_left_of_standings_is_counted(self):
        dark, amber, coral, blue, _ = self.measure(png([10, 15, 20], [0, 130, 210]))
        self.assertGreaterEqual(dark, 0.35)
        self.assertGreaterEqual(blue, 8)

    def test_blank_dark_screen_has_no_accent(self):
        dark, amber, coral, blue, _ = self.measure(png([10, 15, 20]))
        self.assertEqual(dark, 1)
        self.assertEqual((amber, coral, blue), (0, 0, 0))

    def test_blank_white_screen_is_rejected(self):
        dark, _, _, _, _ = self.measure(png([255, 255, 255]))
        self.assertEqual(dark, 0)


if __name__ == '__main__':
    unittest.main()
